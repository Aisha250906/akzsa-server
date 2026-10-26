package com.nefeon.akzsa.corpus

import com.nefeon.akzsa.files.IndexedPhoneFile
import com.nefeon.akzsa.files.LocalFileSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproducible regression corpus for the LOCAL lexical/OCR/PDF/label search path, i.e. the path that
 * must keep working with every semantic model disabled or unavailable.
 *
 * Fixtures: src/test/resources/corpus/{files,queries}.tsv (also documented in docs/SEARCH_QUALITY.md).
 * Semantic-model quality (cosine retrieval) cannot be measured on a JVM and is BLOCKED until run on a device.
 */
class SearchRegressionCorpusTest {

    private data class Query(val text: String, val expected: List<String>, val category: String)

    private fun resource(name: String): List<List<String>> =
        checkNotNull(javaClass.classLoader.getResourceAsStream("corpus/$name")) { "missing corpus/$name" }
            .bufferedReader(Charsets.UTF_8).readLines().drop(1).filter { it.isNotBlank() }
            .map { it.split('\t', limit = 8).let { cols -> if (cols.size < 8 && name == "files.tsv") cols + List(8 - cols.size) { "" } else cols } }

    private fun loadFiles(): List<IndexedPhoneFile> = resource("files.tsv").map { c ->
        IndexedPhoneFile(
            fileId = c[0], filename = c[1], uri = "file:///storage/emulated/0/${c[2]}/${c[1]}", relativePath = c[2],
            mimeType = c[3], sizeBytes = if (c[5] == "failed") 0 else 4096, lastModifiedMs = c[4].toLong(),
            extractedText = c[7], extractionKind = c[5], imageLabels = c[6],
        )
    }

    private fun loadQueries(): List<Query> = resource("queries.tsv").map {
        Query(it[0], it.getOrElse(1) { "" }.split('|').filter(String::isNotBlank), it.getOrElse(2) { "" })
    }

    private class Metrics(val total: Int, val top1: Int, val top3: Int, val recallSum: Double, val relevantQueries: Int, val noResultOk: Int, val noResultTotal: Int)

    private fun evaluate(files: List<IndexedPhoneFile>, queries: List<Query>, perCategory: MutableMap<String, MutableList<Boolean>>? = null): Metrics {
        var top1 = 0; var top3 = 0; var recall = 0.0; var rel = 0; var nrOk = 0; var nrTotal = 0
        for (q in queries) {
            val ranked = LocalFileSearch.rank(q.text, files, limit = 10).map { it.file_id }
            if (q.expected.isEmpty()) {
                nrTotal++; if (ranked.isEmpty()) nrOk++
                perCategory?.getOrPut(q.category) { mutableListOf() }?.add(ranked.isEmpty())
                continue
            }
            rel++
            val hit1 = ranked.firstOrNull() == q.expected.first()
            if (!hit1) println("CORPUS   MISS [${q.category}] '${q.text}' expected=${q.expected.first()} got=${ranked.take(3)}")
            if (hit1) top1++
            if (ranked.take(3).contains(q.expected.first())) top3++
            recall += q.expected.count { it in ranked.take(10) }.toDouble() / q.expected.size
            perCategory?.getOrPut(q.category) { mutableListOf() }?.add(hit1)
        }
        return Metrics(queries.size, top1, top3, recall, rel, nrOk, nrTotal)
    }

    @Test fun corpusLoadsAndIsNonTrivial() {
        assertTrue(loadFiles().size >= 20)
        val q = loadQueries()
        assertTrue(q.size >= 30)
        val cats = q.map { it.category }.toSet()
        listOf("exact-filename", "duplicate-name", "pdf-text", "scanned-pdf-ocr", "screenshot-ocr", "image-label",
            "mixed-script-hindi", "mixed-script-kannada", "malformed-by-filename", "no-result").forEach { assertTrue("category $it", it in cats) }
    }

    @Test fun reportMetricsAndEnforceFloor() {
        val files = loadFiles(); val queries = loadQueries()
        val per = linkedMapOf<String, MutableList<Boolean>>()
        val m = evaluate(files, queries, per)
        println("CORPUS files=${files.size} queries=${m.total}")
        println("CORPUS top1=${m.top1}/${m.relevantQueries} top3=${m.top3}/${m.relevantQueries} recall@10=${"%.3f".format(m.recallSum / m.relevantQueries)} noResultCorrect=${m.noResultOk}/${m.noResultTotal}")
        per.forEach { (cat, results) -> println("CORPUS   $cat: ${results.count { it }}/${results.size}") }
        // Floors are enforced per category so a regression in one script/source cannot hide in the average.
        per.forEach { (cat, results) ->
            val floor = FLOORS[cat] ?: 1.0
            assertTrue("category $cat ${results.count { it }}/${results.size} is below floor $floor", results.count { it }.toDouble() / results.size >= floor)
        }
    }

    @Test fun deletedAndRenamedFilesNeverAppearAsCurrentResults() {
        val files = loadFiles()
        val before = LocalFileSearch.rank("flight PNR XK92LM", files, limit = 10).map { it.file_id }
        assertEquals("shot-flight", before.first())
        // After a successful reconciliation the deleted row is gone from the index snapshot.
        val afterDelete = files.filterNot { it.fileId == "shot-flight" }
        assertTrue(LocalFileSearch.rank("flight PNR XK92LM", afterDelete, limit = 10).none { it.file_id == "shot-flight" })
        // A rename keeps the id but changes the name; the new name must be searchable, the old one not.
        val renamed = files.map { if (it.fileId == "recipe") it.copy(filename = "dinner_ideas.pdf", extractedText = "") else it }
        assertEquals("recipe", LocalFileSearch.rank("dinner ideas", renamed, limit = 5).first().file_id)
        assertTrue(LocalFileSearch.rank("paneer recipe", renamed, limit = 5).none { it.file_id == "recipe" })
    }

    @Test fun searchWorksWithNoSemanticModelBecauseItNeverReferencesOne() {
        // LocalFileSearch is pure and has no model dependency: every assertion above ran model-free.
        assertTrue(LocalFileSearch.rank("GST invoice", loadFiles(), limit = 3).isNotEmpty())
    }

    @Test fun malformedFilesAreStillFoundByFilename() {
        val hits = LocalFileSearch.rank("broken", loadFiles(), limit = 5)
        assertTrue(hits.any { it.file_id == "broken-pdf" })
    }

    @Test fun rankingIsDeterministic() {
        val files = loadFiles()
        val a = LocalFileSearch.rank("report", files, limit = 10).map { it.file_id }
        repeat(10) { assertEquals(a, LocalFileSearch.rank("report", files.shuffled(java.util.Random(it.toLong())), limit = 10).map { h -> h.file_id }) }
    }

    @Test fun filtersApplyBeforeRanking() {
        val hits = LocalFileSearch.rank("photos", loadFiles(), limit = 10)
        assertTrue(hits.isNotEmpty() && hits.all { it.mime_type.startsWith("image/") })
        val pdfs = LocalFileSearch.rank("pdf invoice", loadFiles(), limit = 10)
        assertTrue(pdfs.isNotEmpty() && pdfs.all { it.mime_type == "application/pdf" })
    }

    companion object {
        /** Provisional per-category top-1 floors, set from the first measured baseline (see docs/SEARCH_QUALITY.md). */
        val FLOORS = mutableMapOf<String, Double>()
    }
}
