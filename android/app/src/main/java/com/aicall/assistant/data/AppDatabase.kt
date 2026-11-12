package com.aicall.assistant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun fromStatus(status: CallStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): CallStatus = CallStatus.valueOf(value)
}

@Database(entities = [CallEntity::class, TranscriptSegmentEntity::class, BlockedNumberEntity::class], version = 2)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun callDao(): CallDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    // Local-first per the doc's recommendation in section 3 --
                    // no server-side persistence of transcripts by default.
                    "ai_call_assistant.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS blocked_numbers (phoneNumber TEXT NOT NULL PRIMARY KEY, addedAt INTEGER NOT NULL)"
                )
            }
        }
    }
}
