package com.nefeon.akzsa.files

import com.nefeon.akzsa.network.FileSearchHit
import java.util.Locale
import java.math.BigDecimal

/** Fast deterministic search over AKZSA's private local index. No content or query is uploaded. */
object LocalFileSearch {
    data class StructuredSignals(val gstins: List<String>, val invoiceNumbers: List<String>, val amounts: List<String>)

    /** Called when an extracted-text record is stored, not on every query. */
    fun extractStructuredSignals(text: String): StructuredSignals = StructuredSignals(
        gstins = gstinRegex.findAll(text).map { it.value.uppercase(Locale.ROOT) }.distinct().take(10).toList(),
        invoiceNumbers = invoiceRegex.findAll(text).map { it.groupValues[1].trim('.', ',', ';', ':') }.distinct().take(10).toList(),
        amounts = amountRegex.findAll(text).map { it.groupValues[1].trim() }.distinct().take(20).toList(),
    )

    // Remove command filler only. GST/invoice/bill are useful content terms and must remain searchable.
    private val stopWords = setOf(
        "find", "search", "show", "showme", "me", "my", "the", "a", "an", "for", "from", "please",
        "this", "that", "in", "on", "of", "and", "with", "latest", "get", "locate", "look", "lookfor",
        "where", "which", "containing", "contains", "having", "has", "is", "are", "was", "were", "to", "at",
        "by", "about", "i", "it", "you", "there", "near", "beside", "next", "last", "month", "week", "year",
        "mujhe", "mera", "meri", "mere", "ka", "ki", "ke", "hai", "ko", "dhundo", "dhoondo", "dikhao", "khojo",
        "ಹುಡುಕು", "ಹುಡುಕಿ", "दिखाओ", "मुझे", "मेरा", "मेरी", "ढूंढो", "खोजो", "का", "की", "के"
    )
    private val fileTypeWords = setOf(
        "file", "files", "document", "documents", "pdf", "photo", "photos", "image", "images", "picture", "pictures",
        "pic", "pics", "screenshot", "screenshots", "jpeg", "jpg", "png", "webp", "bmp", "heic", "tif", "tiff", "gif"
    )
    private val imageIntentWords = setOf("photo", "photos", "image", "images", "picture", "pictures", "pic", "pics", "screenshot", "screenshots", "jpeg", "jpg", "png", "webp", "bmp", "heic", "tif", "tiff", "gif")
    private val tokenRegex = Regex("[\\p{L}\\p{M}\\p{N}]+")
    // Identifiers the tokenizer would split or drop: INV-2026-09, 12/B-44, PNR XK92LM. An exact hit is strong evidence.
    private val hyphenatedId = Regex("\\b[A-Za-z0-9]{2,}(?:[-/][A-Za-z0-9]+)+\\b")
    private val mixedAlnumId = Regex("\\b(?=[A-Za-z0-9]*[A-Za-z])(?=[A-Za-z0-9]*\\d)[A-Za-z0-9]{5,}\\b")
    /** Ordinal ceiling only (never shown to users). A tight cap made strong matches tie and fall back to recency. */
    private const val MAX_SCORE = 1000
    private val gstinRegex = Regex("(?i)\\b[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]\\b")
    private val invoiceRegex = Regex("(?i)(?:invoice(?:\\s*(?:no|number|#))?|inv\\.?\\s*(?:no|#)|bill\\s*(?:no|number|#))\\s*[:#-]?\\s*([A-Z0-9][A-Z0-9/-]{2,24})")
    private val amountRegex = Regex("(?i)(?:₹|rs\\.?|inr)?\\s*([0-9]{1,3}(?:[, ][0-9]{2,3})*(?:\\.[0-9]{1,2})?|[0-9]+\\.[0-9]{1,2})")

    private val synonymGroups = listOf(
        setOf("car", "cars", "automobile", "automobiles", "vehicle", "vehicles"),
        setOf("motorcycle", "motorcycles", "motorbike", "motorbikes", "scooter", "scooters"),
        setOf("bicycle", "bicycles", "cycle", "cycles"),
        setOf("dog", "dogs", "puppy", "puppies"),
        setOf("cat", "cats", "kitten", "kittens"),
        setOf("person", "people", "human", "humans", "man", "men", "woman", "women"),
        setOf("invoice", "invoices", "bill", "bills", "receipt", "receipts"),
    )

