package com.nefeon.akzsa.recognition

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * One downloadable file. [sha256] == null means "not yet pinned" (see docs/MODEL_MANIFEST.md): such an
 * artifact is protected by trust-on-first-use (hash recorded at download time and re-checked at load)
 * and MUST be pinned by running scripts/pin-model-hashes before a public release.
 */
data class ModelArtifact(
    val fileName: String,
    val url: String,
    val approxBytes: Long,
    val minBytes: Long,
    val exactBytes: Long? = null,
    val sha256: String? = null,
)

data class ModelSpec(
    val id: String,
    /** Immutable revision (commit hash) once pinned; "UNPINNED-main" while the URL still tracks a branch. */
    val revision: String,
    val embeddingDim: Int,
    val runtime: String,
    val license: String,
    val source: String,
    val artifacts: List<ModelArtifact>,
) {
    /** Stored with every vector so vectors from different models/revisions/dimensions are never mixed. */
    val vectorTag: String get() = "$id@$revision/d$embeddingDim"
    val isFullyPinned: Boolean get() = !revision.startsWith("UNPINNED") && artifacts.all { it.sha256 != null }
}

object ModelManifest {
    val MOBILECLIP2_S0 = ModelSpec(
        id = "mobileclip2-s0-onnx",
        revision = "99ca0cde6c76c0c56fcfeace07eb3a340ba17fc0",
        embeddingDim = 512,
        runtime = "onnxruntime-android",
        license = "See model card (Apple MobileCLIP2); verify redistribution terms before release",
        source = "https://huggingface.co/felixhrdyn/mobileclip2-s0-onnx",
        artifacts = listOf(
            ModelArtifact("vision_model.onnx", "https://huggingface.co/felixhrdyn/mobileclip2-s0-onnx/resolve/99ca0cde6c76c0c56fcfeace07eb3a340ba17fc0/vision_model.onnx", 45_500_000L, 40L * 1024 * 1024),
            ModelArtifact("text_model_quantized.onnx", "https://huggingface.co/felixhrdyn/mobileclip2-s0-onnx/resolve/99ca0cde6c76c0c56fcfeace07eb3a340ba17fc0/text_model_quantized.onnx", 64_300_000L, 55L * 1024 * 1024),
            ModelArtifact("tokenizer.json", "https://huggingface.co/felixhrdyn/mobileclip2-s0-onnx/resolve/99ca0cde6c76c0c56fcfeace07eb3a340ba17fc0/tokenizer.json", 2_200_000L, 1_500_000L),
        ),
    )

    val ALL = listOf(MOBILECLIP2_S0)
}

sealed interface ModelVerification {
    /** Hash and size match the pinned manifest values. */
    data class Verified(val sha256: String) : ModelVerification
    /** No pinned hash; the file matches the hash recorded when it was downloaded (weaker guarantee). */
    data class TrustedFirstUse(val sha256: String) : ModelVerification
    /** No pinned hash and no record yet; the caller may adopt it with [ModelArtifactVerifier.recordFirstUse]. */
    data class UnrecordedUnpinned(val sha256: String) : ModelVerification
    data object Missing : ModelVerification
    data class TooSmall(val actual: Long, val min: Long) : ModelVerification
    data class SizeMismatch(val actual: Long, val expected: Long) : ModelVerification
    data class HashMismatch(val actual: String, val expected: String) : ModelVerification

    val isUsable: Boolean get() = this is Verified || this is TrustedFirstUse
}

object ModelArtifactVerifier {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sidecar(file: File) = File(file.parentFile, file.name + ".sha256")

    /** Writes the trust-on-first-use record (hash + size) next to an unpinned artifact. */
    fun recordFirstUse(file: File): String {
        val hash = sha256(file)
        sidecar(file).writeText("$hash ${file.length()}\n")
        return hash
    }

    fun verify(file: File, artifact: ModelArtifact): ModelVerification {
        if (!file.isFile) return ModelVerification.Missing
        val size = file.length()
        if (size < artifact.minBytes) return ModelVerification.TooSmall(size, artifact.minBytes)
        artifact.exactBytes?.let { if (size != it) return ModelVerification.SizeMismatch(size, it) }
        val actual = sha256(file)
        artifact.sha256?.let { expected ->
            return if (actual.equals(expected, ignoreCase = true)) ModelVerification.Verified(actual)
            else ModelVerification.HashMismatch(actual, expected)
        }
        val record = sidecar(file)
        if (!record.isFile) return ModelVerification.UnrecordedUnpinned(actual)
        val parts = record.readText().trim().split(' ')
        val recordedHash = parts.getOrNull(0).orEmpty()
        val recordedSize = parts.getOrNull(1)?.toLongOrNull()
        if (!actual.equals(recordedHash, ignoreCase = true) || (recordedSize != null && recordedSize != size)) {
            return ModelVerification.HashMismatch(actual, recordedHash)
        }
        return ModelVerification.TrustedFirstUse(actual)
    }

    /**
     * Verifies a fully downloaded temp file and only then moves it into place. The destination never
     * contains a partial or corrupt file. On any failure the temp file is deleted and nothing is promoted.
     */
    fun verifyAndPromote(temp: File, destination: File, artifact: ModelArtifact): ModelVerification {
        val result = verify(temp, artifact)
        val acceptable = result.isUsable || result is ModelVerification.UnrecordedUnpinned
        if (!acceptable) {
            temp.delete()
            return result
        }
        try {
            try {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
        if (artifact.sha256 == null) recordFirstUse(destination)
        return if (artifact.sha256 != null) ModelVerification.Verified(sha256(destination)) else ModelVerification.TrustedFirstUse(sha256(destination))
    }
}

/** Decides whether stored vectors may be searched with the currently active model. */
sealed interface VectorCompatibility {
    data object Compatible : VectorCompatibility
    data class ModelChanged(val stored: String, val active: String) : VectorCompatibility
    data class DimensionMismatch(val stored: Int, val active: Int) : VectorCompatibility
    data object NoVectors : VectorCompatibility

    companion object {
        fun check(storedTag: String?, storedDim: Int?, active: ModelSpec): VectorCompatibility {
            if (storedTag == null) return NoVectors
            if (storedDim != null && storedDim != active.embeddingDim) return DimensionMismatch(storedDim, active.embeddingDim)
            if (storedTag != active.vectorTag) return ModelChanged(storedTag, active.vectorTag)
            return Compatible
        }
    }
}
