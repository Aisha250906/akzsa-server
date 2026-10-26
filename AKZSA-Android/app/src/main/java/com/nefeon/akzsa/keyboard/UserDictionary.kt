package com.nefeon.akzsa.keyboard

/**
 * The user's personal dictionary (Settings > "Personal dictionary"). One field, two kinds of entries,
 * separated by commas, semicolons or new lines:
 *
 *   NEFEON                 a term: spoken hints use it, and its spelling/capital letters are enforced
 *   nefion = NEFEON        a correction: whenever "nefion" appears in the dictated text it becomes "NEFEON"
 *                          ("=>" and "->" work as well as "=")
 *
 * Matching is whole-word and ignores case. Everything is local; only the written terms (never the
 * corrections' wrong spellings) are sent as recognition hints.
 */
object UserDictionary {
    const val MAX_ENTRIES = 400
    private const val MAX_TERM = 80
    private const val MAX_SPOKEN = 60

    data class Entry(val spoken: String?, val written: String)

    fun parse(raw: String): List<Entry> {
        val entries = ArrayList<Entry>()
        for (piece in raw.split(',', ';', '\n', '\r')) {
            val p = piece.trim()
            if (p.isEmpty()) continue
            val arrow = listOf("=>", "->", "=").map { p.indexOf(it) to it }.filter { it.first >= 0 }.minByOrNull { it.first }
            if (arrow != null) {
                val spoken = p.substring(0, arrow.first).trim()
                val written = p.substring(arrow.first + arrow.second.length).trim()
                if (spoken.isNotEmpty() && written.isNotEmpty() && spoken.length <= MAX_SPOKEN && written.length <= MAX_TERM) {
                    entries += Entry(spoken, written)
                }
            } else if (p.length <= MAX_TERM) {
                entries += Entry(null, p)
            }
            if (entries.size >= MAX_ENTRIES) break
        }
        return entries
    }

    /** Written forms only, comma separated, for the cloud recognition hint. */
    fun promptTerms(raw: String, maxChars: Int = 900): String {
        val seen = LinkedHashSet<String>()
        for (e in parse(raw)) seen += e.written
        val sb = StringBuilder()
        for (t in seen) {
            if (sb.length + t.length + 2 > maxChars) break
            if (sb.isNotEmpty()) sb.append(", ")
            sb.append(t)
        }
        return sb.toString()
    }

    /** Written forms for the on-device recogniser's biasing list. */
    fun biasTerms(raw: String, max: Int = 50): List<String> =
        parse(raw).map { it.written }.distinct().take(max)

    private class Rule(val regex: Regex, val replacement: String)

    @Volatile private var cachedRaw: String? = null
    @Volatile private var cachedRules: List<Rule> = emptyList()

    private fun rulesFor(raw: String): List<Rule> {
        if (raw == cachedRaw) return cachedRules
        val entries = parse(raw)
        val rules = ArrayList<Rule>()
        // Explicit corrections first, then plain terms (which only repair spelling and capital letters).
        for (e in entries) if (e.spoken != null) rules += Rule(wholeWord(e.spoken), e.written)
        for (e in entries) if (e.spoken == null && e.written.length >= 2) rules += Rule(wholeWord(e.written), e.written)
        cachedRules = rules
        cachedRaw = raw
        return rules
    }

    private fun wholeWord(phrase: String): Regex {
        val body = phrase.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
        return Regex("(?<![\\p{L}\\p{M}\\p{N}])" + body + "(?![\\p{L}\\p{M}\\p{N}])", RegexOption.IGNORE_CASE)
    }

    fun apply(text: String, raw: String): String {
        if (text.isEmpty() || raw.isBlank()) return text
        var out = text
        for (rule in rulesFor(raw)) {
            if (rule.regex.containsMatchIn(out)) out = rule.regex.replace(out) { rule.replacement }
        }
        return out
    }
}
