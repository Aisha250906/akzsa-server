package com.nefeon.akzsa.files

import android.content.Context
import com.nefeon.akzsa.recognition.ModelArtifactVerifier
import com.nefeon.akzsa.recognition.ModelManifest
import com.nefeon.akzsa.recognition.ModelVerification
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Light on-device photo model: MobileCLIP2-S0 (FP32 vision + INT8 text, about 107 MB in total).
 * Files are downloaded once over Wi-Fi into app-private storage. They never leave the phone.
 */
object ClipModelManager {
    const val UNIQUE_DOWNLOAD = "akzsa-clip-model-download"
    const val BASE_URL = "https://huggingface.co/felixhrdyn/mobileclip2-s0-onnx/resolve/99ca0cde6c76c0c56fcfeace07eb3a340ba17fc0/"
    const val VISION_FILE = "vision_model.onnx"
    const val TEXT_FILE = "text_model_quantized.onnx"
    const val TOKENIZER_FILE = "tokenizer.json"
    const val TOTAL_APPROX_MB = 107

    data class Part(val name: String, val minBytes: Long, val approxBytes: Long)

    // Sizes published on the model card: 43.4 MB, 61.3 MB, 2.1 MB. The minimum catches truncated downloads.
    val PARTS = listOf(
        Part(VISION_FILE, 40L * 1024 * 1024, 45_500_000L),
        Part(TEXT_FILE, 55L * 1024 * 1024, 64_300_000L),
        Part(TOKENIZER_FILE, 1_500_000L, 2_200_000L),
    )

    fun dir(context: Context): File = File(context.applicationContext.filesDir, "clip_models").apply { mkdirs() }
    fun visionFile(context: Context) = File(dir(context), VISION_FILE)
    fun textFile(context: Context) = File(dir(context), TEXT_FILE)
    fun tokenizerFile(context: Context) = File(dir(context), TOKENIZER_FILE)

    private val verifiedFiles = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Called before an ONNX session is created. Removes a corrupt file so it is downloaded again instead of
     * crashing the native runtime. Installs from earlier versions (no hash record yet) are adopted once.
     */
    fun requireIntact(file: File) {
        val stamp = "${file.length()}:${file.lastModified()}"
        if (verifiedFiles[file.absolutePath] == stamp) return
        val artifact = ModelManifest.MOBILECLIP2_S0.artifacts.firstOrNull { it.fileName == file.name } ?: return
        val ok = when (ModelArtifactVerifier.verify(file, artifact)) {
            is ModelVerification.Verified, is ModelVerification.TrustedFirstUse -> true
            is ModelVerification.UnrecordedUnpinned -> { ModelArtifactVerifier.recordFirstUse(file); true }
            else -> false
        }
        if (!ok) {
            file.delete()
            File(file.parentFile, file.name + ".sha256").delete()
            verifiedFiles.remove(file.absolutePath)
            throw IOException("Photo AI model ${file.name} failed its integrity check and was removed; it will be downloaded again.")
        }
        verifiedFiles[file.absolutePath] = stamp
    }

    fun isReady(context: Context): Boolean {
        val folder = dir(context)
        return PARTS.all { part -> File(folder, part.name).let { it.isFile && it.length() >= part.minBytes } }
    }

    /** Starts the Wi-Fi download if needed; once the files exist it just makes sure photo indexing is queued. */
    fun ensureDownload(context: Context, replace: Boolean = false, allowMobileData: Boolean = false) {
        val app = context.applicationContext
        if (!replace && isReady(app)) {
            ClipIndexScheduler.enqueue(app)
            return
        }
        val request = OneTimeWorkRequestBuilder<ClipModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .addTag("akzsa-clip-model-download")
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(
            UNIQUE_DOWNLOAD,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Deletes the downloaded photo model (and the saved error) and downloads it again. */
    fun redownload(context: Context) {
        val app = context.applicationContext
        ClipEngine.release()
        dir(app).deleteRecursively()
        ClipEngine.clearError(app)
        ensureDownload(app, replace = true)
    }
}

class ClipModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override suspend fun doWork(): Result {
        val folder = ClipModelManager.dir(applicationContext)
        val totalApprox = ClipModelManager.PARTS.sumOf { it.approxBytes }
        var doneBefore = 0L
        try {
            for (part in ClipModelManager.PARTS) {
                val finalFile = File(folder, part.name)
                if (finalFile.isFile && finalFile.length() >= part.minBytes) {
                    doneBefore += finalFile.length()
                    continue   // integrity is re-verified (and legacy installs adopted) when the model is loaded
                }
                // Resume a partly downloaded file instead of restarting 107 MB after every dropped connection.
                val partFile = File(folder, part.name + ".part")
                var existing = if (partFile.isFile) partFile.length() else 0L
                if (existing > part.approxBytes * 2) { partFile.delete(); existing = 0L }
                val requestBuilder = Request.Builder().url(ClipModelManager.BASE_URL + part.name)
                if (existing > 0) requestBuilder.header("Range", "bytes=$existing-")
                var written = existing
                http.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.code == 416) { partFile.delete(); throw IOException("Download restarted for ${part.name}") }
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} while downloading ${part.name}")
                    val body = response.body ?: throw IOException("Empty download for ${part.name}")
                    val resumed = existing > 0 && response.code == 206
                    if (!resumed) written = 0L          // server ignored the Range header: start over
                    val expected = body.contentLength()
                    val startedAt = written
                    body.byteStream().use { input ->
                        FileOutputStream(partFile, resumed).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var lastReport = 0L
                            while (true) {
                                if (isStopped) throw CancellationException("Photo AI download was stopped")
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                written += read
                                if (written - lastReport >= 512 * 1024) {
                                    lastReport = written
                                    val percent = (((doneBefore + written) * 100) / totalApprox).toInt().coerceIn(0, 99)
                                    setProgress(workDataOf(KEY_PERCENT to percent, KEY_BYTES_DONE to (doneBefore + written), KEY_BYTES_TOTAL to totalApprox))
                                }
                            }
                        }
                    }
                    if (expected > 0 && written - startedAt != expected) throw IOException("Incomplete download of ${part.name}")
                }
                if (written < part.minBytes) throw IOException("${part.name} is smaller than expected ($written bytes)")
                // Verify before the file can ever be used: size, hash (pinned or trust-on-first-use), atomic move.
                val artifact = ModelManifest.MOBILECLIP2_S0.artifacts.first { it.fileName == part.name }
                val verification = ModelArtifactVerifier.verifyAndPromote(partFile, finalFile, artifact)
                if (!verification.isUsable) throw IOException("${part.name} failed its integrity check")
                doneBefore += written
            }
            ClipIndexScheduler.enqueue(applicationContext)
            return Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            return if (runAttemptCount < 4) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to (error.message ?: "Download failed").take(200)))
        }
    }

    companion object {
        const val KEY_PERCENT = "percent"
        const val KEY_BYTES_DONE = "bytes_done"
        const val KEY_BYTES_TOTAL = "bytes_total"
        const val KEY_ERROR = "error"
    }
}
