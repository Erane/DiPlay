package com.shilapi.xcertplay.legacy

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The modern home's look (DiPlayActivity's palette and cards) rebuilt from symbols that exist on
 * API 19, so the pre-21 units show the same app rather than a wall of ROM-default buttons.
 *
 * Deliberately absent, because each is API 21+ and this branch runs on Dalvik: RippleDrawable (a
 * StateListDrawable paints the press instead), stateListAnimator, elevation, letter spacing, and
 * every tint attribute. androidx is not imported here at all: the dex API gate cannot see calls
 * made from inside androidx, so a helper that reaches an API 21 framework method through
 * ViewCompat would pass the build and crash on the car.
 */
class LegacyStyle(private val context: Context) {

    private val density: Float = context.resources.displayMetrics.density
    private val configuration = context.resources.configuration

    fun dp(value: Int): Int = (value * density).toInt()

    /**
     * A landscape head unit has roughly twice the width and a third of the height of the phone this
     * branch is developed on, so one vertical column cannot serve both.
     *
     * Read from the current configuration rather than from pixels: these ROMs report their panel in
     * whatever orientation the framework believes it is in, and an activity that is rebuilt on
     * rotation gets the other layout for free.
     */
    val wide: Boolean = configuration.screenWidthDp >= 560

    /** Less than about 480 dp of usable height: nothing may be fixed-height and nothing is spare. */
    val short: Boolean = configuration.screenHeightDp < 480

    // ---- spacing ramp ----
    // Type sizes deliberately do not follow this ramp. A 7-inch 800x480 panel is ~135 ppi against
    // this bench phone's ~234, which cancels its lower density, so a sp here is about as many
    // millimetres there; shrinking the small lines on a short screen would buy a few pixels of
    // height and make them unreadable at arm's length. Spacing and control heights give instead.

    val cardPadDp: Int = if (short) 13 else 18
    val heroPadDp: Int = if (short) 14 else 20
    val sectionGapDp: Int = if (short) 9 else 12
    val primaryButtonHeightDp: Int = if (short) 48 else 56
    val secondaryButtonHeightDp: Int = if (short) 44 else 48
    val modeButtonHeightDp: Int = if (short) 42 else 46

    // ---- text ----

    fun label(value: String, sizeSp: Int, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            textSize = sizeSp.toFloat()
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            typeface = if (bold) MEDIUM else REGULAR
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    /** Small coloured lead-in above a heading, e.g. 「CARPLAY 状态」. */
    fun eyebrow(value: String): TextView = label(value, 12, ACCENT, true)

    fun title(value: String, sizeSp: Int = 22): TextView = label(value, sizeSp, TEXT, true)

    fun body(value: String, sizeSp: Int = 15): TextView = label(value, sizeSp, MUTED)

    fun hint(value: String): TextView = label(value, 13, MUTED)

    fun warning(value: String): TextView = label(value, 14, WARNING)

    // ---- containers ----

    fun column(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    fun row(gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        setGravity(gravity)
    }

    fun card(paddingDp: Int = cardPadDp): LinearLayout = column().apply {
        setBackground(rounded(SURFACE, BORDER))
        setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
    }

    /** A card that reads as the most important thing on the page: accent border, slightly lifted fill. */
    fun heroCard(): LinearLayout = column().apply {
        setBackground(rounded(SURFACE_HI, ACCENT_DIM, strokeWidthDp = 1))
        setPadding(dp(heroPadDp), dp(heroPadDp - 2), dp(heroPadDp), dp(heroPadDp))
    }

    /**
     * A titled card. The legacy screens have no vector icons — the drawable directory's glyphs are
     * API 21 vector XML, which this theme cannot inflate — so the heading carries the meaning.
     */
    fun section(
        heading: String,
        caption: String? = null,
        build: (LinearLayout) -> Unit,
    ): LinearLayout {
        val card = card()
        card.addView(title(heading))
        if (caption != null) {
            card.addView(body(caption, 14).apply { setPadding(0, dp(6), 0, dp(if (short) 10 else 14)) })
        } else {
            card.addView(space(if (short) 10 else 14))
        }
        build(card)
        return card
    }

    fun space(heightDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    /** The horizontal twin of [space], for the gutter between two columns. */
    fun gap(widthDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1)
    }

    fun divider(): View = View(context).apply {
        setBackgroundColor(BORDER)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(if (short) 10 else 14)
            bottomMargin = dp(if (short) 10 else 14)
        }
    }

    // ---- controls ----

    /**
     * [primary] is the one action a page wants taken; everything else is a quiet surface so the
     * owner can tell at a glance where to start.
     *
     * [heightDp] is a floor, never a fixed height: a Chinese label that wraps on a narrow head-unit
     * column would otherwise be sliced in half by the button's own bounds.
     */
    fun button(
        text: String,
        primary: Boolean = false,
        enabled: Boolean = true,
        heightDp: Int = if (primary) primaryButtonHeightDp else secondaryButtonHeightDp,
        onClick: () -> Unit,
    ): Button = Button(context).apply {
        this.text = text
        isAllCaps = false
        textSize = if (primary) 18f else 16f
        typeface = MEDIUM
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(6), dp(14), dp(6))
        minHeight = dp(heightDp)
        setBackground(if (primary) primaryBackground() else secondaryBackground())
        setTextColor(ColorStateList.valueOf(if (primary) BG else TEXT))
        isEnabled = enabled
        setOnClickListener { onClick() }
    }

