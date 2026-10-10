package com.shilapi.xcertplay.legacy

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.legacy.LegacyHomeGuide.Action
import com.shilapi.xcertplay.legacy.LegacyHomeGuide.Facts
import com.shilapi.xcertplay.legacy.LegacyHomeGuide.Plan

/**
 * View-based home screen for pre-21 units (the upstream home is Compose and needs API 23).
 *
 * It is laid out the way the modern home is — a status card that says what is happening and what to
 * do next, then the two connection routes, then the settings an owner might need — because on these
 * car ROMs the owner has no manual, only this screen. Pure framework views throughout, so it opens
 * on Android 4.3/4.4; the guidance itself is decided in [LegacyHomeGuide] and only rendered here.
 */
class LegacyHomeActivity : Activity() {

    private val ui by lazy { LegacyStyle(this) }
    private var lastFailure: String? = null
    private var renderedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ui.applyWindowTheme(this)
        super.onCreate(savedInstanceState)
        readLatestFailure()
        render()
    }

    override fun onResume() {
        super.onResume()
        // Bluetooth may have been switched on, and a session may have started or ended, in the
        // screens this one launches; the status card is only worth having if it re-reads them.
        if (renderedOnce) render()
    }

    // ---- facts ----

    /**
     * Everything the guidance needs, read without blocking. The Bluetooth adapter object is null on
     * ROMs that only start that service once the radio is on, so hardware presence is asked of the
     * package manager separately rather than inferred from the adapter.
     */
    private fun facts(): Facts {
        val features = packageManager
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        val remembered = runCatching { DiPlayPreferences.phoneAddress(this) != null }.getOrDefault(false)
        val paired = runCatching { AirPlayPersistence.loadLockdownRecord(this) != null }.getOrDefault(false)
        return Facts(
            apiLevel = Build.VERSION.SDK_INT,
            hasUsbHost = runCatching {
                features.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
            }.getOrDefault(false),
            hasBluetoothHardware = runCatching {
                features.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
            }.getOrDefault(false),
            bluetoothAdapterAvailable = adapter != null,
            bluetoothEnabled = runCatching { adapter != null && adapter.isEnabled }.getOrDefault(false),
            phoneRemembered = remembered,
            lockdownPaired = paired,
            wifiSaved = AirPlayPersistence.loadExistingWifiSsid(this).isNotBlank(),
            sessionActive = CarPlayBackgroundSession.active,
            sessionConnecting = CarPlayBackgroundSession.hasSession(),
            lastFailure = lastFailure,
        )
    }

    /** The newest crash in the app's own log, if it happened today — the file is cumulative. */
    private fun readLatestFailure() {
        val buildTime = runCatching {
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        Thread(
            {
                val tail = runCatching {
                    LegacyDiagnostics.readTail(applicationContext, LegacyDiagnostics.CRASH_FILE, maxBytes = 64 * 1024)
                }.getOrNull()?.text.orEmpty()
                val dayStart = System.currentTimeMillis() - 24L * 60 * 60 * 1000
                // An entry header is `==== <millis> <what> (SDK <n> ====`, and the file keeps every
                // crash since install, so both the day and the install time have to be checked.
                val newest = tail.split("====").map { it.trim() }
                    .mapNotNull { header ->
                        val millis = header.takeWhile { it.isDigit() }.toLongOrNull() ?: return@mapNotNull null
                        val what = header.substring(millis.toString().length)
                            .trim()
                            .substringBefore(" (SDK")
                            .trim()
                        what.takeIf { it.isNotEmpty() && millis >= dayStart && millis >= buildTime }
                            ?.let { millis to it }
                    }
                    .maxByOrNull { it.first }
                if (!isFinishing) {
                    runOnUiThread {
                        val found = newest?.second?.take(120)
                        // No crash today is the common case, and a rebuild costs a slow unit real time.
                        if (found != lastFailure) {
                            lastFailure = found
                            if (renderedOnce && !isFinishing) render()
                        }
                    }
                }
            },
            "legacy-home-crash-read",
        ).apply {
            isDaemon = true
            start()
        }
    }

    // ---- layout ----

    private fun render() {
        val plan = LegacyHomeGuide.plan(facts())
        val page = ui.page()
        val content = ui.pageContent()
        page.addView(content)

        if (ui.wide) {
            // A landscape head unit has the width this page's single column wastes and a third of
            // the height it needs, so the status card and the list it points at sit side by side.
            // The header stays full-width: squeezed into one column its subtitle wrapped to three
            // lines and pushed the primary action below the fold.
            val left = ui.column()
            left.addView(statusCard(plan))
            left.addView(ui.space(ui.sectionGapDp))
            left.addView(routeCard(plan))
            left.addView(ui.space(ui.sectionGapDp))
            left.addView(diagnosticsCard())

            val right = ui.column()
            right.addView(startCard(plan))
            right.addView(ui.space(ui.sectionGapDp))
            right.addView(soundCard())
            right.addView(ui.space(ui.sectionGapDp))
            right.addView(feedbackCard())

            val columns = ui.row(Gravity.TOP)
            columns.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            columns.addView(ui.gap(ui.sectionGapDp))
            columns.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(header())
            content.addView(ui.space(ui.sectionGapDp + 2))
            content.addView(columns)
            content.addView(footer())
        } else {
            content.addView(header())
            content.addView(ui.space(14))
            content.addView(statusCard(plan))
            content.addView(ui.space(ui.sectionGapDp))
            content.addView(routeCard(plan))
            content.addView(ui.space(ui.sectionGapDp))
            content.addView(startCard(plan))
            content.addView(ui.space(ui.sectionGapDp))
            content.addView(soundCard())
            content.addView(ui.space(ui.sectionGapDp))
            content.addView(diagnosticsCard())
            content.addView(ui.space(ui.sectionGapDp))
            content.addView(feedbackCard())
            content.addView(footer())
        }

        setContentView(page)
        renderedOnce = true
    }

    private fun header(): View {
        val row = ui.row(Gravity.CENTER_VERTICAL)
        row.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_carplay)
                contentDescription = "DiPlay"
            },
            LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)),
        )
        val names = ui.column().apply { setPadding(ui.dp(10), 0, 0, 0) }
        names.addView(ui.title("DiPlay", 22))
        names.addView(ui.hint("把 iPhone 的 CarPlay 带到你的车机上"))
        row.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(
            ui.button("回到桌面", false, heightDp = 44) { goHome() }.apply { minWidth = ui.dp(88) },
            LinearLayout.LayoutParams(-2, -2),
        )
        return row
    }

    /** The card that answers "what is happening, and what now" before offering anything to tap. */
    private fun statusCard(plan: Plan): LinearLayout {
        val card = ui.heroCard()
        card.addView(ui.eyebrow("CARPLAY 状态"))
        card.addView(ui.space(4))
        card.addView(ui.title(plan.headline, 20))
        card.addView(ui.body(plan.detail, 14).apply { setPadding(0, ui.dp(6), 0, 0) })
        plan.attention?.let {
            card.addView(ui.warning(it).apply { setPadding(0, ui.dp(8), 0, 0) })
        }
        card.addView(
            ui.button(plan.actionLabel, primary = true) { perform(plan.action) },
            ui.buttonParams(14),
        )
        return card
    }

    /**
     * The two routes, kept out of the status card so that card is short enough to leave its own
     * action button on a 480-dp-tall landscape panel.
     */
    private fun routeCard(plan: Plan): View {
        val card = ui.card()
        // One route per line: side by side, these labels wrapped to three lines inside a half-width
        // button and the taller of the two made the card look broken.
        card.addView(
            modeButton("有线：USB 数据线", plan.wiredAvailable) { perform(Action.CONNECT_WIRED) },
            LinearLayout.LayoutParams(-1, -2),
        )
        card.addView(ui.space(8))
        card.addView(
            modeButton("无线：热点 / 同一 Wi-Fi", plan.wirelessAvailable) {
                if (plan.wirelessAvailable) perform(Action.CONNECT_WIRELESS) else perform(Action.RUN_COMPAT_CHECK)
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        val unavailable = when {
            !plan.wiredAvailable -> "本机没有 USB 主机功能，数据线不会被识别。"
            !plan.wirelessAvailable -> "本机没有蓝牙硬件能力，无线无法进行。"
            else -> null
        }
        unavailable?.let {
            card.addView(ui.hint(it).apply { setPadding(0, ui.dp(8), 0, 0) })
        }
        return card
    }

    private fun modeButton(label: String, available: Boolean, onClick: () -> Unit): View =
        ui.button(
            if (available) label else "$label（不可用）",
            primary = false,
            enabled = available,
            heightDp = ui.modeButtonHeightDp,
        ) { onClick() }.apply { textSize = 15f }

    /** The to-do list the status card refers to: what is already true and what the owner must still do. */
    private fun startCard(plan: Plan): View {
        val remaining = LegacyHomeGuide.remaining(plan)
        val heading = if (remaining == 0) "开始前检查" else "开始前还差 $remaining 步"
        return ui.section(heading, "满足这些条件后，点上方「${plan.actionLabel}」。") { card ->
            plan.checklist.forEach { item ->
                card.addView(ui.checkRow(item.heading, item.caption, item.done))
            }
            if (plan.wirelessAvailable) {
                card.addView(
                    ui.button("无线连接设置（3 步向导）", false) { showWirelessSetup() },
                    ui.buttonParams(14),
                )
            }
        }
    }

    private fun soundCard(): View = ui.section("声音", "只影响声音，不影响能不能连上；改完重新连接一次才生效。") { card ->
        card.addView(
            ui.switchRow(
                "导航播报压低音乐",
                "语音提示时把音乐音量降低，说完自动恢复。",
                { AirPlayPersistence.loadNavigationDuckEnabled(this@LegacyHomeActivity) },
                { AirPlayPersistence.saveNavigationDuckEnabled(this@LegacyHomeActivity, it) },
            )
        )
        card.addView(ui.divider())
        card.addView(
            ui.switchRow(
                "通话用车机喇叭和麦克风",
                "用音响放对方声音、用车机麦克风收音；默认走 iPhone。",
                { AirPlayPersistence.loadCallOnCabinSpeaker(this@LegacyHomeActivity) },
                { AirPlayPersistence.saveCallOnCabinSpeaker(this@LegacyHomeActivity, it) },
            )
        )
    }

    private fun diagnosticsCard(): View =
        ui.section("这台车机能连吗？", "想知道真实系统版本、或无线一直连不上时先跑这两项。") { card ->
            card.addView(ui.body(AndroidVersionProbe.summaryLine(), 13))
            card.addView(ui.hint("这一行也是反馈时最需要的一条信息。").apply { setPadding(0, ui.dp(4), 0, ui.dp(10)) })
            card.addView(
                ui.button("兼容性自检（安卓版本 / 蓝牙能力）") {
                    startActivity(Intent(this, LegacyCompatCheckActivity::class.java))
                },
                ui.buttonParams(),
            )
            card.addView(
                ui.hint("蓝牙检测会试开数据通道，耗时十几秒，期间音频可能短暂中断。")
                    .apply { setPadding(0, ui.dp(8), 0, 0) },
            )
            card.addView(
                ui.button("诊断信息") {
                    startActivity(Intent(this, com.shilapi.xcertplay.DiPlayProbeActivity::class.java))
                },
                ui.buttonParams(12),
            )
        }

    private fun feedbackCard(): View =
        ui.section("把情况反馈给我们", "停在中间某一步不是你的操作问题；导出日志发回来最有帮助。") { card ->
            card.addView(ui.button("导出诊断日志（保存到存储并分享）") { exportDiagnostics(card) }, ui.buttonParams())
            card.addView(ui.button("分享已保存的诊断文件") { shareSavedReport(card) }, ui.buttonParams(10))
        }

    private fun footer(): View = ui.body(
        "compat-4.4 体验版 · 上游 0.2.12 · 简化界面 · ${appVersion()}",
        12,
    ).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, ui.dp(16), 0, ui.dp(8))
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    // ---- actions ----

    private fun perform(action: Action) {
        val session = Intent(this, LegacyCarPlayActivity::class.java)
        when (action) {
            Action.OPEN_PROJECTION, Action.RECONNECT, Action.CONNECT_WIRED -> startActivity(session)
            Action.CONNECT_WIRELESS -> {
                session.putExtra(LegacyCarPlayActivity.EXTRA_WIRELESS, true)
                startActivity(session)
            }
            Action.START_WIRELESS_SETUP -> showWirelessSetup()
            Action.ENABLE_BLUETOOTH -> openBluetoothSettings()
            Action.RUN_COMPAT_CHECK -> startActivity(Intent(this, LegacyCompatCheckActivity::class.java))
        }
    }

    private fun openBluetoothSettings() {
        val opened = runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }.isSuccess
        if (!opened) {
            toast("这份 ROM 没有可打开的蓝牙设置页，请到车机设置里手动开启蓝牙。")
        }
    }

    private fun goHome() {
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        }.isSuccess
        if (!opened) finish()
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    // ---- wireless wizard ----

    private fun showWirelessSetup() {
        LegacyWirelessSetup(this, ui) { startWireless() }.show()
    }

    private fun startWireless() {
        val session = Intent(this, LegacyCarPlayActivity::class.java)
        session.putExtra(LegacyCarPlayActivity.EXTRA_WIRELESS, true)
        startActivity(session)
    }

    // ---- diagnostics export ----

    /**
     * A report on a 4.4 unit is read off the screen or pulled off a stick, so the same text goes to
     * every storage target first, then to a provider-visible copy an ordinary app can open. Only the
     * text is pasted when even that copy fails, because a full log is too large for a share intent.
     */
    private fun exportDiagnostics(card: LinearLayout) {
        val text = diagnosticText()
        val written = LegacyDiagnostics.saveSnapshot(this, text)
        val note = card.findViewWithTag<TextView>(EXPORT_TAG)
            ?: ui.hint("").apply { tag = EXPORT_TAG }.also { card.addView(it) }
        note.text = "已保存到：\n" + written.joinToString("\n") { it.toString() }
        val shareable = runCatching {
            com.shilapi.xcertplay.DiagnosticExportStore.saveWithoutPicker(this, "diplay-log", text)
        }.getOrNull()
        if (shareable != null) {
            shareReport(shareable.uri)
            return
        }
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "DiPlay 诊断日志")
                putExtra(Intent.EXTRA_TEXT, text)
            }, "分享诊断日志"))
        }.onFailure {
            note.text = note.text.toString() +
                "\n分享不可用(${it.javaClass.simpleName})，文件也已保存失败：请换一台设备读文件"
        }
    }

    /** Lets an owner re-share an earlier run: the newest saved reports, with size and time. */
    private fun shareSavedReport(card: LinearLayout) {
        val reports = com.shilapi.xcertplay.DiagnosticExportStore.savedReports(this)
        val note = card.findViewWithTag<TextView>(EXPORT_TAG)
            ?: ui.hint("").apply { tag = EXPORT_TAG }.also { card.addView(it) }
        if (reports.isEmpty()) {
            note.text = "还没有已保存的诊断文件。先按「导出诊断日志」。"
            return
        }
        val stamps = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
        val labels = reports.map { "${it.name}\n${it.length() / 1024} KB · ${stamps.format(java.util.Date(it.lastModified()))}" }
        AlertDialog.Builder(this)
            .setTitle("分享已保存的诊断文件")
            .setItems(labels.toTypedArray()) { _, index ->
                val chosen = runCatching {
                    shareReport(com.shilapi.xcertplay.DiagnosticExportStore.shareUri(this, reports[index]))
                }
                if (chosen.isFailure) {
                    note.text = "无法分享 ${reports[index].name}(${chosen.exceptionOrNull()})"
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** The file travels as a granted content URI, so the receiving app never needs storage permission. */
    private fun shareReport(uri: android.net.Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "DiPlay 诊断日志")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("DiPlay 诊断日志", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(send, "分享诊断日志")) }
            .onFailure { toast("分享目标不可用(${it.javaClass.simpleName})：请改用已保存到存储的文件") }
    }

    private fun diagnosticText(): String = buildString {
        appendLine(LegacyDiagnostics.platformReport(this@LegacyHomeActivity))
        for (name in listOf(
            LegacyDiagnostics.CRASH_FILE,
            LegacyDiagnostics.STARTED_FILE,
            LegacyDiagnostics.LOG_FILE,
        )) {
            val file = LegacyDiagnostics.readTail(this@LegacyHomeActivity, name)
            appendLine("==== $name ${file?.path ?: "(无)"} ====")
            appendLine(file?.text ?: "")
        }
    }

    private companion object {
        const val EXPORT_TAG = "diagnostics-result"
    }
}
