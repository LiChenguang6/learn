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

/**
 * Two channels: a real WebView page refresh and a fallback JSON API.
 * API requests are skipped when the rendered page was recently readable.
 * HTTP 412/-352 pauses BOTH channels with bounded increasing backoff.
 */
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
    private val forceCheck = AtomicBoolean(false)
    private var nextApi: ScheduledFuture<*>? = null
    private var webTick: Runnable? = null
    private var webScanner: BiliWebScanner? = null
    private var redeemer: AutoRedeemer? = null
    private var cpuLock: PowerManager.WakeLock? = null
    @Volatile private var lastWebSuccess = 0L
    @Volatile private var blockedUntil = 0L
    private var blockedCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channels()
        redeemer = AutoRedeemer(this) { url, result ->
            Store.update(this, url, result)
            Store.status(this, result)
            if (result.contains("需要") || result.contains("未核实") ||
                result.contains("请手动") || result.contains("已领取")) giftNotification(url, result)
        }
        webScanner = BiliWebScanner(this) { scan, ready, blocked, info ->
            if (!alive.get()) return@BiliWebScanner
            if (blocked) {
                lastWebSuccess = 0L
                registerBlock(info)
            } else if (ready && scan != null) {
                lastWebSuccess = SystemClock.elapsedRealtime()
                clearBlockCount()
                Store.monitorResult(this, "网页", scan.latestId, scan.latestTime, scan.cardCount, scan.gifts.size)
                val newCount = handleGifts(scan.gifts)
                Store.status(this, "网页：$info，发现 ${scan.gifts.size} 个链接，新增 $newCount")
            } else {
                lastWebSuccess = 0L
                Store.monitorError(this, "网页", info)
                Store.status(this, "网页：$info")
                // Fallback is only useful when the web view fails or has no readable cards.
                requestApiNow()
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
            .setContentText("网页 ${Store.seconds(this)} 秒；API 备用 ${Store.apiSeconds(this)} 秒")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止监控", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(MONITOR_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(MONITOR_ID, note)

        if (intent?.action == RETRY) {
            intent.getStringExtra(LINK)?.let { raw ->
                Store.verifiedUrl(raw)?.let { url -> main.post { redeemer?.enqueue(url) } }
            }
        }
        val alreadyRunning = alive.getAndSet(true)
        Store.setDesired(this, true)
        Store.setActive(this, true)
        applyWakeMode()
        if (!alreadyRunning) Store.event(this, "前台监控服务已启动（含系统恢复启动）")
        if (!alreadyRunning) {
            scheduleWeb(0)
            scheduleApi(Store.apiSeconds(this).toLong())
        } else if (intent?.action == CHECK) {
            forceCheck.set(true)
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

    private fun scheduleWeb(delayMs: Long) {
        main.post {
            webTick?.let { main.removeCallbacks(it) }
            if (!alive.get()) return@post
            val task = Runnable {
                if (!alive.get()) return@Runnable
                val now = SystemClock.elapsedRealtime()
                if (now < blockedUntil) {
                    val remaining = blockedUntil - now
                    Store.status(this, "B站请求冷却中，约 ${(remaining + 999) / 1000} 秒后恢复")
                    scheduleWeb(remaining + 1000)
                    return@Runnable
                }
                Store.status(this, "网页正在检查 UID ${Store.uid(this)}")
                webScanner?.refresh(Store.uid(this))
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
        nextApi?.cancel(false)
        nextApi = scheduler.schedule({
            try {
                val now = SystemClock.elapsedRealtime()
                if (now < blockedUntil) {
                    Store.status(this, "B站限制访问，正在冷却")
                } else {
                    val manual = forceCheck.getAndSet(false)
                    val recentWeb = lastWebSuccess > 0L &&
                        now - lastWebSuccess < (Store.seconds(this).toLong() * 3L + 15000L)
                    if (manual || !recentWeb) {
                        Store.status(this, "备用 API 正在检查 UID ${Store.uid(this)}")
                        val scan = BiliScanner(this).scan(Store.uid(this))
                        clearBlockCount()
                        Store.monitorResult(this, "备用 API", scan.latestId, scan.latestTime, scan.cardCount, scan.gifts.size)
                        val newCount = handleGifts(scan.gifts)
                        Store.status(this, "备用 API：发现 ${scan.gifts.size} 个链接，新增 $newCount")
                    }
                }
            } catch (e: Exception) {
                val reason = (e.message ?: e.javaClass.simpleName).take(150)
                if (reason.contains("HTTP 412") || reason.contains("API -352")) registerBlock(reason)
                else { Store.monitorError(this, "备用 API", reason); Store.status(this, "备用 API 失败：$reason") }
            } finally {
                if (alive.get() && !scheduler.isShutdown) {
                    val remaining = ((blockedUntil - SystemClock.elapsedRealtime()) / 1000L).coerceAtLeast(0L)
                    scheduleApi(maxOf(Store.apiSeconds(this).toLong(), remaining + if (remaining > 0L) 1L else 0L))
                }
            }
        }, delaySeconds.coerceAtLeast(0), TimeUnit.SECONDS)
    }

    private fun handleGifts(gifts: List<BiliScanner.Gift>): Int {
        var count = 0
        for (gift in gifts) {
            if (Store.addNew(this, gift.url, gift.source)) {
                count++
                giftNotification(gift.url, "发现新的科雷礼物链接")
                if (Store.autoClaim(this)) main.post { redeemer?.enqueue(gift.url) }
                else Store.update(this, gift.url, "已发现，等待手动领取")
            }
        }
        return count
    }

    @Synchronized
    private fun registerBlock(reason: String) {
        blockedCount = (blockedCount + 1).coerceAtMost(3)
        val cooldown = when (blockedCount) { 1 -> 120L; 2 -> 300L; else -> 900L }
        blockedUntil = maxOf(blockedUntil, SystemClock.elapsedRealtime() + cooldown * 1000L)
        Store.monitorError(this, "HTTP 412 / 限制访问", "$reason；冷却 $cooldown 秒")
        Store.status(this, "B站访问受限（$reason），暂停请求 $cooldown 秒")
    }

    @Synchronized
    private fun clearBlockCount() {
        blockedCount = 0
        blockedUntil = 0L
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
        cpuLock = null
        main.removeCallbacksAndMessages(null)
        redeemer?.destroy()
        redeemer = null
        Store.setActive(this, false)
        Store.event(this, "监控服务退出；系统允许时可被重启")
        super.onDestroy()
    }
}
