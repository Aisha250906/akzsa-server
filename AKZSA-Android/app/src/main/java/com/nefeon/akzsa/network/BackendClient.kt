package com.nefeon.akzsa.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.Locale

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T, val statusCode: Int, val latencyMs: Long) : ApiResult<T>
    data class Failure(val message: String, val statusCode: Int? = null, val latencyMs: Long? = null) : ApiResult<Nothing>
}

/** Uses only the backend routes confirmed in the supplied FastAPI source: /health and /v1/commands/interpret, and opted-in /v1/voice/transcribe endpoints. */
object BackendClient {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true; encodeDefaults = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val audioMediaType = "audio/mp4".toMediaType()
    private val wavMediaType = "audio/wav".toMediaType()
    private val audioHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(75, TimeUnit.SECONDS)
        .callTimeout(95, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun interpret(
        baseUrl: String,
        bearerToken: String?,
        requestPayload: CommandRequest,
        installId: String? = null,
    ): ApiResult<CommandResponse> = withContext(Dispatchers.IO) {
        val urlCheck = SettingsValidation.validateBackendBaseUrl(baseUrl)
        if (!urlCheck.valid || urlCheck.normalized == null) return@withContext ApiResult.Failure(urlCheck.message)
        val payload = try { json.encodeToString(CommandRequest.serializer(), requestPayload) }
        catch (_: SerializationException) { return@withContext ApiResult.Failure("The command request could not be serialized.") }
        val builder = Request.Builder()
            .url("${urlCheck.normalized}/v1/commands/interpret")
            .post(payload.toRequestBody(mediaType))
            .header("Accept", "application/json")
        if (!bearerToken.isNullOrBlank()) builder.header("Authorization", "Bearer ${bearerToken.trim()}")
        if (!installId.isNullOrBlank()) builder.header("X-AKZSA-Install-Id", installId)
        val start = System.nanoTime()
        try {
            http.newCall(builder.build()).execute().use { response ->
                val elapsed = (System.nanoTime() - start) / 1_000_000
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext ApiResult.Failure(BackendErrorMapper.messageFor(response.code), response.code, elapsed)
                }
                val parsed = try { json.decodeFromString(CommandResponse.serializer(), body) }
                catch (_: Exception) { return@withContext ApiResult.Failure("The backend returned invalid or unexpected JSON.", response.code, elapsed) }
                if (parsed.server_executed_action) {
                    return@withContext ApiResult.Failure("The backend response claimed it executed an action. AKZSA rejected that response for safety.", response.code, elapsed)
                }
                ApiResult.Success(parsed, response.code, elapsed)
            }
        } catch (_: java.net.SocketTimeoutException) {
            ApiResult.Failure("The backend timed out. Render may be starting or the AI provider may be slow.", latencyMs = (System.nanoTime() - start) / 1_000_000)
        } catch (_: IOException) {
            ApiResult.Failure("Could not reach the backend. Check internet access and the configured URL.", latencyMs = (System.nanoTime() - start) / 1_000_000)
        } catch (cancelled: CancellationException) {
            throw cancelled   // never swallow coroutine cancellation
        } catch (_: Exception) {
            ApiResult.Failure("The backend request failed unexpectedly. Check the URL and try again.", latencyMs = (System.nanoTime() - start) / 1_000_000)
        }
    }

