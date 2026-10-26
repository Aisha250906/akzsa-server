package com.nefeon.akzsa.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandSerializationTest {
    @Test fun cloudVoiceLanguageRoutingSupportsEnglishHindiKannadaAndHinglish() {
        assertEquals("en", cloudVoiceLanguageCode("en-IN"))
        assertEquals("hi", cloudVoiceLanguageCode("hi-IN"))
        assertEquals("kn", cloudVoiceLanguageCode("kn-IN"))
        assertEquals("hinglish", cloudVoiceLanguageCode("hinglish"))
    }

    @Test fun requestUsesExactBackendFieldNamesAndModes() {
        val json = Json { explicitNulls = true; encodeDefaults = true }
        val encoded = json.encodeToString(CommandRequest.serializer(), CommandRequest(
            transcript = "delete the last 10 words",
            selected_text = null,
            surrounding_text = null,
            mode = "command",
        ))
        assertEquals(
            """{"transcript":"delete the last 10 words","selected_text":null,"surrounding_text":null,"mode":"command"}""",
            encoded,
        )
    }

    @Test fun responseParserAcceptsKnownBackendShape() {
        val raw = """{"action":{"kind":"delete_last_words","count":10,"text":null,"query":null,"language":null,"requires_confirmation":true,"explanation":"Delete the last 10 words."},"model":"openai/gpt-oss-20b","server_executed_action":false,"message":"Action interpreted only."}"""
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString(CommandResponse.serializer(), raw)
        assertEquals("delete_last_words", parsed.action.kind)
        assertEquals(10, parsed.action.count)
        assertEquals(true, parsed.action.requires_confirmation)
        assertEquals(false, parsed.server_executed_action)
    }
    @Test fun cloudVoiceResponseParsesRawAndPolishedText() {
        val raw = """{"transcript":"um hello world","text":"Hello, world.","model":"whisper-large-v3-turbo","polish_model":"openai/gpt-oss-20b","polish_applied":true,"message":"Audio transcribed; AI cleanup applied."}"""
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString(VoiceTranscriptionResponse.serializer(), raw)
        assertEquals("um hello world", parsed.transcript)
        assertEquals("Hello, world.", parsed.text)
        assertEquals("whisper-large-v3-turbo", parsed.model)
        assertEquals(true, parsed.polish_applied)
    }

}
