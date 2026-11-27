package com.study.focus.data.backup

import com.study.focus.data.database.FocusSessionEntity
import com.study.focus.data.database.StudyFocusDao
import com.study.focus.data.database.TaskEntity
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Versioned offline JSON backup. Validation finishes before the database is changed. */
object BackupManager {
    private const val FORMAT = "studyfocus-backup"
    private const val VERSION = 1
    private const val MAX_BACKUP_CHARS = 10 * 1024 * 1024
    private const val MAX_TASKS = 10_000
    private const val MAX_SESSIONS = 500

    data class RestoreReport(val taskCount: Int, val sessionCount: Int)

    fun createBackup(
        tasks: List<TaskEntity>,
        sessions: List<FocusSessionEntity>,
        createdAtMillis: Long = System.currentTimeMillis()
    ): String {
        require(tasks.size <= MAX_TASKS) { "Too many tasks to export." }
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAtMillis", createdAtMillis.coerceAtLeast(0L))
            .put("tasks", JSONArray().apply {
                tasks.forEach { task ->
                    put(JSONObject()
                        .put("id", task.id)
                        .put("title", task.title)
                        .put("subject", task.subject)
                        .put("priority", task.priority)
                        .put("dueAtMillis", task.dueAtMillis)
                        .put("isDone", task.isDone))
                }
            })
            .put("sessions", JSONArray().apply {
                sessions.sortedByDescending { it.endedAtMillis }.take(MAX_SESSIONS).forEach { session ->
                    put(JSONObject()
                        .put("id", session.id)
                        .put("subject", session.subject)
                        .put("durationSeconds", session.durationSeconds)
                        .put("endedAtMillis", session.endedAtMillis)
                        .put("isAssignment", session.isAssignment))
                }
            })
        val json = root.toString(2)
        require(json.length <= MAX_BACKUP_CHARS) { "Backup is too large to export." }
        return json
    }

    suspend fun restoreBackup(raw: String, dao: StudyFocusDao): RestoreReport {
        require(raw.isNotBlank()) { "The selected backup file is empty." }
        require(raw.length <= MAX_BACKUP_CHARS) { "The selected backup file is too large." }
        val root = try {
            JSONObject(raw)
        } catch (error: JSONException) {
            throw IllegalArgumentException("This file is not a valid StudyFocus backup.", error)
        }

        require(root.optString("format") == FORMAT) { "This file was not created by StudyFocus." }
        require(root.optInt("version", -1) == VERSION) { "This backup version is not supported by this app." }
        val taskArray = root.optJSONArray("tasks")
            ?: throw IllegalArgumentException("The backup is missing its task list.")
        val sessionArray = root.optJSONArray("sessions")
            ?: throw IllegalArgumentException("The backup is missing its focus-session list.")
        require(taskArray.length() <= MAX_TASKS) { "The backup contains too many tasks." }

        val tasks = ArrayList<TaskEntity>(taskArray.length())
        val taskIds = HashSet<Long>()
        for (index in 0 until taskArray.length()) {
            val item = taskArray.optJSONObject(index)
                ?: throw IllegalArgumentException("Task entry ${index + 1} is invalid.")
            val id = requiredLong(item, "id", "task ${index + 1}")
            val title = item.optString("title").trim()
            val subject = item.optString("subject", "General").trim().ifBlank { "General" }
            val priority = requiredInt(item, "priority", "task ${index + 1}")
            val dueAt = requiredLong(item, "dueAtMillis", "task ${index + 1}")
            require(id > 0L && taskIds.add(id)) { "Task IDs must be unique positive numbers." }
            require(title.isNotEmpty()) { "Task ${index + 1} has no title." }
            require(priority in 1..3) { "Task ${index + 1} has an invalid priority." }
            require(dueAt >= 0L) { "Task ${index + 1} has an invalid due date." }
            require(item.has("isDone")) { "Task ${index + 1} is missing its completion status." }
            tasks += TaskEntity(id, title, subject, priority, dueAt, item.optBoolean("isDone"))
        }

        require(sessionArray.length() <= MAX_SESSIONS) {
            "The backup contains more than $MAX_SESSIONS focus sessions."
        }
        val sessions = ArrayList<FocusSessionEntity>(sessionArray.length())
        val sessionIds = HashSet<String>()
        for (index in 0 until sessionArray.length()) {
            val item = sessionArray.optJSONObject(index)
                ?: throw IllegalArgumentException("Focus session ${index + 1} is invalid.")
            val id = item.optString("id").trim()
            val subject = item.optString("subject", "General").trim().ifBlank { "General" }
            val seconds = requiredInt(item, "durationSeconds", "focus session ${index + 1}")
            val endedAt = requiredLong(item, "endedAtMillis", "focus session ${index + 1}")
            require(id.isNotEmpty() && sessionIds.add(id)) { "Focus-session IDs must be unique and non-empty." }
            require(seconds >= 0) { "Focus session ${index + 1} has an invalid duration." }
            require(endedAt >= 0L) { "Focus session ${index + 1} has an invalid end time." }
            require(item.has("isAssignment")) { "Focus session ${index + 1} is missing its session type." }
            sessions += FocusSessionEntity(id, subject, seconds, endedAt, item.optBoolean("isAssignment"))
        }

        // Replace both tables in one Room transaction, after full-document validation.
        dao.replaceAllData(tasks, sessions)
        return RestoreReport(tasks.size, sessions.size)
    }

    private fun requiredLong(item: JSONObject, key: String, label: String): Long {
        require(item.has(key) && !item.isNull(key)) { "$label is missing $key." }
        return try {
            item.getLong(key)
        } catch (error: JSONException) {
            throw IllegalArgumentException("$label has an invalid $key.", error)
        }
    }

    private fun requiredInt(item: JSONObject, key: String, label: String): Int {
        val value = requiredLong(item, key, label)
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            "$label has an invalid $key."
        }
        return value.toInt()
    }
}