    fun rank(query: String, files: List<IndexedPhoneFile>, limit: Int = 3): List<FileSearchHit> {
        val normalizedQuery = normalize(MultilingualQueryNormalizer.toEnglishConceptQuery(query))
        if (normalizedQuery.isBlank() || files.isEmpty()) return emptyList()
        val rawTerms = tokenRegex.findAll(normalizedQuery).map { it.value }.distinct().toList()
        val wantsImages = rawTerms.any { it in imageIntentWords }
        val wantsPdf = rawTerms.any { it == "pdf" }
        val terms = rawTerms.filterNot { it in stopWords || it in fileTypeWords }.distinct()
        val isGstQuery = rawTerms.any { it in setOf("gst", "gstin", "invoice", "invoices", "bill", "bills", "tax") } ||
            listOf("इनवॉइस", "बिल", "ಇನ್ವಾಯ್ಸ್", "ಬಿಲ್").any { normalizedQuery.contains(it) }
        val amountQuery = Regex("(?:₹|rs\\.?|inr)\\s*([0-9][0-9, ]*(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
            .find(query)?.groupValues?.getOrNull(1)?.let(::numericAmount)
            ?.takeIf { it >= BigDecimal(100) }
        val identifiers = (hyphenatedId.findAll(query) + mixedAlnumId.findAll(query)).map { it.value.uppercase(Locale.ROOT) }.distinct().toList()

        val eligibleFiles = files.filter { file ->
            if (IndexPolicy.isJunkPath(file.relativePath) || IndexPolicy.shouldSkipFile(file.filename)) return@filter false   // hidden/thumbnail/trash rows from older indexes
            (!wantsImages || isImageFile(file)) && (!wantsPdf || file.mimeType.equals("application/pdf", true) || file.filename.endsWith(".pdf", true))
        }
        if (eligibleFiles.isEmpty()) return emptyList()

        // "Show photos" is a browse request. Descriptive queries never fall back to random JPEGs.
        if (terms.isEmpty()) {
            if (!wantsImages && !wantsPdf) return emptyList()
            return eligibleFiles.sortedByDescending { it.lastModifiedMs ?: 0L }.take(limit.coerceIn(1, 10)).map { file ->
                FileSearchHit(
                    file_id = file.fileId, filename = file.filename, mime_type = file.mimeType,
                    size_bytes = file.sizeBytes, last_modified_ms = file.lastModifiedMs, score = 1,
                    reason = if (wantsImages) "Image collection browse; add a subject or OCR text to narrow results" else "PDF collection browse",
                    text_preview = buildPreview(file),
                )
            }
        }

        return eligibleFiles.mapNotNull { file ->
            val filename = normalize(file.filename)
            val path = normalize(file.relativePath)
            val body = normalize(file.extractedText)
            val labels = normalize(file.imageLabels)
            val filenameWords = words(filename)
            val pathWords = words(path)
            val bodyWords = words(body)
            val labelWords = words(labels)
            var score = 0
            val reasons = mutableListOf<String>()
            val matchedTerms = mutableSetOf<String>()

            val semanticPhrase = terms.joinToString(" ")
            if (semanticPhrase.length >= 3) {
                when {
                    filename.contains(semanticPhrase) -> { score += 30; reasons += "Filename phrase match" }
                    labels.contains(semanticPhrase) -> { score += 25; reasons += "Image-label phrase match" }
                    body.contains(semanticPhrase) -> { score += 25; reasons += "OCR/text phrase match" }
                    path.contains(semanticPhrase) -> { score += 14; reasons += "Folder-path phrase match" }
                }
            }

            for (term in terms) {
                val variants = variantsFor(term)
                val filenameHit = variants.any { it in filenameWords }
                val labelHit = variants.any { it in labelWords }
                val bodyHit = variants.any { it in bodyWords }
                val pathHit = variants.any { it in pathWords }
                val best = when {
                    filenameHit -> { reasons += "Filename term '$term'"; 18 }
                    labelHit -> { reasons += "Image label matches '$term'"; 16 }
                    bodyHit -> { reasons += "OCR/text matches '$term'"; 14 }
                    pathHit -> { reasons += "Folder path matches '$term'"; 6 }
                    else -> 0
                }
                if (best > 0) { score += best; matchedTerms += term }
            }

            if (matchedTerms.isNotEmpty()) {
                score += (matchedTerms.size * 20 / terms.size).coerceAtMost(20)
                if (matchedTerms.size == terms.size) { score += 12; reasons += "All search terms matched" }
            }

            // Stored signals are used when present; records created before they existed fall back to the text.
            val gstins = file.gstins.ifEmpty { gstinRegex.findAll(file.extractedText).map { it.value.uppercase(Locale.ROOT) }.distinct().take(5).toList() }.take(5)
            val invoiceNumbers = file.invoiceNumbers.ifEmpty { invoiceRegex.findAll(file.extractedText).map { it.groupValues[1].trim('.', ',', ';', ':') }.distinct().take(5).toList() }.take(5)
            val amounts = file.amounts.ifEmpty { amountRegex.findAll(file.extractedText).map { it.groupValues[1].trim() }.distinct().take(8).toList() }.take(8)
            var identifierHit = false
            if (identifiers.isNotEmpty()) {
                val haystack = (file.filename + " " + file.relativePath + " " + file.extractedText).uppercase(Locale.ROOT)
                if (identifiers.any { haystack.contains(it) }) { score += 40; reasons += "Exact identifier match"; identifierHit = true }
            }
            if (isGstQuery) {
                if (gstins.isNotEmpty()) { score += 25; reasons += "GSTIN pattern detected" }
                if (listOf("gst tax invoice", "tax invoice", "invoice", "cgst", "sgst", "igst", "gstin").any { it in body }) {
                    score += 12; reasons += "GST/invoice terms found in extracted text"
                }
                if (listOf("invoice", "tax", "gst", "bill", "receipt").any { it in filename }) {
                    score += 8; reasons += "Invoice-related filename"
                }
            }
            if (amountQuery != null) {
                val amountsInText = amounts.mapNotNull(::numericAmount)
                if (amountsInText.any { it.compareTo(amountQuery) == 0 }) { score += 28; reasons += "Amount matches query" }
            }

            // Descriptive searches require a whole-token hit rather than partial substring matches.
            if (matchedTerms.isEmpty() && amountQuery == null && gstins.isEmpty() && !identifierHit) return@mapNotNull null
            if (score < 12) return@mapNotNull null
            score = score.coerceIn(0, MAX_SCORE)
            FileSearchHit(
                file_id = file.fileId, filename = file.filename, mime_type = file.mimeType,
                size_bytes = file.sizeBytes, last_modified_ms = file.lastModifiedMs,
                score = score, reason = reasons.distinct().take(4).joinToString("; ").ifBlank { "Local whole-term match" },
                gstins = gstins, invoice_numbers = invoiceNumbers, amounts = amounts,
                text_preview = buildPreview(file),
            )
        }.sortedWith(compareByDescending<FileSearchHit> { it.score }.thenByDescending { it.last_modified_ms ?: 0L })
            .take(limit.coerceIn(1, 10))
    }

    private fun buildPreview(file: IndexedPhoneFile): String = buildString {
        if (file.imageLabels.isNotBlank()) append("Image labels: ").append(file.imageLabels.take(240)).append('\n')
        append(file.extractedText.take(280))
    }.trim().take(520)

    private fun isImageFile(file: IndexedPhoneFile): Boolean {
        if (file.mimeType.startsWith("image/", true)) return true
        return file.filename.substringAfterLast('.', "").lowercase(Locale.ROOT) in
            setOf("jpg", "jpeg", "png", "webp", "bmp", "tif", "tiff", "heic", "gif", "avif")
    }

    private fun words(value: String): Set<String> = tokenRegex.findAll(value).map { it.value }.toSet()

    private fun variantsFor(term: String): Set<String> {
        val variants = linkedSetOf(term)
        variants += MultilingualQueryNormalizer.expandToken(term)
        if (term.length > 4 && term.endsWith("ies")) variants += term.dropLast(3) + "y"
        else if (term.length > 3 && term.endsWith("s") && !term.endsWith("ss")) variants += term.dropLast(1)
        synonymGroups.firstOrNull { group -> term in group }?.let { variants += it }
        return variants
    }

    private fun numericAmount(raw: String): BigDecimal? = try {
        BigDecimal(raw.replace(",", "").replace(" ", "").trim())
    } catch (_: Exception) { null }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace('₹', ' ')
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}./-]+"), " ")
        .replace(Regex("\\s+"), " ").trim()
}
