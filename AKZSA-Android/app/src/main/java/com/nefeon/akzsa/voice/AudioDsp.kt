package com.nefeon.akzsa.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small, pure audio helpers for dictation (no Android classes, so they are unit-testable):
 * level measurement, a rumble filter, quiet-speech boost and a WAV writer.
 */
object AudioDsp {
    const val SAMPLE_RATE = 16_000
    const val FRAME_MS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000   // 320

    /** Root-mean-square level of a frame, 0.0 (silence) to 1.0 (full scale). */
    fun rms(frame: ShortArray, length: Int = frame.size): Double {
        if (length <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until length) {
            val v = frame[i] / 32768.0
            sum += v * v
        }
        return sqrt(sum / length)
    }

    fun peak(pcm: ShortArray): Int {
        var p = 0
        for (s in pcm) {
            val a = abs(s.toInt())
            if (a > p) p = a
        }
        return p
    }

    /** Boosts quiet speech so the recogniser sees a healthy level. Never attenuates and never adds more than +18 dB. */
    fun normalized(pcm: ShortArray, targetPeak: Int = 29_000, maxGain: Double = 8.0): ShortArray {
        val p = peak(pcm)
        if (p <= 0) return pcm
        val gain = min(maxGain, targetPeak.toDouble() / p)
        if (gain <= 1.05) return pcm
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            out[i] = (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** 16-bit mono PCM -> WAV file bytes (44-byte header). */
    fun toWav(pcm: ShortArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val dataLen = pcm.size * 2
        val out = ByteArray(44 + dataLen)
        fun putInt(o: Int, v: Int) {
            out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte(); out[o + 2] = (v shr 16).toByte(); out[o + 3] = (v shr 24).toByte()
        }
        fun putShort(o: Int, v: Int) {
            out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte()
        }
        "RIFF".forEachIndexed { i, c -> out[i] = c.code.toByte() }
        putInt(4, 36 + dataLen)
        "WAVE".forEachIndexed { i, c -> out[8 + i] = c.code.toByte() }
        "fmt ".forEachIndexed { i, c -> out[12 + i] = c.code.toByte() }
        putInt(16, 16); putShort(20, 1); putShort(22, 1)
        putInt(24, sampleRate); putInt(28, sampleRate * 2); putShort(32, 2); putShort(34, 16)
        "data".forEachIndexed { i, c -> out[36 + i] = c.code.toByte() }
        putInt(40, dataLen)
        var o = 44
        for (s in pcm) {
            out[o++] = s.toInt().toByte()
            out[o++] = (s.toInt() shr 8).toByte()
        }
        return out
    }

    /** Second-order Butterworth high-pass: removes wind, handling noise, AC hum and table rumble below the voice band. */
    class HighPass(cutoffHz: Double = 100.0, sampleRate: Int = SAMPLE_RATE) {
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        init {
            val w0 = 2.0 * PI * cutoffHz / sampleRate
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * 0.70710678)
            val a0 = 1.0 + alpha
            b0 = (1.0 + cosW) / 2.0 / a0
            b1 = -(1.0 + cosW) / a0
            b2 = b0
            a1 = -2.0 * cosW / a0
            a2 = (1.0 - alpha) / a0
        }

        /** Filters the frame in place. State carries over between frames. */
        fun process(frame: ShortArray, length: Int = frame.size) {
            for (i in 0 until length) {
                val x = frame[i].toDouble()
                val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x; y2 = y1; y1 = y
                frame[i] = y.toInt().coerceIn(-32768, 32767).toShort()
            }
        }
    }
}

/**
 * Cuts a continuous 20 ms frame stream into spoken phrases.
 *
 *  - The speech threshold follows the room: it is [Config.startFactor] times the quietest smoothed level of the
 *    last 3 seconds (never lower than [Config.minSpeechRms]), so fans, traffic hum and AC do not count as speech,
 *    even when they were already running when recording started.
 *  - A short burst (door slam, cough, keyboard click) shorter than [Config.minSpeechMs] is dropped, which also
 *    stops Whisper from inventing text for noise-only clips.
 *  - 300 ms of audio before the first loud frame is kept so word beginnings are not clipped.
 *  - A phrase ends after a pause: 1.3 s while it is still short, 0.8 s once it is longer than 4 s. This keeps
 *    the number of server requests low while text still appears quickly.
 */
class SpeechEndpointer(private val cfg: Config = Config()) {
    data class Config(
        val frameMs: Int = AudioDsp.FRAME_MS,
        val minSpeechRms: Double = 0.012,
        val startFactor: Double = 3.0,
        val continueFactor: Double = 1.8,
        val preRollMs: Int = 300,
        val minSpeechMs: Int = 300,
        val shortHangMs: Int = 1300,
        val longHangMs: Int = 800,
        val longSegmentMs: Int = 4000,
        val maxSegmentMs: Int = 20_000,
        val tailKeepMs: Int = 240,
    )