    fun buttonParams(topDp: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(topDp) }

    /**
     * A setting that is on or off, drawn as a pill: [android.widget.Switch] needs a theme that
     * supplies switchStyle and a tint, and these units fall back to whatever the ROM provides.
     * [reload] re-reads the stored value so a row repainted after returning from a session is honest.
     */
    fun switchRow(heading: String, caption: String, load: () -> Boolean, save: (Boolean) -> Unit): View {
        val line = row(Gravity.CENTER_VERTICAL).apply { setPadding(0, dp(if (short) 7 else 10), 0, dp(if (short) 7 else 10)) }
        val texts = column().apply {
            addView(title(heading, 17))
            addView(hint(caption).apply { setPadding(0, dp(4), dp(10), 0) })
        }
        line.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val pill = label("", 14, TEXT, true)
        fun paint(value: Boolean) {
            pill.text = if (value) "已开启" else "已关闭"
            pill.gravity = Gravity.CENTER
            pill.setBackground(rounded(if (value) ACCENT else SURFACE_LO, if (value) ACCENT else BORDER))
            pill.setTextColor(if (value) BG else MUTED)
        }
        var value = load()
        paint(value)
        pill.minWidth = dp(72)
        pill.minHeight = dp(34)
        pill.layoutParams = LinearLayout.LayoutParams(-2, -2)
        line.addView(pill)
        val toggle = {
            value = !value
            save(value)
            paint(value)
        }
        line.setOnClickListener { toggle() }
        pill.setOnClickListener { toggle() }
        texts.setOnClickListener { toggle() }
        return line
    }

    /** A yes/no line in a checklist; a satisfied row is one quiet line, an open one says what to do. */
    fun checkRow(heading: String, caption: String, done: Boolean): LinearLayout {
        val line = row(Gravity.TOP).apply { setPadding(0, dp(if (short) 4 else 6), 0, dp(if (short) 4 else 6)) }
        line.addView(
            label(if (done) "✓" else "○", 15, if (done) SUCCESS else ACCENT, true),
            LinearLayout.LayoutParams(dp(24), -2),
        )
        val texts = column()
        texts.addView(label(heading, 15, if (done) MUTED else TEXT, true))
        if (caption.isNotBlank()) {
            texts.addView(hint(caption).apply { setPadding(0, dp(2), 0, 0) })
        }
        line.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        return line
    }

