package com.shilapi.xcertplay.legacy

import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.CarPlaySessionDisplay
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** CarPlay takes its canvas only at handshake, so a shape change has to reach a reconnect. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LegacyRotationReconnectTest {
    private lateinit var activity: LegacyCarPlayActivity
    private lateinit var frame: FrameLayout

    @Before fun setUp() {
        activity = Robolectric.buildActivity(LegacyCarPlayActivity::class.java).create().get()
        frame = ReflectionHelpers.getField(activity, "videoFrame")
    }

    private fun host(width: Int, height: Int) {
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        frame.measure(spec, spec)
        frame.layout(0, 0, width, height)
    }

    private fun needs(display: CarPlaySessionDisplay, width: Int, height: Int, rotation: Int): Boolean =
        ReflectionHelpers.callInstanceMethod<Boolean>(
            activity, "displayNeedsReconnect",
            ReflectionHelpers.ClassParameter.from(CarPlaySessionDisplay::class.java, display),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, width),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, height),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, rotation),
        )

    private fun scheduled(): Boolean =
        ReflectionHelpers.getField<Boolean>(activity, "rotationReconnectScheduled")

    @Test fun anUnchangedWindowNeedsNoReconnect() {
        assertFalse(needs(session(540, 922, 0), 540, 922, 0))
    }

    @Test fun aRotatedWindowAlwaysNeedsItEvenAtTheSameSize() {
        assertTrue(needs(session(540, 922, 0), 540, 922, 1))
    }

    @Test fun aNarrowerWindowReachesForAReconnectButACameraShrinkDoesNot() {
        assertTrue(needs(session(960, 540, 0), 540, 922, 0))
        assertFalse("5% is the shrink a camera window makes", needs(session(960, 540, 0), 960, 515, 0))
    }

    @Test fun anUnmeasuredWindowNeverReconnects() {
        assertFalse(needs(session(540, 922, 0), 0, 922, 0))
        assertFalse(needs(session(0, 0, 0), 540, 922, 0))
    }

    @Test fun nothingIsScheduledWithoutASession() {
        host(960, 540)
        ReflectionHelpers.callInstanceMethod<Any?>(activity, "scheduleRotationReconnect")
        assertFalse(scheduled())
    }

    @Test fun rotatingBackBeforeTheDebounceElapsesCancelsTheReconnect() {
        host(540, 922)
        val controller = Mockito.mock(CarPlayController::class.java)
        ReflectionHelpers.setField(activity, "controller", controller)
        ReflectionHelpers.setField(activity, "sessionActive", true)
        ReflectionHelpers.setField(activity, "sessionDisplay", session(540, 922, 0))

        host(960, 540)
        ReflectionHelpers.callInstanceMethod<Any?>(activity, "scheduleRotationReconnect")
        assertTrue(scheduled())

        host(540, 922)
        shadowOf(Looper.getMainLooper()).idleFor(1, java.util.concurrent.TimeUnit.SECONDS)

        assertFalse(scheduled())
        assertNotNull(ReflectionHelpers.getField<Any?>(activity, "controller"))
    }

    @Test fun aShapeChangeLeftUntilTheDebounceFiresTearsTheSessionDown() {
        host(540, 922)
        ReflectionHelpers.setField(activity, "controller", Mockito.mock(CarPlayController::class.java))
        ReflectionHelpers.setField(activity, "sessionActive", true)
        ReflectionHelpers.setField(activity, "sessionDisplay", session(540, 922, 0))

        host(960, 540)
        ReflectionHelpers.callInstanceMethod<Any?>(activity, "scheduleRotationReconnect")
        shadowOf(Looper.getMainLooper()).idleFor(1, java.util.concurrent.TimeUnit.SECONDS)

        assertNull(ReflectionHelpers.getField<Any?>(activity, "controller"))
    }

    private fun session(width: Int, height: Int, rotation: Int) =
        CarPlaySessionDisplay(width, height, rotation, false, false, width, height)
}
