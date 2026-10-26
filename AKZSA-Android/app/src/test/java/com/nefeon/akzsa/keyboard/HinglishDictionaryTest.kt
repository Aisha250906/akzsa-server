package com.nefeon.akzsa.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HinglishDictionaryTest {
    private fun r(s: String) = HinglishTransliterator.toRoman(s)

    @Test fun isBigEnough() = assertTrue(HinglishDictionary.size > 500)

    @Test fun englishWordsWrittenInDevanagariComeBackAsEnglish() {
        assertEquals("invoice", HinglishDictionary.lookup("इनवॉइस"))
        assertEquals("meeting", HinglishDictionary.lookup("मीटिंग"))
        assertEquals("WhatsApp", HinglishDictionary.lookup("व्हाट्सएप"))
        assertEquals("office", HinglishDictionary.lookup("ऑफिस"))
    }

    @Test fun spellingVariantsMatch() {
        assertEquals(HinglishDictionary.lookup("फ़ोन"), HinglishDictionary.lookup("फोन"))      // nukta
        assertEquals("phone", HinglishDictionary.lookup("फ़ोन"))
        assertEquals("haan", HinglishDictionary.lookup("हाँ"))
        assertEquals("haan", HinglishDictionary.lookup("हां"))                              // candrabindu / anusvara
        assertEquals(HinglishDictionary.key("हूँ"), HinglishDictionary.key("हूं"))
        assertEquals("zaroor", HinglishDictionary.lookup("जरूर"))
    }

    @Test fun unknownWordsFallBackToTheRules() {
        assertNull(HinglishDictionary.lookup("राहुल"))
        assertEquals("raahul", r("राहुल"))
    }

    @Test fun wholeSentencesReadLikeHinglish() {
        assertEquals("meri meeting office mein hai", r("मेरी मीटिंग ऑफिस में है"))
        assertEquals("kal client ko invoice bhejna hai.", r("कल क्लाइंट को इनवॉइस भेजना है।"))
        assertEquals("mera WhatsApp number", r("मेरा WhatsApp नंबर"))
    }

    @Test fun neverThrows() {
        listOf("", "।", "॥", "ं", "\u093C", "😀मीटिंग😀").forEach { r(it) }
    }
}
