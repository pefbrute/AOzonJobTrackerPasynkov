package com.example.aozonjobtrackerpasynkov.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room Database для хранения статистики проверок.
 */
@Database(entities = [CheckRecord::class], version = 1, exportSchema = false)
abstract class StatsDatabase : RoomDatabase() {
    
    abstract fun statsDao(): StatsDao
    
    companion object {
        @Volatile
        private var INSTANCE: StatsDatabase? = null
        
        fun getInstance(context: Context): StatsDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    StatsDatabase::class.java,
                    "ozon_job_stats.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
