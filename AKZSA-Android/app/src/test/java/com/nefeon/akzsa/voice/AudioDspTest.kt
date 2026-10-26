package com.nefeon.akzsa.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioDspTest {
    private val n = AudioDsp.FRAME_SAMPLES

    /** Alternating +/- amplitude: its RMS is exactly amplitude / 32768. */
    private fun frame(amplitude: Int) = ShortArray(n) { if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }

    /** Pushes [count] frames and records the length (in frames) of every finished phrase. */
    private fun feed(ep: SpeechEndpointer, amplitude: Int, count: Int, out: MutableList<Int>) {
        repeat(count) { ep.push(frame(amplitude))?.let { out += it.size / n } }
    }

    private val silence = 0
    private val speech = 6000        // RMS 0.18
    private val fan = 655            // RMS 0.02, steady background noise

    @Test fun rmsOfKnownSignals() {
        assertEquals(0.5, AudioDsp.rms(ShortArray(n) { 16384.toShort() }), 1e-9)
        assertEquals(0.0, AudioDsp.rms(ShortArray(n)), 0.0)
        assertEquals(0.0, AudioDsp.rms(ShortArray(0)), 0.0)
    }

    @Test fun peakHandlesNegativeAndMinValue() {
        assertEquals(2000, AudioDsp.peak(shortArrayOf(100, -2000, 300)))
        assertEquals(32768, AudioDsp.peak(shortArrayOf(Short.MIN_VALUE)))
    }

    @Test fun quietSpeechIsBoostedButLoudSpeechIsLeftAlone() {
        val boosted = AudioDsp.normalized(shortArrayOf(1000, -500, 0))
        assertEquals(listOf(8000, -4000, 0), boosted.map { it.toInt() })          // capped at +18 dB (x8)
        val loud = shortArrayOf(30000, -30000)
        assertSame(loud, AudioDsp.normalized(loud))
        val medium = AudioDsp.normalized(shortArrayOf(20000, -20000))
        assertTrue(AudioDsp.peak(medium) in 28_900..29_100)
        val empty = ShortArray(4)
        assertSame(empty, AudioDsp.normalized(empty))
    }

    @Test fun wavHeaderAndSamplesAreCorrect() {
        val wav = AudioDsp.toWav(shortArrayOf(0x1234, -2, 0))
        assertEquals(44 + 6, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(42.toByte(), wav[4])                       // 36 + data length
        assertEquals(1.toByte(), wav[20])                       // PCM
        assertEquals(1.toByte(), wav[22])                       // mono
        assertEquals(0x80.toByte(), wav[24]); assertEquals(0x3E.toByte(), wav[25])   // 16000 Hz
        assertEquals(6.toByte(), wav[40])                       // data length
        assertEquals(0x34.toByte(), wav[44]); assertEquals(0x12.toByte(), wav[45])   // little endian
        assertEquals(0xFE.toByte(), wav[46]); assertEquals(0xFF.toByte(), wav[47])
    }

    @Test fun highPassRemovesDcAndRumbleButKeepsVoice() {
        val dc = ShortArray(16_000) { 10_000.toShort() }
        AudioDsp.HighPass().process(dc)
        assertTrue(abs(dc.last().toInt()) < 50)

        val rumble = ShortArray(32_000) { (10_000 * sin(2 * PI * 30 * it / 16_000)).toInt().toShort() }
        AudioDsp.HighPass().process(rumble)
        assertTrue(AudioDsp.peak(rumble.copyOfRange(16_000, 32_000)) < 2_500)

        val voice = ShortArray(16_000) { (10_000 * sin(2 * PI * 1000 * it / 16_000)).toInt().toShort() }
        AudioDsp.HighPass().process(voice)
        assertTrue(AudioDsp.peak(voice.copyOfRange(8_000, 16_000)) > 9_500)
    }

    @Test fun onePhraseEndsAfterAPauseAndKeepsPreRollAndShortTail() {
        val ep = SpeechEndpointer(); val out = ArrayList<Int>()
        feed(ep, silence, 10, out); feed(ep, speech, 40, out); feed(ep, silence, 80, out)
        assertEquals(listOf(62), out)                           // 10 pre-roll + 40 speech + 12 tail frames
    }

    @Test fun phraseInProgressIsReturnedByFlush() {
        val ep = SpeechEndpointer(); val out = ArrayList<Int>()
        feed(ep, silence, 10, out); feed(ep, speech, 40, out)
        assertTrue(out.isEmpty())
        val last = ep.flush()
        assertNotNull(last)
        assertEquals(50, last!!.size / n)
    }

    @Test fun shortNoiseBurstsAndSilenceNeverProducePhrases() {
        val burst = SpeechEndpointer(); val out = ArrayList<Int>()
        feed(burst, silence, 10, out); feed(burst, speech, 5, out); feed(burst, silence, 100, out)
        assertTrue(out.isEmpty())
        val quiet = SpeechEndpointer(); val none = ArrayList<Int>()
        feed(quiet, silence, 300, none)
        assertTrue(none.isEmpty()); assertNull(quiet.flush())
    }

    @Test fun steadyBackgroundNoiseIsNotSpeech() {
        val ep = SpeechEndpointer(); val out = ArrayList<Int>()
        feed(ep, fan, 200, out)
        assertTrue(out.isEmpty()); assertNull(ep.flush())
        assertEquals(0.02, ep.noiseFloor, 0.002)
        assertEquals(4000, ep.msSinceSpeech)                    // lets the session auto-stop when nobody talks
    }

    @Test fun speechOverSteadyNoiseIsFoundWithoutLeadingNoise() {
        val ep = SpeechEndpointer(); val out = ArrayList<Int>()
        feed(ep, fan, 50, out); feed(ep, 6553, 40, out); feed(ep, fan, 80, out)
        assertEquals(listOf(67), out)                           // 15 pre-roll + 40 speech + 12 tail
    }

    @Test fun twoPhrasesAreSeparatedByANormalPause() {
        val ep = SpeechEndpointer(); val out = ArrayList<Int>()
        feed(ep, silence, 5, out); feed(ep, speech, 60, out); feed(ep, silence, 70, out)
        feed(ep, speech, 60, out); feed(ep, silence, 90, out)
        assertEquals(listOf(77, 77), out)
    }

    @Test fun longPhrasesUseTheShorterPause() {
        val ep = SpeechEndpointer(); val out = ArrayList<Int>()
        feed(ep, silence, 5, out); feed(ep, speech, 250, out); feed(ep, silence, 60, out)
        assertEquals(listOf(267), out)
    }

    @Test fun quietSpeakerInAQuietRoomIsStillHeard() {
        val ep = SpeechEndpointer(); val out = ArrayList<Int>()
        feed(ep, silence, 10, out); feed(ep, fan, 40, out); feed(ep, silence, 80, out)
        assertEquals(listOf(62), out)
    }

    @Test fun continuousSpeechIsCutAtTwentySecondsWithoutLosingTheRest() {
        val ep = SpeechEndpointer(); val out = ArrayList<Int>()
        feed(ep, speech, 1100, out)
        assertEquals(listOf(1000), out)
        val rest = ep.flush()
        assertNotNull(rest)
        assertEquals(100, rest!!.size / n)
    }
}
