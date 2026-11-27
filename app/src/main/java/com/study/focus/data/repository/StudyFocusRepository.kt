package com.study.focus.data.repository

import com.study.focus.data.database.FocusSessionEntity
import com.study.focus.data.database.StudyFocusDao
import com.study.focus.data.database.TaskEntity

/**
 * Small persistence boundary for feature modules.
 * UI code should call these suspend functions from a lifecycle-aware coroutine.
 */
class StudyFocusRepository(private val dao: StudyFocusDao) {
    suspend fun tasks(): List<TaskEntity> = dao.getTasks()

    suspend fun saveTask(task: TaskEntity) {
        dao.putTasks(listOf(task))
    }

    suspend fun saveTasks(tasks: List<TaskEntity>) {
        dao.replaceTasks(tasks)
    }

    suspend fun deleteTask(id: Long) {
        dao.deleteTask(id)
    }

    suspend fun setTaskDone(id: Long, done: Boolean) {
        dao.setTaskDone(id, done)
    }

    suspend fun sessions(): List<FocusSessionEntity> = dao.getSessions()

    suspend fun addSession(session: FocusSessionEntity) {
        dao.insertSessions(listOf(session))
        dao.pruneSessions()
    }

    suspend fun counts(): Pair<Int, Int> = dao.taskCount() to dao.sessionCount()
}