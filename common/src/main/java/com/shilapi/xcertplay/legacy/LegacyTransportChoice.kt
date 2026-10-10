package com.shilapi.xcertplay.legacy

import com.shilapi.xcertplay.orchestration.CarPlayTransport

/**
 * Which route a pre-21 session should start on, from what the launch asked for and what the owner
 * last chose.
 *
 * The question is separated from the activity the way [LegacyHomeGuide] separates its guidance:
 * choosing wrong here can tear down a picture the owner is already using and cost a full
 * re-handshake, so every branch of the ordering is one the test suite can drive without a device.
 */
internal object LegacyTransportChoice {

    /** Why a route was picked, so the session log says which case a field report was looking at. */
    enum class Source {
        USB_ATTACHMENT,
        EXPLICIT_REQUEST,
        PERSISTED_CHOICE,
        DEFAULT_WIRED,
    }

    data class Decision(val transport: CarPlayTransport, val source: Source)

    fun decide(
        usbAttachment: Boolean,
        explicitRequest: CarPlayTransport?,
        persistedChoice: CarPlayTransport?,
    ): Decision = when {
        // The system is reporting a matching device on the bus. That outranks history because a
        // cable plugged in for the first time has no recorded choice to fall back on.
        usbAttachment -> Decision(CarPlayTransport.WIRED, Source.USB_ATTACHMENT)
        // Tapping one of the two routes on the home screen is the owner speaking directly.
        explicitRequest != null -> Decision(explicitRequest, Source.EXPLICIT_REQUEST)
        persistedChoice != null -> Decision(persistedChoice, Source.PERSISTED_CHOICE)
        else -> Decision(CarPlayTransport.WIRED, Source.DEFAULT_WIRED)
    }
}
