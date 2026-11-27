package com.study.focus.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TaskEntity::class, FocusSessionEntity::class],
    version = 1,
    exportSchema = true
)
abstract class StudyFocusDatabase : RoomDatabase() {
    abstract fun studyFocusDao(): StudyFocusDao

    companion object {
        @Volatile
        private var INSTANCE: StudyFocusDatabase? = null

        fun getInstance(context: Context): StudyFocusDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StudyFocusDatabase::class.java,
                    "studyfocus.db"
                ).build().also { INSTANCE = it }
            }
    }
}