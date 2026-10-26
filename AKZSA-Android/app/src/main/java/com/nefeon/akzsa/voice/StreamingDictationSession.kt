package com.nefeon.akzsa.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SegmentResult {
    data class Text(val text: String) : SegmentResult
    /** The server heard no speech in this phrase: nothing to insert, nothing to report. */
    data object Silent : SegmentResult
    data class Failed(val message: String, val retryable: Boolean, val fatal: Boolean = false) : SegmentResult
}

/**
 * Live ("streaming") cloud dictation. The microphone is read continuously; every time the speaker pauses, the
 * finished phrase is cleaned (rumble filter, level boost), sent to the server and inserted, in order, while the
 * speaker keeps talking. It is near-live (text appears about a second after each pause), not word-by-word.
 *
 * Threading: [scope] must be the main-thread scope. All callbacks run on the main thread.
 */
class StreamingDictationSession(
    private val scope: CoroutineScope,
    private val noiseReduction: Boolean,
    private val transcribe: suspend (wav: ByteArray, contextTail: String) -> SegmentResult,
    private val onText: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFinished: (String?) -> Unit,
) {
    private val capture = VoiceCapture(noiseReduction)
    private val segments = Channel<ShortArray>(SEGMENT_QUEUE)

    @Volatile private var stopRequested = false
    @Volatile private var cancelled = false
    @Volatile private var endReason: String? = null
    @Volatile private var droppedSegments = false
    private var captureJob: Job? = null
    private var workerJob: Job? = null
    private var tail = ""
    private var delivered = 0
    private var failures = 0
    private var lastFailure: String? = null

    /** Opens the microphone and starts both loops. False when the microphone cannot be opened (caller falls back). */
    fun start(): Boolean {
        if (!capture.open()) return false
        val endpointer = SpeechEndpointer()
        captureJob = scope.launch(Dispatchers.IO) {
            val startedAt = System.nanoTime()
            val error = capture.readLoop { frame ->
                if (cancelled || stopRequested) return@readLoop false
                val phrase = endpointer.push(frame)
                if (phrase != null && !segments.trySend(phrase).isSuccess) droppedSegments = true
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                when {
                    endpointer.msSinceSpeech >= AUTO_STOP_IDLE_MS -> {
                        endReason = "No speech for ${AUTO_STOP_IDLE_MS / 1000} seconds - stopped listening"
                        false
                    }
                    elapsedMs >= MAX_SESSION_MS -> {
                        endReason = "Maximum dictation length reached"
                        false
                    }
                    else -> true
                }
            }
            if (error != null && endReason == null && !cancelled) endReason = error
            if (!cancelled) {
                val last = endpointer.flush()
                if (last != null && !segments.trySend(last).isSuccess) droppedSegments = true
            }
            capture.close()
            segments.close()
        }
        workerJob = scope.launch {
            for (pcm in segments) {
                if (cancelled) break
                onStatus("Transcribing...")
                val wav = withContext(Dispatchers.Default) {
                    AudioDsp.toWav(if (noiseReduction) AudioDsp.normalized(pcm) else pcm)
                }
                var result = transcribe(wav, tail.takeLast(CONTEXT_CHARS))
                if (result is SegmentResult.Failed && result.retryable && !cancelled) {
                    delay(900)
                    result = transcribe(wav, tail.takeLast(CONTEXT_CHARS))
                }
                if (cancelled) break
                when (result) {
                    is SegmentResult.Text -> {
                        delivered++
                        onText(result.text)
                        tail = (tail + " " + result.text).takeLast(CONTEXT_CHARS * 2).trim()
                    }
                    SegmentResult.Silent -> Unit
                    is SegmentResult.Failed -> {
                        failures++
                        lastFailure = result.message
                        if (result.fatal) {
                            endReason = result.message
                            stopRequested = true
                            segments.cancel()
                            break
                        }
                        onStatus("A phrase could not be transcribed: ${result.message}")
                    }
                }
                if (!stopRequested) onStatus("Listening - text appears after each pause - tap Stop to finish")
            }
            complete()
        }
        onStatus("Listening - text appears after each pause - tap Stop to finish")
        return true
    }

    /** User tapped Stop: the phrase in progress is still sent, then [onFinished] is called. */
    fun stop() {
        stopRequested = true
    }

    /** Drops everything immediately; [onFinished] is not called. */
    fun cancel() {
        cancelled = true
        stopRequested = true
        segments.cancel()
        workerJob?.cancel()
    }

    private fun complete() {
        if (cancelled) return
        val reason = endReason
            ?: if (delivered == 0 && failures > 0) lastFailure
            else if (droppedSegments) "Some speech was skipped because the connection was slow"
            else if (delivered == 0) "No speech was detected"
            else null
        onFinished(reason)
    }

    private companion object {
        const val SEGMENT_QUEUE = 16
        const val CONTEXT_CHARS = 160
        const val AUTO_STOP_IDLE_MS = 25_000
        const val MAX_SESSION_MS = 600_000L
    }
}
