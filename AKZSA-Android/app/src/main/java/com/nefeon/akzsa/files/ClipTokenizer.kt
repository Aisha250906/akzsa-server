package com.nefeon.akzsa.files

import java.util.Locale

/**
 * CLIP byte-pair-encoding tokenizer (same algorithm as OpenAI CLIP / open_clip SimpleTokenizer and the
 * Hugging Face CLIP tokenizer.json). Pure Kotlin, no Android classes, so it can be unit-tested on a JVM.
 */
class ClipTokenizer(
    private val vocab: Map<String, Int>,
    merges: List<Pair<String, String>>,
) {
    private val ranks: Map<Pair<String, String>, Int> = HashMap<Pair<String, String>, Int>().also { map ->
        merges.forEachIndexed { index, pair -> map[pair] = index }
    }
    private val cache = HashMap<String, List<String>>()
    private val byteToUnicode: Map<Int, Char> = buildByteToUnicode()
    private val bosId: Int = vocab["<|startoftext|>"] ?: 49406
    private val eosId: Int = vocab["<|endoftext|>"] ?: 49407
    private val pattern = Regex("""<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|\p{L}+|\p{N}|[^\s\p{L}\p{N}]+""")

    /** Returns exactly [maxLength] token ids: BOS, text tokens, EOS, then zero padding. */
    fun encode(text: String, maxLength: Int = 77): LongArray {
        val cleaned = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT)
        val ids = ArrayList<Int>()
        ids.add(bosId)
        for (match in pattern.findAll(cleaned)) {
            val token = match.value
            val mapped = StringBuilder()
            for (b in token.toByteArray(Charsets.UTF_8)) mapped.append(byteToUnicode.getValue(b.toInt() and 0xFF))
            for (piece in bpe(mapped.toString())) {
                ids.add(vocab[piece] ?: eosId)
            }
        }
        ids.add(eosId)
        val out = LongArray(maxLength)
        if (ids.size <= maxLength) {
            for (i in ids.indices) out[i] = ids[i].toLong()
        } else {
            // Too long: keep the start and make sure the sequence still ends with EOS.
            for (i in 0 until maxLength - 1) out[i] = ids[i].toLong()
            out[maxLength - 1] = eosId.toLong()
        }
        return out
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        if (token.isEmpty()) return emptyList()
        var word: List<String> = token.map { it.toString() }.toMutableList().also {
            it[it.size - 1] = it[it.size - 1] + "</w>"
        }
        while (word.size > 1) {
            var bestRank = Int.MAX_VALUE
            var best: Pair<String, String>? = null
            for (i in 0 until word.size - 1) {
                val pair = word[i] to word[i + 1]
                val rank = ranks[pair]
                if (rank != null && rank < bestRank) {
                    bestRank = rank
                    best = pair
                }
            }
            val bigram = best ?: break
            val merged = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == bigram.first && word[i + 1] == bigram.second) {
                    merged.add(bigram.first + bigram.second)
                    i += 2
                } else {
                    merged.add(word[i])
                    i += 1
                }
            }
            word = merged
        }
        if (cache.size < 20_000) cache[token] = word
        return word
    }

    private fun buildByteToUnicode(): Map<Int, Char> {
        val bs = ArrayList<Int>()
        for (c in '!'.code..'~'.code) bs.add(c)
        for (c in '¡'.code..'¬'.code) bs.add(c)
        for (c in '®'.code..'ÿ'.code) bs.add(c)
        val cs = ArrayList<Int>(bs)
        var n = 0
        for (b in 0 until 256) {
            if (b !in bs) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }
        val map = HashMap<Int, Char>()
        for (i in bs.indices) map[bs[i]] = cs[i].toChar()
        return map
    }

    companion object {
        /** Builds the tokenizer from a Hugging Face tokenizer.json (model.vocab + model.merges). */
        fun fromJson(json: String): ClipTokenizer {
            val model = org.json.JSONObject(json).getJSONObject("model")
            val vocabJson = model.getJSONObject("vocab")
            val vocab = HashMap<String, Int>(vocabJson.length() * 2)
            val keys = vocabJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                vocab[key] = vocabJson.getInt(key)
            }
            val mergesJson = model.getJSONArray("merges")
            val merges = ArrayList<Pair<String, String>>(mergesJson.length())
            for (i in 0 until mergesJson.length()) {
                when (val item = mergesJson.get(i)) {
                    is String -> {
                        val space = item.indexOf(' ')
                        if (space > 0) merges.add(item.substring(0, space) to item.substring(space + 1))
                    }
                    is org.json.JSONArray -> if (item.length() >= 2) merges.add(item.getString(0) to item.getString(1))
                }
            }
            return ClipTokenizer(vocab, merges)
        }
    }
}
