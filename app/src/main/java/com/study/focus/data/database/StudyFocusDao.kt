package com.study.focus.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface StudyFocusDao {
    @Query("SELECT * FROM tasks ORDER BY CASE WHEN dueAtMillis = 0 THEN 1 ELSE 0 END, dueAtMillis ASC, id ASC")
    suspend fun getTasks(): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTasks(tasks: List<TaskEntity>)

    @Query("SELECT * FROM focus_sessions ORDER BY endedAtMillis DESC")
    suspend fun getSessions(): List<FocusSessionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSessions(sessions: List<FocusSessionEntity>)

    @Query("SELECT COUNT(*) FROM tasks")
    suspend fun taskCount(): Int

    @Query("SELECT COUNT(*) FROM focus_sessions")
    suspend fun sessionCount(): Int
}