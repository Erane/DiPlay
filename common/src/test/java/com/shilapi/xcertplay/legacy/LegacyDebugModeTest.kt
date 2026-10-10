package com.shilapi.xcertplay.legacy

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.AirPlayPersistence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Debug mode is what separates a projected phone screen from a wall of log lines, so the two things
 * it has to get right are whether anything is painted over the picture, and whether the answer the
 * owner gave on the home screen is the one the session reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LegacyDebugModeTest {

    private fun session(debugOverlayVisible: Boolean): LegacyCarPlayActivity {
        val activity = Robolectric.buildActivity(LegacyCarPlayActivity::class.java).create().get()
        ReflectionHelpers.setField(activity, "debugOverlayVisible", debugOverlayVisible)
        ReflectionHelpers.callInstanceMethod<Any?>(activity, "applyDebugOverlayVisibility")
        return activity
    }

    private fun view(activity: LegacyCarPlayActivity, name: String): View =
        ReflectionHelpers.getField(activity, name)

    private fun visible(activity: LegacyCarPlayActivity, name: String): Boolean =
        view(activity, name).visibility == View.VISIBLE

    private fun setStatus(activity: LegacyCarPlayActivity, text: String) {
        ReflectionHelpers.callInstanceMethod<Any?>(
            activity, "setStatus",
            ReflectionHelpers.ClassParameter.from(String::class.java, text),
        )
    }

    @Test
    fun aQuietSessionPaintsNothingOverThePicture() {
        val activity = session(debugOverlayVisible = false)
        assertFalse("the last five log lines", visible(activity, "logView"))
        assertFalse("the bluetooth line", visible(activity, "btStatusView"))
        assertFalse("reconnect / bounce / toggle", visible(activity, "debugControls"))
    }

    @Test
    fun theStatusLineSurvivesUntilThePictureIsUp() {
        val activity = session(debugOverlayVisible = false)
        assertTrue("an otherwise black screen has to say why", visible(activity, "statusView"))

        ReflectionHelpers.setField(activity, "sessionActive", true)
        setStatus(activity, "CarPlay 已连接")
        assertFalse(visible(activity, "statusView"))

        ReflectionHelpers.setField(activity, "sessionActive", false)
        setStatus(activity, "会话结束，准备重连…")
        assertTrue(visible(activity, "statusView"))
    }

    @Test
    fun debugModePutsEveryBlockBackOnScreen() {
        val activity = session(debugOverlayVisible = true)
        assertTrue(visible(activity, "logView"))
        assertTrue(visible(activity, "btStatusView"))
        assertTrue(visible(activity, "debugControls"))
        // Connected or not, an owner who asked for debug information gets it.
        ReflectionHelpers.setField(activity, "sessionActive", true)
        setStatus(activity, "CarPlay 已连接")
        assertTrue(visible(activity, "statusView"))
    }

    @Test
    fun theOverlayDefaultsToOffAndTheToggleRemembersIt() {
        val fresh = Robolectric.buildActivity(LegacyCarPlayActivity::class.java).create().get()
        assertFalse("the picture is the default view", AirPlayPersistence.loadLegacyDebugOverlayVisible(fresh))

        val activity = session(debugOverlayVisible = true)
        ReflectionHelpers.callInstanceMethod<Any?>(
            activity, "setDebugOverlayVisible",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        assertFalse(AirPlayPersistence.loadLegacyDebugOverlayVisible(activity))
        assertEquals("显示调试", (view(activity, "debugToggleButton") as TextView).text)
    }

    @Test
    fun theHomeScreenDebugRowWritesTheFlagTheSessionReads() {
        val home = Robolectric.buildActivity(LegacyHomeActivity::class.java).create().get()
        val row = findText(home.window.decorView, "连接时显示调试信息")
        assertFalse(AirPlayPersistence.loadLegacyDebugOverlayVisible(home))

        // The row's own line layout is what carries the click listener of a switch row.
        (row.parent as View).performClick()

        assertTrue(AirPlayPersistence.loadLegacyDebugOverlayVisible(home))
        assertEquals("已开启", findText(home.window.decorView, "已开启").text)
        // A session started afterwards paints the overlay without being told to.
        assertTrue(visible(Robolectric.buildActivity(LegacyCarPlayActivity::class.java).create().get(), "logView"))
    }

    private fun findText(root: View, value: String): TextView {
        if (root is TextView && root.text.toString() == value) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                search(root.getChildAt(index), value)?.let { return it }
            }
        }
        throw AssertionError("«$value» is not on the screen")
    }

    private fun search(root: View, value: String): TextView? {
        if (root is TextView && root.text.toString() == value) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                search(root.getChildAt(index), value)?.let { return it }
            }
        }
        return null
    }
}