    /** Uploads a user-opted-in temporary M4A recording to the documented AKZSA audio endpoint. */
    suspend fun transcribeAudio(
        baseUrl: String,
        bearerToken: String?,
        audioFile: File,
        languageTag: String,
        vocabulary: String,
        polish: Boolean,
        installId: String? = null,
    ): ApiResult<VoiceTranscriptionResponse> = withContext(Dispatchers.IO) {
        val urlCheck = SettingsValidation.validateBackendBaseUrl(baseUrl)
        if (!urlCheck.valid || urlCheck.normalized == null) return@withContext ApiResult.Failure(urlCheck.message)
        if (!audioFile.exists() || !audioFile.isFile || audioFile.length() < 512L) {
            return@withContext ApiResult.Failure("The audio recording is empty or unavailable.")
        }
        if (audioFile.length() > 20L * 1024L * 1024L) {
            return@withContext ApiResult.Failure("This recording is too large. Dictate a shorter session and try again.")
        }
        val language = cloudVoiceLanguageCode(languageTag)
        val dictionaryBytes = vocabulary.take(1200).toByteArray(StandardCharsets.UTF_8)
        val dictionaryHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(dictionaryBytes)
        val start = System.nanoTime()
        try {
            val builder = Request.Builder()
                .url("${urlCheck.normalized}/v1/voice/transcribe")
                .post(audioFile.asRequestBody(audioMediaType))
                .header("Accept", "application/json")
                .header("X-AKZSA-Language", language)
                .header("X-AKZSA-Polish", polish.toString())
                .header("X-AKZSA-Vocabulary-B64", dictionaryHeader)
            if (!bearerToken.isNullOrBlank()) builder.header("Authorization", "Bearer ${bearerToken.trim()}")
            if (!installId.isNullOrBlank()) builder.header("X-AKZSA-Install-Id", installId)
            audioHttp.newCall(builder.build()).execute().use { response ->
                val elapsed = (System.nanoTime() - start) / 1_000_000
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext ApiResult.Failure(BackendErrorMapper.messageFor(response.code), response.code, elapsed)
                }
                val parsed = try { json.decodeFromString(VoiceTranscriptionResponse.serializer(), body) }
                catch (_: Exception) { return@withContext ApiResult.Failure("The voice endpoint returned unexpected JSON.", response.code, elapsed) }
                if (parsed.text.isBlank()) return@withContext ApiResult.Failure("The voice service returned an empty transcript.", response.code, elapsed)
                ApiResult.Success(parsed, response.code, elapsed)
            }
        } catch (_: java.net.SocketTimeoutException) {
            ApiResult.Failure("Cloud transcription timed out. Try a shorter recording or retry when the server is ready.", latencyMs = (System.nanoTime() - start) / 1_000_000)
        } catch (_: IOException) {
            ApiResult.Failure("Could not upload the recording. Check internet access and the backend URL.", latencyMs = (System.nanoTime() - start) / 1_000_000)
        } catch (cancelled: CancellationException) {
            throw cancelled   // never swallow coroutine cancellation
        } catch (_: Exception) {
            ApiResult.Failure("Cloud transcription failed unexpectedly. Your recording was not inserted.", latencyMs = (System.nanoTime() - start) / 1_000_000)
        }
    }

    /**
     * Live dictation: uploads ONE finished phrase (16 kHz mono WAV, a few seconds) and returns its text.
     * [contextTail] is the end of what was already dictated in this session; it helps the recogniser keep
     * spelling, script and style consistent from phrase to phrase. Never polished (speed, and no rewording).
     */
    suspend fun transcribeSegment(
        baseUrl: String,
        bearerToken: String?,
        wav: ByteArray,
        languageTag: String,
        vocabulary: String,
        contextTail: String,
        installId: String? = null,
    ): ApiResult<VoiceTranscriptionResponse> = withContext(Dispatchers.IO) {
        val urlCheck = SettingsValidation.validateBackendBaseUrl(baseUrl)
        if (!urlCheck.valid || urlCheck.normalized == null) return@withContext ApiResult.Failure(urlCheck.message)
        if (wav.size < 512) return@withContext ApiResult.Failure("The phrase was too short.")
        if (wav.size > 20 * 1024 * 1024) return@withContext ApiResult.Failure("This phrase is too large.")
        val language = cloudVoiceLanguageCode(languageTag)
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val dictionaryHeader = encoder.encodeToString(vocabulary.take(900).toByteArray(StandardCharsets.UTF_8))
        val contextHeader = encoder.encodeToString(contextTail.take(200).toByteArray(StandardCharsets.UTF_8))
        val start = System.nanoTime()
        try {
            val builder = Request.Builder()
                .url("${urlCheck.normalized}/v1/voice/transcribe")
                .post(wav.toRequestBody(wavMediaType))
                .header("Accept", "application/json")
                .header("X-AKZSA-Language", language)
                .header("X-AKZSA-Polish", "false")
                .header("X-AKZSA-Vocabulary-B64", dictionaryHeader)
            if (contextTail.isNotBlank()) builder.header("X-AKZSA-Context-B64", contextHeader)
            if (!bearerToken.isNullOrBlank()) builder.header("Authorization", "Bearer ${bearerToken.trim()}")
            if (!installId.isNullOrBlank()) builder.header("X-AKZSA-Install-Id", installId)
            audioHttp.newCall(builder.build()).execute().use { response ->
                val elapsed = (System.nanoTime() - start) / 1_000_000
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext ApiResult.Failure(BackendErrorMapper.messageFor(response.code), response.code, elapsed)
                }
                val parsed = try { json.decodeFromString(VoiceTranscriptionResponse.serializer(), body) }
                catch (_: Exception) { return@withContext ApiResult.Failure("The voice endpoint returned unexpected JSON.", response.code, elapsed) }
                if (parsed.text.isBlank()) return@withContext ApiResult.Failure("The voice service returned an empty transcript.", response.code, elapsed)
                ApiResult.Success(parsed, response.code, elapsed)
            }
        } catch (_: java.net.SocketTimeoutException) {
            ApiResult.Failure("Cloud transcription timed out.", latencyMs = (System.nanoTime() - start) / 1_000_000)
        } catch (_: IOException) {
            ApiResult.Failure("Could not reach the server. Check internet access.", latencyMs = (System.nanoTime() - start) / 1_000_000)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ApiResult.Failure("Cloud transcription failed unexpectedly.", latencyMs = (System.nanoTime() - start) / 1_000_000)
        }
    }

    /** File AI search is intentionally local-only; this client has no file-index upload method. */
    suspend fun checkHealth(baseUrl: String): ApiResult<HealthResponse> = withContext(Dispatchers.IO) {
        val urlCheck = SettingsValidation.validateBackendBaseUrl(baseUrl)
        if (!urlCheck.valid || urlCheck.normalized == null) return@withContext ApiResult.Failure(urlCheck.message)
        val start = System.nanoTime()
        val request = Request.Builder().url("${urlCheck.normalized}/health").get().header("Accept", "application/json").build()
        try {
            http.newCall(request).execute().use { response ->
                val elapsed = (System.nanoTime() - start) / 1_000_000
                if (!response.isSuccessful) return@withContext ApiResult.Failure(BackendErrorMapper.messageFor(response.code), response.code, elapsed)
                val body = response.body?.string().orEmpty()
                val health = try { json.decodeFromString(HealthResponse.serializer(), body) }
                catch (_: Exception) { return@withContext ApiResult.Failure("The /health endpoint returned unexpected JSON.", response.code, elapsed) }
                ApiResult.Success(health, response.code, elapsed)
            }
        } catch (_: java.net.SocketTimeoutException) {
            ApiResult.Failure("The backend health request timed out.", latencyMs = (System.nanoTime() - start) / 1_000_000)
        } catch (_: IOException) {
            ApiResult.Failure("Could not reach the backend health endpoint.", latencyMs = (System.nanoTime() - start) / 1_000_000)
        } catch (cancelled: CancellationException) {
            throw cancelled   // never swallow coroutine cancellation
        } catch (_: Exception) {
            ApiResult.Failure("The backend health check failed.", latencyMs = (System.nanoTime() - start) / 1_000_000)
        }
    }
}

