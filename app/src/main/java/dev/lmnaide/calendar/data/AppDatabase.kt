package dev.lmnaide.calendar.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CalendarEntity::class, EventEntity::class], version = 5, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun calendarDao(): CalendarDao

    abstract fun eventDao(): EventDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "calendar.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN people TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /** Adds tasks and importance. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN kind TEXT NOT NULL DEFAULT 'EVENT'")
                db.execSQL("ALTER TABLE events ADD COLUMN completions TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE events ADD COLUMN importance TEXT")
            }
        }

        /** Adds calendar keywords for quick add. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calendars ADD COLUMN keywords TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Adds a default importance per calendar; Work starts out as High. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calendars ADD COLUMN defaultImportance TEXT")
                db.execSQL("UPDATE calendars SET defaultImportance = 'HIGH' WHERE name = 'Work'")
            }
        }
    }
}
