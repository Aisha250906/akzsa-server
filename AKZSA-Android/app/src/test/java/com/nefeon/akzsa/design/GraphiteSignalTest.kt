package com.nefeon.akzsa.design

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphiteSignalTest {
    private val textMin = 4.5    // WCAG AA, normal text
    private val nonTextMin = 3.0 // WCAG AA, UI boundaries / focus indicators

    private fun check(name: String, fg: Int, bg: Int, min: Double) {
        val ratio = GraphiteSignal.contrastRatio(fg, bg)
        assertTrue("$name contrast ${"%.2f".format(ratio)} < $min", ratio >= min)
    }

    private fun verify(label: String, p: GraphiteSignal.Palette) {
        val containers = mapOf(
            "background" to p.background, "surface" to p.surface,
            "surfaceElevated" to p.surfaceElevated, "secondarySurface" to p.secondarySurface,
        )
        for ((cn, c) in containers) {
            check("$label textPrimary/$cn", p.textPrimary, c, textMin)
            check("$label textSecondary/$cn", p.textSecondary, c, textMin)
            check("$label error/$cn", p.error, c, textMin)
            check("$label success/$cn", p.success, c, textMin)
            check("$label focus/$cn", p.focus, c, nonTextMin)
            check("$label borderStrong/$cn", p.borderStrong, c, nonTextMin)
        }
        // Accent is a fill: its label colour must be readable in normal and pressed states.
        check("$label onAccent/accent", p.onAccent, p.accent, textMin)
        check("$label onAccent/accentPressed", p.onAccent, p.accentPressed, textMin)
        check("$label onError/error", p.onError, p.error, textMin)
        // Disabled content is exempt from WCAG but must stay perceivable.
        check("$label disabledContent/disabledContainer", p.disabledContent, p.disabledContainer, nonTextMin)
    }

    @Test fun lightPaletteMeetsContrast() = verify("light", GraphiteSignal.Light)
    @Test fun darkPaletteMeetsContrast() = verify("dark", GraphiteSignal.Dark)

    @Test fun brandAccentIsIdenticalInBothThemes() {
        assertEquals(GraphiteSignal.Light.accent, GraphiteSignal.Dark.accent)
        assertEquals(GraphiteSignal.Light.onAccent, GraphiteSignal.Dark.onAccent)
    }

    @Test fun promptBaselineValuesAreUsed() {
        assertEquals("#151715", GraphiteSignal.hex(GraphiteSignal.Dark.background))
        assertEquals("#20231F", GraphiteSignal.hex(GraphiteSignal.Dark.surface))
        assertEquals("#F7F7F0", GraphiteSignal.hex(GraphiteSignal.Light.background))
        assertEquals("#FFFFFF", GraphiteSignal.hex(GraphiteSignal.Light.surface))
        assertEquals("#C8F169", GraphiteSignal.hex(GraphiteSignal.Light.accent))
        assertEquals("#E5E9DF", GraphiteSignal.hex(GraphiteSignal.Light.secondarySurface))
        assertEquals("#252923", GraphiteSignal.hex(GraphiteSignal.Light.textPrimary))
        assertEquals("#5E635A", GraphiteSignal.hex(GraphiteSignal.Light.textSecondary))
    }

    @Test fun limeAloneIsNotAnAccessibleIndicatorOnIvory() {
        // Documents why the light theme uses `focus` (dark olive) for focus rings.
        val ratio = GraphiteSignal.contrastRatio(GraphiteSignal.Light.accent, GraphiteSignal.Light.background)
        assertTrue(ratio < 3.0)
        assertTrue(GraphiteSignal.contrastRatio(GraphiteSignal.Light.focus, GraphiteSignal.Light.background) >= 3.0)
    }

    @Test fun contrastMathMatchesKnownValues() {
        assertEquals(21.0, GraphiteSignal.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, GraphiteSignal.contrastRatio(0xFF777777.toInt(), 0xFF777777.toInt()), 0.001)
    }

    @Test fun xmlColorsStayInSyncWithTokens() {
        val xml = File("src/main/res/values/colors.xml").takeIf { it.isFile }
            ?: File("app/src/main/res/values/colors.xml")
        assertTrue("colors.xml not found from ${File(".").absolutePath}", xml.isFile)
        val text = xml.readText()
        fun expectColor(name: String, argb: Int) {
            val m = Regex("<color name=\"$name\">(#[0-9A-Fa-f]{6,8})</color>").find(text)
            assertTrue("colors.xml missing $name", m != null)
            assertEquals("colors.xml $name out of sync", GraphiteSignal.hex(argb), "#" + m!!.groupValues[1].takeLast(6).uppercase())
        }
        val l = GraphiteSignal.Light; val d = GraphiteSignal.Dark
        expectColor("gs_light_background", l.background); expectColor("gs_light_surface", l.surface)
        expectColor("gs_light_secondary_surface", l.secondarySurface); expectColor("gs_light_text_primary", l.textPrimary)
        expectColor("gs_light_text_secondary", l.textSecondary); expectColor("gs_light_accent", l.accent)
        expectColor("gs_light_on_accent", l.onAccent); expectColor("gs_light_focus", l.focus)
        expectColor("gs_light_border", l.border)
        expectColor("gs_dark_background", d.background); expectColor("gs_dark_surface", d.surface)
        expectColor("gs_dark_secondary_surface", d.secondarySurface); expectColor("gs_dark_text_primary", d.textPrimary)
        expectColor("gs_dark_text_secondary", d.textSecondary); expectColor("gs_dark_accent", d.accent)
        expectColor("gs_dark_on_accent", d.onAccent); expectColor("gs_dark_focus", d.focus)
        expectColor("gs_dark_border", d.border)
    }
}
