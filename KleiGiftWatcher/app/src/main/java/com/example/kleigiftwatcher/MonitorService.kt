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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    private var next: ScheduledFuture<*>? = null
    private var redeemer: AutoRedeemer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channels()
        redeemer = AutoRedeemer(this) { url, result ->
            Store.update(this, url, result)
            Store.status(this, result)
            if (result.contains("需要") || result.contains("未核实") || result.contains("请手动") || result.contains("已领取")) giftNotification(url, result)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            alive.set(false)
            Store.setActive(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        val pending = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, MonitorService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note = Notification.Builder(this, CH_MONITOR)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Klei 礼物监控运行中")
            .setContentText("B站动态轮询：每 ${Store.seconds(this)} 秒")
            .setContentIntent(pending)
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
        val wasRunning = alive.getAndSet(true)
        Store.setActive(this, true)
        if (!wasRunning) schedule(0)
        else if (intent?.action == CHECK) {
            next?.cancel(false)
            schedule(0)
        }
        return START_STICKY
    }

    @Synchronized
    private fun schedule(delaySeconds: Long) {
        if (!alive.get()) return
        next = scheduler.schedule({
            try {
                Store.status(this, "正在检查 B站 UID ${Store.uid(this)}")
                val links = BiliScanner(this).scan(Store.uid(this))
                var newCount = 0
                for (gift in links) {
                    if (Store.addNew(this, gift.url, gift.source)) {
                        newCount++
                        Store.status(this, "发现新礼物链接")
                        giftNotification(gift.url, "发现新的科雷礼物链接")
                        if (Store.autoClaim(this)) main.post { redeemer?.enqueue(gift.url) }
                        else Store.update(this, gift.url, "已发现，等待手动领取")
                    }
                }
                Store.status(this, "检测完成：发现 ${links.size} 个候选礼物链接，本次新增 $newCount 个")
            } catch (e: Exception) {
                val reason = (e.message ?: e.javaClass.simpleName).take(150)
                Store.status(this, "检查失败：$reason")
            } finally {
                if (alive.get() && !scheduler.isShutdown) schedule(Store.seconds(this).toLong())
            }
        }, delaySeconds, TimeUnit.SECONDS)
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
        next?.cancel(true)
        scheduler.shutdownNow()
        main.removeCallbacksAndMessages(null)
        redeemer?.destroy()
        redeemer = null
        Store.setActive(this, false)
        super.onDestroy()
    }
}
