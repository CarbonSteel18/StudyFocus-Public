package com.study.focus.data.migration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.study.focus.data.backup.BackupManager
import com.study.focus.data.database.FocusSessionEntity
import com.study.focus.data.database.StudyFocusDatabase
import com.study.focus.data.database.TaskEntity
import com.study.focus.data.repository.StudyFocusRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyPreferencesImporterTest {
    private lateinit var context: Context
    private lateinit var database: StudyFocusDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        listOf(
            "study_focus_v2",
            "study_focus_tasks",
            "study_focus_room_migration",
            "study_focus_migration_recovery"
        ).forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        database = Room.inMemoryDatabaseBuilder(
            context,
            StudyFocusDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun importsValidTasksAndSessionsAndIsRepeatSafe() = runBlocking {
        context.getSharedPreferences("study_focus_v2", Context.MODE_PRIVATE).edit()
            .putString("tasks", """[{"id":7,"title":"Physics module","subject":"Physics","priority":1,"due":1234,"done":false}]""")
            .putString("sessions", """[{"subject":"Mathematics","seconds":1500,"ended":5678,"assignment":true}]""")
            .commit()

        val first = LegacyPreferencesImporter.migrate(context, database.studyFocusDao())
        val second = LegacyPreferencesImporter.migrate(context, database.studyFocusDao())

        assertFalse(first.alreadyCompleted)
        assertEquals(1, first.importedTasks)
        assertEquals(1, first.importedSessions)
        assertTrue(second.alreadyCompleted)
        assertEquals(1, database.studyFocusDao().getTasks().size)
        assertEquals(1, database.studyFocusDao().getSessions().size)
        assertEquals("Physics module", database.studyFocusDao().getTasks().single().title)
        assertEquals(1500, database.studyFocusDao().getSessions().single().durationSeconds)
    }

    @Test
    fun importsValidEntriesAndPreservesDamagedRowsForRecovery() = runBlocking {
        context.getSharedPreferences("study_focus_v2", Context.MODE_PRIVATE).edit()
            .putString("tasks", """[{"id":1,"title":"Valid task","subject":"Chemistry"},null,{"id":3,"title":""}]""")
            .commit()

        val report = LegacyPreferencesImporter.migrate(context, database.studyFocusDao())

        assertEquals(1, report.importedTasks)
        assertEquals(2, report.recoveredPayloads.size)
        assertEquals("Valid task", database.studyFocusDao().getTasks().single().title)
        val recovery = context.getSharedPreferences("study_focus_migration_recovery", Context.MODE_PRIVATE)
        assertEquals(2, recovery.getInt("payload_count", 0))
        assertTrue(recovery.getString("payload_0", "").orEmpty().contains("tasks[1]"))
    }

    @Test
    fun malformedTopLevelPayloadIsRetainedWithoutCrashingMigration() = runBlocking {
        context.getSharedPreferences("study_focus_v2", Context.MODE_PRIVATE).edit()
            .putString("tasks", "[not valid json")
            .commit()

        val report = LegacyPreferencesImporter.migrate(context, database.studyFocusDao())

        assertEquals(0, report.importedTasks)
        assertEquals(1, report.recoveredPayloads.size)
        assertTrue(context.getSharedPreferences("study_focus_room_migration", Context.MODE_PRIVATE)
            .getBoolean("completed_v1", false))
    }

    @Test
    fun repositoryReplacementRemovesDeletedTasks() = runBlocking {
        val repository = StudyFocusRepository(database.studyFocusDao())
        repository.saveTasks(listOf(
            TaskEntity(1, "Keep", "Physics", 1, 0L, false),
            TaskEntity(2, "Remove", "Chemistry", 2, 0L, false)
        ))

        repository.saveTasks(listOf(TaskEntity(1, "Keep", "Physics", 1, 0L, true)))

        val remaining = repository.tasks()
        assertEquals(1, remaining.size)
        assertEquals(1L, remaining.single().id)
        assertTrue(remaining.single().isDone)
    }

    @Test
    fun fallsBackToLegacyTaskPreferencesWhenCurrentTasksAreMissing() = runBlocking {
        context.getSharedPreferences("study_focus_tasks", Context.MODE_PRIVATE).edit()
            .putString("tasks", """[{"id":9,"title":"Old task","done":true}]""")
            .commit()

        val report = LegacyPreferencesImporter.migrate(context, database.studyFocusDao())

        assertEquals(1, report.importedTasks)
        assertTrue(database.studyFocusDao().getTasks().single().isDone)
    }

    @Test
    fun backupRoundTripRestoresTasksAndFocusSessions() = runBlocking {
        val tasks = listOf(TaskEntity(11, "Revise NLM", "Physics", 1, 9876L, false))
        val sessions = listOf(FocusSessionEntity("session-1", "Mathematics", 1500, 12345L, false))
        database.studyFocusDao().replaceAllData(tasks, sessions)

        val json = BackupManager.createBackup(
            database.studyFocusDao().getTasks(),
            database.studyFocusDao().getSessions(),
            createdAtMillis = 999L
        )
        database.studyFocusDao().replaceAllData(
            listOf(TaskEntity(22, "Temporary", "General", 2, 0L, false)),
            emptyList()
        )

        val report = BackupManager.restoreBackup(json, database.studyFocusDao())

        assertEquals(1, report.taskCount)
        assertEquals(1, report.sessionCount)
        assertEquals("Revise NLM", database.studyFocusDao().getTasks().single().title)
        assertEquals("session-1", database.studyFocusDao().getSessions().single().id)
    }

    @Test
    fun invalidBackupDoesNotModifyExistingDatabaseData() = runBlocking {
        database.studyFocusDao().replaceAllData(
            listOf(TaskEntity(31, "Keep this task", "Chemistry", 2, 0L, true)),
            listOf(FocusSessionEntity("keep-session", "Chemistry", 900, 456L, false))
        )
        val invalid = """{"format":"studyfocus-backup","version":1,"tasks":[{"id":1,"title":"Bad","priority":9}],"sessions":[]}"""

        var rejected = false
        try {
            BackupManager.restoreBackup(invalid, database.studyFocusDao())
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertEquals("Keep this task", database.studyFocusDao().getTasks().single().title)
        assertEquals("keep-session", database.studyFocusDao().getSessions().single().id)
    }
}
