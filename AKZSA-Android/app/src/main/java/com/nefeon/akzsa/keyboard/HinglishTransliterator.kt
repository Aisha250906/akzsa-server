package com.nefeon.akzsa.keyboard

/**
 * Best-effort Devanagari -> Roman ("Hinglish") transliteration, fully local.
 *
 * Used when the user chose Hinglish: Hindi speech is recognised with the Hindi engine (which handles Hindi
 * words far better than an English engine) and then written in Latin letters, the way people type
 * Hinglish ("mera naam Rahul hai"). It is a heuristic, not a dictionary: spellings can differ from what a
 * person would write. [HinglishDictionary] is checked first (English words that Hindi engines write in Devanagari, and
 * the most common Hindi words); a small table then fixes irregular schwa words, and the rest is converted by rules.
 * Non-Devanagari text is returned unchanged.
 */
object HinglishTransliterator {
    private val devanagari = Regex("[\\u0900-\\u097F]+")

    fun containsDevanagari(text: String): Boolean = devanagari.containsMatchIn(text)

    fun toRoman(text: String): String =
        if (!containsDevanagari(text)) text else devanagari.replace(text) { romanWord(it.value) }

    /** Dictionary first (English loanwords and common Hindi spellings), then the rule-based conversion. */
    private fun romanWord(run: String): String {
        val core = run.trimEnd('\u0964', '\u0965')
        val dandas = run.length - core.length
        if (core.isEmpty()) return word(run)
        val known = HinglishDictionary.lookup(core) ?: return word(run)
        return known + ".".repeat(dandas)
    }

    private val exceptions = mapOf(
        "है" to "hai", "हैं" to "hain", "मैं" to "main", "में" to "mein", "नहीं" to "nahi", "और" to "aur",
        "यह" to "yeh", "वह" to "woh", "कुछ" to "kuch", "यहाँ" to "yahan", "वहाँ" to "wahan", "कहाँ" to "kahan",
        "आप" to "aap", "हम" to "hum", "तुम" to "tum", "मुझे" to "mujhe", "क्या" to "kya", "कि" to "ki",
        "को" to "ko", "से" to "se", "का" to "ka", "की" to "ki", "के" to "ke", "भी" to "bhi", "तो" to "toh",
        "हो" to "ho", "था" to "tha", "थी" to "thi", "थे" to "the", "ठीक" to "theek", "बहुत" to "bahut",
        "अच्छा" to "accha", "धन्यवाद" to "dhanyavaad", "शुक्रिया" to "shukriya", "कृपया" to "kripya",
        "।" to ".",
    )

