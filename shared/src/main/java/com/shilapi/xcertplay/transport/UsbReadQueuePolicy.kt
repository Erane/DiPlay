package com.shilapi.xcertplay.transport

import android.os.Build
import java.nio.ByteBuffer

internal data class UsbReadQueueResult(val queued: Boolean, val firstBytes: Int, val fallbackBytes: Int? = null)

/**
 * Caps one submitted USB read at a size the platform accepts.
 *
 * Android 8.0/8.1 throw instead of returning false when a queued buffer passes the 16 KiB usbfs
 * ceiling, so the first submission is capped there rather than being fatal. A rejected submission
 * (returning false) walks a halving ladder down to 2 KiB, and the size that was accepted is
 * remembered for the rest of the pipe — which is what pre-26 hosts that fail a 32 KiB submit need,
 * because their failure is indistinguishable from a timeout at the caller.
 * A rejected read that changed its buffer state is ambiguous, so it is never retried.
 * Call under the pipe's state lock, including publication, queueing and the open-state checks.
 */
internal class UsbReadQueuePolicy(private val queueCeiling: Int? = null) {
    private var successfulLimit: Int? = null

    fun queue(buffer: ByteBuffer, checkOpen: () -> Unit, submit: (ByteBuffer) -> Boolean): UsbReadQueueResult {
        require(buffer.isDirect && !buffer.isReadOnly) { "USB read requires a writable direct buffer" }
        checkOpen()
        val position = buffer.position()
        val originalLimit = buffer.limit()
        val originalRemaining = buffer.remaining()
        val firstBytes = minOf(
            originalRemaining, successfulLimit ?: originalRemaining, queueCeiling ?: originalRemaining,
        )
        buffer.limit(position + firstBytes)
        if (submitUnchanged(buffer, position, firstBytes, submit)) {
            return UsbReadQueueResult(true, firstBytes)
        }
        if (originalRemaining <= COMPATIBILITY_BYTES || firstBytes <= MIN_COMPATIBILITY_BYTES) {
            buffer.limit(originalLimit)
            return UsbReadQueueResult(false, firstBytes)
        }

        var size = if (firstBytes > COMPATIBILITY_BYTES) COMPATIBILITY_BYTES else {
            maxOf(MIN_COMPATIBILITY_BYTES, firstBytes / 2)
        }
        var lastAttempt = firstBytes
        while (size >= MIN_COMPATIBILITY_BYTES) {
            checkOpen()
            buffer.limit(position + size)
            lastAttempt = size
            if (submitUnchanged(buffer, position, size, submit)) {
                successfulLimit = size
                return UsbReadQueueResult(true, firstBytes, size)
            }
            size /= 2
        }
        buffer.limit(originalLimit)
        return UsbReadQueueResult(false, firstBytes, lastAttempt)
    }

    /** A false queue result is retryable only while its buffer range remains unchanged. */
    private fun submitUnchanged(
        buffer: ByteBuffer,
        position: Int,
        size: Int,
        submit: (ByteBuffer) -> Boolean,
    ): Boolean {
        if (submit(buffer)) return true
        check(buffer.position() == position && buffer.limit() == position + size) {
            "Rejected USB queue changed its buffer state"
        }
        return false
    }

    companion object {
        private const val COMPATIBILITY_BYTES = USBFS_BULK_URB_CEILING_BYTES
        private const val MIN_COMPATIBILITY_BYTES = 2 * 1024

        fun forCurrentPlatform(): UsbReadQueuePolicy = UsbReadQueuePolicy(
            // Below API 28 the 16 KiB usbfs bound applies to every read shape: queue(ByteBuffer)
            // throws there on 8.0/8.1, and the deprecated queue(ByteBuffer, Int) as well as
            // bulkTransfer report a larger submit as a plain failure. Capping the first submission
            // costs one short read at most, where a rejected submit costs a whole ladder walk.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) USBFS_BULK_URB_CEILING_BYTES else null,
        )
    }
}
