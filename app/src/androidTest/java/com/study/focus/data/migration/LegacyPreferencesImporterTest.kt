package com.study.focus.data.migration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.study.focus.data.database.StudyFocusDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
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
    fun fallsBackToLegacyTaskPreferencesWhenCurrentTasksAreMissing() = runBlocking {
        context.getSharedPreferences("study_focus_tasks", Context.MODE_PRIVATE).edit()
            .putString("tasks", """[{"id":9,"title":"Old task","done":true}]""")
            .commit()

        val report = LegacyPreferencesImporter.migrate(context, database.studyFocusDao())

        assertEquals(1, report.importedTasks)
        assertTrue(database.studyFocusDao().getTasks().single().isDone)
    }
}
