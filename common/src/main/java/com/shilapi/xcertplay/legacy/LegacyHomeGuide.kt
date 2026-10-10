package com.shilapi.xcertplay.legacy

/**
 * Decides what the pre-21 home screen should tell the owner to do next.
 *
 * Kept free of android imports and of any Context read, in the same way
 * [BluetoothCapabilityProbe.verdict] is: every input arrives as a plain value, so the guidance an
 * owner sees on a crippled car ROM is something the test suite can drive, and the screen that
 * renders it holds no logic of its own.
 */
object LegacyHomeGuide {

    /** Which button the home should put under the status, and where a tap goes. */
    enum class Action {
        OPEN_PROJECTION,
        RECONNECT,
        CONNECT_WIRED,
        START_WIRELESS_SETUP,
        CONNECT_WIRELESS,
        ENABLE_BLUETOOTH,
        RUN_COMPAT_CHECK,
    }

    data class ChecklistItem(val heading: String, val caption: String, val done: Boolean)

    /**
     * Everything the home can learn without blocking: [Facts] is filled by the screen, judged here.
     *
     * `bluetoothAdapterAvailable == false` does not mean the unit has no Bluetooth — on these ROMs
     * the Bluetooth service only starts once the radio is switched on, which is why [Action]
     * ENABLE_BLUETOOTH and RUN_COMPAT_CHECK are separate outcomes from wireless being impossible.
     */
    data class Facts(
        val apiLevel: Int,
        val hasUsbHost: Boolean,
        val hasBluetoothHardware: Boolean,
        val bluetoothAdapterAvailable: Boolean,
        val bluetoothEnabled: Boolean,
        val phoneRemembered: Boolean,
        val lockdownPaired: Boolean,
        val wifiSaved: Boolean,
        val sessionActive: Boolean,
        val sessionConnecting: Boolean,
        val lastFailure: String?,
        /** What the USB stack can see right now; only the cable card renders it, the plan ignores it. */
        val attachedUsbDevices: List<LegacyUsbSelfCheck.Device> = emptyList(),
    )

    data class Plan(
        val headline: String,
        val detail: String,
        val action: Action,
        val actionLabel: String,
        val checklist: List<ChecklistItem>,
        val wiredAvailable: Boolean,
        val wirelessAvailable: Boolean,
        /** Shown in the accent colour above the actions; null when there is nothing to flag. */
        val attention: String?,
    )

    /** The iPhone side of a connection can never be read from here, so it is always an open item. */
    private val phoneInHand = ChecklistItem(
        "iPhone 保持解锁、留在车上",
        "锁屏会让连接停下来。",
        done = false,
    )

    /** A satisfied condition needs no explanation; only the outstanding ones carry a caption. */
    private fun item(heading: String, done: Boolean, todoCaption: String) =
        ChecklistItem(heading, if (done) "" else todoCaption, done)

