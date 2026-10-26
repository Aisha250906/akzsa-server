package com.nefeon.akzsa.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.akzsaDataStore by preferencesDataStore(name = "akzsa_settings")

data class AppSettings(
    val backendBaseUrl: String = "https://akzsa-server.onrender.com",
    val recognitionLanguage: String = "en-IN",
    /** Explicit opt-in: recorded audio is uploaded to the AKZSA server and forwarded to Groq Whisper. */
    val cloudDictationEnabled: Boolean = false,
    val dictionaryTerms: String = "AKZSA, NEFEON",
    /** Live cloud dictation: phrases are sent and inserted while you keep talking. Off = record all, upload once. */
    val streamingDictation: Boolean = true,
    /** Rumble filter, the phone's noise suppressor and a quiet-speech boost for cloud dictation. */
    val noiseReduction: Boolean = true,
    /** "comma", "full stop", "new line"... are turned into symbols while dictating. */
    val spokenPunctuation: Boolean = true,
    val aiCommandModeDefault: Boolean = false,
    val appendSpaceAfterDictation: Boolean = true,
    val shareEditorContext: Boolean = false,
    val alwaysConfirmEdits: Boolean = true,
    val hapticFeedback: Boolean = true,
    val theme: String = "system",
    val onboardingComplete: Boolean = false,
) {
    val languageLabel: String get() = when (recognitionLanguage) {
        "hi-IN" -> "Hindi (India)"
        "kn-IN" -> "Kannada (India)"
        "hinglish" -> "Hinglish (Latin script)"
        "en-IN" -> "English (India)"
        else -> "English (US)"
    }
}

class SettingsRepository(private val context: Context) {
    private object Keys {
        val backendUrl = stringPreferencesKey("backend_base_url")
        val language = stringPreferencesKey("recognition_language")
        val cloudDictation = booleanPreferencesKey("cloud_dictation_enabled")
        val dictionaryTerms = stringPreferencesKey("dictation_dictionary_terms")
        val streamingDictation = booleanPreferencesKey("streaming_dictation")
        val noiseReduction = booleanPreferencesKey("noise_reduction")
        val spokenPunctuation = booleanPreferencesKey("spoken_punctuation")
        val commandMode = booleanPreferencesKey("ai_command_mode_default")
        val appendSpace = booleanPreferencesKey("append_space_after_dictation")
        val shareContext = booleanPreferencesKey("share_editor_context")
        val confirmEdits = booleanPreferencesKey("always_confirm_edits")
        val haptics = booleanPreferencesKey("haptic_feedback")
        val theme = stringPreferencesKey("theme")
        val onboarding = booleanPreferencesKey("onboarding_complete")
    }

    val settings: Flow<AppSettings> = context.akzsaDataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map { preferences ->
            AppSettings(
                backendBaseUrl = preferences[Keys.backendUrl] ?: AppSettings().backendBaseUrl,
                recognitionLanguage = preferences[Keys.language] ?: AppSettings().recognitionLanguage,
                cloudDictationEnabled = preferences[Keys.cloudDictation] ?: false,
                dictionaryTerms = preferences[Keys.dictionaryTerms] ?: AppSettings().dictionaryTerms,
                streamingDictation = preferences[Keys.streamingDictation] ?: true,
                noiseReduction = preferences[Keys.noiseReduction] ?: true,
                spokenPunctuation = preferences[Keys.spokenPunctuation] ?: true,
                aiCommandModeDefault = preferences[Keys.commandMode] ?: false,
                appendSpaceAfterDictation = preferences[Keys.appendSpace] ?: true,
                shareEditorContext = preferences[Keys.shareContext] ?: false,
                alwaysConfirmEdits = preferences[Keys.confirmEdits] ?: true,
                hapticFeedback = preferences[Keys.haptics] ?: true,
                theme = preferences[Keys.theme] ?: "system",
                onboardingComplete = preferences[Keys.onboarding] ?: false,
            )
        }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.akzsaDataStore.edit { prefs ->
            val current = AppSettings(
                backendBaseUrl = prefs[Keys.backendUrl] ?: AppSettings().backendBaseUrl,
                recognitionLanguage = prefs[Keys.language] ?: AppSettings().recognitionLanguage,
                cloudDictationEnabled = prefs[Keys.cloudDictation] ?: false,
                dictionaryTerms = prefs[Keys.dictionaryTerms] ?: AppSettings().dictionaryTerms,
                streamingDictation = prefs[Keys.streamingDictation] ?: true,
                noiseReduction = prefs[Keys.noiseReduction] ?: true,
                spokenPunctuation = prefs[Keys.spokenPunctuation] ?: true,
                aiCommandModeDefault = prefs[Keys.commandMode] ?: false,
                appendSpaceAfterDictation = prefs[Keys.appendSpace] ?: true,
                shareEditorContext = prefs[Keys.shareContext] ?: false,
                alwaysConfirmEdits = prefs[Keys.confirmEdits] ?: true,
                hapticFeedback = prefs[Keys.haptics] ?: true,
                theme = prefs[Keys.theme] ?: "system",
                onboardingComplete = prefs[Keys.onboarding] ?: false,
            )
            val next = transform(current)
            prefs[Keys.backendUrl] = next.backendBaseUrl
            prefs[Keys.language] = next.recognitionLanguage
            prefs[Keys.cloudDictation] = next.cloudDictationEnabled
            prefs[Keys.dictionaryTerms] = next.dictionaryTerms.take(4000)
            prefs[Keys.streamingDictation] = next.streamingDictation
            prefs[Keys.noiseReduction] = next.noiseReduction
            prefs[Keys.spokenPunctuation] = next.spokenPunctuation
            prefs[Keys.commandMode] = next.aiCommandModeDefault
            prefs[Keys.appendSpace] = next.appendSpaceAfterDictation
            prefs[Keys.shareContext] = next.shareEditorContext
            prefs[Keys.confirmEdits] = next.alwaysConfirmEdits
            prefs[Keys.haptics] = next.hapticFeedback
            prefs[Keys.theme] = next.theme
            prefs[Keys.onboarding] = next.onboardingComplete
        }
    }

    suspend fun reset() {
        context.akzsaDataStore.edit { it.clear() }
    }
}
