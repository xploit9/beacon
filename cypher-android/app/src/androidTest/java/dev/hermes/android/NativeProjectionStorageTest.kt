package dev.hermes.android

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.core.Scope
import dev.hermes.android.core.digest
import dev.hermes.android.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real Android SQLite + keystore regression; never touches the owner's installed database. */
class NativeProjectionStorageTest {
    private fun database(): ProjectionDb = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), ProjectionDb::class.java
    ).build()

    @Test fun existingLargeEncryptedDraftReadsMigratesAndOverwritesWithoutCursorOverflow() = runBlocking {
        val db = database()
        try {
            val cipher = AppCipher()
            val cache = ProjectionCache(db, cipher)
            val scope = Scope("https://native.example", "fixture-owner", "default", "fixture-chat")
            val kind = "draft"
            val oldKey = "${scope.key}:$kind"
            val newKey = "${scope.key}:${kind.digest()}"
            val text = "large retained draft\n".repeat(180_000)
            val ciphertext = cipher.encrypt(text.toByteArray(), oldKey)
            assertTrue(ciphertext.size > 3 * 1024 * 1024)
            db.rows().put(Projection(oldKey, scope.key, ciphertext, 1234L, true, ciphertext.size.toLong()))
            assertEquals(text, cache.read(scope, kind))
            assertNull(db.rows().get(oldKey))
            val migrated = requireNotNull(db.rows().get(newKey))
            assertEquals(1234L, migrated.updated)
            assertTrue(migrated.protected)
            assertEquals(1, db.rows().protectedRows(scope.key).size)
            assertNull(cache.read(scope.copy(owner = "another-owner"), kind))
            cache.write(scope, kind, "replacement", protected = true)
            assertEquals("replacement", cache.read(scope, kind))
            cache.write(scope, "another", "second checkpoint", protected = true)
            assertEquals(mapOf(kind to "replacement", "another" to "second checkpoint"), cache.readMany(scope, listOf(kind, "missing", "another", kind)))
            assertTrue(cache.readMany(scope.copy(owner = "other-owner"), listOf(kind, "another")).isEmpty())
            val damaged = cipher.encrypt(text.toByteArray(), newKey).apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            db.rows().put(Projection(newKey, scope.key, damaged, 1234L, true, damaged.size.toLong()))
            assertTrue(runCatching { cache.read(scope, kind) }.isFailure)
            assertNotNull("Damaged draft must remain recoverable", db.rows().get(newKey))
        } finally { db.close() }
    }

    @Test fun reviewingAnOlderReceiptCannotDeleteAReplacementCheckpoint() = runBlocking {
        val db = database()
        try {
            val cache = ProjectionCache(db, AppCipher())
            val scope = Scope("https://native.example", "fixture-owner", "default", "fixture-chat")
            cache.write(scope, "pending", "new receipt", protected = true)
            assertFalse(cache.delete(scope, "pending", "old receipt"))
            assertEquals("new receipt", cache.read(scope, "pending"))
            assertTrue(cache.delete(scope, "pending", "new receipt"))
            assertNull(cache.read(scope, "pending"))
            assertTrue(cache.delete(scope, "pending", "new receipt"))
        } finally { db.close() }
    }

    @Test fun evictionSelectsMetadataWithoutLoadingOversizedPayloadRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "native-projection-proof-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, ProjectionDb::class.java, name).build()
        try {
            val scope = Scope("https://native.example", "fixture-owner", "default")
            val cache = ProjectionCache(db, AppCipher())
            cache.write(scope, "draft", "never evict protected drafts", protected = true)
            val now = System.currentTimeMillis() + 1000
            // Real large BLOBs trigger the old SELECT * CursorWindow failure in oldest()/get().
            repeat(13) { n -> db.openHelper.writableDatabase.execSQL(
                "INSERT INTO projections (`key`,scope,encrypted,updated,protected,bytes) VALUES (?,?,zeroblob(?),?,0,?)",
                arrayOf<Any>("cache-$n", "fixture", 11 * 1024 * 1024, now + n, 11 * 1024 * 1024)
            ) }
            assertTrue(db.rows().total() > ProjectionCache.LIMIT)
            cache.write(scope, "trigger", "evict only cache rows", protected = false)
            assertTrue(db.rows().total() <= ProjectionCache.LIMIT)
            assertNull(db.rows().get("cache-0"))
            assertEquals("never evict protected drafts", cache.read(scope, "draft"))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
