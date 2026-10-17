package dev.hermes.android.data

/** Reading a BLOB in small SQLite substr windows avoids Android's per-row CursorWindow limit. */
internal const val PROJECTION_CHUNK_BYTES = 64 * 1024
internal const val MAX_PROJECTION_BYTES = 16 * 1024 * 1024

internal suspend fun readProjectionBlob(row: ProjectionMetadata, chunk: suspend (Int, Int) -> ByteArray?): ByteArray {
    require(row.encryptedSize in APP_CIPHER_OVERHEAD.toLong()..MAX_PROJECTION_BYTES.toLong()) { "Encrypted checkpoint size is invalid; saved state was retained" }
    require(row.bytes == row.encryptedSize) { "Encrypted checkpoint size metadata is inconsistent; saved state was retained" }
    val result = ByteArray(row.encryptedSize.toInt())
    var offset = 0
    while (offset < result.size) {
        val count = minOf(PROJECTION_CHUNK_BYTES, result.size - offset)
        val bytes = chunk(offset + 1, count) ?: error("Encrypted checkpoint disappeared; saved state was retained")
        require(bytes.size == count) { "Encrypted checkpoint is incomplete; saved state was retained" }
        bytes.copyInto(result, offset)
        offset += count
    }
    return result
}
