package com.shilapi.xcertplay.legacy

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.TextView
import org.robolectric.shadows.ShadowActivity
import java.lang.reflect.InvocationTargetException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [29],
    shadows = [LegacyVpnConsentTest.LegacyStartActivityShadow::class, LegacyVpnConsentTest.LegacyPrepareShadow::class],
)
class LegacyVpnConsentTest {
    private lateinit var activity: LegacyCarPlayActivity

    private val status: String
        get() = ReflectionHelpers.getField<TextView>(activity, "statusView").text.toString()

    @Before fun setUp() {
        LegacyPrepareShadow.consent = Intent().setClassName(
            "com.android.vpndialogs", "com.android.vpndialogs.ConfirmDialog",
        )
        LegacyStartActivityShadow.started = 0
        LegacyStartActivityShadow.failure = ActivityNotFoundException("Missing OEM VPN dialog")
        activity = Robolectric.buildActivity(LegacyCarPlayActivity::class.java).create().get()
    }

    @After fun tearDown() {
        LegacyStartActivityShadow.failure = null
        LegacyPrepareShadow.consent = null
    }

    @Test fun aRomWithoutTheVpnDialogReportsItInsteadOfCrashingTheSession() {
        invoke("startSession")

        assertTrue(status.contains("车机无法打开 VPN 授权界面"))
        assertFalse(pending())
        assertNull(ReflectionHelpers.getField<Any?>(activity, "controller"))
        // The top-level handler used to log this as a session crash, which buried the real reason.
        val crash = LegacyDiagnostics.readTail(activity, LegacyDiagnostics.CRASH_FILE)?.text ?: ""
        assertFalse(crash.contains("startSession"))
    }

    @Test fun aBlockedConsentLaunchIsNamedInTheLog() {
        LegacyStartActivityShadow.failure = SecurityException("OEM blocked VPN activity")

        invoke("startSession")

        val log = LegacyDiagnostics.readTail(activity, LegacyDiagnostics.LOG_FILE)?.text ?: ""
        assertTrue(log.contains("failureClass=SecurityException"))
        assertTrue(log.contains("component=com.android.vpndialogs/com.android.vpndialogs.ConfirmDialog"))
        assertFalse(pending())
    }

    @Test fun aStrayResultWithoutAPendingAttemptStartsNothing() {
        onActivityResult(Activity.RESULT_OK)

        assertEquals(0, LegacyStartActivityShadow.started)
    }

    @Test fun theResultOfThePendingAttemptIsHonoredExactlyOnce() {
        LegacyStartActivityShadow.failure = null
        invoke("startSession")
        assertEquals(1, LegacyStartActivityShadow.started)
        assertTrue(pending())

        onActivityResult(Activity.RESULT_OK)

        // Consent restarts the wired session, which asks again because this ROM has not recorded
        // the grant; what must not happen is a second session stacked on the first stray result.
        assertTrue(pending())
        assertEquals(2, LegacyStartActivityShadow.started)
    }

    @Test fun aDeniedConsentLeavesNothingPending() {
        LegacyStartActivityShadow.failure = null
        invoke("startSession")

        onActivityResult(Activity.RESULT_CANCELED)

        assertFalse(pending())
        assertEquals(1, LegacyStartActivityShadow.started)
        assertTrue(status.contains("VPN 授权被拒绝"))
    }

    private fun onActivityResult(resultCode: Int) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "onActivityResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, LegacyCarPlayActivity.VPN_REQUEST),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, resultCode),
            ReflectionHelpers.ClassParameter.from(Intent::class.java, null),
        )
    }

    private fun pending(): Boolean = ReflectionHelpers.getField(activity, "awaitingVpnConsent")

    private fun invoke(name: String) {
        try {
            activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    @Implements(VpnService::class)
    class LegacyPrepareShadow {
        companion object {
            @JvmStatic var consent: Intent? = null

            @JvmStatic
            @Implementation
            fun prepare(context: Context): Intent? = consent
        }
    }

    /** The ROM's missing VPN dialog surfaces as a throw from the launch call itself. */
    @Implements(Activity::class)
    class LegacyStartActivityShadow : ShadowActivity() {
        companion object {
            @JvmStatic var started = 0
            @JvmStatic var failure: RuntimeException? = null
        }

        @Implementation
        protected fun startActivityForResult(intent: Intent, requestCode: Int) {
            started += 1
            failure?.let { throw it }
        }
    }
}
