package com.nefeon.akzsa.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationFormatterNewlineTest {
    private fun fmt(text: String, before: String) = DictationFormatter.format(
        transcript = text, textBeforeCursor = before, hasSelection = false,
        spaceSafe = true, appendTrailingSpace = true, capitalizeFirst = false,
    )

    @Test fun trailingNewlineFromSpokenCommandIsKept() = assertEquals("line\n", fmt("line\n", ""))

    @Test fun leadingNewlineIsNotPrecededBySpace() = assertEquals("\nnext ", fmt("\nnext", "a"))

    @Test fun ordinaryTextStillTrimmedAndSpaced() = assertEquals(" hello ", fmt("  hello  ", "a"))
}
