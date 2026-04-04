package com.gratus.usagepeek.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AppEntity::class, DailyTotalEntity::class], // Removed SessionEntity
    version = 2, // Bumped to 2
    exportSchema = false
)
abstract class UsageDb : RoomDatabase() {
    abstract fun dao(): UsageDao

    companion object {
        @Volatile private var INSTANCE: UsageDb? = null

        fun get(context: Context): UsageDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    UsageDb::class.java, "usagepeek.db"
                )
                    .fallbackToDestructiveMigration() // Clears old data to match new schema
                    .build().also { INSTANCE = it }
            }
    }
}