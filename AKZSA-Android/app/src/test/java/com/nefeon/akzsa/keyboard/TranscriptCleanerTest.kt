package com.nefeon.akzsa.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptCleanerTest {
    @Test fun punctuationThenDictionary() {
        assertEquals("NEFEON ko invoice bhejo,", TranscriptCleaner.clean("nefion ko invoice bhejo comma", "nefion = NEFEON", true))
    }

    @Test fun spokenPunctuationCanBeSwitchedOff() {
        assertEquals("NEFEON ko invoice bhejo comma", TranscriptCleaner.clean("nefion ko invoice bhejo comma", "nefion = NEFEON", false))
    }

    @Test fun plainTextPassesThrough() {
        assertEquals("hello world", TranscriptCleaner.clean("hello world", "", true))
    }
}
