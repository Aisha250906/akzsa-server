package com.nefeon.akzsa.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDictionaryTest {
    @Test fun parsesTermsAndCorrections() {
        val e = UserDictionary.parse("AKZSA, NEFEON\nnefion = NEFEON; kar na => karna; x -> y")
        assertEquals(listOf("AKZSA", "NEFEON", "NEFEON", "karna", "y"), e.map { it.written })
        assertEquals(listOf(null, null, "nefion", "kar na", "x"), e.map { it.spoken })
    }

    @Test fun ignoresBlankAndBrokenEntries() {
        assertTrue(UserDictionary.parse("  ,\n; = ; abc = ; = xyz").isEmpty())
    }

    @Test fun correctionReplacesWholeWordsIgnoringCase() {
        assertEquals("NEFEON is great", UserDictionary.apply("nefion is great", "nefion = NEFEON"))
        assertEquals("NEFEON is great", UserDictionary.apply("Nefion is great", "nefion = NEFEON"))
        assertEquals("nefions are great", UserDictionary.apply("nefions are great", "nefion = NEFEON"))
    }

    @Test fun plainTermsFixSpellingAndCapitals() {
        assertEquals("send it to NEFEON today", UserDictionary.apply("send it to nefeon today", "NEFEON"))
        assertEquals("ask Groq", UserDictionary.apply("ask groq", "AKZSA, Groq"))
        assertEquals("i like C++ a lot", UserDictionary.apply("i like c++ a lot", "C++"))
    }

    @Test fun multiWordCorrectionToleratesExtraSpaces() {
        assertEquals("mujhe karna hai", UserDictionary.apply("mujhe kar na hai", "kar na = karna"))
        assertEquals("mujhe karna hai", UserDictionary.apply("mujhe kar   na hai", "kar na = karna"))
    }

    @Test fun blankDictionaryOrTextChangesNothing() {
        assertEquals("hello", UserDictionary.apply("hello", ""))
        assertEquals("", UserDictionary.apply("", "NEFEON"))
    }

    @Test fun promptTermsAreWrittenFormsOnlyDedupedAndCapped() {
        assertEquals("AKZSA, NEFEON, groq", UserDictionary.promptTerms("AKZSA, nefion = NEFEON, groq, AKZSA"))
        assertEquals("aaaa, bbbb", UserDictionary.promptTerms("aaaa, bbbb, cccc", maxChars = 10))
        assertEquals(listOf("AKZSA", "NEFEON"), UserDictionary.biasTerms("AKZSA, nefion = NEFEON"))
    }

    @Test fun changingTheDictionaryTakesEffectImmediately() {
        assertEquals("one", UserDictionary.apply("uno", "uno = one"))
        assertEquals("uno", UserDictionary.apply("uno", "dos = two"))
    }
}
