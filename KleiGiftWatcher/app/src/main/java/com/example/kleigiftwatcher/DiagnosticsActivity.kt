package com.example.kleigiftwatcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DiagnosticsActivity : Activity() {
    private val exportRequest = 42
    private lateinit var column: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16.dp(),16.dp(),16.dp(),16.dp()) }
        setContentView(ScrollView(this).apply { addView(column) })
        refresh()
    }
    private fun refresh() {
        column.removeAllViews()
        column.addView(TextView(this).apply {
            text = "领取诊断 · v${BuildConfig.VERSION_NAME}\n日志和截图仅保存在本机。最多保留 20 张截图（合计约 10 MB）与两份轮转日志。截图可能包含账号名称和礼物链接，分享前请检查。后台 WebView 截图可能为空白；日志仍可查看。登录页面不截图。"
            textSize = 15f
        })
        column.addView(Button(this).apply { text = "导出日志和截图 ZIP"; setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "Klei-diagnostics-${System.currentTimeMillis()}.zip")
            }, exportRequest)
        } })
        column.addView(Button(this).apply { text = "刷新"; setOnClickListener { refresh() } })
        column.addView(Button(this).apply { text = "清空本机诊断"; setOnClickListener {
            AlertDialog.Builder(this@DiagnosticsActivity).setMessage("删除本机诊断日志与截图？不影响登录和领取记录。")
                .setPositiveButton("删除") { _, _ -> ClaimDiagnostics.execute {
                    ClaimDiagnostics.directory(this@DiagnosticsActivity).listFiles()?.forEach { it.delete() }
                    runOnUiThread { refresh() }
                } }.setNegativeButton("取消", null).show()
        } })
        val dir = ClaimDiagnostics.directory(this)
        dir.listFiles { f -> f.extension == "png" }.orEmpty().sortedByDescending { it.name }.forEach { file ->
            column.addView(Button(this).apply { text = "查看截图：${file.name}"; setOnClickListener {
                val picture = ImageView(this@DiagnosticsActivity).apply {
                    adjustViewBounds = true; setImageBitmap(BitmapFactory.decodeFile(file.path))
                }
                AlertDialog.Builder(this@DiagnosticsActivity).setTitle(file.name).setView(picture).setPositiveButton("关闭", null).show()
            } })
        }
        ClaimDiagnostics.execute {
            val content = listOf("events.previous.jsonl", "events.jsonl").joinToString("\n") { name ->
                try { File(dir, name).readText() } catch (_: Exception) { "" }
            }.takeLast(60000).ifBlank { "尚无领取诊断。下一次自动尝试时会记录。" }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) column.addView(TextView(this).apply { text = content; textSize = 12f; setTextIsSelectable(true) })
            }
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val target = data?.data ?: return
        if (requestCode != exportRequest || resultCode != RESULT_OK) return
        ClaimDiagnostics.execute {
            val message = try {
                val output = contentResolver.openOutputStream(target) ?: error("无法打开目标文件")
                ZipOutputStream(output).use { zip ->
                    ClaimDiagnostics.directory(this).listFiles().orEmpty().filter { it.isFile }.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    }
                }
                "诊断 ZIP 已导出，请检查后再分享"
            } catch (_: Exception) { "导出失败，请重新选择保存位置" }
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }
    }
}
