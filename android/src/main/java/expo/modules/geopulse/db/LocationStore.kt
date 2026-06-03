package expo.modules.geopulse.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import expo.modules.geopulse.core.GeoPulseController

/**
 * Lightweight offline buffer for locations, backed by raw SQLite (no Room / no
 * annotation processor — keeps the library's build simple and dependency-light).
 *
 * Each row stores the location's JSON exactly as it is emitted to JS, so batch
 * upload is a trivial string join and no re-serialization is needed.
 */
class LocationStore private constructor(
  context: Context,
) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
  data class Record(
    val id: Long,
    val json: String,
  )

  // Fresh installs land directly at the latest schema. SQLiteOpenHelper tracks the
  // version via PRAGMA user_version (the DB_VERSION arg), so onCreate/onUpgrade are
  // driven by it.
  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE $TABLE (" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
        "uuid TEXT, " +
        "timestamp INTEGER, " +
        "json TEXT NOT NULL)",
    )
    db.execSQL("CREATE INDEX idx_${TABLE}_id ON $TABLE(id)")
    // v2: buffer is idempotent on uuid (mirrors the server's dedup).
    db.execSQL("CREATE UNIQUE INDEX idx_${TABLE}_uuid ON $TABLE(uuid)")
  }

  /**
   * Incremental, data-preserving migrations (NOT a drop-and-recreate, which would
   * lose un-synced buffered points on a version bump). Runs each step in order
   * from [oldVersion] to [newVersion]. SQLiteOpenHelper wraps this in a
   * transaction, so a thrown error rolls back: the DB stays at the old version
   * (never silently wiped) and the migration is retried on the next open.
   */
  override fun onUpgrade(
    db: SQLiteDatabase,
    oldVersion: Int,
    newVersion: Int,
  ) {
    try {
      var v = oldVersion
      while (v < newVersion) {
        when (v) {
          1 -> migrateTo2(db)
          // future: 2 -> migrateTo3(db)
        }
        v++
      }
    } catch (t: Throwable) {
      // Surface the failure (best-effort; emit() falls back to the headless task),
      // then rethrow so SQLiteOpenHelper rolls back rather than committing a
      // half-migrated schema or wiping data.
      runCatching {
        GeoPulseController.emit(
          "onError",
          mapOf(
            "code" to "DB_MIGRATION_FAILED",
            "message" to "Location DB migration $oldVersion -> $newVersion failed: ${t.message}",
            "fromVersion" to oldVersion,
            "toVersion" to newVersion,
          ),
        )
      }
      throw t
    }
  }

  /**
   * v1 -> v2: make the local buffer idempotent on `uuid` (the deferred half of
   * P0-1). De-dupe any pre-existing rows (keep the newest per uuid; NULL/empty
   * uuids are left alone), then add the UNIQUE index. Buffered points survive.
   */
  private fun migrateTo2(db: SQLiteDatabase) {
    db.execSQL(
      "DELETE FROM $TABLE WHERE uuid IS NOT NULL AND uuid <> '' AND id NOT IN " +
        "(SELECT MAX(id) FROM $TABLE WHERE uuid IS NOT NULL AND uuid <> '' GROUP BY uuid)",
    )
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_${TABLE}_uuid ON $TABLE(uuid)")
  }

  private var insertsSinceTrim = 0

  /**
   * Insert a fix, enforcing the buffer cap [maxRecords] per [dropOldest]:
   * - `dropOldest = true` (default): insert, then periodically trim to the newest
   *   [maxRecords] rows (oldest dropped) — right for route tracking.
   * - `dropOldest = false` (drop-newest): if the buffer is already full, drop the
   *   incoming fix instead of inserting it.
   *
   * Returns how many points this call dropped (0 while under the cap), so the
   * caller can surface a `BUFFER_OVERFLOW` event.
   */
  @Synchronized
  fun insert(
    uuid: String,
    timestamp: Long,
    json: String,
    maxRecords: Int,
    dropOldest: Boolean,
  ): Int {
    val db = writableDatabase
    if (maxRecords > 0 && !dropOldest && count() >= maxRecords) {
      // drop-newest: the buffer is full, so discard the incoming fix.
      return 1
    }
    val values =
      ContentValues().apply {
        put("uuid", uuid)
        put("timestamp", timestamp)
        put("json", json)
      }
    // OR IGNORE: with the UNIQUE(uuid) index (schema v2) a re-used uuid is skipped
    // rather than throwing, so the local buffer is idempotent like the server.
    db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)

    // Trimming on every insert is wasteful. Only run the DELETE periodically
    // (every TRIM_EVERY inserts) — the table can briefly exceed maxRecords by at
    // most TRIM_EVERY rows, which is harmless. delete() returns the row count so
    // the caller knows how many points were dropped.
    if (maxRecords > 0 && dropOldest && ++insertsSinceTrim >= TRIM_EVERY) {
      insertsSinceTrim = 0
      return db.delete(
        TABLE,
        "id NOT IN (SELECT id FROM $TABLE ORDER BY id DESC LIMIT $maxRecords)",
        null,
      )
    }
    return 0
  }

  /**
   * Oldest-first (FIFO) up to [limit] rows. Used by the sync worker, which must
   * upload locations in the order they were recorded.
   */
  @Synchronized
  fun getAll(limit: Int): List<Record> {
    val sql =
      buildString {
        append("SELECT id, json FROM $TABLE ORDER BY id ASC")
        if (limit > 0) append(" LIMIT $limit")
      }
    val records = mutableListOf<Record>()
    readableDatabase.rawQuery(sql, null).use { cursor ->
      while (cursor.moveToNext()) {
        records.add(Record(cursor.getLong(0), cursor.getString(1)))
      }
    }
    return records
  }

  /**
   * The most recent [limit] rows, returned in chronological (oldest-first) order.
   * Used by `getLocations()` where callers expect the *latest* track, not the
   * oldest backlog.
   */
  @Synchronized
  fun getLatest(limit: Int): List<Record> {
    val sql =
      buildString {
        append("SELECT id, json FROM $TABLE ORDER BY id DESC")
        if (limit > 0) append(" LIMIT $limit")
      }
    val records = mutableListOf<Record>()
    readableDatabase.rawQuery(sql, null).use { cursor ->
      while (cursor.moveToNext()) {
        records.add(Record(cursor.getLong(0), cursor.getString(1)))
      }
    }
    // Query was newest-first for the LIMIT; flip back to chronological.
    records.reverse()
    return records
  }

  @Synchronized
  fun count(): Int {
    readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { cursor ->
      return if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }
  }

  @Synchronized
  fun deleteByIds(ids: List<Long>) {
    if (ids.isEmpty()) return
    val db = writableDatabase
    // SQLite caps bound variables (SQLITE_MAX_VARIABLE_NUMBER — 999 on older
    // Android), so a large maxBatchSize would overflow a single IN (...) delete.
    // Chunk to stay well under the limit.
    ids.chunked(DELETE_CHUNK).forEach { chunk ->
      val placeholders = chunk.joinToString(",") { "?" }
      val args = chunk.map { it.toString() }.toTypedArray()
      db.delete(TABLE, "id IN ($placeholders)", args)
    }
  }

  @Synchronized
  fun deleteAll() {
    writableDatabase.delete(TABLE, null, null)
  }

  companion object {
    private const val DB_NAME = "geopulse.db"
    private const val DB_VERSION = 2
    private const val TABLE = "locations"
    private const val TRIM_EVERY = 50
    private const val DELETE_CHUNK = 500 // stay under SQLite's ~999 variable cap

    @Volatile private var instance: LocationStore? = null

    /**
     * Process-wide singleton. Two separate [SQLiteOpenHelper] instances would
     * each open their own connection, so the per-method `@Synchronized` (which
     * locks on `this`) would NOT mutually exclude the controller's IO executor
     * from the WorkManager sync thread — causing SQLITE_BUSY and lost writes. A
     * single shared helper makes `@Synchronized` actually serialize access.
     */
    fun getInstance(context: Context): LocationStore =
      instance ?: synchronized(this) {
        instance ?: LocationStore(context.applicationContext).also { instance = it }
      }

    /**
     * Serializes a full read→upload→delete sync cycle across the manual `sync()`
     * path and the WorkManager [SyncWorker], so they can't both claim and upload
     * the same rows (duplicate uploads).
     */
    val syncLock = Any()
  }
}
