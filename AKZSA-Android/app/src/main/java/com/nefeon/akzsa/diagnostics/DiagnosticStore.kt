package com.nefeon.akzsa.diagnostics

import android.content.Context

class DiagnosticStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("akzsa_diagnostics", Context.MODE_PRIVATE)

    fun recordBackendSuccess(status: Int, latencyMs: Long, authentication: String? = null) {
        val editor = prefs.edit()
            .putLong("last_success_epoch_ms", System.currentTimeMillis())
            .putInt("last_status", status)
            .putLong("last_latency_ms", latencyMs)
        if (authentication != null) editor.putString("auth_status", authentication)
        editor.apply()
    }

    fun recordBackendFailure(status: Int?, latencyMs: Long?, authentication: String? = null) {
        val editor = prefs.edit()
            .putLong("last_attempt_epoch_ms", System.currentTimeMillis())
            .putString("last_failure", status?.toString() ?: "network")
        if (authentication != null) editor.putString("auth_status", authentication)
        else if (status == 401 || status == 403) editor.putString("auth_status", "failed")
        if (status != null) editor.putInt("last_status", status)
        if (latencyMs != null) editor.putLong("last_latency_ms", latencyMs)
        editor.apply()
    }

    fun snapshot(): Snapshot = Snapshot(
        lastSuccessEpochMs = prefs.getLong("last_success_epoch_ms", 0L),
        lastAttemptEpochMs = prefs.getLong("last_attempt_epoch_ms", 0L),
        lastStatus = if (prefs.contains("last_status")) prefs.getInt("last_status", 0) else null,
        lastLatencyMs = if (prefs.contains("last_latency_ms")) prefs.getLong("last_latency_ms", 0L) else null,
        authStatus = prefs.getString("auth_status", "not verified") ?: "not verified",
    )

    data class Snapshot(
        val lastSuccessEpochMs: Long,
        val lastAttemptEpochMs: Long,
        val lastStatus: Int?,
        val lastLatencyMs: Long?,
        val authStatus: String,
    )
}
