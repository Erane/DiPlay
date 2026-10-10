package com.shilapi.xcertplay.legacy

import com.shilapi.xcertplay.legacy.LegacyHomeGuide.Action
import com.shilapi.xcertplay.legacy.LegacyHomeGuide.Facts
import com.shilapi.xcertplay.legacy.LegacyHomeGuide.plan
import com.shilapi.xcertplay.legacy.LegacyHomeGuide.remaining
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home's next-step card is what a non-technical owner of an old head unit acts on, so it is
 * tested as pure logic. Two mistakes are worth guarding against specifically: telling someone their
 * unit has no Bluetooth because the ROM only starts that service when the radio is switched on, and
 * offering a cable route on a device that has no USB host at all.
 */
class LegacyHomeGuideTest {

    private fun readyUnit(
        hasUsbHost: Boolean = true,
        bluetoothEnabled: Boolean = true,
        adapterAvailable: Boolean = true,
        wifiSaved: Boolean = true,
        paired: Boolean = true,
        sessionActive: Boolean = false,
        sessionConnecting: Boolean = false,
        lastFailure: String? = null,
    ) = Facts(
        apiLevel = 19,
        hasUsbHost = hasUsbHost,
        hasBluetoothHardware = true,
        bluetoothAdapterAvailable = adapterAvailable,
        bluetoothEnabled = bluetoothEnabled,
        phoneRemembered = paired,
        lockdownPaired = paired,
        wifiSaved = wifiSaved,
        sessionActive = sessionActive,
        sessionConnecting = sessionConnecting,
        lastFailure = lastFailure,
    )

    @Test fun aLiveSessionOffersThePictureInsteadOfAnotherConnect() {
        val plan = plan(readyUnit(sessionActive = true))

        assertEquals(Action.OPEN_PROJECTION, plan.action)
        assertTrue(plan.headline.contains("已连接"))
        assertTrue(plan.wiredAvailable && plan.wirelessAvailable)
    }

    @Test fun aConnectingSessionTellsTheOwnerToLeaveTheCableAndScreenAlone() {
        val plan = plan(readyUnit(sessionConnecting = true))

        assertEquals(Action.RECONNECT, plan.action)
        assertTrue(plan.detail.contains("别拔"))
    }

    @Test fun aUnitWithNeitherRouteSaysSoAndPointsAtTheSelfTest() {
        val plan = plan(readyUnit(hasUsbHost = false).copy(hasBluetoothHardware = false))

        assertFalse(plan.wiredAvailable)
        assertFalse(plan.wirelessAvailable)
        assertEquals(Action.RUN_COMPAT_CHECK, plan.action)
    }

    /** A phone-shaped device (no USB host) must not be pushed at the cable button. */
    @Test fun withoutUsbHostTheCableRouteIsTurnedOffNotMerelyDeprioritised() {
        val plan = plan(readyUnit(hasUsbHost = false))

        assertFalse(plan.wiredAvailable)
        assertTrue(plan.wirelessAvailable)
        assertEquals(Action.CONNECT_WIRELESS, plan.action)
    }

    @Test fun bluetoothHardwareWithoutARadioIsTreatedAsSwitchedOffNotAbsent() {
        val plan = plan(readyUnit(bluetoothEnabled = false, adapterAvailable = false))

        assertTrue("the ROM starts the Bluetooth service only when the radio is on", plan.wirelessAvailable)
        assertEquals(Action.ENABLE_BLUETOOTH, plan.action)
        assertTrue(plan.headline.contains("打开车机蓝牙"))
    }

    @Test fun aMissingSsidSendsTheOwnerIntoTheWirelessSetupRatherThanAFailedConnect() {
        val plan = plan(readyUnit(wifiSaved = false))

        assertEquals(Action.START_WIRELESS_SETUP, plan.action)
        assertTrue(plan.checklist.any { !it.done && it.heading.contains("无线上网方式") })
    }

    @Test fun aReadyUnitStillCountsTheIphoneStepAsRemaining() {
        val plan = plan(readyUnit())

        assertEquals(Action.CONNECT_WIRELESS, plan.action)
        assertTrue(plan.headline.contains("可以连接"))
        assertEquals("only the iPhone side cannot be read from here", 1, remaining(plan))
    }

    @Test fun aPreviousFailureIsSurvivedNotHidden() {
        val plan = plan(readyUnit(lastFailure = "准备网络失败"))

        assertTrue(plan.attention.orEmpty().contains("准备网络失败"))
        assertEquals(Action.CONNECT_WIRELESS, plan.action)
    }

    @Test fun anUnpairedIphoneIsExplainedRatherThanReportedAsAMissingFeature() {
        val plan = plan(readyUnit(paired = false))
        val pairing = plan.checklist.first { it.heading.contains("配对") }

        assertFalse(pairing.done)
        assertTrue(pairing.caption.contains("设置"))
    }
}
