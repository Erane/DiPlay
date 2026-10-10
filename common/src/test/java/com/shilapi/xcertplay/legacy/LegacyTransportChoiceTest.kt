package com.shilapi.xcertplay.legacy

import com.shilapi.xcertplay.legacy.LegacyTransportChoice.Source
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which route a pre-21 session starts on is the one decision in this path that can drop a picture the
 * owner is already using, so the ordering is guarded branch by branch: a cable the system reports
 * outranks history, an owner's tap outranks history, and only a launch that named nothing follows the
 * recorded route.
 */
class LegacyTransportChoiceTest {

    private fun decide(
        usbAttachment: Boolean = false,
        explicit: CarPlayTransport? = null,
        persisted: CarPlayTransport? = null,
    ) = LegacyTransportChoice.decide(usbAttachment, explicit, persisted)

    @Test fun anAttachFromTheSystemStartsTheCableWhateverWasChosenBefore() {
        assertEquals(
            LegacyTransportChoice.Decision(CarPlayTransport.WIRED, Source.USB_ATTACHMENT),
            decide(usbAttachment = true, explicit = CarPlayTransport.WIRELESS, persisted = CarPlayTransport.WIRELESS),
        )
    }

    @Test fun aTapOnTheWiredRouteBeatsARecordedWirelessChoice() {
        assertEquals(
            LegacyTransportChoice.Decision(CarPlayTransport.WIRED, Source.EXPLICIT_REQUEST),
            decide(explicit = CarPlayTransport.WIRED, persisted = CarPlayTransport.WIRELESS),
        )
    }

    @Test fun aTapOnTheWirelessRouteBeatsARecordedWiredChoice() {
        assertEquals(
            LegacyTransportChoice.Decision(CarPlayTransport.WIRELESS, Source.EXPLICIT_REQUEST),
            decide(explicit = CarPlayTransport.WIRELESS, persisted = CarPlayTransport.WIRED),
        )
    }

    @Test fun aReentryThatNamedNoRouteFollowsTheRecordedRoute() {
        // This is the home card's 打开 CarPlay 画面: without this it silently restarted the session on
        // the cable, because 有线 used to be the default when no extra arrived.
        assertEquals(
            LegacyTransportChoice.Decision(CarPlayTransport.WIRELESS, Source.PERSISTED_CHOICE),
            decide(persisted = CarPlayTransport.WIRELESS),
        )
    }

    @Test fun anUntouchedUnitStillDefaultsToTheCable() {
        assertEquals(
            LegacyTransportChoice.Decision(CarPlayTransport.WIRED, Source.DEFAULT_WIRED),
            decide(),
        )
    }
}
