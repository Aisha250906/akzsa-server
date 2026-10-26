package com.nefeon.akzsa.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexPolicyAndMimeTest {
    // ---- walk order: documents before bulk media ----
    @Test fun documentsAndDownloadsAreVisitedBeforeCameraAndVideos() {
        val order = listOf("DCIM", "Movies", "Download", "Documents", "Pictures", "Music", "Notes", "Android")
            .sortedBy { IndexPolicy.childRank(it, true, false) }
        assertTrue(order.indexOf("Download") < order.indexOf("DCIM"))
        assertTrue(order.indexOf("Documents") < order.indexOf("DCIM"))
        assertTrue(order.indexOf("Pictures") < order.indexOf("DCIM"))
        assertEquals("Android", order.last())
    }

    @Test fun supportedDocumentFilesBeatOtherFilesAndMostFolders() {
        assertTrue(IndexPolicy.childRank("report.pdf", false, true) < IndexPolicy.childRank("random", true, false))
        assertTrue(IndexPolicy.childRank("clip.bin", false, false) > IndexPolicy.childRank("android", true, false))
    }

    @Test fun capIsBigEnoughForRealPhones() {
        assertTrue(IndexPolicy.MAX_FILES >= 20_000)
        assertTrue(IndexPolicy.MAX_VISITED > IndexPolicy.MAX_FILES)
    }

    // ---- junk is never indexed and never returned ----
    @Test fun hiddenAndJunkFoldersAndFilesAreSkipped() {
        listOf(".thumbnails", ".Trash", ".cache", "cache", "Thumbnails", "LOST.DIR", ".git", "node_modules").forEach { assertTrue(it, IndexPolicy.shouldSkipDirectory(it)) }
        listOf(".nomedia", ".trashed-1699999999-IMG_0001.jpg", "file.tmp", "movie.crdownload", "x.part", "notes.txt~", ".hidden.pdf").forEach { assertTrue(it, IndexPolicy.shouldSkipFile(it)) }
    }

    @Test fun normalFoldersAndFilesAreKept() {
        listOf("Download", "Documents", "WhatsApp", "My Cache Report", "2026 Taxes").forEach { assertFalse(it, IndexPolicy.shouldSkipDirectory(it)) }
        listOf("invoice.pdf", "IMG_0001.jpg", "résumé.docx", "ಇನ್ವಾಯ್ಸ್.pdf", "बिल.pdf").forEach { assertFalse(it, IndexPolicy.shouldSkipFile(it)) }
    }

    @Test fun junkPathDetectionCoversRowsIndexedByOlderVersions() {
        assertTrue(IndexPolicy.isJunkPath("DCIM/.thumbnails/1699.jpg"))
        assertTrue(IndexPolicy.isJunkPath("Pictures/.Trash/photo.jpg"))
        assertTrue(IndexPolicy.isJunkPath("Download/.trashed-1-invoice.pdf"))
        assertFalse(IndexPolicy.isJunkPath("Download/invoice.pdf"))
        assertFalse(IndexPolicy.isJunkPath("Documents/Taxes/2026.pdf"))
        assertFalse(IndexPolicy.isJunkPath(""))
        assertTrue(IndexPolicy.isJunkPath("DCIM\\.thumbnails\\x.jpg"))
    }

    // ---- file types (the cause of "Open goes to Photos") ----
    @Test fun unknownStoredTypeIsReplacedByTheExtensionType() {
        assertEquals("video/mp4", MimeResolver.resolve("clip.MP4", "application/octet-stream"))
        assertEquals("audio/mpeg", MimeResolver.resolve("song.mp3", ""))
        assertEquals("application/pdf", MimeResolver.resolve("a.pdf", "*/*"))
        assertEquals("application/zip", MimeResolver.resolve("a.zip", null))
    }

    @Test fun aSpecificStoredTypeIsKept() = assertEquals("image/png", MimeResolver.resolve("weird.name", "image/png"))

    @Test fun unknownExtensionStaysOctetStream() = assertEquals("application/octet-stream", MimeResolver.resolve("data.zzz", ""))

    @Test fun mediaDetectionDecidesWhichFilesNeedAMediaStoreUri() {
        assertTrue(MimeResolver.isMedia("image/jpeg")); assertTrue(MimeResolver.isMedia("video/mp4")); assertTrue(MimeResolver.isMedia("audio/mpeg"))
        assertFalse(MimeResolver.isMedia("application/pdf")); assertFalse(MimeResolver.isMedia("text/plain"))
    }

    @Test fun allCommonOfficeAndMediaTypesResolve() {
        listOf("doc","docx","xls","xlsx","ppt","pptx","odt","rtf","csv","txt","heic","webp","mkv","m4a","wav","apk").forEach {
            assertTrue(it, MimeResolver.fromName("x.$it") != "application/octet-stream")
        }
    }
}
