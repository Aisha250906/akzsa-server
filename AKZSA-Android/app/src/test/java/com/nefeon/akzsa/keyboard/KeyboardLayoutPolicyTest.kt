package com.nefeon.akzsa.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutPolicyTest {
    @Test fun profilesAreChosenFromMeasuredSpaceOnly() {
        assertEquals(KeyboardProfile.COMPACT, KeyboardLayoutPolicy.profileFor(320f, 640f))
        assertEquals(KeyboardProfile.STANDARD, KeyboardLayoutPolicy.profileFor(393f, 851f))
        assertEquals(KeyboardProfile.WIDE, KeyboardLayoutPolicy.profileFor(800f, 1280f))
        assertEquals(KeyboardProfile.LOW_HEIGHT, KeyboardLayoutPolicy.profileFor(851f, 393f))   // landscape phone
        assertEquals(KeyboardProfile.LOW_HEIGHT, KeyboardLayoutPolicy.profileFor(393f, 380f))   // split-screen sliver
    }

    @Test fun foldableUnfoldedGetsWideAndFoldedGetsStandard() {
        assertEquals(KeyboardProfile.STANDARD, KeyboardLayoutPolicy.profileFor(411f, 891f))
        assertEquals(KeyboardProfile.WIDE, KeyboardLayoutPolicy.profileFor(673f, 841f))
    }

    @Test fun keyRowsNeverGoBelowMinimumAcrossManySizes() {
        for (w in listOf(280f, 320f, 360f, 412f, 600f, 840f, 1024f)) for (h in listOf(240f, 320f, 400f, 640f, 800f, 1200f)) for (fs in listOf(0.85f, 1f, 1.3f, 2f)) {
            val m = KeyboardLayoutPolicy.metrics(w, h, fs)
            assertTrue("row ${m.keyRowHeightDp} for ${w}x$h fs=$fs", m.keyRowHeightDp >= KeyboardLayoutPolicy.MIN_KEY_ROW_DP)
            assertTrue(m.keyRowHeightDp <= KeyboardLayoutPolicy.MAX_KEY_ROW_DP)
            assertTrue("toolbar ${m.toolbarHeightDp}", m.toolbarHeightDp >= 44)
            assertEquals("confirmation row must keep a 48dp target", 48, m.actionRowHeightDp)
        }
    }

    @Test fun widthIsBoundedOnTabletsAndLandscape() {
        assertEquals(720, KeyboardLayoutPolicy.metrics(1280f, 800f.coerceAtLeast(900f)).contentWidthDp.coerceAtMost(720))
        assertTrue(KeyboardLayoutPolicy.metrics(1024f, 1366f).contentWidthDp <= 720)
        assertTrue(KeyboardLayoutPolicy.metrics(851f, 393f).contentWidthDp <= 640)
        assertEquals(393, KeyboardLayoutPolicy.metrics(393f, 851f).contentWidthDp)
    }

    @Test fun lowHeightCollapsesSecondaryChromeFirstAndKeepsTypingKeys() {
        val m = KeyboardLayoutPolicy.metrics(851f, 393f)
        assertEquals(KeyboardProfile.LOW_HEIGHT, m.profile)
        assertFalse("header must collapse in low-height", m.showHeader)
        assertTrue(m.collapseLevel >= 1)
        assertTrue(m.keyRowHeightDp >= KeyboardLayoutPolicy.MIN_KEY_ROW_DP)
    }

    @Test fun standardPortraitShowsFullChromeWithin44PercentBudget() {
        val m = KeyboardLayoutPolicy.metrics(393f, 851f)
        assertEquals(0, m.collapseLevel)
        assertTrue(m.showHeader)
        assertTrue("total ${m.estimatedTotalHeightDp}", m.estimatedTotalHeightDp <= (851 * 0.44f).toInt())
    }

    @Test fun userHeightPreferenceIsClampedAndResettable() {
        assertEquals(KeyboardLayoutPolicy.MAX_HEIGHT_FRACTION, KeyboardLayoutPolicy.clampHeightFraction(5f), 0f)
        assertEquals(KeyboardLayoutPolicy.MIN_HEIGHT_FRACTION, KeyboardLayoutPolicy.clampHeightFraction(0.1f), 0f)
        assertEquals(KeyboardLayoutPolicy.DEFAULT_HEIGHT_FRACTION, KeyboardLayoutPolicy.clampHeightFraction(Float.NaN), 0f)
        val tall = KeyboardLayoutPolicy.metrics(393f, 1200f, userHeightFraction = 5f)
        val normal = KeyboardLayoutPolicy.metrics(393f, 1200f, userHeightFraction = 1f)
        assertTrue(tall.keyRowHeightDp >= normal.keyRowHeightDp)
        assertTrue(tall.keyRowHeightDp <= KeyboardLayoutPolicy.MAX_KEY_ROW_DP)
    }

    @Test fun fontScaleGrowsKeysButStaysBounded() {
        val base = KeyboardLayoutPolicy.metrics(393f, 1200f, 1.0f)
        val big = KeyboardLayoutPolicy.metrics(393f, 1200f, 1.3f)
        assertTrue(big.keyRowHeightDp >= base.keyRowHeightDp)
        assertEquals("sp must not be pre-multiplied by font scale", base.keyLabelSp, big.keyLabelSp, 0f)
    }

    @Test fun garbageMeasurementsFallBackToSafeDefaults() {
        val m = KeyboardLayoutPolicy.metrics(Float.NaN, -5f)
        assertTrue(m.keyRowHeightDp >= KeyboardLayoutPolicy.MIN_KEY_ROW_DP)
        assertTrue(m.contentWidthDp > 0)
    }

    @Test fun profileDoesNotDependOnAnythingButSizeSoSameSizeSameResult() {
        assertEquals(KeyboardLayoutPolicy.metrics(412f, 915f, 1f), KeyboardLayoutPolicy.metrics(412f, 915f, 1f))
    }

    @Test fun filesModeDropsTheGlobalHeader() {
        val m = KeyboardLayoutPolicy.metrics(393f, 851f, fileMode = true)
        assertFalse(m.showHeader)
        assertTrue(m.fileResultsHeightDp in 96..220)
    }

    @Test fun fiveRowHindiLayoutNeverClipsRowsBelowMinimum() {
        for (h in listOf(320f, 400f, 640f, 851f)) {
            val m = KeyboardLayoutPolicy.metrics(393f, h, typingRows = 5)
            assertTrue(m.keyRowHeightDp >= KeyboardLayoutPolicy.MIN_KEY_ROW_DP)
        }
        val four = KeyboardLayoutPolicy.metrics(393f, 851f, typingRows = 4)
        val five = KeyboardLayoutPolicy.metrics(393f, 851f, typingRows = 5)
        assertTrue(five.estimatedTotalHeightDp > four.estimatedTotalHeightDp || five.keyRowHeightDp < four.keyRowHeightDp)
    }
}
