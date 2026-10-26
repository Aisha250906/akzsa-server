package com.nefeon.akzsa.diagnostics

/**
 * Removes private values from any text that may be stored in diagnostics or shared by the user.
 * Redaction is applied by default; there is deliberately no "include everything" switch.
 */
object Redactor {
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val apiKey = Regex("\\b(?:gsk|sk|pk|ghp|xox[bap])[-_][A-Za-z0-9_-]{12,}\\b")
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val contentUri = Regex("content://[^\\s\"']+")
    private val absolutePath = Regex("(?:/storage|/sdcard|/data|/mnt)/[^\\s\"']+")
    private val longDigits = Regex("\\b\\d{9,}\\b")
    private val gstin = Regex("(?i)\\b\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]\\b")

    fun redact(text: String): String {
        var out = text
        out = bearer.replace(out, "Bearer [redacted]")
        out = apiKey.replace(out, "[redacted-key]")
        out = email.replace(out, "[redacted-email]")
        out = contentUri.replace(out, "[redacted-uri]")
        out = absolutePath.replace(out, "[redacted-path]")
        out = gstin.replace(out, "[redacted-id]")
        out = longDigits.replace(out, "[redacted-number]")
        return out
    }

    /** Safe diagnostic line: code and exception class only, never the message (messages can echo user data). */
    fun errorCode(error: Throwable, area: String): String = "$area:${error.javaClass.simpleName}"
}
