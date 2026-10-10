package com.shilapi.xcertplay.legacy

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The recorded route is what a re-entry with no extra of its own is judged by, so the storage round
 * trip and the two intent shapes the host reads are tested together: a value written by a newer build
 * must read back as nothing chosen, and only the system's own attach intent may count as an insertion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LegacyTransportRecordTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun prefs() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Test fun nothingIsRecordedUntilTheOwnerHasChosen() {
        assertNull(AirPlayPersistence.loadLegacyTransport(context))
    }

    @Test fun theRecordedRouteSurvivesAReentryThatNamesNoRoute() {
        AirPlayPersistence.saveLegacyTransport(context, CarPlayTransport.WIRELESS)

        assertEquals(CarPlayTransport.WIRELESS, AirPlayPersistence.loadLegacyTransport(context))
    }

    @Test fun aValueFromAnotherBuildReadsAsUnsetInsteadOfThrowing() {
        prefs().edit().putString("legacy_transport", "BLUETOOTH").commit()

        assertNull(AirPlayPersistence.loadLegacyTransport(context))
    }

    @Test fun theWiredAndWirelessButtonsBothNameTheirRoute() {
        val wired = Intent().putExtra(LegacyCarPlayActivity.EXTRA_WIRELESS, false)
        val wireless = Intent().putExtra(LegacyCarPlayActivity.EXTRA_WIRELESS, true)
        val unnamed = Intent()

        assertEquals(CarPlayTransport.WIRED, explicitTransport(wired))
        assertEquals(CarPlayTransport.WIRELESS, explicitTransport(wireless))
        // The old home sent no extra for the cable at all, which is why a re-entry could not tell
        // "the owner chose wired" apart from "the owner only wanted to look".
        assertNull(explicitTransport(unnamed))
    }

    @Test fun onlyTheSystemsAttachIntentWithADeviceCountsAsAnInsertion() {
        val attach = Intent(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            .putExtra(UsbManager.EXTRA_DEVICE, Mockito.mock(UsbDevice::class.java))

        assertTrue(isUsbAttachment(attach))
        // The same action without the device parcel is a relaunch, not an insertion.
        assertFalse(isUsbAttachment(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)))
        assertFalse(isUsbAttachment(Intent().setClassName(context, "LegacyCarPlayActivity")))
    }

    private fun explicitTransport(intent: Intent): Any? = callOnHost("explicitTransport", intent)

    private fun isUsbAttachment(intent: Intent): Boolean =
        callOnHost("isUsbAttachmentIntent", intent) as Boolean

    /** Both helpers are private to the host; the decision they feed is what the owner sees. */
    private fun callOnHost(name: String, intent: Intent): Any? {
        val activity = Robolectric.buildActivity(LegacyCarPlayActivity::class.java, Intent()).create().get()
        val method = LegacyCarPlayActivity::class.java.getDeclaredMethod(name, Intent::class.java)
        method.isAccessible = true
        return method.invoke(activity, intent)
    }
}
