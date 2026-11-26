# StudyFocus

A minimal, dark-themed Android study companion for building consistent study habits.

StudyFocus brings your study tasks and focused sessions together in one place. The public edition is designed to work locally on your Android device and does not include the private Notion integration or its sync backend.

## Features

- **Focus timer** — timed study sessions with 15, 25, 45 and 60-minute presets, pause and reset controls.
- **Subject-wise tracking** — organise tasks and focus sessions by subject.
- **Tasks and deadlines** — add tasks, set priorities and optional due dates, and mark work complete.
- **Study statistics** — review completed focus time and session history.
- **Flip clock** — a fullscreen clock view.
- **Dark interface** — a distraction-light, dark UI.

## Project

This repository contains the native Android application.

- Language: Kotlin
- UI: Android Views
- Build system: Gradle
- Minimum Android version: Android 8.0 (API 26)

## Build

1. Install Android Studio and a Java 17 JDK.
2. Open this repository in Android Studio.
3. Allow Gradle to sync.
4. Run the `app` configuration on an Android device or emulator.

To build a debug APK from a terminal with Gradle installed:

```bash
gradle assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Data and privacy

Tasks and focus-session history are stored locally using Android app preferences. This public edition does not connect to Notion and does not require Notion credentials, a sync server, or external database configuration.

## Public edition

This is the standalone public edition of StudyFocus. The private development repository may contain additional integrations that are intentionally not included here.

---

Made with a focus on getting the work done.
