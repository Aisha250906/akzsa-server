package com.nefeon.akzsa.keyboard

/**
 * Hindi (Devanagari) typing layout data. Pure data so it can be validated by tests.
 * Page 0: consonants + vowel signs (matras). Page 1: independent vowels, signs and common conjuncts.
 * Conjuncts such as "क्ष" are single keys; any other conjunct is typed with the halant key (्).
 */
object DevanagariLayout {
    /** Key rows above the bottom row. Page 0 uses 4 rows, page 1 uses 3 rows; both are padded to 4 for equal height. */
    val page0: List<List<String>> = listOf(
        listOf("क", "ख", "ग", "घ", "च", "छ", "ज", "झ", "ट", "ठ"),
        listOf("ड", "ढ", "त", "थ", "द", "ध", "न", "प", "फ", "ब"),
        listOf("भ", "म", "य", "र", "ल", "व", "श", "ष", "स", "ह"),
        listOf("ा", "ि", "ी", "ु", "ू", "े", "ै", "ो", "ौ"),
    )
    val page1: List<List<String>> = listOf(
        listOf("अ", "आ", "इ", "ई", "उ", "ऊ", "ए", "ऐ", "ओ", "औ"),
        listOf("ऋ", "ऑ", "ॉ", "ँ", "ं", "ः", "्", "़", "ृ", "।"),
        listOf("क्ष", "त्र", "ज्ञ", "श्र", "ड़", "ढ़", "ळ", "ङ", "ञ", "ण"),
        listOf("क़", "ख़", "ग़", "ज़", "फ़", "०", "१", "२", "३", "४"),
    )
    const val HALANT = "्"
    const val DANDA = "।"
    const val ROWS_WITH_BOTTOM = 5

    val allKeys: List<String> get() = (page0 + page1).flatten()

    /** Every code point of every key must be inside the Devanagari block (U+0900..U+097F). */
    fun isDevanagari(key: String): Boolean = key.isNotEmpty() && key.codePoints().allMatch { it in 0x0900..0x097F }
}
