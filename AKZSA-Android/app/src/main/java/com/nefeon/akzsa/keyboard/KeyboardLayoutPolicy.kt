package com.nefeon.akzsa.keyboard

import kotlin.math.max
import kotlin.math.min

/** Layout profile chosen from measured space only, never from device brand or model. */
enum class KeyboardProfile { COMPACT, STANDARD, WIDE, LOW_HEIGHT }

/** Everything the renderer needs. All values are dp / sp so they scale with density. */
data class KeyboardMetrics(
    val profile: KeyboardProfile,
    /** Keyboard content width (<= available width; centred by the renderer when narrower). */
    val contentWidthDp: Int,
    val keyRowHeightDp: Int,
    val toolbarHeightDp: Int,
    val headerHeightDp: Int,
    val actionRowHeightDp: Int,
    val keyGapDp: Int,
    val horizontalPaddingDp: Int,
    val fileResultsHeightDp: Int,
    /** 0 = everything shown; 1 = status header hidden; 2 = + secondary panels compacted; 3 = minimum typing layout. */
    val collapseLevel: Int,
    val estimatedTotalHeightDp: Int,
    val keyLabelSp: Float,
) {
    val showHeader: Boolean get() = headerHeightDp > 0
}

/**
 * The only place that decides keyboard sizing. Re-evaluate on every configuration change, window
 * resize, fold/unfold, font-scale change, or user height change.
 *
 * Invariants (enforced by tests):
 *  - key rows are never below [MIN_KEY_ROW_DP] and the typing rows are never collapsed away;
 *  - the destructive-action confirmation row is never collapsed (it is part of the fixed chrome);
 *  - width is bounded so keys do not stretch on tablets and landscape phones.
 */
object KeyboardLayoutPolicy {
    const val MIN_KEY_ROW_DP = 34
    const val MAX_KEY_ROW_DP = 56
    const val DEFAULT_HEIGHT_FRACTION = 1.0f
    const val MIN_HEIGHT_FRACTION = 0.85f
    const val MAX_HEIGHT_FRACTION = 1.20f
    const val DEFAULT_TYPING_ROWS = 4
    private const val FONT_SCALE_CAP = 1.3f

    fun profileFor(widthDp: Float, heightDp: Float): KeyboardProfile = when {
        heightDp < 420f || (widthDp > heightDp && heightDp < 500f) -> KeyboardProfile.LOW_HEIGHT
        widthDp >= 600f -> KeyboardProfile.WIDE
        widthDp < 360f -> KeyboardProfile.COMPACT
        else -> KeyboardProfile.STANDARD
    }

    /** User preference is stored as a fraction of the default height and clamped to safe bounds. */
    fun clampHeightFraction(requested: Float): Float =
        if (requested.isNaN()) DEFAULT_HEIGHT_FRACTION else requested.coerceIn(MIN_HEIGHT_FRACTION, MAX_HEIGHT_FRACTION)

    /**
     * @param widthDp available window width in dp (`configuration.screenWidthDp` or measured IME width)
     * @param heightDp available window height in dp
     * @param fontScale `configuration.fontScale`
     * @param userHeightFraction stored preference, see [clampHeightFraction]
     * @param fileMode Files mode shows a results surface and needs less chrome
     * @param typingRows number of key rows incl. the bottom row (4 for Latin, 5 for the Hindi layout)
     */
    fun metrics(
        widthDp: Float,
        heightDp: Float,
        fontScale: Float = 1.0f,
        userHeightFraction: Float = DEFAULT_HEIGHT_FRACTION,
        fileMode: Boolean = false,
        typingRows: Int = DEFAULT_TYPING_ROWS,
    ): KeyboardMetrics {
        val safeWidth = if (widthDp.isNaN() || widthDp <= 0f) 360f else widthDp
        val safeHeight = if (heightDp.isNaN() || heightDp <= 0f) 640f else heightDp
        val profile = profileFor(safeWidth, safeHeight)
        val fraction = clampHeightFraction(userHeightFraction)
        val scale = fontScale.coerceIn(1.0f, FONT_SCALE_CAP)

        val maxContentWidth = when (profile) {
            KeyboardProfile.WIDE -> 720
            KeyboardProfile.LOW_HEIGHT -> 640
            else -> Int.MAX_VALUE
        }
        val contentWidth = min(safeWidth.toInt(), maxContentWidth)

        val baseRow = when (profile) {
            KeyboardProfile.COMPACT -> 44
            KeyboardProfile.STANDARD -> 46
            KeyboardProfile.WIDE -> 52
            KeyboardProfile.LOW_HEIGHT -> 38
        }
        val toolbar = if (profile == KeyboardProfile.LOW_HEIGHT) 44 else 48
        val gap = if (profile == KeyboardProfile.COMPACT) 3 else 4
        val hPad = if (profile == KeyboardProfile.WIDE) 12 else 6
        val actionRow = 48                              // confirmation controls are never shrunk
        val fullHeader = 24

        // Keyboards should not take more than this share of the window height.
        val budgetShare = when (profile) {
            KeyboardProfile.LOW_HEIGHT -> 0.62f
            KeyboardProfile.WIDE -> 0.38f
            else -> 0.44f
        }
        val budget = (safeHeight * budgetShare).toInt()

        var row = (baseRow * fraction * min(scale, 1.15f)).toInt().coerceIn(MIN_KEY_ROW_DP, MAX_KEY_ROW_DP)
        var header = fullHeader
        var level = 0
        val vPad = 8
        val rows = typingRows.coerceIn(3, 6)

        fun total(r: Int, h: Int, extraPanel: Int): Int =
            vPad + h + toolbar + extraPanel + rows * (r + gap)

        // Files mode carries its own status line inside the results surface, so the global header is redundant.
        if (fileMode) { header = 0; level = 1 }

        if (total(row, header, 0) > budget) { header = 0; level = max(level, 1) }
        if (total(row, header, 0) > budget) {
            // shrink rows toward the minimum, but never below it
            while (row > MIN_KEY_ROW_DP && total(row, header, 0) > budget) row -= 1
            level = max(level, 2)
        }
        // Minimum typing layout: may exceed the budget slightly rather than clip essential keys.
        if (total(row, header, 0) > budget) level = 3

        val fileResults = (budget - vPad - toolbar - gap * 2).coerceIn(96, 220)

        return KeyboardMetrics(
            profile = profile,
            contentWidthDp = contentWidth,
            keyRowHeightDp = row,
            toolbarHeightDp = toolbar,
            headerHeightDp = header,
            actionRowHeightDp = actionRow,
            keyGapDp = gap,
            horizontalPaddingDp = hPad,
            fileResultsHeightDp = fileResults,
            collapseLevel = level,
            estimatedTotalHeightDp = total(row, header, 0),
            keyLabelSp = 18f,  // sp already follows the user's font scale; do not multiply again
        )
    }
}
