package com.shilapi.xcertplay.transport

import android.bluetooth.BluetoothSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The reader parks without a timeout once its bounded buffer is full, so anything that frees space
 * has to wake it. A stalled reader is a silent wireless bootstrap failure on a head unit: the iAP2
 * side keeps sending, nothing arrives, and no exception is ever raised.
 */
class BluetoothRfcommDuplexStreamTest {
    /** RFCOMM-like source that keeps handing out full chunks until told to stop. */
    private class InfiniteSource : InputStream() {
        @Volatile var closed = false
        val reads = java.util.concurrent.atomic.AtomicInteger()
        override fun read(buffer: ByteArray): Int {
            if (closed) return -1
            reads.incrementAndGet()
            java.util.Arrays.fill(buffer, 7)
            return buffer.size
        }

        override fun read(): Int = if (closed) -1 else 7

        override fun available() = 8192
    }

    private fun socket(source: InputStream?, sink: OutputStream?): BluetoothSocket {
        val socket = mock(BluetoothSocket::class.java)
        `when`(socket.inputStream).thenReturn(source)
        `when`(socket.outputStream).thenReturn(sink)
        return socket
    }

    @Test fun drainingAFullPendingBufferKeepsTheReaderFeeding() {
        val source = InfiniteSource()
        val stream = BluetoothRfcommDuplexStream(socket(source, ByteArrayOutputStream()))
        // Park the reader against the limit before anyone consumes, the way a slow iAP2 handler is
        // while the iPhone keeps pushing bootstrap data; a racing consumer never fills the buffer.
        val deadline = System.nanoTime() + 2_000_000_000L
        while (source.reads.get() < FILLS_BEFORE_PARK && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals("reader should stop at the pending limit", FILLS_BEFORE_PARK, source.reads.get())
        var received = 0
        try {
            repeat(24) {
                val chunk = stream.recv(MAX_PENDING_BYTES, RECV_TIMEOUT_MILLIS) ?: ByteArray(0)
                received += chunk.size
            }
        } finally {
            source.closed = true
            stream.close()
        }
        // Without the wake-up the reader stays parked and the link silently stops delivering.
        assertTrue(
            "reader stalled after $received bytes with ${source.reads.get()} reads",
            received > MAX_PENDING_BYTES,
        )
    }

    @Test fun connectedSocketWithoutInputStreamFailsAsIoException() {
        val failure = runCatching {
            BluetoothRfcommDuplexStream(socket(null, ByteArrayOutputStream()))
        }.exceptionOrNull()
        assertEquals(IOException::class.java, failure?.javaClass)
        assertTrue(failure?.message?.contains("input stream") == true)
    }

    @Test fun connectedSocketWithoutOutputStreamFailsAsIoException() {
        val failure = runCatching {
            BluetoothRfcommDuplexStream(socket(InfiniteSource(), null))
        }.exceptionOrNull()
        assertEquals(IOException::class.java, failure?.javaClass)
        assertTrue(failure?.message?.contains("output stream") == true)
    }

    @Test fun throwingStreamGetterBecomesAnIoExceptionWithItsReason() {
        val socket = mock(BluetoothSocket::class.java)
        `when`(socket.inputStream).thenThrow(IllegalStateException("read ret: -1"))
        val failure = runCatching { BluetoothRfcommDuplexStream(socket) }.exceptionOrNull()
        assertEquals(IOException::class.java, failure?.javaClass)
        assertEquals("read ret: -1", failure?.cause?.message)
    }

    private companion object {
        const val MAX_PENDING_BYTES = 65_536
        const val FILLS_BEFORE_PARK = MAX_PENDING_BYTES / 8_192
        const val RECV_TIMEOUT_MILLIS = 250L
    }
}
