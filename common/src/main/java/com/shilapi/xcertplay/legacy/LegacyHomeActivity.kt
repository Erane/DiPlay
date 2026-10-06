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
        root.addView(TextView(this).apply {
            text = "compat-4.4 分支 · 上游 0.2.12 · View 界面"
            textSize = 11f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, pad * 2, 0, 0)
        })
        setContentView(root)
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
                text = "先在车机设置里开启热点，让 iPhone 连上热点；然后填写热点的名称和密码。（iPhone 需要与车机完成蓝牙配对）"
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
        status.text = "系统: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · 待连接"
    }
}
