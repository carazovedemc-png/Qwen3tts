package com.qwen.tts.android.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [VoiceProfileEntity::class, GenerationEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class QwenDatabase : RoomDatabase() {
    abstract fun qwenDao(): QwenDao

    companion object {
        @Volatile
        private var INSTANCE: QwenDatabase? = null

        fun getDatabase(context: Context): QwenDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    QwenDatabase::class.java,
                    "qwen_tts_database",
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