/** Map the app's user-facing language preference to the server's transcription contract. */
internal fun cloudVoiceLanguageCode(languageTag: String): String = when (languageTag.trim().lowercase(Locale.ROOT)) {
    "hi", "hi-in" -> "hi"
    "kn", "kn-in", "kn-in-kannada" -> "kn"
    "hinglish", "hinglish-latn", "hi-latn" -> "hinglish"
    "en", "en-in", "en-us", "en-gb" -> "en"
    else -> "en"
}

object BackendErrorMapper {
    fun messageFor(status: Int): String = when (status) {
        400 -> "The backend rejected the request (400). Check the command and fields."
        422 -> "The backend validation rejected the request (422). Check required fields and size limits."
        401 -> "Authentication failed (401). Check the saved bearer token."
        403 -> "The backend denied this request (403). Check access configuration."
        404 -> "The configured backend or API route was not found (404). Check the base URL."
        413 -> "The recording is too large. Dictate a shorter session."
        415 -> "The voice endpoint did not accept this audio format."
        429 -> "The backend or AI provider is rate-limiting requests (429). Try again later."
        500 -> "The backend encountered an internal error (500). Check Render logs."
        502 -> "The AI provider returned an error or invalid response (502)."
        503 -> "The backend or AI provider is not ready (503). Check Render and Groq configuration."
        504 -> "The AI request timed out (504). Try again when the service is responsive."
        in 300..399 -> "The backend redirected this request. Redirects are blocked to protect credentials."
        else -> "The backend request failed with HTTP $status."
    }

