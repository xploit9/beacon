package dev.hermes.android.data

import android.content.Context
import androidx.room.*
import dev.hermes.android.core.Scope
import dev.hermes.android.core.digest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Entity(tableName = "projections", indices = [Index("scope"), Index("updated")])
data class Projection(@PrimaryKey val key: String, val scope: String, val encrypted: ByteArray, val updated: Long, val protected: Boolean, val bytes: Long)

data class ProjectionMetadata(val key: String, val scope: String, val updated: Long, val protected: Boolean, val bytes: Long, val encryptedSize: Long)

@Dao interface ProjectionDao {
    @Query("SELECT `key`, scope, updated, protected, bytes, length(encrypted) AS encryptedSize FROM projections WHERE `key`=:key") suspend fun get(key: String): ProjectionMetadata?
    @Query("SELECT substr(encrypted, :start, :count) FROM projections WHERE `key`=:key") suspend fun encryptedChunk(key: String, start: Int, count: Int): ByteArray?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: Projection)
    @Query("DELETE FROM projections WHERE `key`=:key") suspend fun delete(key: String)
    @Query("DELETE FROM projections WHERE scope=:scope") suspend fun clearScope(scope: String)
    @Query("DELETE FROM projections WHERE protected=0 AND updated<:before") suspend fun expire(before: Long)
    @Query("SELECT COALESCE(SUM(length(encrypted)),0) FROM projections") suspend fun total(): Long
    @Query("SELECT `key`, scope, updated, protected, bytes, length(encrypted) AS encryptedSize FROM projections WHERE protected=0 ORDER BY updated ASC LIMIT 1") suspend fun oldest(): ProjectionMetadata?
    @Query("SELECT `key`, scope, updated, protected, bytes, length(encrypted) AS encryptedSize FROM projections WHERE scope=:scope AND protected=1") suspend fun protectedRows(scope: String): List<ProjectionMetadata>
}
@Database(entities = [Projection::class], version = 1, exportSchema = false)
abstract class ProjectionDb : RoomDatabase() { abstract fun rows(): ProjectionDao }

class ProjectionCache internal constructor(private val db: ProjectionDb, private val cipher: AppCipher) {
    constructor(context: Context, cipher: AppCipher) : this(Room.databaseBuilder(context, ProjectionDb::class.java, "hermes-projections.db").build(), cipher)
    private val lock = Mutex()
    private fun key(scope: Scope, kind: String) = "${scope.key}:${kind.digest()}"
    private fun legacyKey(scope: Scope, kind: String) = "${scope.key}:$kind"
    suspend fun read(scope: Scope, kind: String): String? = readMany(scope, listOf(kind))[kind]
    suspend fun readMigrating(scope: Scope, kind: String, legacyKind: String): String? = withContext(Dispatchers.IO) {
        require(kind != legacyKind)
        lock.withLock {
            db.withTransaction {
                val rows = db.rows()
                val newKey = key(scope, kind)
                val oldKey = key(scope, legacyKind)
                suspend fun read(row: ProjectionMetadata, target: String): String? {
                    if (!row.protected && row.updated < System.currentTimeMillis() - MAX_AGE) { rows.delete(row.key); return null }
                    require(row.scope == scope.key) { "Checkpoint belongs to another scope; saved state was retained" }
                    val encrypted = readProjectionBlob(row) { start, count -> rows.encryptedChunk(row.key, start, count) }
                    val clear = cipher.decrypt(encrypted, row.key)
                    if (row.key != target) {
                        val migrated = cipher.encrypt(clear, target)
                        rows.put(Projection(target, row.scope, migrated, row.updated, row.protected, migrated.size.toLong()))
                        rows.delete(row.key)
                    }
                    return String(clear, Charsets.UTF_8)
                }
                val current = (rows.get(newKey) ?: rows.get(legacyKey(scope, kind)))?.let { read(it, newKey) }
                if (current != null) {
                    rows.delete(oldKey); rows.delete(legacyKey(scope, legacyKind))
                    current
                } else {
                    val old = (rows.get(oldKey) ?: rows.get(legacyKey(scope, legacyKind)))?.let { read(it, newKey) }
                    rows.delete(oldKey); rows.delete(legacyKey(scope, legacyKind))
                    old
                }
            }
        }
    }
    suspend fun readMany(scope: Scope, kinds: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        lock.withLock {
            db.withTransaction {
                val rows = db.rows()
                buildMap {
                    for (kind in kinds.distinct()) {
                        val id = key(scope, kind)
                        val row = rows.get(id) ?: rows.get(legacyKey(scope, kind)) ?: continue
                        if (!row.protected && row.updated < System.currentTimeMillis() - MAX_AGE) { rows.delete(row.key); continue }
                        require(row.scope == scope.key) { "Checkpoint belongs to another scope; saved state was retained" }
                        val encryptedValue = readProjectionBlob(row) { start, count -> rows.encryptedChunk(row.key, start, count) }
                        val clear = cipher.decrypt(encryptedValue, row.key)
                        if (row.key != id) {
                            // Preserve checkpoints and their age while removing path/kind metadata from old keys.
                            val encrypted = cipher.encrypt(clear, id)
                            rows.put(Projection(id, row.scope, encrypted, row.updated, row.protected, encrypted.size.toLong()))
                            rows.delete(row.key)
                        }
                        put(kind, String(clear, Charsets.UTF_8))
                    }
                }
            }
        }
    }
    suspend fun write(scope: Scope, kind: String, value: String, protected: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock {
            val id = key(scope, kind)
            val encrypted = cipher.encrypt(value.toByteArray(), id)
            require(encrypted.size <= MAX_PROJECTION_BYTES) { "Local checkpoint exceeds 16 MiB" }
            db.withTransaction {
                val rows = db.rows()
                rows.delete(legacyKey(scope, kind))
                rows.expire(System.currentTimeMillis() - MAX_AGE)
                var oldSize = rows.get(id)?.encryptedSize ?: 0
                var totalSize = rows.total()
                while (totalSize - oldSize + encrypted.size > LIMIT) {
                    val oldest = rows.oldest() ?: error("Protected drafts fill local storage. Export or finish a draft before continuing.")
                    rows.delete(oldest.key)
                    totalSize -= oldest.encryptedSize
                    if (oldest.key == id) oldSize = 0
                }
                rows.put(Projection(id, scope.key, encrypted, System.currentTimeMillis(), protected, encrypted.size.toLong()))
            }
        }
    }
    suspend fun delete(scope: Scope, kind: String) { delete(scope, kind, null) }
    suspend fun delete(scope: Scope, kind: String, expected: String?): Boolean = withContext(Dispatchers.IO) { lock.withLock { db.withTransaction {
        val rows = db.rows()
        if (expected != null) {
            val row = rows.get(key(scope, kind)) ?: rows.get(legacyKey(scope, kind)) ?: return@withTransaction true
            require(row.scope == scope.key) { "Checkpoint belongs to another scope; saved state was retained" }
            val encrypted = readProjectionBlob(row) { start, count -> rows.encryptedChunk(row.key, start, count) }
            if (String(cipher.decrypt(encrypted, row.key), Charsets.UTF_8) != expected) return@withTransaction false
        }
        rows.delete(key(scope, kind)); rows.delete(legacyKey(scope, kind)); true
    } } }
    suspend fun trim() = withContext(Dispatchers.IO) { lock.withLock { db.rows().expire(System.currentTimeMillis() - MAX_AGE) } }
    companion object { const val LIMIT = 128L * 1024 * 1024; private const val MAX_AGE = 2 * 24 * 60 * 60 * 1000L }
}
