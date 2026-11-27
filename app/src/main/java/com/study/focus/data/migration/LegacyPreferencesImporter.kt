package com.study.focus.data.migration

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import com.study.focus.data.database.FocusSessionEntity
import com.study.focus.data.database.StudyFocusDao
import com.study.focus.data.database.TaskEntity

/**
 * One-time, repeat-safe import from the app's existing SharedPreferences store.
 * Original preferences are never cleared; invalid entries are retained for recovery.
 */
object LegacyPreferencesImporter {
    private const val SOURCE_PREFS = "study_focus_v2"
    private const val LEGACY_TASK_PREFS = "study_focus_tasks"
    private const val MIGRATION_PREFS = "study_focus_room_migration"
    private const val RECOVERY_PREFS = "study_focus_migration_recovery"
    private const val COMPLETE_KEY = "completed_v1"

    data class Report(
        val alreadyCompleted: Boolean,
        val importedTasks: Int,
        val importedSessions: Int,
        val recoveredPayloads: List<String>
    )

    suspend fun migrate(context: Context, dao: StudyFocusDao): Report {
        val appContext = context.applicationContext
        val migrationState = appContext.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (migrationState.getBoolean(COMPLETE_KEY, false)) {
            return Report(true, 0, 0, emptyList())
        }

        val source = appContext.getSharedPreferences(SOURCE_PREFS, Context.MODE_PRIVATE)
        val taskJson = source.getString("tasks", null)
            ?: appContext.getSharedPreferences(LEGACY_TASK_PREFS, Context.MODE_PRIVATE).getString("tasks", null)
        val sessionJson = source.getString("sessions", null)
        val recovered = mutableListOf<String>()

        val tasks = parseTasks(taskJson, recovered)
        val sessions = parseSessions(sessionJson, recovered)

        // Writes are idempotent: task IDs are preserved and session IDs are deterministic.
        dao.putTasks(tasks)
        dao.insertSessions(sessions)

        if (recovered.isNotEmpty()) {
            val recovery = appContext.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE).edit()
            recovered.forEachIndexed { index, raw -> recovery.putString("payload_$index", raw) }
            recovery.putInt("payload_count", recovered.size)
                .putString("reason", "Some legacy JSON entries could not be imported.")
                .apply()
        }

        migrationState.edit()
            .putBoolean(COMPLETE_KEY, true)
            .putInt("tasks_imported", tasks.size)
            .putInt("sessions_imported", sessions.size)
            .putBoolean("has_recovery_payloads", recovered.isNotEmpty())
            .apply()

        return Report(false, tasks.size, sessions.size, recovered.indices.map { "payload_$it" })
    }

    private fun parseTasks(raw: String?, recovered: MutableList<String>): List<TaskEntity> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (_: JSONException) {
            recovered.add("tasks: $raw")
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val item = try {
                    array.getJSONObject(index)
                } catch (_: JSONException) {
                    recovered.add("tasks[$index]: ${array.opt(index)}")
                    continue
                }
                try {
                    val title = item.optString("title").trim()
                    if (title.isEmpty()) {
                        recovered.add("tasks[$index]: $item")
                        continue
                    }
                    add(TaskEntity(
                        id = item.optLong("id", index.toLong() + 1L),
                        title = title,
                        subject = item.optString("subject", "General").ifBlank { "General" },
                        priority = item.optInt("priority", 2).coerceIn(1, 3),
                        dueAtMillis = item.optLong("due", 0L).coerceAtLeast(0L),
                        isDone = item.optBoolean("done", false)
                    ))
                } catch (_: RuntimeException) {
                    recovered.add("tasks[$index]: $item")
                }
            }
        }
    }

    private fun parseSessions(raw: String?, recovered: MutableList<String>): List<FocusSessionEntity> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (_: JSONException) {
            recovered.add("sessions: $raw")
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val item = try {
                    array.getJSONObject(index)
                } catch (_: JSONException) {
                    recovered.add("sessions[$index]: ${array.opt(index)}")
                    continue
                }
                try {
                    val ended = item.optLong("ended", 0L).coerceAtLeast(0L)
                    val seconds = item.optInt("seconds", item.optInt("minutes") * 60).coerceAtLeast(0)
                    add(FocusSessionEntity(
                        id = "legacy-$ended-$index",
                        subject = item.optString("subject", "General").ifBlank { "General" },
                        durationSeconds = seconds,
                        endedAtMillis = ended,
                        isAssignment = item.optBoolean("assignment", false)
                    ))
                } catch (_: RuntimeException) {
                    recovered.add("sessions[$index]: $item")
                }
            }
        }
    }
}
