package com.shilapi.xcertplay.legacy

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * A pre-21 session can only be rebuilt by tearing it down, and on these units a rebuild costs the
 * owner tens of seconds of black screen - sometimes, on the K2X, a re-enumeration freeze. So the
 * difference between "I want to look" and "I want to reconnect" is worth pinning down: re-entry that
 * names no route, and a cable the owner never asked to switch to, must leave the live session alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LegacySessionReentryTest {

    private fun hostWithRunningWirelessSession(): Pair<LegacyCarPlayActivity, CarPlayController> {
        val activity = Robolectric
            .buildActivity(LegacyCarPlayActivity::class.java, Intent())
            .create()
            .get()
        val controller = Mockito.mock(CarPlayController::class.java)
        ReflectionHelpers.setField(activity, "controller", controller)
        ReflectionHelpers.setField(activity, "sessionTransport", CarPlayTransport.WIRELESS)
        return activity to controller
    }

    private fun controllerOf(activity: LegacyCarPlayActivity): Any? =
        ReflectionHelpers.getField<Any?>(activity, "controller")

    /** `onNewIntent` keeps Activity's protected visibility, so the launch arrives the way the OS sends it. */
    private fun reenter(activity: LegacyCarPlayActivity, intent: Intent) {
        val method = LegacyCarPlayActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
        method.isAccessible = true
        method.invoke(activity, intent)
    }

    @Test fun aReentryThatNamesNoRouteKeepsTheSessionItWasAskedToShow() {
        val (activity, controller) = hostWithRunningWirelessSession()

        // The home card's 打开 CarPlay 画面 used to relaunch the host with no extra at all, which the
        // old default read as "the cable", dropping a working wireless picture.
        reenter(activity, Intent())

        assertSame(controller, controllerOf(activity))
    }

    @Test fun anUnattendedPlugKeepsTheRouteAlreadyOnScreen() {
        val (activity, controller) = hostWithRunningWirelessSession()
        val attach = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            .putExtra(UsbManager.EXTRA_DEVICE, Mockito.mock(UsbDevice::class.java))

        reenter(activity, attach)

        assertSame(controller, controllerOf(activity))
    }

    @Test fun anExplicitTapOnTheSameRouteIsStillAReconnect() {
        val (activity, _) = hostWithRunningWirelessSession()

        reenter(activity, Intent().putExtra(LegacyCarPlayActivity.EXTRA_WIRELESS, true))

        // The teardown has begun, which is what a stuck owner on a unit whose debug block is hidden
        // needs: the two route buttons stay the way to force a fresh handshake.
        assertNull(controllerOf(activity))
    }

    @Test fun anExplicitTapOnTheOtherRouteSwitches() {
        val activity = hostWithRunningWirelessSession().first

        reenter(activity, Intent().putExtra(LegacyCarPlayActivity.EXTRA_WIRELESS, false))

        assertNull(controllerOf(activity))
    }
}
