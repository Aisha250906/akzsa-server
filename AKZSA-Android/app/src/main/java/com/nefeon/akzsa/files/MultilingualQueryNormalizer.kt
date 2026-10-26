package com.nefeon.akzsa.files

import java.text.Normalizer
import java.util.Locale

/**
 * Lightweight, deterministic query normalization for local file search.
 * This is a small phrasebook/alias layer, not a machine translation system. Unknown words are kept.
 * The image embedding model remains English-oriented; supported translated concepts are normalized
 * before embedding while lexical search continues to search the original indexed contents locally.
 */
object MultilingualQueryNormalizer {
    private val conceptAliases: Map<String, String> = buildMap {
        fun add(canonical: String, aliases: String) {
            aliases.split('|').map(String::trim).filter(String::isNotEmpty).forEach { put(normalize(it), canonical) }
        }
        add("car", "car|cars|automobile|automobiles|gaadi|gadi|gaadiyan|gaadiyon|गाड़ी|गाड़ी|कार|गाड़ियाँ|गाड़ियाँ|ಗಾಡಿ|ಕಾರು|ಕಾರ್|மகிழுந்து|கார்|కారు|క్యారు|গাড়ি|গাড়ি|गाडी|گاڑی")
        add("motorcycle", "motorcycle|motorcycles|motorbike|motorbikes|motor bike|motor cycle|two wheeler|two-wheelers|bike|bikes|biker|scooter|scooters|ಬೈಕ್|ಸ್ಕೂಟರ್|बाइक|मोटरसाइकिल|स्कूटर|பைக்|மோட்டார் சைக்கிள்|ஸ்கூட்டர்|బైక్|మోటార్ సైకిల్|స్కూటర్|বাইক|মোটরসাইকেল|স্কুটার|बाईक|मोटरसायकल|موٹر سائیکل|motor gaadi")
        add("bicycle", "bicycle|bicycles|cycle|cycles|cycling|ಸೈಕಲ್|ಬೈಸಿಕಲ್|ಸೈಕ್ಲಿಂಗ್|साइकिल|साइकल|சைக்கிள்|சைக்கிள் வண்டி|సైకిల్|সাইকেল|सायकल")
        add("truck", "truck|trucks|lorry|lorries|ಟ್ರಕ್|ಲಾರಿ|ट्रक|लॉरी")
        add("bus", "bus|buses|ಬಸ್|बस|பேருந்து|బస్సు|বাস|বাসগাড়ি|बस")
        add("train", "train|trains|railway|ರೈಲು|ಟ್ರೈನ್|ट्रेन|रेलगाड़ी|रेलगाड़ी|ரயில்|రైలు|ট্রেন|रेल्वे")
        add("airplane", "airplane|aeroplane|plane|planes|ವಿಮಾನ|हवाई जहाज|विमान")
        add("dog", "dog|dogs|puppy|puppies|कुत्ता|कुत्ते|कुत्तों|ನಾಯಿ|ನಾಯಿಗಳು|நாய்|కుక్క|কুকুর|कुत्रा|کتا")
        add("cat", "cat|cats|kitten|kittens|बिल्ली|बिल्लियां|बिल्लियाँ|ಬೆಕ್ಕು|ಬೆಕ್ಕುಗಳು|பூனை|పిల్లి|বিড়াল|बिल्ली|بلی")
        add("bird", "bird|birds|ಪಕ್ಷಿ|ಹಕ್ಕಿ|ಪಕ್ಷಿಗಳು|पक्षी|चिड़िया|चिड़िया")
        add("person", "person|people|human|humans|man|men|woman|women|आदमी|औरत|महिला|पुरुष|ಮನುಷ್ಯ|ಮಹಿಳೆ|ಪುರುಷ")
        add("baby", "baby|infant|toddler|बच्चा|बच्चे|ಶಿಶು|ಮಗು|ಮಕ್ಕಳು")
        add("family", "family|families|परिवार|ಕುಟುಂಬ")
        add("house", "house|home|building|घर|मकान|ಮನೆ|ಕಟ್ಟಡ")
        add("food", "food|meal|dish|cuisine|खाना|भोजन|ಆಹಾರ|ಊಟ")
        add("fruit", "fruit|fruits|फ्रूट|फल|ಹಣ್ಣು|ಹಣ್ಣುಗಳು")
        add("flower", "flower|flowers|फूल|ಹೂವು|ಹೂಗಳು")
        add("tree", "tree|trees|पेड़|पेड़|वृक्ष|ಮರ|ಮರಗಳು")
        add("mountain", "mountain|mountains|hill|hills|पहाड़|पहाड़|ಪರ್ವತ|ಬೆಟ್ಟ")
        add("river", "river|rivers|नदी|नदियां|नदियाँ|ನದಿ|ನದಿಗಳು")
        add("beach", "beach|seaside|समुद्र तट|ಸಮುದ್ರ ತೀರ|ಕಡಲ ತೀರ")
        add("water", "water|पानी|जल|ನೀರು")
        add("sky", "sky|आकाश|आसमान|ಆಕಾಶ")
        add("sunset", "sunset|sunrise|सूर्यास्त|सूर्योदय|ಸೂರ್ಯಾಸ್ತ|ಸೂರ್ಯೋದಯ")
        add("book", "book|books|किताब|किताबें|ಪುಸ್ತಕ|ಪುಸ್ತಕಗಳು")
        add("document", "document|documents|file|files|कागज|दस्तावेज|ದಾಖಲೆ|ದಸ್ತಾವೇಜು")
        add("invoice", "invoice|invoices|bill|bills|receipt|receipts|इनवॉइस|बिल|रसीद|ಇನ್ವಾಯ್ಸ್|ಬಿಲ್|ರಸೀದಿ")
        add("screenshot", "screenshot|screenshots|स्क्रीनशॉट|ಸ್ಕ್ರೀನ್‌ಶಾಟ್")
        add("photo", "photo|photos|picture|pictures|image|images|pic|pics|फोटो|तस्वीर|तस्वीरें|चित्र|ಚಿತ್ರங்கள்|ಚಿತ್ರ|ಚಿತ್ರಗಳು|ಫೋಟೋ|புகைப்படம்|படம்|ఫోటో|చిత్రం|ছবি|फोटो|تصویر")
        add("wedding", "wedding|marriage|शादी|विवाह|ಮದುವೆ")
        add("clothing", "clothing|clothes|dress|shirt|saree|साड़ी|साड़ी|कपड़े|ಬಟ್ಟೆ|ಉಡುಪು")
        add("phone", "phone|mobile|smartphone|फोन|मोबाइल|ಮೊಬೈಲ್|ದೂರವಾಣಿ")
        add("laptop", "laptop|computer|ಕಂಪ್ಯೂಟರ್|ಲ್ಯಾಪ್‌ಟಾಪ್|लैपटॉप|कंप्यूटर")
    }

