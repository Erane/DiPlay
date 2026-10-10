package com.shilapi.xcertplay.legacy

import com.shilapi.xcertplay.transport.UsbDeviceId

/**
 * One line about the cable route, from what the USB stack can already see, before any session runs.
 *
 * A wired attempt can only be judged on the head unit itself, and the owner has three distinct
 * failures to tell apart - the unit has no USB host, nothing is on the bus, or a device the app has
 * not been allowed to open. Saying which one it is turns "插了线没反应" into something a report can
 * answer. Plain values in, text out, so the matching rules are testable without a device.
 */
object LegacyUsbSelfCheck {

    data class Device(val vendorId: Int, val productId: Int, val permitted: Boolean)

    /** The two devices the session host accepts, same values as `xml/usb_device_filter.xml`. */
    private const val APPLE_VENDOR_ID = 0x05AC
    private val CH341_MFI_BRIDGE = UsbDeviceId(0x1A86, 0x5512)

    fun verdict(hasUsbHost: Boolean, devices: List<Device>): String {
        if (!hasUsbHost) return "USB 自检：本机没有 USB 主机功能，插数据线不会被识别。"
        if (devices.isEmpty()) return "USB 自检：总线上没有设备，先把 iPhone 或 MFi 桥接插到车机的 USB 口。"
        val iphone = devices.firstOrNull { it.vendorId == APPLE_VENDOR_ID }
        val bridge = devices.firstOrNull {
            it.vendorId == CH341_MFI_BRIDGE.vendorId && it.productId == CH341_MFI_BRIDGE.productId
        }
        val target = iphone ?: bridge
        if (target == null) {
            return "USB 自检：只看到 ${devices.joinToString { describe(it) }}，" +
                "既不是 iPhone 也不是 MFi 桥接。"
        }
        if (!target.permitted) {
            return "USB 自检：已识别 ${describe(target)}，但还没有授权 DiPlay 打开它；" +
                "插上时弹出的「允许 DiPlay 访问此 USB 设备？」要点允许。"
        }
        return "USB 自检：${if (iphone != null) "iPhone" else "MFi 桥接"} ${describe(target)} 已接入并已授权。"
    }

    private fun describe(device: Device): String =
        "%04x:%04x".format(device.vendorId, device.productId)
}
