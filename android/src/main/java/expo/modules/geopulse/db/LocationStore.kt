package expo.modules.geopulse.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Lightweight offline buffer for locations, backed by raw SQLite (no Room / no
 * annotation processor — keeps the library's build simple and dependency-light).
 *
 * Each row stores the location's JSON exactly as it is emitted to JS, so batch
 * upload is a trivial string join and no re-serialization is needed.
 */
class LocationStore(context: Context) :
  SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

  data class Record(val id: Long, val json: String)

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE $TABLE (" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
        "uuid TEXT, " +
        "timestamp INTEGER, " +
        "json TEXT NOT NULL)",
    )
    db.execSQL("CREATE INDEX idx_${TABLE}_id ON $TABLE(id)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    db.execSQL("DROP TABLE IF EXISTS $TABLE")
    onCreate(db)
  }

  private var insertsSinceTrim = 0

  @Synchronized
  fun insert(uuid: String, timestamp: Long, json: String, maxRecords: Int) {
    val db = writableDatabase
    val values = ContentValues().apply {
      put("uuid", uuid)
      put("timestamp", timestamp)
      put("json", json)
    }
    db.insert(TABLE, null, values)

    // Trimming on every insert is wasteful. Only run the DELETE periodically
    // (every TRIM_EVERY inserts) — the table can briefly exceed maxRecords by at
    // most TRIM_EVERY rows, which is harmless.
    if (maxRecords > 0 && ++insertsSinceTrim >= TRIM_EVERY) {
      insertsSinceTrim = 0
      db.execSQL(
        "DELETE FROM $TABLE WHERE id NOT IN " +
          "(SELECT id FROM $TABLE ORDER BY id DESC LIMIT $maxRecords)",
      )
    }
  }

  /**
   * Oldest-first (FIFO) up to [limit] rows. Used by the sync worker, which must
   * upload locations in the order they were recorded.
   */
  @Synchronized
  fun getAll(limit: Int): List<Record> {
    val sql = buildString {
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
    val sql = buildString {
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
    val placeholders = ids.joinToString(",") { "?" }
    val args = ids.map { it.toString() }.toTypedArray()
    writableDatabase.delete(TABLE, "id IN ($placeholders)", args)
  }

  @Synchronized
  fun deleteAll() {
    writableDatabase.delete(TABLE, null, null)
  }

  companion object {
    private const val DB_NAME = "geopulse.db"
    private const val DB_VERSION = 1
    private const val TABLE = "locations"
    private const val TRIM_EVERY = 50
  }
}
