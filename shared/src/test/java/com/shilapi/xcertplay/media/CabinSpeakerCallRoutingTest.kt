package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A call has to be routed with the music, not merely mapped in the table: the sink builds one
 * renderer per stream, and only the renderer's own selection decides the buffer plan, the ducking
 * and the output channel a call lands on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CabinSpeakerCallRoutingTest {
    private val rendererType = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")

    @Test fun aCabinSpeakerCallSelectsTheMediaChannel() {
        assertEquals(AudioChannel.PHONE, selection(renderer("telephony", false)).channel)
        val call = selection(renderer("telephony", true))
        assertEquals(AudioChannel.MEDIA, call.channel)
        assertEquals(AudioContentType.SPEECH, call.contentType)
    }

    @Test fun theFlagLeavesMusicAndGuidanceWhereTheyWere() {
        assertEquals(AudioChannel.MEDIA, selection(renderer("media", true)).channel)
        assertEquals(AudioChannel.NAVIGATION, selection(renderer("default", true)).channel)
    }

    private data class Selection(val channel: AudioChannel, val contentType: AudioContentType)

    private fun selection(renderer: Any): Selection {
        val value = rendererType.getDeclaredMethod("mappedSelection")
            .apply { isAccessible = true }
            .invoke(renderer)
        fun field(name: String) = value.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(value)
        return Selection(field("channel") as AudioChannel, field("contentType") as AudioContentType)
    }

    private fun renderer(audioType: String, speakerphoneCall: Boolean): Any {
        val constructor = rendererType.declaredConstructors.single { !it.isSynthetic }
        constructor.isAccessible = true
        return constructor.newInstance(
            AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, audioType),
            false, false, 0, 0,
            AudioFocusCoordinator(null, false, { }),
            0, 1000,
            { _: String -> },
            null,
            speakerphoneCall,
        )
    }
}
