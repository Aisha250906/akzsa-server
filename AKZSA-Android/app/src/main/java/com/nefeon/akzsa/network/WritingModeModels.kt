package com.nefeon.akzsa.network

import kotlinx.serialization.Serializable

@Serializable
data class WritingModeRequest(
    val action: String,
    val text: String,
    val instruction: String? = null,
    val language: String? = null,
    val tone: String? = null,
    val output_language: String? = null,
)

@Serializable
data class WritingModeResponse(
    val text: String = "",
    val suggestions: List<String> = emptyList(),
    val explanation: String = "",
)