    fun plan(f: Facts): Plan {
        val wired = f.hasUsbHost
        val wireless = f.hasBluetoothHardware
        val paired = f.phoneRemembered || f.lockdownPaired

        if (f.sessionActive) {
            return Plan(
                headline = "已连接 · CarPlay 画面在车机上",
                detail = "现在可以直接用车上的地图、音乐和通话。要换回手机，请在 iPhone 上断开。",
                action = Action.OPEN_PROJECTION,
                actionLabel = "打开 CarPlay 画面",
                checklist = listOf(
                    ChecklistItem("会话正在运行", "如需重新握手，用画面页的「重新连接」。", true),
                ),
                wiredAvailable = wired,
                wirelessAvailable = wireless,
                attention = null,
            )
        }

        if (f.sessionConnecting) {
            return Plan(
                headline = "正在连接 iPhone…",
                detail = "别拔数据线、别锁 iPhone 屏幕。无线方式下这一步最长约等 35 秒。",
                action = Action.RECONNECT,
                actionLabel = "查看连接进度",
                checklist = listOf(
                    ChecklistItem("正在等待 iPhone 回应", "如果卡住超过一分钟，进去点「重新连接」。", false),
                ),
                wiredAvailable = wired,
                wirelessAvailable = wireless,
                attention = f.lastFailure?.let { "上一次失败：$it" },
            )
        }

        if (!wired && !wireless) {
            return Plan(
                headline = "这台设备用不了 DiPlay",
                detail = "它既没有 USB 主机功能，也没有声明蓝牙硬件能力，两种连接方式都起不来。" +
                    "可以先跑一次兼容性自检，确认这不是系统瞒报能力。",
                action = Action.RUN_COMPAT_CHECK,
                actionLabel = "兼容性自检",
                checklist = listOf(
                    ChecklistItem("没有 USB 主机功能", "无法通过数据线连接 iPhone。", false),
                    ChecklistItem("没有声明蓝牙硬件", "无法走无线 CarPlay。", false),
                ),
                wiredAvailable = false,
                wirelessAvailable = false,
                attention = "自检仍显示不通的话，这台车机需要换路线（见使用说明第五节）。",
            )
        }

        if (!wireless && wired) {
            return Plan(
                headline = "这台车机只能用数据线连接",
                detail = "系统没有蓝牙硬件能力，无线方式走不通；有线 CarPlay 不受影响。",
                action = Action.CONNECT_WIRED,
                actionLabel = "通过 USB 连接 iPhone",
                checklist = listOf(
                    item("准备一根能传数据的 USB 线", false, "只能充电的线不会被识别。"),
                    phoneInHand,
                    item("插上后在 iPhone 点「信任此电脑」", false, "需要输入锁屏密码。"),
                ),
                wiredAvailable = true,
                wirelessAvailable = false,
                attention = null,
            )
        }

        if (wireless && !f.bluetoothEnabled) {
            val adapterMissing = !f.bluetoothAdapterAvailable
            return Plan(
                headline = if (adapterMissing) "请先打开车机蓝牙" else "车机蓝牙当前是关闭的",
                detail = if (adapterMissing) {
                    "这类车机在蓝牙关闭时不会启动蓝牙服务，所以现在读不到适配器。" +
                        "到车机设置里打开蓝牙再回本页即可，不需要重启应用。"
                } else {
                    "到车机设置里把蓝牙打开，回到本页就能继续。"
                },
                action = Action.ENABLE_BLUETOOTH,
                actionLabel = "打开车机蓝牙设置",
                checklist = wirelessChecklist(f, paired),
                wiredAvailable = wired,
                wirelessAvailable = true,
                attention = null,
            )
        }

        if (wireless && !f.wifiSaved) {
            return Plan(
                headline = "先选一种无线上网方式",
                detail = "车机开热点给 iPhone 连，或者让车机和 iPhone 连同一个 Wi-Fi。后者成功率更高。",
                action = Action.START_WIRELESS_SETUP,
                actionLabel = "设置无线连接",
                checklist = wirelessChecklist(f, paired),
                wiredAvailable = wired,
                wirelessAvailable = true,
                attention = null,
            )
        }

        return Plan(
            headline = "可以连接了",
            detail = connectionDetail(f),
            action = if (f.wifiSaved) Action.CONNECT_WIRELESS else Action.CONNECT_WIRED,
            actionLabel = if (f.wifiSaved) "连接 iPhone" else "通过 USB 连接 iPhone",
            checklist = wirelessChecklist(f, paired),
            wiredAvailable = wired,
            wirelessAvailable = wireless,
            attention = f.lastFailure?.let { "上次连接未成功：$it。可以重试，导出日志发回来对我们最有用。" },
        )
    }

    /** What a wireless run still needs, in the order the owner can act on it. */
    private fun wirelessChecklist(f: Facts, paired: Boolean): List<ChecklistItem> = listOf(
        item("车机蓝牙已打开", f.bluetoothEnabled, "到车机设置里把蓝牙打开。"),
        item(
            "iPhone 与车机配对过蓝牙",
            paired,
            "在 iPhone「设置 → 蓝牙」里选中车机名字，两边都点「配对」。",
        ),
        item("已填好无线上网方式", f.wifiSaved, "点「设置无线连接」填网络名称和密码。"),
        phoneInHand,
    )

    private fun connectionDetail(f: Facts): String = if (f.wifiSaved && f.hasBluetoothHardware) {
        "把 iPhone 解锁放到车上，点下面的连接；弹出配对和「允许建立 VPN 连接？」时都点允许。"
    } else {
        "把 iPhone 用数据线插到车机的 USB 口，手机上弹「信任此电脑」时点信任。"
    }

    /** How many checklist items still need the owner's attention — the home's 「还差 2 步」 hint. */
    fun remaining(plan: Plan): Int = plan.checklist.count { !it.done }
}