    /**
     * A selectable row in a dialog or list; the chosen one gets the accent border so the owner can
     * see what their tap did on a screen with no other feedback.
     */
    fun choiceRow(heading: String, caption: String, selected: Boolean, onClick: () -> Unit): View {
        val card = column().apply {
            setBackground(rounded(if (selected) SURFACE_HI else SURFACE_LO, if (selected) ACCENT else BORDER))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(label(heading, 16, if (selected) ACCENT else TEXT, true))
            addView(hint(caption).apply { setPadding(0, dp(4), 0, 0) })
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        card.setOnClickListener { onClick() }
        return card
    }

    /** The thin 「第 2 步 · 共 3 步」 line that tells an owner how much is left of a flow. */
    fun progress(value: String): TextView = label(value, 12, ACCENT, true).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, 0, 0, dp(12))
    }

    fun page(): ScrollView = ScrollView(context).apply {
        setBackgroundColor(BG)
        isFillViewport = true
        clipToPadding = false
    }

    fun pageContent(padDp: Int = if (wide) 16 else 14): LinearLayout = column().apply {
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
    }

    fun addTo(parent: LinearLayout, view: View, topDp: Int = 12) {
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(topDp) })
    }

    /**
     * These ROMs have no Material theme, and the application theme is a Material one that only
     * exists from API 21, so a pre-21 screen asks for Holo explicitly: dark, no action bar, and an
     * AlertDialog whose default text colour matches this palette instead of inverting it.
     */
    fun applyWindowTheme(activity: Activity) {
        if (android.os.Build.VERSION.SDK_INT < 21) {
            activity.setTheme(android.R.style.Theme_Holo_NoActionBar)
        }
    }

    // ---- drawables ----

    private fun rounded(color: Int, stroke: Int, radiusDp: Int = 14, strokeWidthDp: Int = 1) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(strokeWidthDp), stroke)
        }

    private fun pressed(color: Int, stroke: Int, pressedColor: Int) = StateListDrawable().apply {
        // Order matters: the first matching state wins, so pressed and disabled precede the default.
        addState(intArrayOf(-android.R.attr.state_enabled), dimmed(color, stroke))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressedColor, stroke))
        addState(intArrayOf(android.R.attr.state_selected), rounded(pressedColor, stroke))
        addState(intArrayOf(), rounded(color, stroke))
    }

    private fun dimmed(color: Int, stroke: Int) = GradientDrawable().apply {
        // A disabled control should look unreachable, not merely uncoloured.
        setColor(blend(color, BG, 0.55f))
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1), blend(stroke, BG, 0.55f))
    }

    private fun primaryBackground() = pressed(ACCENT, ACCENT, ACCENT_PRESSED)

    private fun secondaryBackground() = pressed(SURFACE, BORDER, SURFACE_PRESSED)

    companion object {
        // The modern home's palette, so both generations of the app share one set of values.
        val BG = Color.rgb(12, 17, 27)
        val SURFACE = Color.rgb(21, 30, 44)
        private val SURFACE_HI = Color.rgb(26, 37, 54)
        private val SURFACE_LO = Color.rgb(17, 24, 36)
        private val SURFACE_PRESSED = Color.rgb(41, 58, 82)
        val BORDER = Color.rgb(42, 56, 75)
        val ACCENT = Color.rgb(166, 200, 255)
        private val ACCENT_PRESSED = Color.rgb(196, 219, 255)
        private val ACCENT_DIM = Color.rgb(96, 132, 180)
        val TEXT = Color.rgb(241, 245, 252)
        val MUTED = Color.rgb(168, 182, 202)
        val WARNING = Color.rgb(255, 196, 128)
        val SUCCESS = Color.rgb(126, 211, 160)

        private val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        private val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        private fun blend(from: Int, to: Int, fraction: Float): Int {
            val inverse = 1f - fraction
            return Color.rgb(
                (Color.red(from) * inverse + Color.red(to) * fraction).toInt(),
                (Color.green(from) * inverse + Color.green(to) * fraction).toInt(),
                (Color.blue(from) * inverse + Color.blue(to) * fraction).toInt(),
            )
        }
    }
}
