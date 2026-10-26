package com.nefeon.akzsa.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFileSearchTest {
    private fun file(
        id: String,
        name: String,
        text: String,
        modified: Long = 1_700_000_000_000L,
    ) = IndexedPhoneFile(
        fileId = id,
        filename = name,
        uri = "file:///storage/emulated/0/Download/$name",
        relativePath = "Download",
        mimeType = when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg", "png", "webp" -> "image/jpeg"
            else -> "text/plain"
        },
        sizeBytes = 2048,
        lastModifiedMs = modified,
        extractedText = text,
        extractionKind = "unit-test",
    )

    @Test
    fun gstInvoiceRanksAheadOfUnrelatedDocument() {
        val invoice = file(
            "invoice-1", "Tax_Invoice_October.pdf",
            "GST TAX INVOICE GSTIN: 29ABCDE1234F1Z5 Invoice No INV-2026-17 " +
                "Taxable Value Rs 25,000.00 CGST 2,250.00 SGST 2,250.00 Total 29,500.00"
        )
        val note = file("note-1", "meeting-notes.txt", "Meeting about project milestones and planning")

        val results = LocalFileSearch.rank("Find my GST invoice for ₹25,000", listOf(note, invoice), limit = 3)

        assertTrue(results.isNotEmpty())
        assertEquals("invoice-1", results.first().file_id)
        // Unrelated documents are intentionally omitted from results, so a single
        // valid match is expected to be both first and last. Verify relevance directly.
        assertTrue(results.none { it.file_id == "note-1" })
        assertTrue(results.first().score > 0)
        assertTrue(results.first().gstins.contains("29ABCDE1234F1Z5"))
    }

    @Test
    fun queryReturnsAtMostThreeMatchesAndDoesNotNeedNetwork() {
        val files = (1..10).map { i ->
            file("$i", "GST_Invoice_$i.pdf", "GST TAX INVOICE Invoice No INV-$i GSTIN 29ABCDE1234F1Z5")
        }
        assertEquals(3, LocalFileSearch.rank("find GST invoices", files, limit = 3).size)
    }

    @Test
    fun unrelatedQueryDoesNotReturnEveryFile() {
        val files = listOf(
            file("invoice", "gst-invoice.pdf", "GST tax invoice CGST SGST"),
            file("photo", "holiday.png", "beach family photo")
        )
        val results = LocalFileSearch.rank("find my gst invoice", files, limit = 3)
        assertTrue(results.any { it.file_id == "invoice" })
        assertTrue(results.none { it.file_id == "photo" })
    }
    @Test
    fun imageLabelsCanFindPhotosWithoutReadableOcrText() {
        val carPhoto = file("car-photo", "IMG_2026.jpg", "").copy(imageLabels = "car vehicle road automobile")
        val catPhoto = file("cat-photo", "IMG_2025.jpg", "").copy(imageLabels = "cat pet animal")
        val results = LocalFileSearch.rank("find photo of a car", listOf(catPhoto, carPhoto), limit = 3)
        assertTrue(results.isNotEmpty())
        assertEquals("car-photo", results.first().file_id)
    }

    @Test
    fun gstQueryBoostsPdfAndSearchesExtractedPdfText() {
        val pdf = file("deep-pdf", "scan-export.pdf", "Supplier ABC GST TAX INVOICE GSTIN 29ABCDE1234F1Z5 Total payable 14750.00 " + "terms ".repeat(100))
        val unrelated = file("other", "family-photo.jpg", "holiday beach")
        val results = LocalFileSearch.rank("find GST invoice", listOf(unrelated, pdf), limit = 3)
        assertTrue(results.isNotEmpty())
        assertEquals("deep-pdf", results.first().file_id)
    }


    @Test
    fun jpegOcrTextCanFindSpecificPhotoAndDoesNotReturnUnrelatedJpegs() {
        val target = file(
            "target-jpeg", "IMG_4012.jpeg",
            "GST TAX INVOICE Invoice No INV-1042 Supplier Nefeon Total 29500.00"
        )
        val unrelated = file("unrelated-jpeg", "IMG_4013.jpeg", "family picnic beach children smiling")
        val pdf = file("pdf-copy", "Invoice-1042.pdf", "GST TAX INVOICE Invoice No INV-1042")
        val results = LocalFileSearch.rank("find photo of invoice number INV-1042", listOf(unrelated, pdf, target), 10)
        assertEquals(listOf("target-jpeg"), results.map { it.file_id })
        assertTrue(results.first().text_preview.contains("Invoice No", ignoreCase = true))
    }

    @Test
    fun imageQueryDoesNotMatchSubstringInsideUnrelatedWords() {
        val vacation = file("vacation", "vacation.jpg", "beautiful vacation picture")
        val cat = file("cat", "IMG_2100.jpg", "").copy(imageLabels = "cat kitten animal pet")
        val results = LocalFileSearch.rank("find photo of car", listOf(vacation, cat), 10)
        assertTrue(results.isEmpty())
    }

    @Test
    fun explicitImageIntentExcludesPdfResults() {
        val jpg = file("car", "car.jpg", "").copy(imageLabels = "car vehicle road")
        val pdf = file("car-pdf", "car-details.pdf", "car vehicle road")
        val results = LocalFileSearch.rank("find photo of a car", listOf(pdf, jpg), 10)
        assertEquals(listOf("car"), results.map { it.file_id })
    }


    @Test
    fun hinglishHindiAndKannadaQueriesMatchEnglishImageLabels() {
        val car = file("car", "IMG_9001.jpg", "").copy(imageLabels = "car vehicle road automobile")
        val dog = file("dog", "IMG_9002.jpg", "").copy(imageLabels = "dog animal pet")
        assertEquals("car", LocalFileSearch.rank("meri gaadi ki photo dikhao", listOf(dog, car), 10).first().file_id)
        assertEquals("car", LocalFileSearch.rank("गाड़ी की फोटो दिखाओ", listOf(dog, car), 10).first().file_id)
        assertEquals("car", LocalFileSearch.rank("ನನ್ನ ಕಾರು ಚಿತ್ರ", listOf(dog, car), 10).first().file_id)
        assertEquals("dog", LocalFileSearch.rank("कुत्ता फोटो", listOf(car, dog), 10).first().file_id)
    }

    @Test
    fun motorcycleQueriesAndAdditionalIndicScriptsMatchEnglishLabels() {
        val motorcycle = file("motorcycle", "IMG_9003.jpg", "").copy(imageLabels = "motorcycle vehicle road two wheeler")
        val car = file("car", "IMG_9004.jpg", "").copy(imageLabels = "car automobile vehicle road")
        assertEquals("motorcycle", LocalFileSearch.rank("meri bike ki photo dikhao", listOf(car, motorcycle), 10).first().file_id)
        assertEquals("car", LocalFileSearch.rank("என் கார் படம் காட்டு", listOf(motorcycle, car), 10).first().file_id)
        assertEquals("car", LocalFileSearch.rank("నా కారు ఫోటో చూపించు", listOf(motorcycle, car), 10).first().file_id)
        assertEquals("car", LocalFileSearch.rank("আমার গাড়ি ছবি দেখাও", listOf(motorcycle, car), 10).first().file_id)
        assertEquals("car", LocalFileSearch.rank("میری گاڑی کی تصویر دکھاؤ", listOf(motorcycle, car), 10).first().file_id)
        assertEquals("motorcycle", LocalFileSearch.rank("show photo of a motor cycle", listOf(car, motorcycle), 10).first().file_id)
        val airplane = car.copy(fileId = "plane", imageLabels = "airplane aircraft flying vehicle")
        assertEquals("plane", LocalFileSearch.rank("मेरी हवाई जहाज की फोटो", listOf(motorcycle, airplane), 10).first().file_id)
    }

}
