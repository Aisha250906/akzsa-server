package com.nefeon.akzsa.network

import java.net.URI

object SettingsValidation {
    data class UrlCheck(val valid: Boolean, val normalized: String? = null, val message: String)

    fun validateBackendBaseUrl(input: String): UrlCheck {
        val value = input.trim().trimEnd('/')
        if (value.isBlank()) return UrlCheck(false, message = "Enter a backend base URL.")
        val uri = try { URI(value) } catch (_: Exception) {
            return UrlCheck(false, message = "That URL is not valid.")
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if (host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
            return UrlCheck(false, message = "Use a base URL without credentials, query parameters, or fragments.")
        }
        val localHosts = setOf("localhost", "127.0.0.1", "10.0.2.2", "::1")
        if (scheme == "https") return UrlCheck(true, value, "HTTPS URL looks valid.")
        if (scheme == "http" && host in localHosts) {
            return UrlCheck(true, value, "Local development URL looks valid.")
        }
        return UrlCheck(false, message = "HTTPS is required except for localhost or Android-emulator loopback during development.")
    }
}
