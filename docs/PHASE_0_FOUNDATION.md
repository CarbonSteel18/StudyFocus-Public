# Phase 0 — Data and architecture foundation

## Current application constraints

- Native Android application written in Kotlin using Android Views.
- Minimum Android version: API 26; Java/Kotlin target: 17.
- Existing tasks and focus sessions are JSON in SharedPreferences.
- Existing application identity and release signing configuration must remain unchanged.

## Foundation and integration

- Room 2.6.1 database with task and focus-session entities.
- DAO methods use suspend functions; database access does not run synchronously on the UI thread.
- A repository provides the persistence boundary used by the existing screens.
- Startup imports the current `study_focus_v2` preferences and falls back to the older `study_focus_tasks` task store when needed.
- Valid legacy entries are imported even when another entry in the same JSON array is damaged.
- Existing task IDs and represented task/session fields are retained. Imported session IDs are deterministic, making interrupted imports safe to retry.
- Malformed payloads and individual damaged entries are copied into a dedicated recovery preference store.
- Room becomes the primary read source after migration succeeds. Task and session writes are also mirrored to the original preferences as a temporary rollback safeguard.
- If Room setup or migration fails, the app remains usable with the original SharedPreferences store.
- The database is created lazily and does not enable main-thread queries.
- Task replacement removes deleted rows from Room, and focus-session history is capped at 500 records to match the previous store.

## Verification

Instrumented tests cover:
- Importing valid tasks and focus sessions.
- Re-running migration without duplicating records.
- Importing valid rows while retaining damaged rows for recovery.
- Preserving malformed top-level JSON for recovery without crashing.
- Falling back to the older task preference store.
- Ensuring repository task replacement removes tasks deleted by the UI.

GitHub Actions compiles the debug app and runs the migration tests on an Android 14 emulator.

## Rollout safeguards

- The original SharedPreferences values are not deleted.
- The private StudyFocus Admin repository and its signing configuration are not changed.
- This work remains on the `phase-0-foundation` branch and draft PR until build, test, and device regression checks pass.
- Do not remove the legacy store until a backup/export path and rollback plan are reviewed.
