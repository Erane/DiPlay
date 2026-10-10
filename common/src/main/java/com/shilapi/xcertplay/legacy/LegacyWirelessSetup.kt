package com.shilapi.xcertplay.legacy

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.widget.ScrollView
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import com.shilapi.xcertplay.AirPlayPersistence

/**
 * The wireless route in three steps, because one dialog that explains two hotspot schemes and then
 * asks for a name and a password is what lost owners of a 4.4 unit reported.
 *
 * The two schemes are the owner's choice of *which network* the iPhone joins: the session only ever
 * binds the address the unit's Wi-Fi interface already holds (`LegacyCarPlayActivity.passiveWifiAddress`),
 * so nothing here promises to join or open a network for them.
 */
class LegacyWirelessSetup(
    private val activity: Activity,
    private val ui: LegacyStyle,
    private val onConnect: () -> Unit,
) {
    private var step = STEP_SCHEME
    private var sharedNetwork = true
    private var ssid = AirPlayPersistence.loadExistingWifiSsid(activity)
    private var passphrase = AirPlayPersistence.loadExistingWifiPassphrase(activity)
    private var ssidField: EditText? = null
    private var passField: EditText? = null
    private var dialog: AlertDialog? = null

    fun show() {
        sharedNetwork = ssid.isNotBlank()
        render()
    }

    private fun render() {
        // A step change rebuilds the whole dialog: Holo has no way to swap a custom view in place.
        dialog?.dismiss()
        val content = ui.column().apply { setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(2)) }
        content.addView(ui.progress(stepLabel()))
        when (step) {
            STEP_SCHEME -> schemeStep(content)
            STEP_NETWORK -> networkStep(content)
            else -> checklistStep(content)
        }
        // A car screen is 480 tall, so an over-tall custom view would push the buttons off the
        // bottom; scrolling the body keeps 下一步 and 保存并连接 always reachable.
        val scroll = ScrollView(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFillViewport = true
            addView(content)
        }
        val next = AlertDialog.Builder(activity)
            .setTitle("无线连接设置")
            .setView(scroll)
            .setPositiveButton(if (step == STEP_CHECKLIST) "保存并连接" else "下一步", null)
            .setNeutralButton("以后再说", null)
            .apply { if (step != STEP_SCHEME) setNegativeButton("上一步", null) }
            .show()
        dialog = next
        // The buttons a builder installs always dismiss, so they are re-bound: an empty network name
        // must stay on this step and say what is missing instead of moving on to a doomed connect.
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { advance() }
        next.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener { back() }
    }

    private fun stepLabel(): String = when (step) {
        STEP_SCHEME -> "第 1 步 · 共 3 步 · 选择上网方式"
        STEP_NETWORK -> "第 2 步 · 共 3 步 · 填写这个网络"
        else -> "第 3 步 · 共 3 步 · 连接前检查"
    }

    private fun advance() {
        capture()
        if (step == STEP_NETWORK && ssid.isBlank()) {
            render()
            return
        }
        if (step == STEP_CHECKLIST) {
            AirPlayPersistence.saveExistingWifiCredentials(activity, ssid.trim(), passphrase)
            dialog?.dismiss()
            onConnect()
            return
        }
        step += 1
        render()
    }

    private fun back() {
        capture()
        step -= 1
        render()
    }

    /** The text fields exist only on step 2, so leaving any step folds what is on screen into the model. */
    private fun capture() {
        ssidField?.text?.toString()?.let { ssid = it }
        passField?.text?.toString()?.let { passphrase = it }
        ssidField = null
        passField = null
    }

    private fun schemeStep(content: LinearLayout) {
        // The 「第 1 步 · 选择上网方式」 line already says what this step asks, so on a short panel
        // the sentence goes first and this one does not: an AlertDialog whose body outruns the
        // window paints its own buttons across the bottom row.
        if (!ui.short) {
            content.addView(ui.body("iPhone 和车机要在同一个网络里。选一种你的车机做得到的方式："))
        }
        content.addView(
            ui.choiceRow(
                "车机和 iPhone 连同一个 Wi-Fi（推荐）",
                if (ui.short) {
                    "另一台手机的热点、随身 Wi-Fi 或家里路由器都行。"
                } else {
                    "另一台手机的热点、随身 Wi-Fi 或家里路由器都行。这条路更容易成功：部分车机自带热点打不开 CarPlay 需要的端口。"
                },
                selected = sharedNetwork,
            ) {
                sharedNetwork = true
                render()
            }
        )
        content.addView(
            ui.choiceRow(
                "用车机自带的热点，让 iPhone 连它",
                "先在车机设置里打开热点，再让 iPhone 连上它。",
                selected = !sharedNetwork,
            ) {
                sharedNetwork = false
                render()
            }
        )
    }

    private fun networkStep(content: LinearLayout) {
        if (!ui.short) {
            content.addView(ui.body("填写 iPhone 要加入的那个网络。车机自己也要已经连上（或正在开出）同一个网络。"))
        }
        val name = field("网络名称（SSID）", ssid, InputType.TYPE_CLASS_TEXT)
        val password = field(
            "网络密码",
            passphrase,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        ssidField = name
        passField = password
        content.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        content.addView(password, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        content.addView(
            ui.hint(
                if (ui.short) {
                    "车机自己也要连上同一个网络。"
                } else {
                    "密码只保存在这台车机上，用于记录与对照，不会上传。"
                }
            ).apply { setPadding(0, ui.dp(10), 0, 0) }
        )
        if (ssid.isBlank()) {
            content.addView(ui.warning("请先填写网络名称。").apply { setPadding(0, ui.dp(8), 0, 0) })
        }
    }

    private fun field(hint: String, value: String, inputType: Int) = EditText(activity).apply {
        this.hint = hint
        setText(value)
        setSelection(this.text.length)
        textSize = 16f
        setTextColor(LegacyStyle.TEXT)
        setHintTextColor(LegacyStyle.MUTED)
        this.inputType = inputType
        isSingleLine = true
    }

    private fun checklistStep(content: LinearLayout) {
        if (!ui.short) {
            content.addView(ui.body("确认这几条都满足，再点「保存并连接」："))
        }
        content.addView(ui.checkRow("iPhone 已连上这个网络", "$networkLabel：$ssid", ssid.isNotBlank()))
        content.addView(
            ui.checkRow("车机也在这个网络里", if (ui.short) "" else "车机不在网络上时，会话找不到自己的地址。", true)
        )
        content.addView(
            ui.checkRow(
                "iPhone 与车机配对过蓝牙",
                if (ui.short) "" else "没配对过也可以：连接过程中车机会再发起一次配对，两边都点「配对 / 允许」。",
                false,
            )
        )
        content.addView(ui.checkRow("iPhone 保持解锁、留在车上", if (ui.short) "" else "锁屏会让连接停下来。", false))
        content.addView(
            ui.hint(
                if (ui.short) {
                    "连接时系统会问「允许建立 VPN 连接？」——请点「允许」。"
                } else {
                    "连接时系统会问「允许建立 VPN 连接？」——请点「允许」：CarPlay 的网络要靠它建立，拒绝就连不上。"
                }
            ).apply { setPadding(0, ui.dp(10), 0, 0) }
        )
    }

    private val networkLabel: String
        get() = if (sharedNetwork) "网络名称" else "车机热点名称"

    private companion object {
        const val STEP_SCHEME = 0
        const val STEP_NETWORK = 1
        const val STEP_CHECKLIST = 2
    }
}
