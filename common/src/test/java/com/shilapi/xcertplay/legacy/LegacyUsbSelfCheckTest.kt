package com.shilapi.xcertplay.legacy

import com.shilapi.xcertplay.legacy.LegacyUsbSelfCheck.Device
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home's USB line is the only place an owner of a wired-only unit learns why the cable does
 * nothing, so the three failure states have to be distinguishable in text - no host feature, nothing
 * on the bus, and a device the app may not open - and the VID:PID has to come out readable enough to
 * be typed into a report.
 */
class LegacyUsbSelfCheckTest {

    private val iphone = Device(0x05AC, 0x12A8, permitted = true)
    private val bridge = Device(0x1A86, 0x5512, permitted = true)

    @Test fun aUnitWithoutHostFeatureSaysTheCableWillNeverBeSeen() {
        val verdict = LegacyUsbSelfCheck.verdict(hasUsbHost = false, devices = listOf(iphone))

        assertTrue(verdict.contains("没有 USB 主机功能"))
    }

    @Test fun anEmptyBusTellsTheOwnerToPlugSomethingIn() {
        val verdict = LegacyUsbSelfCheck.verdict(hasUsbHost = true, devices = emptyList())

        assertTrue(verdict.contains("总线上没有设备"))
    }

    @Test fun anIphoneWithoutPermissionIsNamedAsPermissionNotAbsence() {
        val verdict = LegacyUsbSelfCheck.verdict(
            hasUsbHost = true,
            devices = listOf(iphone.copy(permitted = false)),
        )

        assertTrue(verdict.contains("还没有授权"))
        assertTrue(verdict.contains("05ac:12a8"))
    }

    @Test fun anAuthorisedIphoneReadsAsReadyForTheCable() {
        val verdict = LegacyUsbSelfCheck.verdict(hasUsbHost = true, devices = listOf(iphone))

        assertTrue(verdict.contains("iPhone 05ac:12a8"))
        assertTrue(verdict.contains("已授权"))
    }

    @Test fun theCh341BridgeIsRecognisedByVendorAndProduct() {
        val verdict = LegacyUsbSelfCheck.verdict(hasUsbHost = true, devices = listOf(bridge))

        assertTrue(verdict.contains("MFi 桥接 1a86:5512"))
    }

    @Test fun aForeignDeviceIsShownByItsIdsInsteadOfBeingSilentlyDropped() {
        val verdict = LegacyUsbSelfCheck.verdict(
            hasUsbHost = true,
            devices = listOf(Device(0x1A86, 0x7523, permitted = true)),
        )

        assertTrue(verdict.contains("1a86:7523"))
        assertFalse(verdict.contains("已授权"))
    }
}
