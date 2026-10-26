package com.nefeon.akzsa.files

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/** Bounded text extraction for OOXML/ODT files without loading a heavyweight Office runtime. */
object OfficeTextExtractor {
    private const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
    private val wordText = Regex("(?s)<w:t(?:\\s[^>]*)?>(.*?)</w:t>")
    private val drawingText = Regex("(?s)<a:t(?:\\s[^>]*)?>(.*?)</a:t>")
    private val genericText = Regex("(?s)<t(?:\\s[^>]*)?>(.*?)</t>")
    private val odtParagraph = Regex("(?s)<text:p(?:\\s[^>]*)?>(.*?)</text:p>")
    private val cellRegex = Regex("(?is)<c\\b([^>]*)>(.*?)</c>")
    private val valueRegex = Regex("(?s)<v(?:\\s[^>]*)?>(.*?)</v>")

    fun extract(input: InputStream, extension: String, maxChars: Int = 200_000): String {
        require(maxChars > 0) { "maxChars must be positive" }
        val type = extension.substringAfterLast('.', extension).lowercase(Locale.ROOT)
        if (type !in setOf("docx", "xlsx", "pptx", "odt")) return ""
        val xmlEntries = LinkedHashMap<String, String>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && xmlEntries.values.sumOf { it.length } < maxChars * 3) {
                if (!entry.isDirectory && shouldRead(type, entry.name.lowercase(Locale.ROOT))) {
                    val bytes = readBounded(zip, MAX_ENTRY_BYTES)
                    xmlEntries[entry.name.lowercase(Locale.ROOT)] = bytes.toString(Charsets.UTF_8)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val out = StringBuilder()
        when (type) {
            "docx" -> xmlEntries.filterKeys { it == "word/document.xml" || it.startsWith("word/header") || it.startsWith("word/footer") }
                .toSortedMap().values.forEach { appendMatches(out, wordText, it, maxChars) }
            "xlsx" -> {
                val shared = ArrayList<String>()
                xmlEntries["xl/sharedstrings.xml"]?.let { xml ->
                    val rich = Regex("(?s)<si(?:\\s[^>]*)?>(.*?)</si>")
                    for (item in rich.findAll(xml)) {
                        shared += genericText.findAll(item.groupValues[1]).joinToString(" ") { unescape(it.groupValues[1]) }
                        if (shared.size > 250_000) break
                    }
                }
                out.append(shared.joinToString(" "))
                xmlEntries.filterKeys { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }.toSortedMap().values.forEach { xml ->
                    for (cell in cellRegex.findAll(xml)) {
                        val attributes = cell.groupValues[1]
                        val body = cell.groupValues[2]
                        val value = valueRegex.find(body)?.groupValues?.getOrNull(1)
                        if (attributes.contains(Regex("(?:^|\\s)t=[\"']s[\"']"))) {
                            val index = value?.trim()?.toIntOrNull()
                            if (index != null && index in shared.indices) append(out, shared[index], maxChars)
                        } else if (attributes.contains(Regex("(?:^|\\s)t=[\"']inlineStr[\"']"))) {
                            appendMatches(out, genericText, body, maxChars)
                        } else if (value != null) {
                            append(out, unescape(value), maxChars)
                        }
                        if (out.length >= maxChars) break
                    }
                    // Inline/shared strings are covered above; also pick up rare sheet-level plain text nodes.
                    if (out.length < maxChars) appendMatches(out, genericText, xml, maxChars)
                }
            }
            "pptx" -> xmlEntries.filterKeys { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }.toSortedMap().values
                .forEach { appendMatches(out, drawingText, it, maxChars) }
            "odt" -> xmlEntries["content.xml"]?.let { appendMatches(out, odtParagraph, it, maxChars) }
        }
        return out.toString().replace(Regex("\\s+"), " ").trim().take(maxChars)
    }

    private fun shouldRead(type: String, name: String): Boolean = when (type) {
        "docx" -> name == "word/document.xml" || name.startsWith("word/header") || name.startsWith("word/footer")
        "xlsx" -> name == "xl/sharedstrings.xml" || name.matches(Regex("xl/worksheets/sheet\\d+\\.xml"))
        "pptx" -> name.matches(Regex("ppt/slides/slide\\d+\\.xml"))
        "odt" -> name == "content.xml"
        else -> false
    }

    private fun appendMatches(out: StringBuilder, regex: Regex, xml: String, maxChars: Int) {
        for (match in regex.findAll(xml)) {
            append(out, unescape(match.groupValues[1]), maxChars)
            if (out.length >= maxChars) return
        }
    }

    private fun append(out: StringBuilder, text: String, maxChars: Int) {
        if (out.length >= maxChars) return
        out.append(text).append(' ')
        if (out.length > maxChars) out.setLength(maxChars)
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val buffer = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit - total))
            if (n <= 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun unescape(value: String): String = value
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
}
