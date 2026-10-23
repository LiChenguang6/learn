package com.example.kleigiftwatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Restarts user-requested monitoring only when the OS permits it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Store.desired(context) || !Store.resumeOnBoot(context)) return
        try {
            val service = Intent(context, MonitorService::class.java).setAction(MonitorService.START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service)
            else context.startService(service)
            Store.event(context, "设备开机或程序更新：尝试恢复监控")
        } catch (e: Exception) {
            Store.monitorError(context, "开机恢复", "系统限制启动：${e.javaClass.simpleName}")
        }
    }
}
