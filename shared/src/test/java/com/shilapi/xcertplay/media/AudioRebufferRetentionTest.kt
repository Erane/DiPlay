package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Pausing an underrunned track keeps whatever PCM the hardware had not played yet. The renderer has
 * to count that residual toward the restart threshold, or a low-buffer unit refills a paused track
 * and calls it a fresh start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [AudioRebufferRetentionTest.ResidualTrack::class])
class AudioRebufferRetentionTest {
    private val threshold = 4096
    private val rendererType = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")

    @Test fun aPausedTrackCountsItsResidualTowardTheRestartThreshold() {
        val renderer = renderer()
        try {
            ResidualTrack.head = 0
            ResidualTrack.underruns = 1
            ResidualTrack.paused = false
            ResidualTrack.playing = true
            set(renderer, "track", track())
            set(renderer, "mappedChannel", AudioChannel.MEDIA)
            set(renderer, "playbackStarted", true)
            set(renderer, "startThresholdBytes", threshold)
            set(renderer, "lastPcmWriteNs", 1L)
            typed<AudioBufferProgress>(renderer, "bufferProgress")
                .written(threshold / 2)

            maintainPlaybackBuffer(renderer)

            assertTrue("the track was not paused", ResidualTrack.paused)
            assertEquals(threshold / 2, get(renderer, "prebufferBytes"))
            assertEquals(1, get(renderer, "rebufferCount"))
            // The wait before a short tail is played restarts, so the paused track is not
            // immediately played again with almost nothing buffered.
            assertFalse("the paused track resumed at once", ResidualTrack.playing)
        } finally {
            (get(renderer, "track") as AudioTrack).release()
        }
    }

    @Test fun aTrackStillHoldingMoreThanTheFloorKeepsPlaying() {
        val renderer = renderer()
        try {
            ResidualTrack.head = 0
            ResidualTrack.underruns = 1
            ResidualTrack.paused = false
            ResidualTrack.playing = true
            set(renderer, "track", track())
            set(renderer, "mappedChannel", AudioChannel.MEDIA)
            set(renderer, "playbackStarted", true)
            set(renderer, "startThresholdBytes", threshold)
            set(renderer, "prebufferBytes", threshold)
            typed<AudioBufferProgress>(renderer, "bufferProgress")
                .written(threshold + threshold / 2)

            maintainPlaybackBuffer(renderer)

            assertFalse(ResidualTrack.paused)
            assertEquals(threshold, get(renderer, "prebufferBytes"))
            assertEquals(0, get(renderer, "rebufferCount"))
        } finally {
            (get(renderer, "track") as AudioTrack).release()
        }
    }

    private fun track(): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().build())
        .setAudioFormat(
            AndroidAudioFormat.Builder()
                .setSampleRate(48_000)
                .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AndroidAudioFormat.CHANNEL_OUT_STEREO)
                .build(),
        )
        .setBufferSizeInBytes(threshold * 4)
        .build()

    private fun renderer(): Any {
        val constructor = rendererType.declaredConstructors.single { !it.isSynthetic }
        constructor.isAccessible = true
        return constructor.newInstance(
            AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media"),
            false, false, 0, 0,
            AudioFocusCoordinator(null, false, { }),
            0, 1000,
            { _: String -> },
            null,
            false,
        )
    }

    private fun maintainPlaybackBuffer(renderer: Any) =
        rendererType.getDeclaredMethod("maintainPlaybackBuffer")
            .apply { isAccessible = true }
            .invoke(renderer)

    private fun set(target: Any, name: String, value: Any?) =
        rendererType.getDeclaredField(name).apply { isAccessible = true }.set(target, value)

    private fun get(target: Any, name: String): Any? =
        rendererType.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun <T> typed(target: Any, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return get(target, name) as T
    }

    /** An AudioTrack whose buffer state the test drives directly. */
    @Implements(AudioTrack::class)
    class ResidualTrack {
        @Implementation fun getPlaybackHeadPosition(): Int = head
        @Implementation fun getUnderrunCount(): Int = underruns
        @Implementation fun pause() { paused = true; playing = false }
        @Implementation fun play() { playing = true }

        companion object {
            var head = 0
            var underruns = 0
            var paused = false
            var playing = true
        }
    }
}
