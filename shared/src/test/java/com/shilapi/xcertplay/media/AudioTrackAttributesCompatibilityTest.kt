package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AudioTrackAttributesCompatibilityTest {
    @Test
    @Config(sdk = [28])
    fun android9UsesTheConfiguredAttributesWithoutCallingTheNewGetter() {
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val track = buildTrack(attributes)
        try {
            assertSame(attributes, audioTrackAttributesForFocus(track, attributes))
        } finally {
            track.release()
        }
    }

    @Test
    @Config(sdk = [29])
    fun android10KeepsTheActualTrackAttributes() {
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val track = buildTrack(attributes)
        try {
            val different = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build()
            assertEquals(attributes, audioTrackAttributesForFocus(track, different))
        } finally {
            track.release()
        }
    }

    @Test
    @Config(sdk = [28])
    fun api21AudioTypesAreNeverFieldTypesInTheRendererPath() {
        // Dalvik checks a field store against the field's declared class, so a field typed with a
        // class that 4.x does not have throws NoClassDefFoundError on the Android 4.4 head units,
        // which killed the whole CarPlay session the moment audio started.
        val forbidden = setOf(AudioAttributes::class.java.name, AudioFocusRequest::class.java.name)
        listOf(
            "com.shilapi.xcertplay.media.AudioRenderer",
            "com.shilapi.xcertplay.media.AudioFocusCoordinator",
            "com.shilapi.xcertplay.media.AudioFocusCoordinator\$Entry",
        ).forEach { name ->
            val owner = Class.forName(name)
            owner.declaredFields.forEach { field ->
                assertFalse("$name.${field.name} is typed ${field.type.name}", field.type.name in forbidden)
            }
        }
    }

    private fun buildTrack(attributes: AudioAttributes): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(attributes)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(44100)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(4096)
        .build()
}
