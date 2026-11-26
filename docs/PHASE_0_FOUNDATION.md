# Phase 0 — Data and architecture foundation

## Current application constraints

- Native Android application written in Kotlin using Android Views.
- Minimum Android version: API 26; Java/Kotlin target: 17.
- Existing tasks and focus sessions are JSON in SharedPreferences.
- Existing application identity and release signing configuration must remain unchanged.

## Foundation added

- Room 2.6.1 database with task and focus-session entities.
- DAO methods use suspend functions; database access must not run synchronously on the UI thread.
- A repeat-safe legacy importer reads the current study_focus_v2 preferences and the older study_focus_tasks task store.
- Existing task IDs and represented task/session fields are retained.
- Session IDs are deterministic so a retry after an interrupted import cannot duplicate imported legacy sessions.
- Original preference values are never deleted. Unparseable JSON is copied into a dedicated recovery preference store and reported by the importer.
- The database is created lazily and does not enable main-thread queries.

## Migration rollout rule

The importer is intentionally not called from MainActivity yet. The integration step must open Room off the main thread, run the importer before switching reads/writes to Room, verify counts and representative records, and keep old preferences intact for at least one stable release. Do not remove the legacy store until a rollback plan is reviewed.

## Next steps

1. Add repository methods and connect existing screens to Room through lifecycle-aware coroutines.
2. Add migration tests for empty, valid, partially damaged, and malformed legacy payloads.
3. Add a recovery/export path before retiring the old preference store.
