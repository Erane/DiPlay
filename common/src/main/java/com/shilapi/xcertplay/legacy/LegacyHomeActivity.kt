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

    override fun onResume() {
        super.onResume()
        status.text = "系统: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · 待连接"
    }
}
