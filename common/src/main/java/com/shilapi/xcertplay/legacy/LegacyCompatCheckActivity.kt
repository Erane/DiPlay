package com.shilapi.xcertplay.legacy

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Compatibility self-test screen for pre-21 car units: two buttons, one result area, and nothing on
 * the home screen but the entry to it.
 *
 * The two questions an owner of an old head unit actually has are "what Android is this really"
 * (car ROMs rewrite the version the settings page shows) and "is its Bluetooth crippled to
 * audio-only, which would decide whether wireless CarPlay is possible at all". Both answers are
 * long and full of jargon, so they live here rather than on the home screen, and each result is
 * written incrementally while the check runs because the Bluetooth part blocks for tens of seconds.
 */
class LegacyCompatCheckActivity : Activity() {
    private lateinit var resultView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var bluetoothButton: Button
    private val lines = StringBuilder()

    @Volatile private var alive = true
    @Volatile private var bluetoothRunning = false

    /** The Bluetooth report streams in over tens of seconds and should track its tail; the version
     *  report is written at once and starts with the answer, so it stays at the top. */
    private var followTail = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT < 21) setTheme(android.R.style.Theme_Holo_Light_NoActionBar)
        super.onCreate(savedInstanceState)

        val pad = (resources.displayMetrics.density * 12).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(12, 17, 27))
        }

        root.addView(
            TextView(this).apply {
                text = "兼容性自检"
                textSize = 22f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, pad)
            },
        )
        root.addView(
            Button(this).apply {
                text = "检测真实安卓版本"
                setOnClickListener { showVersion() }
            },
        )
        bluetoothButton = Button(this).apply {
            text = "检测蓝牙能力"
            setOnClickListener { runBluetoothCheck() }
        }
        root.addView(bluetoothButton)

        resultView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(214, 226, 245))
            setPadding(0, pad, 0, pad * 3)
        }
        scrollView = ScrollView(this).apply {
            addView(resultView)
        }
        root.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        appendLine("选一项开始检测，结果只显示在本页。")
    }

    private fun showVersion() {
        followTail = false
        clear()
        appendLine(AndroidVersionProbe.report().trimEnd())
        appendLine()
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("ROM build: ${Build.DISPLAY}")
        appendLine("硬件: ${Build.HARDWARE}")
        appendLine("内核: ${System.getProperty("os.version")}")
        appendLine("运行环境: ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}")
        appendLine("ABI: ${LegacyDiagnostics.abiList()}")
        appendLine("应用: $packageName ${LegacyDiagnostics.appVersion(this)}")
        appendLine()
        appendLine("提示：判断能用哪些功能以「运行时 API 等级」为准，设置页写的版本号可以造假。")
    }

    private fun runBluetoothCheck() {
        if (bluetoothRunning) return
        followTail = true
        bluetoothRunning = true
        bluetoothButton.isEnabled = false
        bluetoothButton.text = "蓝牙检测中…"
        clear()
        appendLine("==== 蓝牙能力自检 ====")
        appendLine("接下来会向 iPhone 试开蓝牙数据通道，请让手机解锁并留在车上；测试期间车机音频可能短暂中断。")
        Thread(
            {
                val outcome = runCatching {
                    BluetoothCapabilityProbe.run(applicationContext) { check -> publish(check) }
                }
                if (!alive) return@Thread
                outcome.exceptionOrNull()?.let { error ->
                    LegacyDiagnostics.append(
                        applicationContext,
                        LegacyDiagnostics.CRASH_FILE,
                        LegacyDiagnostics.crashEntry("BluetoothCapabilityProbe", error),
                    )
                    appendLine("蓝牙检测自身出错：${error.javaClass.simpleName}: ${error.message}")
                }
                outcome.getOrNull()?.let { checks ->
                    LegacyDiagnostics.append(
                        applicationContext,
                        LegacyDiagnostics.LOG_FILE,
                        buildString {
                            appendLine("==== 蓝牙能力自检 ====")
                            checks.forEach { appendLine("${it.status} ${it.label}：${it.detail}") }
                            appendLine(BluetoothCapabilityProbe.verdict(checks))
                        },
                    )
                    appendLine()
                    appendLine(BluetoothCapabilityProbe.verdict(checks).trimEnd())
                    appendLine()
                    appendLine("想把这页结果发给别人排查：回首页用「导出诊断日志」。")
                }
                runOnUiThread {
                    bluetoothRunning = false
                    bluetoothButton.isEnabled = true
                    bluetoothButton.text = "重新检测蓝牙能力"
                }
            },
            "legacy-bt-capability",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun publish(check: BluetoothCapabilityProbe.Check) {
        if (!alive) return
        val mark = when (check.status) {
            BluetoothCapabilityProbe.Status.PASS -> "✔"
            BluetoothCapabilityProbe.Status.WARN -> "⚠"
            BluetoothCapabilityProbe.Status.FAIL -> "✖"
            BluetoothCapabilityProbe.Status.SKIP -> "—"
            BluetoothCapabilityProbe.Status.INFO -> "·"
        }
        appendLine("$mark ${check.label}：${check.detail}")
    }

    private fun clear() {
        lines.setLength(0)
        flush()
    }

    private fun appendLine(text: String = "") {
        lines.appendLine(text)
        flush()
    }

    private fun flush() {
        val tail = followTail
        runOnUiThread {
            if (!alive) return@runOnUiThread
            resultView.text = lines.toString()
            scrollView.post {
                scrollView.fullScroll(if (tail) View.FOCUS_DOWN else View.FOCUS_UP)
            }
        }
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }
}
