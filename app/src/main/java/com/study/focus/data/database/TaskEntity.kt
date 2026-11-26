package com.study.focus.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "tasks", indices = [Index(value = ["isDone"]), Index(value = ["subject"])])
data class TaskEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val subject: String,
    val priority: Int,
    val dueAtMillis: Long,
    val isDone: Boolean
)

@Entity(tableName = "focus_sessions", indices = [Index(value = ["endedAtMillis"]), Index(value = ["subject"])])
data class FocusSessionEntity(
    @PrimaryKey val id: String,
    val subject: String,
    val durationSeconds: Int,
    val endedAtMillis: Long,
    val isAssignment: Boolean = false
)