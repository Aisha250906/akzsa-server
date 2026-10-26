package com.nefeon.akzsa.keyboard

/** Final, purely local clean-up of recognised text before it is inserted: spoken punctuation, then the user's dictionary. */
object TranscriptCleaner {
    fun clean(text: String, userDictionary: String, spokenPunctuation: Boolean): String {
        var out = text
        if (spokenPunctuation) out = SpokenPunctuation.apply(out)
        out = UserDictionary.apply(out, userDictionary)
        return out
    }
}
