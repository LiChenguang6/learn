package com.example.kleigiftwatcher

import android.Manifest
import android.net.Uri
import android.provider.Settings
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var uid: EditText
    private lateinit var seconds: EditText
    private lateinit var apiSeconds: EditText
    private lateinit var auto: CheckBox
    private lateinit var keepAwake: CheckBox
    private lateinit var boot: CheckBox
    private lateinit var diagnostics: TextView
    private lateinit var events: TextView
    private lateinit var state: TextView
    private lateinit var records: LinearLayout
    private var previousHistory = ""
    private var lastStatus = ""
    private var lastDiagnostics = ""
    private val refresh = object : Runnable {
        override fun run() {
            if (::state.isInitialized) drawStatus()
            handler.postDelayed(this, 1800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(247, 249, 252)) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 24.dp(), 20.dp(), 30.dp())
        }
        scroll.addView(column)
        column.addView(label("Klei 礼物监控", 26f, Color.rgb(24, 43, 78)))
        column.addView(label("B站动态 → 识别科雷链接 → 尝试领取", 13f, Color.DKGRAY))
        column.addView(label("B站 UP 主 UID", 14f))
        uid = EditText(this).apply {
            setText(Store.uid(this@MainActivity))
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            hint = "12875864"
        }
        column.addView(uid)
        column.addView(label("网页刷新间隔（秒，最低 10 秒；建议 15 秒）", 14f))
        seconds = EditText(this).apply {
            setText(Store.seconds(this@MainActivity).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            hint = "15"
        }
        column.addView(seconds)
        column.addView(label("备用 API 检查间隔（秒，30～90 秒）", 14f))
        apiSeconds = EditText(this).apply {
            setText(Store.apiSeconds(this@MainActivity).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            hint = "60"
        }
        column.addView(apiSeconds)
        auto = CheckBox(this).apply {
            text = "发现链接后自动尝试领取（网页自动化，不能保证成功）"
            isChecked = Store.autoClaim(this@MainActivity)
            textSize = 14f
        }
        column.addView(auto)
        keepAwake = CheckBox(this).apply {
            text = "锁屏增强：保持 CPU 唤醒（耗电明显，建议充电时使用）"
            isChecked = Store.keepAwake(this@MainActivity)
            textSize = 13f
        }
        column.addView(keepAwake)
        boot = CheckBox(this).apply {
            text = "设备重启后尝试恢复监控（受系统版本限制）"
            isChecked = Store.resumeOnBoot(this@MainActivity)
            textSize = 13f
        }
        column.addView(boot)
        row(column, "打开系统后台设置" to {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            catch (e: Exception) { toast("无法打开设置：${e.message}") }
        }, "刷新检测状态" to { drawStatus() })
        diagnostics = label("", 13f, Color.DKGRAY)
        column.addView(diagnostics)
        events = label("", 11f, Color.GRAY)
        column.addView(events)
        state = label("", 14f, Color.rgb(18, 88, 60))
        column.addView(state)

        row(column, "保存并启动监控" to {
            if (save()) {
                try { startForegroundService(Intent(this, MonitorService::class.java).setAction(MonitorService.START)) }
                catch (e: Exception) { toast("启动失败：${e.message}") }
                drawStatus()
            }
        }, "停止" to {
            startService(Intent(this, MonitorService::class.java).setAction(MonitorService.STOP))
            toast("已请求停止")
        })
        row(column, "立即检查" to {
            if (save()) {
                try { startForegroundService(Intent(this, MonitorService::class.java).setAction(MonitorService.CHECK)) }
                catch (e: Exception) { toast("启动失败：${e.message}") }
            }
        }, "登录科雷账号" to { openBrowser("klei") })
        row(column, "打开 B站动态网页诊断" to { openBrowser("bili_debug") }, "刷新记录" to {
            previousHistory = ""
            drawStatus()
        })
        row(column, "登录 B站（可选）" to { openBrowser("bili") }, "立即检查动态" to {
            if (save()) {
                try { startForegroundService(Intent(this, MonitorService::class.java).setAction(MonitorService.CHECK)) }
                catch (e: Exception) { toast("检查失败：${e.message}") }
            }
        })
        column.addView(label("手动测试科雷链接（可选）", 14f))
        val manual = EditText(this).apply {
            hint = "https://accounts.klei.com/creatorlink/..."
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        column.addView(manual)
        val test = Button(this).apply {
            text = "添加链接并尝试领取"
            setOnClickListener {
                val url = Store.verifiedUrl(manual.text.toString())
                if (url == null) toast("请输入有效的 accounts.klei.com/creatorlink/ 链接")
                else {
                    Store.addNew(this@MainActivity, url, "手动测试")
                    Store.update(this@MainActivity, url, "已安排自动尝试")
                    try {
                        startForegroundService(Intent(this@MainActivity, MonitorService::class.java)
                            .setAction(MonitorService.RETRY).putExtra(MonitorService.LINK, url))
                        drawStatus()
                    } catch (e: Exception) { toast("操作失败：${e.message}") }
                }
            }
        }
        column.addView(test)
        column.addView(label("领取记录（点“查看”核实；失败可重试）", 18f, Color.rgb(24, 43, 78)))
        records = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(records)
        column.addView(label("注意：WebView 自动操作属于实验功能；验证码、Steam 登录或科雷页面更新时请手动处理。手机省电策略可能中断监控。", 12f, Color.DKGRAY))
        setContentView(scroll)
        drawStatus()
    }

    private fun save(): Boolean {
        val uidText = uid.text.toString().trim()
        if (!uidText.matches(Regex("[0-9]{1,20}"))) {
            toast("请输入正确的数字 UID")
            return false
        }
        val entered = seconds.text.toString().toIntOrNull()
        if (entered == null || entered !in 10..3600) {
            toast("网页刷新间隔请输入 10～3600 秒")
            return false
        }
        val apiEntered = apiSeconds.text.toString().toIntOrNull()
        if (apiEntered == null || apiEntered !in 30..90) {
            toast("备用 API 间隔请输入 30～90 秒")
            return false
        }
        Store.configure(this, uidText, entered, apiEntered, auto.isChecked, keepAwake.isChecked, boot.isChecked)
        toast("设置已保存")
        return true
    }

    private fun openBrowser(site: String, url: String? = null) {
        val intent = Intent(this, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_SITE, site)
        if (url != null) intent.putExtra(BrowserActivity.EXTRA_URL, url)
        startActivity(intent)
    }

    private fun drawStatus() {
        val message = "监控：${if (Store.active(this)) "运行中" else "已停止"}\n${Store.lastStatus(this)}"
        if (lastStatus != message) { state.text = message; lastStatus = message }
        val diagText = "检测状态：${Store.diagnostic(this)}\n${Store.latestInfo(this)}"
        val eventText = "最近运行日志：\n" + Store.events(this).joinToString("\n")
        val detail = diagText + eventText
        if (lastDiagnostics != detail) {
            diagnostics.text = diagText
            events.text = eventText
            lastDiagnostics = detail
        }
        val rows = Store.rows(this)
        val signature = rows.joinToString("|") { it.optString("url") + it.optString("status") + it.optLong("updated") }
        if (signature == previousHistory) return
        previousHistory = signature
        records.removeAllViews()
        if (rows.isEmpty()) {
            records.addView(label("暂无检测记录。先登录科雷，再启动监控。", 14f, Color.GRAY))
            return
        }
        rows.take(30).forEach { row ->
            val url = row.optString("url")
            val status = row.optString("status")
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(10.dp(), 8.dp(), 10.dp(), 10.dp())
                setBackgroundColor(Color.WHITE)
                val params = LinearLayout.LayoutParams(-1, -2)
                params.bottomMargin = 10.dp()
                layoutParams = params
            }
            container.addView(label("● $status", 14f, Color.rgb(27, 85, 137)))
            container.addView(label(url, 12f, Color.DKGRAY))
            container.addView(label("来自：${row.optString("source")}", 11f, Color.GRAY))
            row(container, "查看 / 浏览器补领" to { openBrowser("klei", url) }, "重新尝试" to {
                if (Store.verifiedUrl(url) != null) {
                    try {
                        startForegroundService(Intent(this, MonitorService::class.java)
                            .setAction(MonitorService.RETRY).putExtra(MonitorService.LINK, url))
                        Store.update(this, url, "已安排重新尝试")
                    } catch (e: Exception) { toast("重试失败：${e.message}") }
                }
            })
            records.addView(container)
        }
    }

    private fun row(target: LinearLayout, left: Pair<String, () -> Unit>, right: Pair<String, () -> Unit>) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (button in listOf(left, right)) {
            val b = Button(this).apply {
                text = button.first
                isAllCaps = false
                textSize = 12f
                setOnClickListener { button.second.invoke() }
            }
            line.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        target.addView(line)
    }

    private fun label(message: String, size: Float, color: Int = Color.BLACK) = TextView(this).apply {
        text = message
        textSize = size
        setTextColor(color)
        setPadding(0, 8.dp(), 0, 5.dp())
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    override fun onResume() { super.onResume(); handler.removeCallbacks(refresh); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
