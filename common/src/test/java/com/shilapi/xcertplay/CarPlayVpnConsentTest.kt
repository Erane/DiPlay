package com.shilapi.xcertplay

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import java.io.Closeable
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
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
@Config(sdk = [29], qualifiers = "en", shadows = [VpnConsentTestShadow::class])
class CarPlayVpnConsentTest {
    private lateinit var activity: CarPlayHostActivity
    private var launches = 0

    @Before fun setUp() {
        VpnConsentTestShadow.failure = null
        VpnConsentTestShadow.consent = Intent().setClassName(
            "com.android.vpndialogs", "com.android.vpndialogs.ConfirmDialog",
        )
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(activity, "stageStatusView", TextView(activity))
        invoke("initializeSessionLog")
        replaceLauncher { launches += 1 }
    }

    @After fun tearDown() {
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            ReflectionHelpers.getField<ExecutorService>(activity, name).shutdownNow()
        }
        (ReflectionHelpers.getField<Any?>(activity, "sessionLog") as? Closeable)?.close()
    }

    @Test fun missingConsentDialogDoesNotCrashOrLeaveAuthorizationPending() {
        ReflectionHelpers.setField(activity, "vpnReady", true)
        replaceLauncher { throw ActivityNotFoundException("Missing OEM VPN dialog") }

        invoke("requestVpnConsent")

        assertUnavailable()
        assertTrue(log().contains("failureClass=ActivityNotFoundException"))
        assertTrue(log().contains("component=com.android.vpndialogs/com.android.vpndialogs.ConfirmDialog"))
    }

    @Test fun blockedConsentLaunchDoesNotCrashOrUnlockUsb() {
        replaceLauncher { throw SecurityException("OEM blocked VPN activity") }

        invoke("requestVpnConsent")

        assertUnavailable()
        assertTrue(log().contains("operation=launch"))
        assertTrue(log().contains("failureClass=SecurityException"))
    }

    @Test fun pendingAuthorizationDoesNotLaunchTwice() {
        repeat(2) { invoke("requestVpnConsent") }

        assertFalse(field("vpnReady"))
        assertTrue(field("awaitingVpnConsent"))
        assertEquals(1, launches)
    }

    @Test fun failedLaunchCanBeRetried() {
        replaceLauncher { throw ActivityNotFoundException("Missing OEM VPN dialog") }
        invoke("requestVpnConsent")
        replaceLauncher { launches += 1 }

        invoke("requestVpnConsent")

        assertTrue(field("awaitingVpnConsent"))
        assertFalse(field("vpnReady"))
        assertEquals(1, launches)
    }

    @Test fun lateSuccessfulResultAfterFailedLaunchDoesNotUnlockUsb() {
        replaceLauncher { throw ActivityNotFoundException("Missing OEM VPN dialog") }
        invoke("requestVpnConsent")

        deliverResult(Activity.RESULT_OK)

        assertFalse(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
        assertNull(ReflectionHelpers.getField<Any?>(activity, "controller"))
    }

    @Test fun resultOfPendingConsentCompletesOrDeniesAuthorization() {
        invoke("requestVpnConsent")

        deliverResult(Activity.RESULT_OK)
        assertTrue(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))

        invoke("requestVpnConsent")
        deliverResult(Activity.RESULT_CANCELED)
        assertFalse(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
    }

    @Test fun grantedAuthorizationDoesNotOpenAConsentActivity() {
        VpnConsentTestShadow.consent = null

        invoke("requestVpnConsent")

        assertTrue(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
        assertEquals(0, launches)
    }

    @Test fun anUnanswerablePrepareIsLeftToTheTunnelNotToTheConsentScreen() {
        // compat-4.4 deliberately differs from upstream here: CarPlayVpnService.prepare contains the
        // ROM failure itself, and establish stays the real permission gate.
        VpnConsentTestShadow.failure = SecurityException("OEM blocked VPN preparation")

        invoke("requestVpnConsent")

        assertTrue(field("vpnReady"))
        assertEquals(0, launches)
    }

    @Test fun theUnavailableMessageIsNotRewrittenAsAGenericStage() {
        replaceLauncher { throw ActivityNotFoundException("Missing OEM VPN dialog") }

        invoke("requestVpnConsent")

        val message = ReflectionHelpers.getField<TextView>(activity, "stageStatusView").text.toString()
        assertTrue(message.contains("connection permissions"))
        assertFalse(message.contains("Exception"))
        assertFalse(message.contains("adb shell"))
    }

    private fun deliverResult(resultCode: Int) = ReflectionHelpers.callInstanceMethod<Unit>(
        activity,
        "onVpnConsentResult",
        ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, resultCode),
    )

    private fun replaceLauncher(launch: (Intent) -> Unit) {
        ReflectionHelpers.setField(activity, "vpnConsent", object : ActivityResultLauncher<Intent>() {
            override fun launch(input: Intent, options: ActivityOptionsCompat?) = launch(input)
            override fun unregister() = Unit
            override fun getContract() = ActivityResultContracts.StartActivityForResult()
        })
    }

    private fun assertUnavailable() {
        assertFalse(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
        assertNull(ReflectionHelpers.getField<Any?>(activity, "controller"))
        val message = ReflectionHelpers.getField<TextView>(activity, "stageStatusView").text.toString()
        assertTrue(message.contains("VPN authorization"))
        assertTrue(message.contains("Return to DiPlay"))
    }

    private fun field(name: String): Boolean = ReflectionHelpers.getField(activity, name)

    private fun log(): String {
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        return File(activity.filesDir, "logs/diplay.log").readText()
    }

    private fun invoke(name: String) {
        try {
            activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }
}

/** Java cannot name a Boolean-shaped helper for the AtomicBoolean teardown field. */
private typealias AtomicBooleanShim = java.util.concurrent.atomic.AtomicBoolean

@Implements(VpnService::class)
class VpnConsentTestShadow {
    companion object {
        var consent: Intent? = null
        var failure: RuntimeException? = null

        @JvmStatic
        @Implementation
        fun prepare(context: Context): Intent? {
            failure?.let { throw it }
            return consent
        }
    }
}
