package com.nefeon.akzsa.security

import android.content.Context
import java.util.UUID

/**
 * Random per-installation identifier used ONLY as a rate-limit key on the AKZSA server.
 * It is not derived from the device, account, advertising ID or any hardware identifier, it never leaves
 * the app except in the `X-AKZSA-Install-Id` request header, and clearing app data creates a new one.
 */
object InstallId {
    private const val PREFS = "akzsa_install"
    private const val KEY = "install_id"
    private val format = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    @Synchronized
    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY, null)
        if (existing != null && isValid(existing)) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, created).apply()
        return created
    }

    fun isValid(value: String): Boolean = format.matches(value)
}
