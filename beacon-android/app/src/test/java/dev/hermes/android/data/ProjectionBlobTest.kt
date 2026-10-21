package dev.hermes.android.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ProjectionBlobTest {
    private fun row(size: Long, recorded: Long = size) = ProjectionMetadata("legacy-scoped-key", "scope", 123L, true, recorded, size)
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private fun encrypt(clear: ByteArray, aad: String): ByteArray {
        val iv = ByteArray(12) { (it + 1).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)); updateAAD(aad.toByteArray()) }
        return iv + cipher.doFinal(clear)
    }
    private fun decrypt(value: ByteArray, aad: String): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, value.copyOfRange(0, 12))); updateAAD(aad.toByteArray())
    }.doFinal(value.copyOfRange(12, value.size))
    @Test fun legacyCiphertextLargerThanCursorWindowAssemblesWithoutChangingAadOrBytes() = runBlocking<Unit> {
        val clear = ByteArray(3 * 1024 * 1024 + 19) { (it % 251).toByte() }
        val source = encrypt(clear, "legacy-scoped-key")
        val requested = mutableListOf<Pair<Int, Int>>()
        val restored = readProjectionBlob(row(source.size.toLong())) { start, count -> requested.add(start to count); source.copyOfRange(start - 1, start - 1 + count) }
        assertArrayEquals(source, restored)
        assertArrayEquals(clear, decrypt(restored, "legacy-scoped-key"))
        assertTrue(requested.size > 1)
        assertTrue(requested.all { it.first >= 1 && it.second in 1..PROJECTION_CHUNK_BYTES })
        assertEquals(source.size, requested.sumOf { it.second })
        assertTrue(runCatching { decrypt(restored, "new-scoped-key") }.isFailure)
    }
    @Test fun invalidLengthOrMetadataNeverAllocatesOrReadsPayload() = runBlocking<Unit> {
        for (size in listOf(-1L, 0L, 27L, MAX_PROJECTION_BYTES.toLong() + 1, Long.MAX_VALUE)) {
            var read = false
            assertTrue(runCatching { readProjectionBlob(row(size)) { _, _ -> read = true; ByteArray(0) } }.isFailure)
            assertFalse(read)
        }
        var read = false
        assertTrue(runCatching { readProjectionBlob(row(28, 29)) { _, _ -> read = true; ByteArray(28) } }.isFailure)
        assertFalse(read)
    }
    @Test fun missingShortAndOversizedChunksFailRatherThanTruncate() = runBlocking<Unit> {
        for (chunk in listOf<ByteArray?>(null, ByteArray(27), ByteArray(29))) {
            assertTrue(runCatching { readProjectionBlob(row(28)) { _, _ -> chunk } }.isFailure)
        }
    }
    @Test fun corruptionStillFailsAuthenticatedDecryption() = runBlocking<Unit> {
        val source = encrypt(ByteArray(100) { 7 }, "legacy-scoped-key").apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        val original = source.copyOf()
        val restored = readProjectionBlob(row(source.size.toLong())) { start, count -> source.copyOfRange(start - 1, start - 1 + count) }
        assertTrue(runCatching { decrypt(restored, "legacy-scoped-key") }.isFailure)
        assertArrayEquals(original, source)
    }
    @Test fun maximumPermittedSizeIsReadInBoundedChunks() = runBlocking<Unit> {
        var calls = 0
        val restored = readProjectionBlob(row(MAX_PROJECTION_BYTES.toLong())) { _, count -> calls++; ByteArray(count) }
        assertEquals(MAX_PROJECTION_BYTES, restored.size)
        assertEquals(MAX_PROJECTION_BYTES / PROJECTION_CHUNK_BYTES, calls)
    }
}