    private val ignoredCommandWords = setOf(
        "find", "search", "show", "open", "give", "get", "please", "pls", "me", "my", "the", "a", "an", "of", "for", "to", "in", "on", "and", "with", "where", "is", "are",
        "mujhe", "mujhko", "mera", "meri", "mere", "please", "dikhao", "dhundo", "dhoondo", "khojo", "karo", "hai", "hain", "ka", "ki", "ke", "ko", "mein", "me", "par", "wali", "wale", "waale", "wala", "wali", "aur",
        "मुझे", "मेरा", "मेरी", "मेरे", "दिखाओ", "ढूंढो", "खोजो", "है", "हैं", "का", "की", "के", "में", "और", "पर",
        "ನನ್ನ", "ನನಗೆ", "ತೋರಿಸು", "ಹುಡುಕು", "ಹುಡುಕಿ", "ಇದೆ", "ಮತ್ತು", "ಗೆ", "ನಲ್ಲಿ", "ನ", "ನನ್ನದು",
        "मला", "माझा", "माझी", "माझे", "दाखवा", "शोधा", "मुझे", "আমার", "আমাকে", "দেখাও", "খুঁজুন", "నా", "నాకు", "చూపించు", "వెతుకు", "என்", "எனக்கு", "காட்டு", "தேடு", "میری", "میرا", "مجھے", "کی", "کے", "کا", "دکھاؤ"
    )

    /** Unicode-normalized lowercase form preserving script and searchable punctuation boundaries. */
    fun normalize(input: String): String = Normalizer.normalize(input, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace('’', '\'')
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}']+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Map known phrase-level and single-token aliases to model-friendly English concepts. Unknown words survive. */
    fun toEnglishConceptQuery(input: String): String {
        var normalized = normalize(input)
        if (normalized.isBlank()) return ""
        // Greedy phrase replacement prevents aliases like "motor cycle" and "हवाई जहाज"
        // from being split into unrelated concepts before they are looked up.
        val multiwordAliases = conceptAliases.entries
            .filter { it.key.contains(' ') }
            .sortedWith(compareByDescending<Map.Entry<String, String>> { it.key.length }.thenBy { it.key })
        for ((alias, canonical) in multiwordAliases) {
            val pattern = Regex("(?<![\\p{L}\\p{M}\\p{N}])${Regex.escape(alias)}(?![\\p{L}\\p{M}\\p{N}])")
            normalized = pattern.replace(normalized, canonical)
        }
        val tokens = Regex("[\\p{L}\\p{M}\\p{N}']+").findAll(normalized).map { it.value }.toList()
        return tokens.mapNotNull { token ->
            if (token in ignoredCommandWords) null else conceptAliases[token] ?: token
        }.joinToString(" ").trim()
    }

    /** English equivalents used by lexical filename/OCR/label matching; original query remains private/local. */
    fun expandToken(token: String): Set<String> {
        val normalized = normalize(token)
        val canonical = conceptAliases[normalized] ?: return setOf(normalized).filter { it.isNotBlank() }.toSet()
        val aliases = conceptAliases.filterValues { it == canonical }.keys
        return (aliases + canonical).toSet()
    }

    fun isCommandWord(token: String): Boolean = normalize(token) in ignoredCommandWords
}
