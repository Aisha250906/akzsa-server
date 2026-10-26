package com.nefeon.akzsa.files

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * Runs MobileCLIP2-S0 with ONNX Runtime on the CPU. Limited to 2-4 threads (half the cores) so photo indexing
 * stays cool: it is slower than "full speed" on purpose.
 */
object ClipEngine {
    const val DIM = 512
    const val IMAGE_SIZE = 256

    private val visionLock = Any()
    private val textLock = Any()
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    @Volatile private var vision: OrtSession? = null
    @Volatile private var text: OrtSession? = null
    @Volatile private var tokenizer: ClipTokenizer? = null

    /** Half of the CPU cores, 2 to 4: faster than a fixed 2 on 8-core phones; ThermalGuard still pauses if the phone warms up. */
    private fun inferenceThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    private fun newSession(file: File): OrtSession {
        ClipModelManager.requireIntact(file)
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(inferenceThreads())
        options.setInterOpNumThreads(1)
        return env.createSession(file.absolutePath, options)
    }

    /** [chw] is a 3x256x256 float tensor (see [ClipPreprocess.forEachTensor]). Returns a unit-length 512 vector. */
    fun embedImage(context: Context, chw: FloatArray): FloatArray = synchronized(visionLock) {
        val session = vision ?: newSession(ClipModelManager.visionFile(context)).also { vision = it }
        val inputName = session.inputNames.first()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result -> normalize(flatten(result[0].value)) }
        }
    }

    /** Embeds a short English phrase. Returns a unit-length 512 vector. */
    fun embedText(context: Context, phrase: String): FloatArray = synchronized(textLock) {
        val tok = tokenizer ?: ClipTokenizer.fromJson(ClipModelManager.tokenizerFile(context).readText()).also { tokenizer = it }
        val session = text ?: newSession(ClipModelManager.textFile(context)).also { text = it }
        val inputName = session.inputNames.first()
        val ids = tok.encode(phrase, 77)
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result -> normalize(flatten(result[0].value)) }
        }
    }

    /** Frees the model memory (vision part only when [textToo] is false). */
    fun release(textToo: Boolean = true) {
        synchronized(visionLock) {
            try { vision?.close() } catch (_: Exception) { /* Native session is discarded below. */ }
            vision = null
        }
        if (textToo) synchronized(textLock) {
            try { text?.close() } catch (_: Exception) { /* Native session is discarded below. */ }
            text = null
            tokenizer = null
        }
    }

    private fun flatten(value: Any?): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*> -> value.flatMap { flatten(it).asList() }.toFloatArray()
        else -> throw IllegalStateException("Unexpected model output type: ${value?.javaClass?.name}")
    }

    private fun normalize(vector: FloatArray): FloatArray {
        check(vector.size == DIM) { "Unexpected embedding size ${vector.size} (expected $DIM)" }
        var sum = 0.0
        for (v in vector) sum += (v * v).toDouble()
        val norm = sqrt(sum).toFloat().coerceAtLeast(1e-12f)
        return FloatArray(DIM) { vector[it] / norm }
    }

    private const val PREFS = "akzsa_file_ai"
    private const val KEY_ERROR = "clip_last_error"

    fun recordError(context: Context, error: Throwable) {
        // Record only a type/code, never an exception message that could include private file paths.
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ERROR, error.javaClass.simpleName.take(80)).commit()
        } catch (_: Exception) { /* Diagnostics must never prevent local search from continuing. */ }
    }

    fun lastError(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ERROR, null)

    fun clearError(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ERROR).commit()
    }
}

/** Turns an image file into the exact input MobileCLIP2-S0 expects (256x256, CLIP mean/std). */
object ClipPreprocess {
    private const val SIZE = ClipEngine.IMAGE_SIZE
    private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    /**
     * Emits one full-frame (squashed) tensor followed by four quadrant tiles. Each temporary bitmap
     * is recycled before the next is created, keeping crop inference bounded on low-memory phones.
     * Returns false only if the source image cannot be decoded.
     */
    fun forEachTensor(context: Context, uriString: String, consume: (FloatArray) -> Unit): Boolean {
        val decoded = decode(context, Uri.parse(uriString)) ?: return false
        try {
            if (decoded.width <= 0 || decoded.height <= 0) return false
            val full = Bitmap.createScaledBitmap(decoded, SIZE, SIZE, true)
            try { consume(tensorFromBitmap(full)) } finally { if (full !== decoded) full.recycle() }

            // Use actual quadrants, not four square corner crops. The old square-crop method
            // left large gaps in portrait/landscape images and missed small subjects near the middle.
            val xSplit = if (decoded.width > 1) decoded.width / 2 else 0
            val ySplit = if (decoded.height > 1) decoded.height / 2 else 0
            val xRanges = if (xSplit > 0) listOf(0 to xSplit, xSplit to decoded.width - xSplit)
                else listOf(0 to decoded.width, 0 to decoded.width)
            val yRanges = if (ySplit > 0) listOf(0 to ySplit, ySplit to decoded.height - ySplit)
                else listOf(0 to decoded.height, 0 to decoded.height)
            for ((x, tileWidth) in xRanges) for ((y, tileHeight) in yRanges) {
                val tile = Bitmap.createBitmap(decoded, x, y, tileWidth, tileHeight)
                val scaled = Bitmap.createScaledBitmap(tile, SIZE, SIZE, true)
                try { consume(tensorFromBitmap(scaled)) } finally {
                    if (scaled !== tile) scaled.recycle()
                    if (tile !== decoded) tile.recycle()
                }
            }
            return true
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private fun tensorFromBitmap(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(SIZE * SIZE)
        bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        val plane = SIZE * SIZE
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = pixels[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            out[i] = (r - MEAN[0]) / STD[0]
            out[plane + i] = (g - MEAN[1]) / STD[1]
            out[2 * plane + i] = (b - MEAN[2]) / STD[2]
        }
        return out
    }

    private fun decode(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return decodeModern(context, uri)
            } catch (_: Exception) {
                // fall back to BitmapFactory below
            }
        }
        return decodeLegacy(context, uri)
    }

    /** ImageDecoder reads HEIC/WebP and applies EXIF rotation. Sample size keeps big photos small in memory. */
    private fun decodeModern(context: Context, uri: Uri): Bitmap {
        val source = if (uri.scheme == "file") {
            ImageDecoder.createSource(File(uri.path ?: throw IllegalArgumentException("File path is missing")))
        } else {
            ImageDecoder.createSource(context.contentResolver, uri)
        }
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val minSide = minOf(info.size.width, info.size.height)
            var sample = 1
            while (minSide / (sample * 2) >= SIZE) sample *= 2
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }
    }

    private fun openStream(context: Context, uri: Uri): InputStream? =
        if (uri.scheme == "file") uri.path?.let { FileInputStream(File(it)) } else context.contentResolver.openInputStream(uri)

    private fun decodeLegacy(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return openStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
}
