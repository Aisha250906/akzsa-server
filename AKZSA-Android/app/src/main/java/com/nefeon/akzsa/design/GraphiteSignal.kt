package com.nefeon.akzsa.design

import kotlin.math.pow

/**
 * Graphite Signal design tokens (single source of truth).
 *
 * Plain ARGB ints and plain Kotlin only, so the same tokens feed:
 *  - the Compose theme (`Color(palette.background)`),
 *  - the classic-View keyboard (`view.setBackgroundColor(palette.background)`),
 *  - res/values/colors.xml (kept in sync by GraphiteSignalTest).
 *
 * Electric Lime is a FILL colour, always paired with [Palette.onAccent]. On the light theme lime has
 * only ~1.2:1 contrast against Ivory, so it must never be the sole indicator of state there; use
 * [Palette.focus] (dark olive) for focus rings and selected outlines.
 */
object GraphiteSignal {

    data class Palette(
        val background: Int,
        val surface: Int,
        val surfaceElevated: Int,
        val secondarySurface: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        /** Decorative hairlines only (not an accessibility boundary). */
        val border: Int,
        /** Boundaries of interactive controls; >= 3:1 against background and surface. */
        val borderStrong: Int,
        val accent: Int,
        val accentPressed: Int,
        val onAccent: Int,
        val focus: Int,
        val error: Int,
        val onError: Int,
        val success: Int,
        val disabledContainer: Int,
        val disabledContent: Int,
    )

    val Light = Palette(
        background = 0xFFF7F7F0.toInt(),        // Soft Ivory
        surface = 0xFFFFFFFF.toInt(),
        surfaceElevated = 0xFFFFFFFF.toInt(),
        secondarySurface = 0xFFE5E9DF.toInt(),  // Sage Mist
        textPrimary = 0xFF252923.toInt(),
        textSecondary = 0xFF5E635A.toInt(),
        border = 0xFFD5DACB.toInt(),
        borderStrong = 0xFF7A8172.toInt(),
        accent = 0xFFC8F169.toInt(),            // Electric Lime
        accentPressed = 0xFFA9D44F.toInt(),
        onAccent = 0xFF151715.toInt(),
        focus = 0xFF3F5F00.toInt(),
        error = 0xFFB3261E.toInt(),
        onError = 0xFFFFFFFF.toInt(),
        success = 0xFF2E6B1F.toInt(),
        disabledContainer = 0xFFE5E9DF.toInt(),
        disabledContent = 0xFF767B72.toInt(),
    )

    val Dark = Palette(
        background = 0xFF151715.toInt(),        // Graphite
        surface = 0xFF20231F.toInt(),
        surfaceElevated = 0xFF2A2E28.toInt(),
        secondarySurface = 0xFF262A24.toInt(),
        textPrimary = 0xFFF2F4EC.toInt(),
        textSecondary = 0xFFA7AEA0.toInt(),
        border = 0xFF3A3F37.toInt(),
        borderStrong = 0xFF7A8274.toInt(),
        accent = 0xFFC8F169.toInt(),
        accentPressed = 0xFFA9D44F.toInt(),
        onAccent = 0xFF151715.toInt(),
        focus = 0xFFC8F169.toInt(),
        error = 0xFFFFB4AB.toInt(),
        onError = 0xFF151715.toInt(),
        success = 0xFF9BD67A.toInt(),
        disabledContainer = 0xFF262A24.toInt(),
        disabledContent = 0xFF7A8174.toInt(),
    )

    fun palette(dark: Boolean): Palette = if (dark) Dark else Light

    /** Layout/shape/motion tokens in dp / sp / ms. Use these instead of literals. */
    object Dimens {
        const val minTouchTargetDp = 48
        const val spaceXs = 4
        const val spaceS = 8
        const val spaceM = 12
        const val spaceL = 16
        const val spaceXl = 24
        const val radiusKey = 8
        const val radiusControl = 12
        const val radiusCard = 16
        const val strokeHairline = 1
        const val strokeFocus = 2
        const val iconS = 18
        const val iconM = 24
        const val keyLabelSp = 18f
        const val toolbarLabelSp = 13f
        const val captionSp = 12f
        const val bodySp = 15f
        const val motionFastMs = 120
        const val motionNormalMs = 200
    }

    // ---- WCAG 2.x contrast (used by tests and by runtime guard rails) ----

    private fun channel(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    fun relativeLuminance(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    fun contrastRatio(foreground: Int, background: Int): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        val hi = maxOf(a, b)
        val lo = minOf(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** Hex such as `#C8F169` (alpha dropped) for docs and XML generation. */
    fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)
}
