package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * A head unit without adb only gives us the session log, so a wrapped failure has to carry the
 * reason that made it fail rather than the wrapper's text alone.
 */
class SessionFailureDescriptionTest {
    @Test fun theWrapperAndTheRealCauseBothReachTheLog() {
        val inner = IllegalStateException("bulk transfer rejected by the controller")
        val outer = IOException("USBMUX read failed", inner)
        val text = describeThrowable(outer)
        assertTrue(text, text.startsWith("IOException: USBMUX read failed"))
        assertTrue(text, text.contains("<- IllegalStateException: bulk transfer rejected by the controller"))
        assertTrue(
            "each entry names the frame it came from: $text",
            Regex("@ [\\w.]+\\.[\\w$]+\\([\\w.]+:\\d+\\)").containsMatchIn(text),
        )
    }

    @Test fun aThrowableWithoutAMessageOrCauseIsStillNamed() {
        val text = describeThrowable(NullPointerException())
        assertTrue(
            text,
            text.startsWith(
                "NullPointerException @ SessionFailureDescriptionTest" +
                    ".aThrowableWithoutAMessageOrCauseIsStillNamed(SessionFailureDescriptionTest.kt:",
            ),
        )
        assertFalse(text, text.contains("<-"))
    }

    @Test fun theChainIsBoundedEvenWhenCausesPointAtEachOther() {
        val second = IOException("loop")
        val first = IOException("loop", second)
        second.initCause(first)
        assertEquals(5, describeThrowable(first).split(": loop").size - 1)
    }

    @Test fun aDeepChainStopsAtTheLimit() {
        var error: Throwable = IllegalStateException("root cause")
        repeat(8) { error = IOException("layer", error) }
        val text = describeThrowable(error)
        assertEquals(5, text.split(": layer").size - 1)
        assertFalse("the limit drops the oldest causes: $text", text.contains("root cause"))
    }
}
