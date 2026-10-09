package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The iPhone reads the receiver's audio format mask before it routes anything, and withholding the
 * Opus bit on a unit without an Opus codec does not make it fall back to the PCM offered in the
 * same entry: it keeps media and navigation audio playing on the phone instead. Navigation audio
 * therefore has to be decoded in software on Android 4.x, never renegotiated away.
 */
class AirPlayAudioFormatNegotiationTest {
    private val opusBits = 0x70000000

    private val config = AirPlayConfig(
        deviceName = "test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
    )

    private val formats: List<Map<*, *>>
        get() = (AirPlayInfoPlist.build(config)["audioFormats"] as List<*>).filterIsInstance<Map<*, *>>()

    @Test fun everyStreamThatCanBeOpusKeepsOfferingIt() {
        listOf(101 to "default", 100 to "default", 100 to "alert", 100 to "telephony")
            .forEach { (type, audioType) ->
                val entry = formats.single { it["type"] == type && it["audioType"] == audioType }
                assertTrue("type=$type audioType=$audioType", (entry["audioOutputFormats"] as Int and opusBits) != 0)
            }
    }
}
