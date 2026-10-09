package com.shilapi.xcertplay.legacy

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** A soak log can outgrow a 4.4 heap, so the export reads only its end. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LegacyDiagnosticsTailTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun aLongLogIsCarriedOnlyFromItsEndAndOnALineBoundary() {
        val directory = LegacyDiagnostics.targets(context).first().second.apply { mkdirs() }
        val body = (1..5_000).joinToString("\n") { "line-$it payload-padding-padding" }
        File(directory, LegacyDiagnostics.LOG_FILE).writeText(body)

        val tail = requireNotNull(
            LegacyDiagnostics.readTail(context, LegacyDiagnostics.LOG_FILE, maxBytes = 8_192),
        ) { "the written log must be found in one target" }
        assertTrue("expected a truncated read, got ${tail.text.length} of ${body.length}", tail.text.length < body.length)
        assertTrue(tail.text.endsWith("line-5000 payload-padding-padding"))
        assertTrue("the tail must not begin mid-line: " + tail.text.lineSequence().first(),
            tail.text.lineSequence().first().matches(Regex("line-\\d+ payload-padding-padding")))
    }
}
