package com.nefeon.akzsa.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import kotlin.math.max

/**
 * Microphone input for live dictation: 16 kHz mono PCM from the VOICE_RECOGNITION source (tuned for speech),
 * the platform noise suppressor when the phone has one, and a rumble filter in software.
 * Everything fails soft: if anything is unavailable, [open] returns false and the caller falls back.
 */
class VoiceCapture(private val noiseReduction: Boolean) {
    private var record: AudioRecord? = null
    private var suppressor: NoiseSuppressor? = null

    fun open(): Boolean {
        var rec: AudioRecord? = null
        return try {
            val minBuffer = AudioRecord.getMinBufferSize(AudioDsp.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) return false
            rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AudioDsp.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer * 2, AudioDsp.FRAME_SAMPLES * 2 * 10),
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }
            if (noiseReduction) {
                try {
                    if (NoiseSuppressor.isAvailable()) {
                        suppressor = NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true }
                    }
                } catch (_: Exception) {
                    suppressor = null
                }
            }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                try { suppressor?.release() } catch (_: Exception) { }
                suppressor = null
                rec.release()
                return false
            }
            record = rec
            true
        } catch (_: Exception) {
            try { suppressor?.release() } catch (_: Exception) { }
            suppressor = null
            try { rec?.release() } catch (_: Exception) { }
            record = null
            false
        }
    }

    /**
     * Blocking. Hands over one 20 ms frame at a time until [onFrame] returns false or the microphone fails.
     * Returns null after a normal stop, or a short error message.
     */
    fun readLoop(onFrame: (ShortArray) -> Boolean): String? {
        val rec = record ?: return "The microphone is not open"
        val buffer = ShortArray(AudioDsp.FRAME_SAMPLES)
        val highPass = if (noiseReduction) AudioDsp.HighPass() else null
        while (true) {
            var filled = 0
            while (filled < buffer.size) {
                val n = rec.read(buffer, filled, buffer.size - filled)
                if (n < 0) return "Microphone error ($n)"
                if (n == 0) return "The microphone stopped delivering audio"
                filled += n
            }
            highPass?.process(buffer)
            if (!onFrame(buffer)) return null
        }
    }

    fun close() {
        val rec = record
        record = null
        try { rec?.stop() } catch (_: Exception) { }
        try { rec?.release() } catch (_: Exception) { }
        try { suppressor?.release() } catch (_: Exception) { }
        suppressor = null
    }
}
