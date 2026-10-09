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
        append(com.shilapi.xcertplay.legacy.AndroidVersionProbe.report())
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
        append(softwareOpusSelfTest())
        appendLine()
        var found = false
        val probeContext = this@DiPlayProbeActivity
        appendLine("诊断文件写入目标（导出日志的落点）:")
        for ((label, dir) in com.shilapi.xcertplay.legacy.LegacyDiagnostics.targets(probeContext)) {
            val present = listOf(
                com.shilapi.xcertplay.legacy.LegacyDiagnostics.CRASH_FILE,
                com.shilapi.xcertplay.legacy.LegacyDiagnostics.STARTED_FILE,
                com.shilapi.xcertplay.legacy.LegacyDiagnostics.LOG_FILE,
            ).filter { File(dir, it).exists() }
            appendLine(
                "  $label ${dir.absolutePath} exists=${dir.exists()} " +
                    "canWrite=${dir.canWrite()} 已有=${if (present.isEmpty()) "无" else present.joinToString(",")}",
            )
        }
        appendLine()
        for (name in listOf(
            com.shilapi.xcertplay.legacy.LegacyDiagnostics.CRASH_FILE,
            com.shilapi.xcertplay.legacy.LegacyDiagnostics.STARTED_FILE,
            com.shilapi.xcertplay.legacy.LegacyDiagnostics.LOG_FILE,
        )) {
            val file = com.shilapi.xcertplay.legacy.LegacyDiagnostics.readTail(probeContext, name)
            if (file != null) {
                found = true
                appendLine("==== $name (${file.path}) ====")
                val shown = if (name == com.shilapi.xcertplay.legacy.LegacyDiagnostics.LOG_FILE && file.text.length > 4000) {
                    file.text.takeLast(4000)
                } else file.text
                appendLine(shown)
            }
        }
        if (!found) appendLine("==== 未发现任何诊断文件：进程此前从未启动过 ====")
    }

    /**
     * Decodes one real Opus packet with the bundled Concentus classes. Navigation voice depends on
     * those classes surviving this ROM's verifier, which no desktop run can show, and the timing says
     * whether this CPU can decode 20 ms packets faster than they arrive. Reflection because the probe
     * lives in the module below the player, so it must open even when the player cannot.
     */
    private fun softwareOpusSelfTest(): String = buildString {
        appendLine("软件 Opus 解码自检（安卓 4.x 的导航播报靠它）:")
        val hex = "7883110aac7c87bcb005500516d93ece6c433b5a1be0f2b431a24d47580efcb7" +
            "9aca37a60529b816aa92a182ac327bf2950f6500bbe7ca116589ba0907f3c89480e5a5ea4a"
        val packet = ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        try {
            val decoderClass = Class.forName("org.concentus.OpusDecoder")
            val decoder = decoderClass
                .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance(48_000, 1)
            val decode = decoderClass.getMethod(
                "decode",
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                ShortArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
            val pcm = ShortArray(5760)
            var samples = decode.invoke(decoder, packet, 0, packet.size, pcm, 0, 5760, false) as Int
            val rounds = 40
            val started = System.nanoTime()
            repeat(rounds) {
                samples = decode.invoke(decoder, packet, 0, packet.size, pcm, 0, 5760, false) as Int
            }
            val millisPerPacket = (System.nanoTime() - started) / 1_000_000.0 / rounds
            var peak = 0
            for (index in 0 until samples.coerceAtLeast(0)) {
                peak = maxOf(peak, kotlin.math.abs(pcm[index].toInt()))
            }
            appendLine("  解出样本=$samples（期望 960） 峰值=$peak（期望 1000 以上）")
            appendLine(
                "  每包耗时=${String.format("%.3f", millisPerPacket)}ms" +
                    "（实时要求每 20ms 解完一包）",
            )
            appendLine(
                if (samples == 960 && peak > 1000) {
                    "  结论: 本机可以软件解码 Opus，导航播报有戏"
                } else {
                    "  结论: 解码结果异常，导航播报大概率仍然无声"
                },
            )
        } catch (error: Throwable) {
            appendLine("  自检失败: $error")
        }
    }

    private fun buildReport(content: String): ScrollView = ScrollView(this).apply {
        addView(
            TextView(this@DiPlayProbeActivity).apply {
                textSize = 13f
                setPadding(32, 32, 32, 32)
                text = content
            },
        )
    }

    private fun appendCrash(what: String, error: Throwable) {
        com.shilapi.xcertplay.legacy.LegacyDiagnostics.append(
            this,
            com.shilapi.xcertplay.legacy.LegacyDiagnostics.CRASH_FILE,
            com.shilapi.xcertplay.legacy.LegacyDiagnostics.crashEntry(what, error),
        )
    }
}
