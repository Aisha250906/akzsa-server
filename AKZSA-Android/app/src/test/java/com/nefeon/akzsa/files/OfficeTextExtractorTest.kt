package com.nefeon.akzsa.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfficeTextExtractorTest {
    private fun zip(entries: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            entries.forEach { (name, value) ->
                out.putNextEntry(ZipEntry(name))
                out.write(value.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test fun docxReadsTableTextWithoutLeakingRawXmlTags() {
        val bytes = zip(mapOf("word/document.xml" to "<w:document><w:body><w:p><w:r><w:t>Invoice</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t xml:space=\"preserve\">GST Number</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>"))
        val text = OfficeTextExtractor.extract(ByteArrayInputStream(bytes), "docx")
        assertTrue(text.contains("Invoice"))
        assertTrue(text.contains("GST Number"))
        assertFalse(text.contains("<w:tbl"))
        assertFalse(text.contains("<w:tab/"))
    }

    @Test fun xlsxReadsSharedStringsAndEveryWorksheet() {
        val bytes = zip(mapOf(
            "xl/sharedStrings.xml" to "<sst><si><t>Vehicle</t></si><si><r><t>Motor</t></r><r><t>cycle</t></r></si></sst>",
            "xl/worksheets/sheet1.xml" to "<worksheet><sheetData><row><c r=\"A1\" t=\"s\"><v>0</v></c></row></sheetData></worksheet>",
            "xl/worksheets/sheet12.xml" to "<worksheet><sheetData><row><c r=\"B8\" t=\"inlineStr\"><is><t>Two-wheeler</t></is></c></row></sheetData></worksheet>"
        ))
        val text = OfficeTextExtractor.extract(ByteArrayInputStream(bytes), "xlsx")
        assertTrue(text.contains("Vehicle"))
        assertTrue(text.contains("Two-wheeler"))
        assertTrue(text.contains("Motor"))
    }

    @Test fun pptxReadsSlidesBeyondTheFifth() {
        val entries = (1..8).associate { "ppt/slides/slide$it.xml" to "<p:sld><a:p><a:r><a:t>Slide $it</a:t></a:r></a:p></p:sld>" }
        val text = OfficeTextExtractor.extract(ByteArrayInputStream(zip(entries)), "pptx")
        assertTrue(text.contains("Slide 1"))
        assertTrue(text.contains("Slide 8"))
        assertFalse(text.contains("<a:p"))
    }
}
