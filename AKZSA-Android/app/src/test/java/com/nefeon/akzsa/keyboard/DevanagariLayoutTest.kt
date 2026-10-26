package com.nefeon.akzsa.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevanagariLayoutTest {
    @Test fun everyKeyIsPureDevanagari() {
        DevanagariLayout.allKeys.forEach { assertTrue("'$it' is not Devanagari", DevanagariLayout.isDevanagari(it)) }
    }

    @Test fun pagesHaveFourRowsAndNoRowIsTooWideForAPhone() {
        assertEquals(4, DevanagariLayout.page0.size); assertEquals(4, DevanagariLayout.page1.size)
        (DevanagariLayout.page0 + DevanagariLayout.page1).forEach { assertTrue(it.size in 1..10) }
    }

    @Test fun noDuplicateKeysWithinAPageAndEveryBasicConsonantIsReachable() {
        listOf(DevanagariLayout.page0, DevanagariLayout.page1).forEach { p -> assertEquals(p.flatten().size, p.flatten().toSet().size) }
        val basic = "कखगघचछजझटठडढणतथदधनपफबभमयरलवशषसह"
        val onPage0 = DevanagariLayout.page0.flatten().toSet()
        basic.forEach { c -> assertTrue("missing consonant $c", c.toString() in onPage0 || c.toString() in DevanagariLayout.page1.flatten()) }
    }

    @Test fun allIndependentVowelsAndAllMatrasAreReachable() {
        "अआइईउऊएऐओऔ".forEach { assertTrue("vowel $it", it.toString() in DevanagariLayout.page1.flatten()) }
        "ािीुूेैोौ".forEach { assertTrue("matra $it", it.toString() in DevanagariLayout.page0.flatten()) }
    }

    @Test fun halantAndDandaAreAvailable() {
        assertTrue(DevanagariLayout.HALANT in DevanagariLayout.page1.flatten())
        assertTrue(DevanagariLayout.DANDA in DevanagariLayout.page1.flatten())
    }

    @Test fun keyDescriptionsAreNeverBlank() {
        listOf("⌫", "⇧", "?123", "ABC", "space", "↵", "🎙 Talk", "⌨", "अ/A", "⚙", "⌄").forEach { assertTrue(KeyLabels.describe(it).isNotBlank()) }
        assertEquals("Delete", KeyLabels.describe("⌫"))
    }
}
