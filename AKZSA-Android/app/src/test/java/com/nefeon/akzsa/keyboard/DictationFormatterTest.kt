package com.nefeon.akzsa.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationFormatterTest {
    private fun fmt(t: String, before: String = "", sel: Boolean = false, safe: Boolean = true, trail: Boolean = true, cap: Boolean = false) =
        DictationFormatter.format(t, before, sel, safe, trail, cap)

    @Test fun dictationAfterTypedWordGetsALeadingSpaceInsteadOfGluing() =
        assertEquals(" how are you ", fmt("how are you", before = "o"))

    @Test fun noLeadingSpaceAtStartAfterSpaceOrNewline() {
        assertEquals("hello ", fmt("hello"))
        assertEquals("hello ", fmt("hello", before = "a "))
        assertEquals("hello ", fmt("hello", before = "a\n"))
    }

    @Test fun punctuationStaysAttachedToThePreviousWord() = assertEquals(", and then ", fmt(", and then", before = "x"))

    @Test fun noSpaceAfterOpeningBracketOrQuote() {
        assertEquals("yes ", fmt("yes", before = "("))
        assertEquals("yes ", fmt("yes", before = "\""))
    }

    @Test fun replacingASelectionNeverAddsALeadingSpace() = assertEquals("hello ", fmt("hello", before = "word", sel = true))

    @Test fun emailUrlAndNumericFieldsGetNoSpacesAtAll() = assertEquals("john", fmt("john", before = "x", safe = false))

    @Test fun capitalisesFirstLetterOnlyWhenTheFieldAsksForIt() {
        assertEquals("Hello world ", fmt("hello world", cap = true))
        assertEquals("hello world ", fmt("hello world", cap = false))
    }

    @Test fun devanagariAndKannadaAreUnaffectedByCapitalisation() {
        assertEquals("नमस्ते दोस्त ", fmt("नमस्ते दोस्त", cap = true))
        assertEquals("ನಮಸ್ಕಾರ ", fmt("ನಮಸ್ಕಾರ", cap = true))
    }

    @Test fun trailingSpaceIsOptionalAndNeverDoubled() {
        assertEquals("hi", fmt("hi", trail = false))
        assertEquals("hi ", fmt("hi ", trail = true))
    }

    @Test fun collapsesRepeatedSpacesAndTrims() = assertEquals("a b c ", fmt("  a   b  c  "))

    @Test fun emptyTranscriptInsertsNothing() { assertEquals("", fmt("   ")); assertEquals("", fmt("")) }

    @Test fun twoConsecutiveDictationsReadNaturally() {
        val first = fmt("send the report", cap = true)
        val second = fmt("by friday", before = first.takeLast(2), cap = false)
        assertEquals("Send the report by friday ", first + second)
    }

    @Test fun capsModeMapping() { assertFalse(DictationFormatter.wantsCapital(0)); assertTrue(DictationFormatter.wantsCapital(0x4000)) }
}
