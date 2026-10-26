package com.nefeon.akzsa.keyboard

/**
 * Turns spoken punctuation and layout words into symbols while dictating:
 * "hello comma how are you question mark" -> "hello, how are you?".
 * English, Hindi (Devanagari) and Hinglish (Roman) phrases. Can be switched off in Settings.
 * Only distinctive phrases are converted ("full stop", "new line"...); ambiguous words such as "period" are left alone.
 */
object SpokenPunctuation {
    private const val WORD_START = "(?<![\\p{L}\\p{M}\\p{N}])"
    private const val WORD_END = "(?![\\p{L}\\p{M}\\p{N}])"

    private fun phrase(alternatives: String): Regex = Regex(WORD_START + "(?:" + alternatives + ")" + WORD_END, RegexOption.IGNORE_CASE)

    private val rules: List<Pair<Regex, String>> = listOf(
        phrase("new paragraph|next paragraph|naya paragraph|नया पैराग्राफ|न्यू पैराग्राफ") to "\n\n",
        phrase("new line|next line|newline|nayi line|nai line|naya line|नई लाइन|नयी लाइन|न्यू लाइन") to "\n",
        phrase("full stop|full-stop|poorn viram|purn viram|पूर्ण विराम|फुल स्टॉप") to ".",
        phrase("question mark|prashn chinh|प्रश्न चिन्ह|प्रश्नचिह्न|क्वेश्चन मार्क") to "?",
        phrase("exclamation mark|exclamation point|विस्मयादिबोधक चिन्ह|एक्सक्लेमेशन मार्क") to "!",
        phrase("comma|कॉमा|कोमा") to ",",
        phrase("semicolon|semi colon|सेमीकोलन") to ";",
        phrase("colon|कोलन") to ":",
        phrase("open bracket|open parenthesis") to "(",
        phrase("close bracket|close parenthesis") to ")",
    )

    private val spaceBeforePunctuation = Regex("[ \\t]+([,.;:!?)])")
    private val spacesAroundNewline = Regex("[ \\t]*\\n[ \\t]*")
    private val spaceAfterOpenBracket = Regex("\\([ \\t]+")
    private val capitalAfterStop = Regex("([.!?][ \\t]+|\\n)(\\p{Ll})")

    fun apply(text: String): String {
        if (text.isBlank()) return text
        var out = text
        var changed = false
        for ((regex, symbol) in rules) {
            if (regex.containsMatchIn(out)) {
                out = regex.replace(out) { symbol }
                changed = true
            }
        }
        if (!changed) return text
        out = spaceBeforePunctuation.replace(out) { it.groupValues[1] }
        out = spaceAfterOpenBracket.replace(out, "(")
        out = spacesAroundNewline.replace(out, "\n")
        out = capitalAfterStop.replace(out) { it.groupValues[1] + it.groupValues[2].uppercase() }
        return out.trim(' ', '\t')
    }
}
