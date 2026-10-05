package com.shilapi.xcertplay

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * A dependency-free launcher entry that turns the car's own screen into the diagnostic report:
 * real platform identity (settings screens on car ROMs show rebranded version strings) plus the
 * contents of the startup marker and crash files. Opens even when the main Compose activity
 * cannot, which separates "the app's process starts" from "the main UI is broken".
 */
class DiPlayProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sb = StringBuilder()
        sb.append("DiPlay 诊断探针\n\n本界面能打开 = 应用进程可以启动\n\n")
        sb.append("显示版本: ${Build.VERSION.RELEASE}\n")
        sb.append("真实 SDK: ${Build.VERSION.SDK_INT}\n")
        sb.append("ROM build: ${Build.DISPLAY}\n")
        sb.append("设备: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("硬件: ${Build.HARDWARE}\n")
        sb.append("内核: ${System.getProperty("os.version")}\n")
        sb.append("ABI: ${Build.SUPPORTED_ABIS?.let { java.util.Arrays.toString(it) } ?: "?"}\n")
        sb.append("指纹: ${Build.FINGERPRINT}\n\n")
        var found = false
        for (name in listOf("diplay-crash.txt", "diplay-started.txt")) {
            for (dir in listOf(getExternalFilesDir(null), filesDir)) {
                val file = dir?.let { File(it, name) }
                if (file != null && file.exists()) {
                    found = true
                    sb.append("==== $name (${file.absolutePath}) ====\n")
                    sb.append(runCatching { file.readText() }.getOrElse { "(读取失败: $it)" })
                    sb.append("\n")
                }
            }
        }
        if (!found) sb.append("==== 未发现任何诊断文件：进程此前从未启动过 ====\n")
        val view = TextView(this).apply {
            textSize = 13f
            setPadding(32, 32, 32, 32)
            text = sb.toString()
            setTextIsSelectable(true)
        }
        setContentView(ScrollView(this).apply { addView(view) })
    }
}
