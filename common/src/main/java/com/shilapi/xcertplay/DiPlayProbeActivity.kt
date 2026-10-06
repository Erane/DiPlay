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
 *
 * Must itself survive pre-21 platforms: Material themes and Build.SUPPORTED_ABIS start at 21,
 * so fall back to Holo and the CPU_ABI fields, and dump any failure to the diagnostic files.
 */
class DiPlayProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT < 21) {
            setTheme(android.R.style.Theme_Holo_Light_NoActionBar)
        }
        super.onCreate(savedInstanceState)
        try {
            setContentView(buildReport(reportText()))
        } catch (error: Throwable) {
            appendCrash("DiPlayProbeActivity", error)
            try {
                setContentView(buildReport(reportText() + "\n==== 探针自身出错（已记录到诊断文件）====\n$error"))
            } catch (_: Throwable) {
                throw error
            }
        }
    }

    private fun reportText(): String = buildString {
        appendLine("DiPlay 诊断探针")
        appendLine()
        appendLine("本界面能打开 = 应用进程可以启动")
        appendLine()
        appendLine("显示版本: ${Build.VERSION.RELEASE}")
        appendLine("真实 SDK: ${Build.VERSION.SDK_INT}")
        appendLine("ROM build: ${Build.DISPLAY}")
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("硬件: ${Build.HARDWARE}")
        appendLine("内核: ${System.getProperty("os.version")}")
        appendLine(
            "ABI: " + if (Build.VERSION.SDK_INT >= 21) {
                Build.SUPPORTED_ABIS?.let { java.util.Arrays.toString(it) } ?: "?"
            } else {
                // CPU_ABI exists since API 1; on a 32-bit-only unit CPU_ABI2 is the second entry.
                "${Build.CPU_ABI} / ${Build.CPU_ABI2}"
            },
        )
        appendLine("指纹: ${Build.FINGERPRINT}")
        appendLine()
        appendLine("网络接口:")
        runCatching {
            val enumerated = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (iface in enumerated) {
                val addrs = iface.inetAddresses.toList()
                    .joinToString(",") { it.hostAddress ?: "" }
                appendLine("  ${iface.name} up=${iface.isUp} [$addrs]")
            }
            if (enumerated.isEmpty()) appendLine("  (无)")
        }.onFailure { appendLine("  (枚举失败: $it)") }
        appendLine()
        var found = false
        for (name in listOf("diplay-crash.txt", "diplay-started.txt")) {
            for (dir in listOf(getExternalFilesDir(null), filesDir)) {
                val file = dir?.let { File(it, name) }
                if (file != null && file.exists()) {
                    found = true
                    appendLine("==== $name (${file.absolutePath}) ====")
                    appendLine(runCatching { file.readText() }.getOrElse { "(读取失败: $it)" })
                }
            }
        }
        if (!found) appendLine("==== 未发现任何诊断文件：进程此前从未启动过 ====")
    }

    private fun buildReport(content: String): ScrollView = ScrollView(this).apply {
        addView(
            TextView(this@DiPlayProbeActivity).apply {
                textSize = 13f
                setPadding(32, 32, 32, 32)
                text = content
                setTextIsSelectable(true)
            },
        )
    }

    private fun appendCrash(what: String, error: Throwable) {
        val trace = java.io.StringWriter().also { java.io.PrintWriter(it).use { w -> error.printStackTrace(w) } }
        val entry = "==== ${System.currentTimeMillis()} $what (SDK ${Build.VERSION.SDK_INT}) ====\n$trace\n"
        for (dir in listOfNotNull(getExternalFilesDir(null), filesDir)) {
            runCatching {
                dir.mkdirs()
                File(dir, "diplay-crash.txt").appendText(entry)
            }
        }
    }
}
