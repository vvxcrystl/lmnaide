package dev.lmnaide.calendar.sync

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.lmnaide.calendar.data.AppDatabase
import java.util.UUID

/** SQLite triggers journal every write, including cascades and recurrence/task edits. */
object CloudDatabase {
    fun install(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sync_control (id INTEGER PRIMARY KEY, applying INTEGER NOT NULL, opGroup TEXT)"
        )
        ensureColumn(db, "sync_control", "opGroup", "TEXT")
        db.execSQL("INSERT OR IGNORE INTO sync_control(id,applying) VALUES(1,0)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sync_meta (kind TEXT NOT NULL, localId INTEGER NOT NULL, remoteId TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 0, generation INTEGER NOT NULL DEFAULT 1, dirty INTEGER NOT NULL DEFAULT 1, deleted INTEGER NOT NULL DEFAULT 0, opGroup TEXT, syncError TEXT, PRIMARY KEY(kind,localId), UNIQUE(kind,remoteId))"
        )
        ensureColumn(db, "sync_meta", "opGroup", "TEXT")
        ensureColumn(db, "sync_meta", "syncError", "TEXT")
        for ((kind, table) in listOf("calendar" to "calendars", "event" to "events")) {
            db.execSQL(
                "INSERT OR IGNORE INTO sync_meta(kind,localId,remoteId) SELECT '$kind',id,lower(hex(randomblob(16))) FROM $table"
            )
            db.execSQL("DROP TRIGGER IF EXISTS sync_${table}_insert")
            db.execSQL("DROP TRIGGER IF EXISTS sync_${table}_update")
            db.execSQL("DROP TRIGGER IF EXISTS sync_${table}_delete")
            db.execSQL(
                "CREATE TRIGGER sync_${table}_insert AFTER INSERT ON $table WHEN (SELECT applying FROM sync_control WHERE id=1)=0 BEGIN INSERT INTO sync_meta(kind,localId,remoteId,opGroup) VALUES('$kind',NEW.id,lower(hex(randomblob(16))),(SELECT opGroup FROM sync_control WHERE id=1)); END"
            )
            db.execSQL(
                "CREATE TRIGGER sync_${table}_update AFTER UPDATE ON $table WHEN (SELECT applying FROM sync_control WHERE id=1)=0 BEGIN UPDATE sync_meta SET dirty=1,deleted=0,generation=generation+1,opGroup=(SELECT opGroup FROM sync_control WHERE id=1),syncError=NULL WHERE kind='$kind' AND localId=NEW.id; END"
            )
            db.execSQL(
                "CREATE TRIGGER sync_${table}_delete AFTER DELETE ON $table WHEN (SELECT applying FROM sync_control WHERE id=1)=0 BEGIN UPDATE sync_meta SET dirty=1,deleted=1,generation=generation+1,opGroup=(SELECT opGroup FROM sync_control WHERE id=1),syncError=NULL WHERE kind='$kind' AND localId=OLD.id; END"
            )
        }
    }

    /** Keeps multi-row recurrence edits together so sync can never split their operation group. */
    suspend fun <T> withOperationGroup(db: AppDatabase, block: suspend () -> T): T = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("UPDATE sync_control SET opGroup=? WHERE id=1", arrayOf<Any>(UUID.randomUUID().toString()))
        try {
            block()
        } finally {
            sql.execSQL("UPDATE sync_control SET opGroup=NULL WHERE id=1")
        }
    }

    private fun ensureColumn(db: SupportSQLiteDatabase, table: String, column: String, type: String) {
        val present = db.query("PRAGMA table_info($table)").use { rows ->
            val nameColumn = rows.getColumnIndex("name")
            while (rows.moveToNext()) if (rows.getString(nameColumn) == column) return@use true
            false
        }
        if (!present) db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
    }
}
