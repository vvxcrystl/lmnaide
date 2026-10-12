package dev.lmnaide.calendar.sync

import androidx.sqlite.db.SupportSQLiteDatabase

/** SQLite triggers journal every write, including cascades and recurrence/task edits. */
object CloudDatabase {
    fun install(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sync_control (id INTEGER PRIMARY KEY, applying INTEGER NOT NULL)"
        )
        db.execSQL("INSERT OR IGNORE INTO sync_control VALUES(1,0)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sync_meta (kind TEXT NOT NULL, localId INTEGER NOT NULL, remoteId TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 0, generation INTEGER NOT NULL DEFAULT 1, dirty INTEGER NOT NULL DEFAULT 1, deleted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(kind,localId), UNIQUE(kind,remoteId))"
        )
        for ((kind, table) in listOf("calendar" to "calendars", "event" to "events")) {
            db.execSQL(
                "INSERT OR IGNORE INTO sync_meta(kind,localId,remoteId) SELECT '$kind',id,lower(hex(randomblob(16))) FROM $table"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS sync_${table}_insert AFTER INSERT ON $table WHEN (SELECT applying FROM sync_control WHERE id=1)=0 BEGIN INSERT INTO sync_meta(kind,localId,remoteId) VALUES('$kind',NEW.id,lower(hex(randomblob(16)))); END"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS sync_${table}_update AFTER UPDATE ON $table WHEN (SELECT applying FROM sync_control WHERE id=1)=0 BEGIN UPDATE sync_meta SET dirty=1,generation=generation+1 WHERE kind='$kind' AND localId=NEW.id; END"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS sync_${table}_delete AFTER DELETE ON $table WHEN (SELECT applying FROM sync_control WHERE id=1)=0 BEGIN UPDATE sync_meta SET dirty=1,deleted=1,generation=generation+1 WHERE kind='$kind' AND localId=OLD.id; END"
            )
        }
    }
}