    private val frameMs = cfg.frameMs
    private val preRollFrames = max(1, cfg.preRollMs / cfg.frameMs)
    private val maxFrames = max(1, cfg.maxSegmentMs / cfg.frameMs)
    private val tailKeepFrames = cfg.tailKeepMs / cfg.frameMs
    private val shortHangFrames = max(1, cfg.shortHangMs / cfg.frameMs)
    private val longHangFrames = max(1, cfg.longHangMs / cfg.frameMs)

    private val preRoll = ArrayDeque<ShortArray>()
    private var segment: ArrayList<ShortArray>? = null
    private var speechFrames = 0
    private var silentRun = 0
    private var floor = cfg.minSpeechRms / cfg.startFactor
    private val floorWindow = DoubleArray(FLOOR_WINDOW_FRAMES)
    private var floorWindowPos = 0
    private var floorWindowFilled = 0
    private var smoothedLevel = 0.0
    private var cutWhileSpeaking = false

    var framesSinceSpeech: Int = 0
        private set
    val noiseFloor: Double get() = floor
    val inSpeech: Boolean get() = segment != null
    val msSinceSpeech: Int get() = framesSinceSpeech * frameMs

    /** Feeds one frame. Returns the PCM of a finished phrase, or null. The frame buffer may be reused by the caller. */
    fun push(frame: ShortArray): ShortArray? {
        val level = AudioDsp.rms(frame)
        // First frame of the session: if it is quiet it is the room, so steady noise that is already running
        // (fan, AC) is never mistaken for speech. A loud first frame is left alone (the speaker may already be talking).
        if (floorWindowFilled == 0 && level < CALIBRATION_MAX_LEVEL) floor = max(level, 0.0005)
        val active = segment != null || cutWhileSpeaking
        val threshold = if (active) max(cfg.minSpeechRms * 0.7, floor * cfg.continueFactor)
        else max(cfg.minSpeechRms, floor * cfg.startFactor)
        val speech = level >= threshold
        if (speech) framesSinceSpeech = 0 else if (framesSinceSpeech < Int.MAX_VALUE / 2) framesSinceSpeech++
        updateFloor(level)

        val seg = segment
        if (seg == null) {
            cutWhileSpeaking = false
            if (speech) {
                val started = ArrayList<ShortArray>(preRoll.size + 64)
                started.addAll(preRoll)
                started.add(frame.copyOf())
                preRoll.clear()
                segment = started
                speechFrames = 1
                silentRun = 0
            } else {
                preRoll.addLast(frame.copyOf())
                while (preRoll.size > preRollFrames) preRoll.removeFirst()
            }
            return null
        }
        seg.add(frame.copyOf())
        if (speech) {
            speechFrames++
            silentRun = 0
        } else {
            silentRun++
        }
        val hang = if (speechFrames * frameMs >= cfg.longSegmentMs) longHangFrames else shortHangFrames
        return when {
            silentRun >= hang -> finish(forcedCut = false)
            seg.size >= maxFrames -> finish(forcedCut = true)
            else -> null
        }
    }

    /** End of input: returns the phrase still in progress (if it contains real speech). */
    fun flush(): ShortArray? = finish(forcedCut = false)

    /** Floor = quietest smoothed level in the last 3 s. Speech has pauses and valleys; steady noise does not. */
    private fun updateFloor(level: Double) {
        smoothedLevel = if (floorWindowFilled == 0) level else smoothedLevel * 0.8 + level * 0.2
        floorWindow[floorWindowPos] = smoothedLevel
        floorWindowPos = (floorWindowPos + 1) % FLOOR_WINDOW_FRAMES
        if (floorWindowFilled < FLOOR_WINDOW_FRAMES) floorWindowFilled++
        var lowest = Double.MAX_VALUE
        for (i in 0 until floorWindowFilled) if (floorWindow[i] < lowest) lowest = floorWindow[i]
        floor = lowest.coerceIn(0.0005, 0.08)
    }

    private companion object {
        const val FLOOR_WINDOW_FRAMES = 150   // 3 seconds of 20 ms frames
        const val CALIBRATION_MAX_LEVEL = 0.04
    }

    private fun finish(forcedCut: Boolean): ShortArray? {
        val seg = segment ?: return null
        val speechMs = speechFrames * frameMs
        val trailing = max(0, silentRun - tailKeepFrames)
        val keep = seg.size - trailing
        // A forced cut in the middle of continuous speech must not make the very next speech frames look like noise.
        cutWhileSpeaking = forcedCut && silentRun == 0
        segment = null
        speechFrames = 0
        silentRun = 0
        if (speechMs < cfg.minSpeechMs || keep <= 0) return null
        var total = 0
        for (i in 0 until keep) total += seg[i].size
        val out = ShortArray(total)
        var pos = 0
        for (i in 0 until keep) {
            val f = seg[i]
            System.arraycopy(f, 0, out, pos, f.size)
            pos += f.size
        }
        return out
    }
}
