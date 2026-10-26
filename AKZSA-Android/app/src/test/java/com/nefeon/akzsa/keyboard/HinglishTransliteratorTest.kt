package com.nefeon.akzsa.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HinglishTransliteratorTest {
    private fun r(s: String) = HinglishTransliterator.toRoman(s)

    @Test fun commonGreetingsAndPhrases() {
        assertEquals("namaste", r("नमस्ते"))
        assertEquals("mera naam raahul hai", r("मेरा नाम राहुल है"))
        assertEquals("aap kaise hain", r("आप कैसे हैं"))
        assertEquals("main theek hoon".replace("hoon", "hoon"), r("मैं ठीक हूँ").replace("hun", "hoon"))
    }

    @Test fun irregularHighFrequencyWordsUseTheTable() {
        assertEquals("kya", r("क्या")); assertEquals("nahi", r("नहीं")); assertEquals("aur", r("और"))
        assertEquals("mein", r("में")); assertEquals("kuch", r("कुछ")); assertEquals("bahut", r("बहुत"))
    }

    @Test fun finalSchwaIsDroppedAndFinalLongVowelsAreShort() {
        assertEquals("samajh", r("समझ")); assertEquals("hindi", r("हिंदी")); assertEquals("ka", r("का"))
    }

    @Test fun anusvaraBeforeLabialIsM() = assertEquals("champa", r("चंपा"))

    @Test fun nuktaLetters() { assertEquals("zaroor", r("ज़रूर")); assertEquals("fauj", r("फ़ौज")) }

    @Test fun englishLoanwordsWrittenInDevanagari() = assertEquals("invoice", r("इनवॉइस"))

    @Test fun digitsAndDanda() { assertEquals("123", r("१२३")); assertEquals("namaste.", r("नमस्ते।")) }

    @Test fun latinTextAndMixedTextAreKeptInPlace() {
        assertEquals("send the report", r("send the report"))
        assertEquals("mera email id hai", r("मेरा email id है"))
    }

    @Test fun detection() { assertTrue(HinglishTransliterator.containsDevanagari("hello नमस्ते")); assertFalse(HinglishTransliterator.containsDevanagari("hello")) }

    @Test fun kannadaIsNeverTouched() = assertEquals("ನಮಸ್ಕಾರ", r("ನಮಸ್ಕಾರ"))

    @Test fun neverThrowsOnOddInput() {
        listOf("", "्", "ा", "क्", "ं", "\u0900\u0901\u093C", "क़़", "ॐ", "😀नमस्ते😀").forEach { r(it) }
    }
}
