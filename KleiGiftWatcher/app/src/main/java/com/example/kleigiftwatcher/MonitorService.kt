package com.example.kleigiftwatcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** WebView and JSON API have independent schedules, failure counts and cooldowns.
 * Discovered gift URLs share only the persistent deduplication/claim queue. */
class MonitorService : Service() {
    companion object {
        const val START = "com.example.kleigiftwatcher.START"
        const val CHECK = "com.example.kleigiftwatcher.CHECK"
        const val STOP = "com.example.kleigiftwatcher.STOP"
        const val RETRY = "com.example.kleigiftwatcher.RETRY"
        const val LINK = "url"
        private const val CH_MONITOR = "klei_monitor"
        private const val CH_GIFT = "klei_gift"
        private const val MONITOR_ID = 1
    }

    private val main = Handler(Looper.getMainLooper())
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val alive = AtomicBoolean(false)
    private var nextApi: ScheduledFuture<*>? = null
    private var webTick: Runnable? = null
    private var webScanner: BiliWebScanner? = null
    private var redeemer: AutoRedeemer? = null
    private var cpuLock: PowerManager.WakeLock? = null
    private var claimLock: PowerManager.WakeLock? = null
    @Volatile private var lastApiStartedAt = 0L
    private val webBackoff = ChannelBackoff()
    private val apiBackoff = ChannelBackoff()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channels()
        webBackoff.restore(SystemClock.elapsedRealtime(), Store.cooldownRemaining(this, MonitorChannel.WEB))
        apiBackoff.restore(SystemClock.elapsedRealtime(), Store.cooldownRemaining(this, MonitorChannel.API))
        redeemer = AutoRedeemer(this, { url, result ->
            Store.status(this, "领取结果：$result")
            Store.event(this, "科雷领奖结果：${result.take(110)}")
            giftNotification(url, result)
            releaseClaimWake()
        }, { acquireClaimWake() })
        webScanner = BiliWebScanner(this) { scan, ready, blocked, info, retryAfter ->
            if (!alive.get()) return@BiliWebScanner
            if (blocked) {
                registerBlock(MonitorChannel.WEB, info, retryAfter)
            } else if (ready && scan != null) {
                webBackoff.success(SystemClock.elapsedRealtime())
                Store.monitorResult(this, "网页", scan.latestId, scan.latestTime, scan.cardCount, scan.gifts.size)
                val newCount = handleGifts(scan.gifts)
                Store.status(this, "网页：$info，发现 ${scan.gifts.size} 个链接，新增 $newCount", MonitorChannel.WEB)
            } else {
                Store.monitorError(this, "网页", info)
                Store.status(this, "网页：$info", MonitorChannel.WEB)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            alive.set(false)
            Store.setDesired(this, false)
            Store.setActive(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, MonitorService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note = Notification.Builder(this, CH_MONITOR)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Klei 礼物监控运行中")
            .setContentText("网页 ${Store.seconds(this)} 秒；API 自适应间隔（风控后 +1 秒）")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止监控", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(MONITOR_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(MONITOR_ID, note)

        if (intent?.action == RETRY) {
            intent.getStringExtra(LINK)?.let { raw ->
                Store.verifiedUrl(raw)?.let { url -> main.post {
                    acquireClaimWake()
                    redeemer?.retryUnsubmitted(url)
                } }
            }
        }
        val alreadyRunning = alive.getAndSet(true)
        Store.setDesired(this, true)
        Store.setActive(this, true)
        applyWakeMode()
        if (!alreadyRunning) Store.event(this, "前台监控服务已启动（含系统恢复启动）")
        if (!alreadyRunning) {
            if (Store.autoClaim(this)) {
                val old = Store.pendingClaims(this)
                if (old.isNotEmpty()) Store.event(this, "恢复 ${old.size} 条尚未提交领取的任务")
                if (old.isNotEmpty()) main.post {
                    acquireClaimWake()
                    redeemer?.enqueueAll(old)
                }
            }
            scheduleWeb(0)
            scheduleApi(Store.apiSeconds(this).toLong())
        } else if (intent?.action == CHECK) {
            scheduleWeb(0)
            requestApiNow()
        }
        return START_STICKY
    }

    /** Optional high-power mode requested by the user. Keeps CPU awake with screen OFF. */
    private fun applyWakeMode() {
        if (Store.keepAwake(this)) {
            try {
                if (cpuLock == null) cpuLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KleiWatcher:LockscreenMonitor")
                    .apply { setReferenceCounted(false) }
                if (cpuLock?.isHeld != true) cpuLock?.acquire()
                Store.event(this, "锁屏增强已启用（高耗电；不保证系统放行网络）")
            } catch (e: Exception) { Store.monitorError(this, "CPU 唤醒", e.message ?: "权限不可用") }
        } else {
            releaseWakeMode()
        }
    }

    private fun releaseWakeMode() {
        try { if (cpuLock?.isHeld == true) cpuLock?.release() } catch (_: Exception) {}
    }

    // A short timed CPU lock gives a just-discovered reward time to load when
    // the user has not enabled the high-power persistent lock-screen mode.
    private fun acquireClaimWake() {
        try {
            if (claimLock == null) claimLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KleiWatcher:Claim")
                .apply { setReferenceCounted(false) }
            claimLock?.acquire(70000L)
        } catch (e: Exception) { Store.monitorError(this, "领奖保活", e.javaClass.simpleName) }
    }

    private fun releaseClaimWake() {
        try { if (claimLock?.isHeld == true) claimLock?.release() } catch (_: Exception) {}
    }

    private fun scheduleWeb(delayMs: Long) {
        main.post {
            webTick?.let { main.removeCallbacks(it) }
            if (!alive.get()) return@post
            val task = Runnable {
                if (!alive.get()) return@Runnable
                val now = SystemClock.elapsedRealtime()
                val remaining = webBackoff.remainingMillis(now)
                if (remaining > 0) {
                    Store.status(this, "网页通道冷却中，约 ${(remaining + 999) / 1000} 秒后恢复", MonitorChannel.WEB)
                    scheduleWeb(remaining + 1000)
                    return@Runnable
                }
                if (webScanner?.isBusy() == false) {
                    Store.status(this, "网页正在检查 UID ${Store.uid(this)}", MonitorChannel.WEB)
                    webScanner?.refresh(Store.uid(this))
                }
                scheduleWeb(Store.seconds(this).toLong() * 1000L)
            }
            webTick = task
            main.postDelayed(task, delayMs)
        }
    }

    private fun requestApiNow() {
        scheduleApi(0)
    }

    @Synchronized
    private fun scheduleApi(delaySeconds: Long) {
        if (!alive.get() || scheduler.isShutdown) return
        // API timing depends only on its own interval, last start and cooldown.
        val minSpacing = Store.apiSeconds(this).toLong() * 1000L
        val now = SystemClock.elapsedRealtime()
        val untilAllowed = if (lastApiStartedAt > 0L) (lastApiStartedAt + minSpacing - now).coerceAtLeast(0L) else 0L
        val delayMs = maxOf(delaySeconds.coerceAtLeast(0) * 1000L, untilAllowed)
        nextApi?.cancel(false)
        nextApi = scheduler.schedule({
            try {
                val now = SystemClock.elapsedRealtime()
                if (apiBackoff.remainingMillis(now) > 0) {
                    Store.status(this, "API 通道冷却中", MonitorChannel.API)
                } else {
                    Store.status(this, "API 正在检查 UID ${Store.uid(this)}", MonitorChannel.API)
                    lastApiStartedAt = SystemClock.elapsedRealtime()
                    val scan = BiliScanner(this).scan(Store.uid(this))
                    apiBackoff.success(SystemClock.elapsedRealtime())
                    Store.monitorResult(this, "API", scan.latestId, scan.latestTime, scan.cardCount, scan.gifts.size)
                    val newCount = handleGifts(scan.gifts)
                    Store.status(this, "API：发现 ${scan.gifts.size} 个链接，新增 $newCount", MonitorChannel.API)
                }
            } catch (e: Exception) {
                val reason = (e.message ?: e.javaClass.simpleName).take(150)
                if (e is BiliAccessException) registerBlock(MonitorChannel.API, reason, e.retryAfterSeconds)
                else { Store.monitorError(this, "API", reason); Store.status(this, "API 失败：$reason", MonitorChannel.API) }
            } finally {
                if (alive.get() && !scheduler.isShutdown) {
                    val remaining = (apiBackoff.remainingMillis(SystemClock.elapsedRealtime()) / 1000L)
                    scheduleApi(maxOf(Store.apiSeconds(this).toLong(), remaining + if (remaining > 0L) 1L else 0L))
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun handleGifts(gifts: List<BiliScanner.Gift>): Int {
        val newlyDiscovered = ArrayList<String>()
        // Each unique Klei URL in the SAME dynamic receives an individual job.
        // Do not start job #1 before the remaining links have been queued.
        for (gift in gifts) {
            if (Store.addNew(this, gift.url, gift.source)) {
                newlyDiscovered.add(gift.url)
                giftNotification(gift.url, "发现新的科雷礼物链接")
                if (!Store.autoClaim(this))
                    Store.update(this, gift.url, "已发现，等待手动领取")
            }
        }
        if (newlyDiscovered.isNotEmpty()) {
            Store.event(this, "本次发现 ${newlyDiscovered.size} 个不同的科雷链接，逐个排队领取")
            if (Store.autoClaim(this)) main.post {
                acquireClaimWake()
                redeemer?.enqueueAll(newlyDiscovered)
            }
        }
        return newlyDiscovered.size
    }

    private fun registerBlock(channel: MonitorChannel, reason: String, retryAfter: Long = 0) {
        val backoff = if (channel == MonitorChannel.WEB) webBackoff else apiBackoff
        val remaining = backoff.block(SystemClock.elapsedRealtime(), retryAfter)
        val cooldown = (remaining + 999) / 1000
        val label = if (channel == MonitorChannel.WEB) "网页" else "API"
        if (channel == MonitorChannel.API) {
            val interval = Store.increaseApiInterval(this)
            Store.event(this, "API 风控：基础检测间隔 +1 秒，当前 $interval 秒；冷却结束后生效")
            ClaimDiagnostics.event(this, "", "api_backoff", "intervalSeconds=$interval; reason=$reason; cooldownSeconds=$cooldown")
        }
        if (channel == MonitorChannel.WEB)
            ClaimDiagnostics.event(this, "", "web_backoff", "reason=$reason; cooldownSeconds=$cooldown")
        Store.saveCooldown(this, channel, remaining)
        Store.monitorError(this, "$label 通道限制访问", "$reason；本通道冷却 $cooldown 秒")
        Store.status(this, "$label 访问受限（$reason），本通道暂停 $cooldown 秒", channel)
    }

    private fun channels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CH_MONITOR, "持续监控", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CH_GIFT, "礼物发现与领取结果", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun giftNotification(url: String, message: String) {
        val safe = Store.verifiedUrl(url) ?: return
        val id = 2000 + (safe.hashCode() and Int.MAX_VALUE) % 10000
        val intent = Intent(this, BrowserActivity::class.java)
            .putExtra(BrowserActivity.EXTRA_SITE, "klei")
            .putExtra(BrowserActivity.EXTRA_URL, safe)
        val pending = PendingIntent.getActivity(this, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note = Notification.Builder(this, CH_GIFT)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("科雷礼物 · 领取状态")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText("$message\n点击打开科雷网页并核实"))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, note)
    }

    override fun onDestroy() {
        alive.set(false)
        nextApi?.cancel(true)
        scheduler.shutdownNow()
        webTick?.let { main.removeCallbacks(it) }
        webScanner?.destroy()
        webScanner = null
        releaseWakeMode()
        releaseClaimWake()
        claimLock = null
        cpuLock = null
        main.removeCallbacksAndMessages(null)
        redeemer?.destroy()
        redeemer = null
        Store.setActive(this, false)
        Store.event(this, "监控服务退出；系统允许时可被重启")
        super.onDestroy()
    }
}
