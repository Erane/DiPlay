package com.shilapi.xcertplay.legacy

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Compatibility self-test screen for pre-21 car units.
 *
 * The two questions an owner of an old head unit actually has are "what Android is this really"
 * (car ROMs rewrite the version the settings page shows) and "is its Bluetooth crippled to
 * audio-only, which would decide whether wireless CarPlay is possible at all". Each question is a
 * card that says what it will measure before it is tapped, and the answer streams into one result
 * area whose lines are coloured by outcome, because a wall of grey text on a car screen at arm's
 * length is unreadable. The Bluetooth part blocks for tens of seconds, so its lines arrive as the
 * probe finishes each check rather than at the end.
 */
class LegacyCompatCheckActivity : Activity() {
    private val ui by lazy { LegacyStyle(this) }
    private lateinit var resultArea: LinearLayout
    private lateinit var resultCard: View
    private lateinit var page: ScrollView
    private lateinit var bluetoothButton: Button

    @Volatile private var alive = true
    @Volatile private var bluetoothRunning = false

    /** The Bluetooth report streams in over tens of seconds and should track its tail; the version
     *  report is written at once and starts with the answer, so it stays at the top. */
    private var followTail = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ui.applyWindowTheme(this)
        super.onCreate(savedInstanceState)

        page = ui.page()
        val content = ui.pageContent()
        page.addView(content)

        val header = ui.row(Gravity.CENTER_VERTICAL)
        header.addView(
            ui.button("返回", false, heightDp = 44) { finish() }.apply { minWidth = ui.dp(76) },
            LinearLayout.LayoutParams(-2, -2),
        )
        val names = ui.column().apply { setPadding(ui.dp(10), 0, 0, 0) }
        names.addView(ui.title("兼容性自检", 21))
        if (!ui.short) {
            names.addView(ui.hint("结果只写在屏幕上，不会外发；要发给别人请用首页的「导出诊断日志」"))
        }
        header.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(header)
        content.addView(ui.space(ui.sectionGapDp + 2))

        val versionCard = ui.section(
            "这台车机的真实安卓版本",
            if (ui.short) {
                "设置页显示的版本可能是 ROM 改出来的假号。"
            } else {
                "不少车机设置页显示的版本是 ROM 改出来的假号。这里以运行时 API 等级为准。"
            },
        ) { card ->
            card.addView(
                ui.button("检测真实安卓版本") { showVersion() },
                ui.buttonParams(),
            )
        }
        val bluetoothCard = ui.section(
            "蓝牙能力（决定无线能否走通）",
            if (ui.short) {
                "检查适配器、配对、服务记录和蓝牙数据串口。"
            } else {
                "逐项检查适配器、配对、服务记录和蓝牙数据串口，并试着连上你的 iPhone。"
            },
        ) { card ->
            bluetoothButton = ui.button("检测蓝牙能力") { runBluetoothCheck() }
            card.addView(bluetoothButton, ui.buttonParams())
            card.addView(
                ui.hint(
                    if (ui.short) {
                        "耗时约十几秒，期间车机音频可能短暂中断。"
                    } else {
                        "耗时约十几秒，期间车机音频可能短暂中断。请让 iPhone 解锁并留在车上；" +
                            "正在连着 CarPlay 时请先断开再测。"
                    }
                ).apply { setPadding(0, ui.dp(10), 0, 0) }
            )
        }
        resultArea = ui.column()
        resultCard = ui.section("检测结果", null) { card ->
            card.addView(resultArea)
            card.addView(
                ui.hint("选一项开始检测。").apply { tag = EMPTY_TAG },
            )
        }

        if (ui.wide) {
            // On a landscape panel the two checks are the short things and the report is the long
            // one, so the controls sit side by side above a full-width result: the report's lines
            // read better wide, and neither control card has to share a narrow column.
            val controls = ui.row(Gravity.TOP)
            controls.addView(versionCard, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(ui.gap(ui.sectionGapDp))
            controls.addView(bluetoothCard, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(controls)
            content.addView(ui.space(ui.sectionGapDp))
            content.addView(resultCard)
        } else {
            content.addView(versionCard)
            content.addView(ui.space(ui.sectionGapDp))
            content.addView(bluetoothCard)
            content.addView(ui.space(ui.sectionGapDp))
            content.addView(resultCard)
        }
        setContentView(page)
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
        revealResult()
    }

    /**
     * Bring the report to the owner. In portrait the report is the last card and its first line is
     * the answer, so the top of the page is the right place; on a landscape panel the report starts
     * below the two control cards, so scrolling there would hide what was just written.
     */
    private fun revealResult() {
        page.post {
            if (ui.wide) {
                page.smoothScrollTo(0, maxOf(0, resultCard.top - ui.dp(8)))
            } else {
                page.fullScroll(View.FOCUS_UP)
            }
        }
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
                    if (alive) {
                        bluetoothButton.isEnabled = true
                        bluetoothButton.text = "重新检测蓝牙能力"
                    }
                }
            },
            "legacy-bt-capability",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun publish(check: BluetoothCapabilityProbe.Check) {
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
        runOnUiThread {
            resultArea.removeAllViews()
            resultArea.findViewWithTag<android.widget.TextView>(EMPTY_TAG)?.text = "检测中…"
        }
    }

    private fun appendLine(text: String = "") {
        if (!alive) return
        runOnUiThread {
            if (!alive) return@runOnUiThread
            resultArea.findViewWithTag<android.widget.TextView>(EMPTY_TAG)?.visibility = View.GONE
            resultArea.addView(colouredLine(text))
            if (followTail) page.post { page.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** An outcome per line colour, so the eye lands on what failed instead of reading every row. */
    private fun colouredLine(text: String): View {
        val color = when {
            text.startsWith("====") -> LegacyStyle.ACCENT
            text.startsWith("结论") -> LegacyStyle.TEXT
            text.startsWith("✔") -> LegacyStyle.SUCCESS
            text.startsWith("✖") -> LegacyStyle.WARNING
            text.startsWith("⚠") -> LegacyStyle.WARNING
            text.isBlank() -> LegacyStyle.MUTED
            else -> LegacyStyle.MUTED
        }
        return ui.body(text, if (text.startsWith("结论")) 15 else 13).apply {
            setTextColor(color)
            setPadding(0, ui.dp(2), 0, ui.dp(2))
        }
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }

    private companion object {
        const val EMPTY_TAG = "empty-hint"
    }
}
