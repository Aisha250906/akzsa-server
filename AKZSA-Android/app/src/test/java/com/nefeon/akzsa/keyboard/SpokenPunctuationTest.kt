package com.nefeon.akzsa.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenPunctuationTest {
    private fun p(s: String) = SpokenPunctuation.apply(s)

    @Test fun commaAndQuestionMark() = assertEquals("hello, how are you?", p("hello comma how are you question mark"))

    @Test fun fullStopCapitalisesTheNextSentence() = assertEquals("done. Next point", p("done full stop next point"))

    @Test fun newLineAndParagraph() {
        assertEquals("first line\nSecond line", p("first line new line second line"))
        assertEquals("\n\n", p("new paragraph"))
    }

    @Test fun semicolonAndColon() {
        assertEquals("a;", p("a semicolon"))
        assertEquals("note: this", p("note colon this"))
    }

    @Test fun brackets() = assertEquals("(hello)", p("open bracket hello close bracket"))

    @Test fun hindiAndHinglishPhrases() {
        assertEquals("ठीक है.", p("ठीक है पूर्ण विराम"))
        assertEquals("theek hai.", p("theek hai poorn viram"))
        assertEquals("kya hua?", p("kya hua question mark"))
    }

    @Test fun textWithoutSpokenPunctuationIsUntouched() {
        assertEquals("hello there", p("hello there"))
        assertEquals("the commander arrived", p("the commander arrived"))
    }

    @Test fun neverThrowsOnOddInput() {
        listOf("", " ", "comma", "new line new line", "😀 full stop 😀").forEach { p(it) }
    }
}
