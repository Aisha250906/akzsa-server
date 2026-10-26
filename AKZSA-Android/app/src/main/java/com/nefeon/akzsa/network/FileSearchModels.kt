package com.nefeon.akzsa.network

import kotlinx.serialization.Serializable

/** UI result model for local, on-device search. It is never uploaded to the backend. */
@Serializable
data class FileSearchHit(
    val file_id: String,
    val filename: String,
    val mime_type: String = "application/octet-stream",
    val size_bytes: Long = 0L,
    val last_modified_ms: Long? = null,
    val score: Int = 0,
    val reason: String = "",
    val gstins: List<String> = emptyList(),
    val invoice_numbers: List<String> = emptyList(),
    val amounts: List<String> = emptyList(),
    val text_preview: String = "",
)
