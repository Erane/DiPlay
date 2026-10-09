package com.shilapi.xcertplay.media

import org.concentus.OpusApplication
import org.concentus.OpusEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Navigation and alert audio arrive as Opus, and MediaCodec has no Opus decoder before Android 5.0,
 * so on 4.x the bundled Concentus port has to produce the samples itself. Concentus is pure Java, so
 * this round trip runs on the JVM exactly as it does on Dalvik.
 */
class SoftwareOpusDecoderTest {
    @Test
    fun decodesAnOpusPacketIntoLittleEndianPcm() {
        val encoder = OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP)
        encoder.setBitrate(24_000)
        val tone = ShortArray(FRAME_SAMPLES) { index ->
            (tone(index) * 12_000).toInt().toShort()
        }
        val packet = ByteArray(1_275)
        val packetBytes = encoder.encode(tone, 0, FRAME_SAMPLES, packet, 0, packet.size)
        assertTrue("the encoder produced no packet", packetBytes > 0)

        val decoder = SoftwareOpusDecoder(SAMPLE_RATE, 1)
        val produced = decoder.decode(packet.copyOf(packetBytes))
        assertEquals(FRAME_SAMPLES * 2, produced)

        var energy = 0L
        for (index in 0 until FRAME_SAMPLES) {
            val low = decoder.pcm[index * 2].toInt() and 0xff
            val high = decoder.pcm[index * 2 + 1].toInt()
            energy += abs((high shl 8) or low)
        }
        assertTrue("decoded output is silent: meanMagnitude=${energy / FRAME_SAMPLES}",
            energy / FRAME_SAMPLES > 500)
    }

    @Test
    fun malformedPacketsNeverThrowOutOfTheAudioWorker() {
        val decoder = SoftwareOpusDecoder(SAMPLE_RATE, 1)
        listOf(
            byteArrayOf(0, 0, 0, 0),
            byteArrayOf(-8, -2, -3, -4, -5),
            ByteArray(40) { (it * 37 % 251).toByte() },
        ).forEach { packet ->
            val produced = decoder.decode(packet)
            assertTrue("byte count must stay even: $produced", produced % 2 == 0)
        }
    }

    private fun tone(index: Int): Double = sin(2.0 * PI * 220.0 * index / SAMPLE_RATE)

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = 960
    }
}