    private val consonants = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "ng", 'च' to "ch", 'छ' to "chh", 'ज' to "j",
        'झ' to "jh", 'ञ' to "ny", 'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t",
        'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n", 'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh",
        'म' to "m", 'य' to "y", 'र' to "r", 'ल' to "l", 'ळ' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh",
        'स' to "s", 'ह' to "h",
        // precomposed nukta letters U+0958..U+095F
        '\u0958' to "q", '\u0959' to "kh", '\u095A' to "gh", '\u095B' to "z", '\u095C' to "r", '\u095D' to "rh",
        '\u095E' to "f", '\u095F' to "y",
    )
    private val nukta = mapOf('क' to "q", 'ख' to "kh", 'ग' to "gh", 'ज' to "z", 'ड' to "r", 'ढ' to "rh", 'फ' to "f", 'य' to "y")

    private val vowels = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri", 'ए' to "e", 'ऐ' to "ai",
        'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऍ' to "e", 'ऎ' to "e", 'ऒ' to "o",
    )
    private val matras = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri", 'ॅ' to "e", 'ॆ' to "e", 'े' to "e",
        'ै' to "ai", 'ॉ' to "o", 'ॊ' to "o", 'ो' to "o", 'ौ' to "au",
    )
    private const val ANUSVARA = 'ं'; private const val CANDRABINDU = 'ँ'; private const val VISARGA = 'ः'
    private const val VIRAMA = '्'; private const val NUKTA = '़'
    private val labials = setOf('प', 'फ', 'ब', 'भ', 'म')

    private sealed interface Tok
    private class Syl(val cons: String, var vowel: String?, val inherent: Boolean, val halant: Boolean, val labial: Boolean) : Tok
    private class Vow(val roman: String) : Tok
    private class Nasal(val ch: Char) : Tok
    private class Lit(val s: String) : Tok

    private fun word(w: String): String {
        exceptions[w]?.let { return it }
        val toks = ArrayList<Tok>()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            when {
                c in consonants -> {
                    var roman = consonants.getValue(c)
                    if (i + 1 < w.length && w[i + 1] == NUKTA) { roman = nukta[c] ?: roman; i++ }
                    val next = w.getOrNull(i + 1)
                    when {
                        next == VIRAMA -> { toks += Syl(roman, null, false, true, c in labials); i += 2 }
                        next != null && next in matras -> { toks += Syl(roman, matras.getValue(next), false, false, c in labials); i += 2 }
                        else -> { toks += Syl(roman, "a", true, false, c in labials); i++ }
                    }
                }
                c in vowels -> { toks += Vow(vowels.getValue(c)); i++ }
                c == ANUSVARA || c == CANDRABINDU || c == VISARGA -> { toks += Nasal(c); i++ }
                c in '\u0966'..'\u096F' -> { toks += Lit((c - '\u0966').toString()); i++ }
                c == '\u0964' || c == '\u0965' -> { toks += Lit("."); i++ }
                c == 'ॐ' -> { toks += Lit("om"); i++ }
                c == 'ऽ' || c == VIRAMA || c == NUKTA -> i++
                else -> { toks += Lit(c.toString()); i++ }
            }
        }
        return render(toks)
    }

    private fun isUnit(t: Tok) = t is Syl || t is Vow

    private fun render(toks: List<Tok>): String {
        val units = toks.withIndex().filter { isUnit(it.value) }.map { it.index }
        val sb = StringBuilder()
        for ((index, tok) in toks.withIndex()) {
            when (tok) {
                is Syl -> {
                    sb.append(tok.cons)
                    val v = tok.vowel
                    if (v != null) {
                        val unitPos = units.indexOf(index)
                        val isLast = unitPos == units.lastIndex
                        val nextIsNasalFollow = toks.getOrNull(index + 1) is Nasal
                        val out = when {
                            tok.inherent && isLast && !nextIsNasalFollow -> ""          // word-final schwa deletion
                            tok.inherent && medialDeletable(toks, units, unitPos) -> ""
                            // final long vowels are written short: mera, kya, hindi
                            !tok.inherent && isLast && !nextIsNasalFollow && v == "aa" -> "a"
                            !tok.inherent && isLast && !nextIsNasalFollow && (v == "ee") -> "i"
                            !tok.inherent && isLast && !nextIsNasalFollow && (v == "oo") -> "u"
                            else -> v
                        }
                        sb.append(out)
                    }
                }
                is Vow -> {
                    val unitPos = units.indexOf(index)
                    val isLast = unitPos == units.lastIndex
                    sb.append(if (isLast && tok.roman == "aa" && units.size > 1) "a" else tok.roman)
                }
                is Nasal -> when (tok.ch) {
                    VISARGA -> sb.append('h')
                    else -> {
                        val nextSyl = toks.drop(toks.indexOf(tok) + 1).firstOrNull { it is Syl } as? Syl
                        sb.append(if (nextSyl?.labial == true) 'm' else 'n')
                    }
                }
                is Lit -> sb.append(tok.s)
            }
        }
        return sb.toString()
    }

    /** Medial schwa deletion only between two syllables that both carry an explicit vowel (sunta, mujhse). */
    private fun medialDeletable(toks: List<Tok>, units: List<Int>, unitPos: Int): Boolean {
        if (unitPos <= 0 || unitPos >= units.lastIndex) return false
        fun explicit(t: Tok): Boolean = (t is Syl && !t.inherent && !t.halant) || t is Vow
        return explicit(toks[units[unitPos - 1]]) && explicit(toks[units[unitPos + 1]])
    }
}
