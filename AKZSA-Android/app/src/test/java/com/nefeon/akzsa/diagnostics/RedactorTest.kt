package com.nefeon.akzsa.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    @Test fun removesBearerTokensKeysEmailsPathsUrisAndIds() {
        val raw = "auth Bearer abcdefghijklmnop1234 key gsk_abcdefghijklmnopqrstu mail a.b@example.com " +
            "path /storage/emulated/0/Download/invoice.pdf uri content://media/external/file/12 gst 29ABCDE1234F1Z5 phone 9876543210"
        val out = Redactor.redact(raw)
        listOf("abcdefghijklmnop1234", "gsk_abc", "a.b@example.com", "/storage/emulated", "content://media", "29ABCDE1234F1Z5", "9876543210")
            .forEach { assertFalse("leaked $it in: $out", out.contains(it)) }
        assertTrue(out.contains("[redacted"))
    }

    @Test fun leavesOrdinaryDiagnosticsReadable() {
        assertEquals("backend status 503 latency 412ms", Redactor.redact("backend status 503 latency 412ms"))
    }

    @Test fun errorCodeNeverIncludesTheExceptionMessage() {
        val code = Redactor.errorCode(IllegalStateException("secret user text 1234567890"), "index")
        assertEquals("index:IllegalStateException", code)
    }
}
