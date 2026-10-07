package com.shilapi.xcertplay.legacy

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * View-based home screen for pre-21 units (the upstream home is Compose and needs API 23).
 * Pure framework views so it opens on Android 4.3/4.4; mirrors the DiPlay home's core actions:
 * wired/wireless connect and diagnostics.
 */
class LegacyHomeActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 16).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.rgb(12, 17, 27))
        }

        val title = TextView(this).apply {
            text = "DiPlay"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "CarPlay 待连接。插上 iPhone 使用有线；或开启车机热点后连接无线。"
            textSize = 14f
            setTextColor(Color.rgb(166, 200, 255))
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad * 2)
        }

        fun button(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }

        root.addView(title)
        root.addView(status)
        root.addView(button("通过 USB 连接 iPhone") {
            status.text = "正在启动 USB 会话…（把 iPhone 插到车机 USB 口）"
            startActivity(Intent(this, LegacyCarPlayActivity::class.java))
        })
        root.addView(button("连接手机（无线/USB 自动识别）") {
            status.text = "正在启动 CarPlay…"
            startActivity(Intent(this, LegacyCarPlayActivity::class.java))
        })
        root.addView(button("无线（车机热点模式）") {
            showWirelessDialog()
        })
        root.addView(button("诊断信息") {
            startActivity(Intent(this, com.shilapi.xcertplay.DiPlayProbeActivity::class.java))
        })
        root.addView(button("导出诊断日志（写入存储并分享）") {
            val text = diagnosticText()
            val written = com.shilapi.xcertplay.legacy.LegacyDiagnostics.saveSnapshot(
                this@LegacyHomeActivity, text,
            )
            status.text = "日志已写入:\n" + written.joinToString("\n") { it.toString() }
            runCatching {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "DiPlay 诊断日志")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(send, "分享诊断日志"))
            }.onFailure {
                status.text = status.text.toString() +
                    "\n分享目标不可用(${it.javaClass.simpleName})：请改用上面的文件"
            }
        })
        root.addView(TextView(this).apply {
            text = "compat-4.4 分支 · 上游 0.2.12 · View 界面"
            textSize = 11f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, pad * 2, 0, 0)
        })
        setContentView(root)
    }

    private fun diagnosticText(): String = buildString {
        appendLine(com.shilapi.xcertplay.legacy.LegacyDiagnostics.platformReport(this@LegacyHomeActivity))
        for (name in listOf(
            com.shilapi.xcertplay.legacy.LegacyDiagnostics.CRASH_FILE,
            com.shilapi.xcertplay.legacy.LegacyDiagnostics.STARTED_FILE,
            com.shilapi.xcertplay.legacy.LegacyDiagnostics.LOG_FILE,
        )) {
            val file = com.shilapi.xcertplay.legacy.LegacyDiagnostics.readAll(this@LegacyHomeActivity, name)
            appendLine("==== $name ${file?.path ?: "(无)"} ====")
            appendLine(file?.text ?: "")
        }
    }

    private fun showWirelessDialog() {
        val ssidInput = android.widget.EditText(this).apply {
            hint = "车机热点名称 (SSID)"
            setText(com.shilapi.xcertplay.AirPlayPersistence.loadExistingWifiSsid(this@LegacyHomeActivity))
        }
        val passInput = android.widget.EditText(this).apply {
            hint = "车机热点密码"
            setText(com.shilapi.xcertplay.AirPlayPersistence.loadExistingWifiPassphrase(this@LegacyHomeActivity))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(TextView(this@LegacyHomeActivity).apply {
                text = ("两种无线方式（iPhone 需与车机完成蓝牙配对）：\n" +
                "A. 车机自带热点：车机开热点，iPhone 连上它；\n" +
                "B. 共同外部热点（社区已在安卓 7 验证可行）：车机在设置里连入一个 Wi-Fi（另一台手机的热点/随身 Wi-Fi/家用路由），iPhone 也连入同一个网络。\n" +
                "   注意：部分固件的自带热点无法打开 AirPlay 端口（安卓 7 移植者实测），外部热点是更可靠的无线路线。\n" +
                "下方填写该网络的名称和密码。")
                textSize = 13f
            })
            addView(ssidInput)
            addView(passInput)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("无线 CarPlay（车机热点）")
            .setView(box)
            .setPositiveButton("保存并连接") { _, _ ->
                com.shilapi.xcertplay.AirPlayPersistence.saveExistingWifiCredentials(
                    this, ssidInput.text.toString().trim(), passInput.text.toString(),
                )
                val intent = Intent(this, LegacyCarPlayActivity::class.java)
                intent.putExtra(LegacyCarPlayActivity.EXTRA_WIRELESS, true)
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        status.text = "系统: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · 待连接\n" +
            com.shilapi.xcertplay.legacy.AndroidVersionProbe.summaryLine()
    }
}