    suspend fun generateWriting(
        baseUrl: String,
        bearerToken: String?,
        requestPayload: WritingModeRequest,
        installId: String? = null,
    ): ApiResult<WritingModeResponse> = withContext(Dispatchers.IO) {
        val urlCheck = SettingsValidation.validateBackendBaseUrl(baseUrl)
        if (!urlCheck.valid || urlCheck.normalized == null) return@withContext ApiResult.Failure(urlCheck.message)
        val payload = try { json.encodeToString(WritingModeRequest.serializer(), requestPayload) }
        catch (_: SerializationException) { return@withContext ApiResult.Failure("The writing request could not be serialized.") }
        val builder = Request.Builder()
            .url("${urlCheck.normalized}/v1/writing/generate")
            .post(payload.toRequestBody(mediaType))
            .header("Accept", "application/json")
        if (!bearerToken.isNullOrBlank()) builder.header("Authorization", "Bearer ${bearerToken.trim()}")
        if (!installId.isNullOrBlank()) builder.header("X-AKZSA-Install-Id", installId)
        val start = System.nanoTime()
        try {
            http.newCall(builder.build()).execute().use { response ->
                val elapsed = (System.nanoTime() - start) / 1_000_000
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = try {
                        val parsed = json.parseToJsonElement(body)
                        parsed.jsonObject["detail"]?.toString()?.trim('"') ?: BackendErrorMapper.messageFor(response.code)
                    } catch (_: Exception) { BackendErrorMapper.messageFor(response.code) }
                    return@withContext ApiResult.Failure(message, response.code, elapsed)
                }
                val parsed = try { json.decodeFromString(WritingModeResponse.serializer(), body) }
                catch (_: Exception) { return@withContext ApiResult.Failure("The backend returned an invalid writing response.", response.code, elapsed) }
                if (requestPayload.action == "reply_suggestions" && parsed.suggestions.size != 3) {
                    return@withContext ApiResult.Failure("The backend did not return exactly three reply suggestions.", response.code, elapsed)
                }
                if (requestPayload.action != "reply_suggestions" && parsed.text.isBlank()) {
                    return@withContext ApiResult.Failure("The backend returned empty text. Your original text is unchanged.", response.code, elapsed)
                }
                ApiResult.Success(parsed, response.code, elapsed)
            }
        } catch (_: java.net.SocketTimeoutException) {
            ApiResult.Failure("Writing request timed out. Your original text is unchanged.", latencyMs = (System.nanoTime() - start) / 1_000_000)
        } catch (_: IOException) {
            ApiResult.Failure("Could not reach the backend. Check internet access and the configured URL.", latencyMs = (System.nanoTime() - start) / 1_000_000)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ApiResult.Failure("The writing request failed unexpectedly. Your original text is unchanged.", latencyMs = (System.nanoTime() - start) / 1_000_000)
        }
    }

}
