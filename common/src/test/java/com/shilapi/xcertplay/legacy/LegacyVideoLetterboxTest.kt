package com.shilapi.xcertplay.legacy

import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/** A pre-Lollipop SurfaceView has no transform, so the letterbox lives in its layout params. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LegacyVideoLetterboxTest {
    private lateinit var activity: LegacyCarPlayActivity
    private lateinit var surfaceView: SurfaceView
    private lateinit var frame: FrameLayout

    @Before fun setUp() {
        activity = Robolectric.buildActivity(LegacyCarPlayActivity::class.java).create().get()
        surfaceView = ReflectionHelpers.getField(activity, "surfaceView")
        frame = ReflectionHelpers.getField(activity, "videoFrame")
    }

    private fun host(width: Int, height: Int) {
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        frame.measure(spec, spec)
        frame.layout(0, 0, width, height)
    }

    private fun params(): FrameLayout.LayoutParams = surfaceView.layoutParams as FrameLayout.LayoutParams

    @Test fun aPortraitCanvasInALandscapeHostKeepsItsAspectAndCenters() {
        host(960, 540)
        setCanvas(540, 922)

        val layout = params()
        assertEquals(540, layout.height)
        assertTrue(
            "letterboxed width ${layout.width} does not hold the 540x922 aspect",
            kotlin.math.abs(layout.width.toDouble() / layout.height - 540.0 / 922) < 0.02,
        )
        assertTrue(
            "not centered: width=${layout.width} leftMargin=${layout.leftMargin}",
            kotlin.math.abs((960 - layout.width) / 2 - layout.leftMargin) <= 1,
        )
        assertEquals(0, layout.topMargin)
    }

    @Test fun rotatingBackToTheCanvasShapeFillsTheHost() {
        host(960, 540)
        setCanvas(540, 922)
        host(540, 922)
        setCanvas(540, 922)

        val layout = params()
        assertEquals(540, layout.width)
        assertEquals(922, layout.height)
        assertEquals(0, layout.leftMargin)
        assertEquals(0, layout.topMargin)
    }

    @Test fun rotationWithoutACanvasStaysFullBleed() {
        host(960, 540)
        setCanvas(540, 922)
        setCanvas(0, 0)

        val layout = params()
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, layout.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, layout.height)
    }

    @Test fun onConfigurationChangedReAppliesTheLetterbox() {
        host(540, 922)
        setCanvas(540, 922)

        host(960, 540)
        val config = activity.resources.configuration
        ReflectionHelpers.callInstanceMethod<Any?>(
            activity, "onConfigurationChanged",
            ReflectionHelpers.ClassParameter.from(android.content.res.Configuration::class.java, config),
        )
        shadowOf(Looper.getMainLooper()).idle()

        val layout = params()
        assertEquals(540, layout.height)
        assertTrue(kotlin.math.abs(layout.width.toDouble() / layout.height - 540.0 / 922) < 0.02)
    }

    private fun setCanvas(width: Int, height: Int) {
        ReflectionHelpers.setField(activity, "videoWidth", width)
        ReflectionHelpers.setField(activity, "videoHeight", height)
        ReflectionHelpers.callInstanceMethod<Any?>(activity, "applyVideoLayout")
    }
}
