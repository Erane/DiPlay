package com.shilapi.xcertplay.media

import org.concentus.OpusDecoder

/**
 * Decodes the Opus navigation and alert streams where the platform cannot. MediaCodec only gained an
 * Opus decoder in Android 5.0, so on 4.x this is the only way a turn prompt reaches the speakers;
 * Concentus is pure Java, so it also runs on Dalvik.
 */
internal class SoftwareOpusDecoder(
    sampleRate: Int,
    private val channels: Int,
) {
    private val decoder = OpusDecoder(sampleRate, channels)
    private val samples = ShortArray(MAX_FRAME_SAMPLES * channels)

    /** Little-endian s16 output. Reused every frame, so it is only valid until the next [decode]. */
    val pcm = ByteArray(MAX_FRAME_SAMPLES * channels * 2)

    var failures = 0
        private set

    /** @return how many PCM bytes were produced, or 0 when the packet could not be decoded. */
    fun decode(packet: ByteArray): Int {
        val decoded = try {
            decoder.decode(packet, 0, packet.size, samples, 0, MAX_FRAME_SAMPLES, false)
        } catch (error: Exception) {
            failures++
            0
        }
        val bytes = decoded * channels * 2
        if (bytes <= 0 || bytes > pcm.size) return 0
        for (index in 0 until decoded * channels) {
            val value = samples[index].toInt()
            pcm[index * 2] = (value and 0xff).toByte()
            pcm[index * 2 + 1] = (value shr 8).toByte()
        }
        return bytes
    }

    private companion object {
        // An Opus packet can carry up to 120 ms, which is also the decoder's own frame ceiling.
        const val MAX_FRAME_SAMPLES = 5760
    }
}
