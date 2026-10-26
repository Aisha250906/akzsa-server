package com.nefeon.akzsa.recognition

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelArtifactVerifierTest {
    private lateinit var dir: File
    @Before fun setUp() { dir = Files.createTempDirectory("akzsa-model").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun file(name: String, bytes: ByteArray) = File(dir, name).also { it.writeBytes(bytes) }
    private val payload = ByteArray(4096) { (it % 251).toByte() }
    private val payloadSha = ModelArtifactVerifier.sha256(File.createTempFile("payload", ".bin").also { it.writeBytes(payload); it.deleteOnExit() })

    private fun art(sha: String? = payloadSha, min: Long = 1000, exact: Long? = null) =
        ModelArtifact("m.bin", "https://example.invalid/m.bin", 4096, min, exact, sha)

    @Test fun sha256MatchesKnownVector() {
        val f = file("abc", "abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ModelArtifactVerifier.sha256(f))
    }

    @Test fun pinnedHashVerifies() = assertTrue(ModelArtifactVerifier.verify(file("m.bin", payload), art()) is ModelVerification.Verified)

    @Test fun corruptedByteIsHashMismatch() {
        val bad = payload.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val r = ModelArtifactVerifier.verify(file("m.bin", bad), art())
        assertTrue(r is ModelVerification.HashMismatch); assertFalse(r.isUsable)
    }

    @Test fun missingTruncatedAndWrongSizeAreRejected() {
        assertEquals(ModelVerification.Missing, ModelArtifactVerifier.verify(File(dir, "nope"), art()))
        assertTrue(ModelArtifactVerifier.verify(file("t.bin", ByteArray(10)), art()) is ModelVerification.TooSmall)
        assertTrue(ModelArtifactVerifier.verify(file("s.bin", payload), art(exact = 9999)) is ModelVerification.SizeMismatch)
    }

    @Test fun unpinnedArtifactUsesTrustOnFirstUse() {
        val f = file("m.bin", payload)
        val a = art(sha = null)
        assertTrue(ModelArtifactVerifier.verify(f, a) is ModelVerification.UnrecordedUnpinned)
        ModelArtifactVerifier.recordFirstUse(f)
        assertTrue(ModelArtifactVerifier.verify(f, a) is ModelVerification.TrustedFirstUse)
        // later tampering/bit-rot is detected
        f.writeBytes(payload.copyOf().also { it[0] = 99 })
        assertTrue(ModelArtifactVerifier.verify(f, a) is ModelVerification.HashMismatch)
    }

    @Test fun promoteMovesVerifiedFileAtomicallyAndLeavesNoPartial() {
        val temp = file("m.bin.part", payload); val dest = File(dir, "m.bin")
        val r = ModelArtifactVerifier.verifyAndPromote(temp, dest, art())
        assertTrue(r is ModelVerification.Verified)
        assertTrue(dest.isFile); assertFalse(temp.exists())
    }

    @Test fun promoteNeverInstallsACorruptFileAndKeepsExistingGoodOne() {
        val dest = file("m.bin", payload)                           // existing good model
        val temp = file("m.bin.part", ByteArray(4096) { 7 })        // corrupt new download
        val r = ModelArtifactVerifier.verifyAndPromote(temp, dest, art())
        assertTrue(r is ModelVerification.HashMismatch)
        assertFalse("corrupt temp must be deleted", temp.exists())
        assertEquals("existing model untouched", payloadSha, ModelArtifactVerifier.sha256(dest))
    }

    @Test fun promoteOfUnpinnedArtifactRecordsFirstUseHash() {
        val temp = file("u.bin.part", payload); val dest = File(dir, "u.bin")
        val a = ModelArtifact("u.bin", "https://example.invalid/u", 4096, 1000)
        assertTrue(ModelArtifactVerifier.verifyAndPromote(temp, dest, a) is ModelVerification.TrustedFirstUse)
        assertTrue(File(dir, "u.bin.sha256").isFile)
    }

    // ---- vector/model compatibility ----

    @Test fun vectorCompatibilityRules() {
        val spec = ModelManifest.MOBILECLIP2_S0
        assertEquals(VectorCompatibility.NoVectors, VectorCompatibility.check(null, null, spec))
        assertEquals(VectorCompatibility.Compatible, VectorCompatibility.check(spec.vectorTag, 512, spec))
        assertTrue(VectorCompatibility.check(spec.vectorTag, 768, spec) is VectorCompatibility.DimensionMismatch)
        assertTrue(VectorCompatibility.check("mobileclip2-s0-onnx@abc123/d512", 512, spec) is VectorCompatibility.ModelChanged)
    }

    @Test fun manifestIsInternallyConsistent() {
        for (spec in ModelManifest.ALL) {
            assertTrue(spec.embeddingDim > 0 && spec.artifacts.isNotEmpty())
            for (a in spec.artifacts) {
                assertTrue(a.url.startsWith("https://"))
                assertTrue(a.minBytes > 0 && a.approxBytes >= a.minBytes)
                a.sha256?.let { assertTrue(it.matches(Regex("[0-9a-f]{64}"))) }
            }
        }
        assertEquals("mobileclip2-s0-onnx@99ca0cde6c76c0c56fcfeace07eb3a340ba17fc0/d512", ModelManifest.MOBILECLIP2_S0.vectorTag)
        // Honest status: CLIP artifacts are not hash-pinned yet; this test fails loudly once someone pins them
        // so that docs/MODEL_MANIFEST.md is updated too.
        assertFalse(ModelManifest.MOBILECLIP2_S0.isFullyPinned)
    }
}
