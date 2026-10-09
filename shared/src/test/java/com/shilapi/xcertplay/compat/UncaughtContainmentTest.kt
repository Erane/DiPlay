package com.shilapi.xcertplay.compat

import com.shilapi.xcertplay.network.isMdnsOwnedThread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Dalvik unit links a missing framework class or method with a `LinkageError`, and the process
 * handler used to hand anything that was not a JmDNS thread straight back to the platform. The bench
 * crash this gates had exactly that shape: `pool-2-thread-1` died on `Map.getOrDefault`, the process
 * went with it, and the user saw the app 闪退到桌面 while the iPhone kept refusing every reconnect.
 * Thread names and error classes below are copied from that device's `diplay-crash.txt`.
 */
class UncaughtContainmentTest {
    private val uiThread = Thread.currentThread()

    private fun threadNamed(name: String): Thread = Thread { }.apply { this.name = name }

    @Test
    fun `a library thread is still contained for an ordinary failure`() {
        val jmdnsThread = threadNamed("SocketListener(carplay-3E586F7E0476)")
        assertTrue(isMdnsOwnedThread(jmdnsThread.name))
        assertTrue(
            uncaughtFailureIsContained(jmdnsThread, IllegalStateException("socket closed"), uiThread),
        )
    }

    @Test
    fun `a background thread that cannot link a platform class does not end the process`() {
        for (name in listOf("pool-2-thread-1", "airplay-control", "airplay-audio-rx", "carplay-video")) {
            for (error in listOf<Throwable>(
                NoSuchMethodError("java.util.Map.getOrDefault"),
                NoClassDefFoundError("javax.jmdns.impl.DNSIncoming.MessageInputStream"),
                VerifyError("bad class"),
            )) {
                assertTrue("$name on $error", uncaughtFailureIsContained(threadNamed(name), error, uiThread))
            }
        }
    }

    @Test
    fun `the ui thread is still allowed to die`() {
        // Nothing left to present after it: only a new process gives the driver a screen back.
        assertFalse(
            uncaughtFailureIsContained(uiThread, NoClassDefFoundError("android.view.Surface"), uiThread),
        )
    }

    @Test
    fun `an ordinary bug on our own thread stays loud`() {
        assertFalse(
            uncaughtFailureIsContained(
                threadNamed("airplay-control"), IllegalStateException("bug"), uiThread,
            ),
        )
    }

    @Test
    fun `without a resolved main looper the ui thread is still recognised by name`() {
        // The handler installs in attachBaseContext, where a ROM may not expose the looper yet.
        assertTrue(uncaughtFailureIsContained(threadNamed("carplay-video"), VerifyError("x"), null))
        assertFalse(uncaughtFailureIsContained(threadNamed("main"), VerifyError("x"), null))
    }
}
