# Kestrel feature update

## Sleep (fixed)
- Proper sleep session detection from kind=120 + deep/rem flags
- Night window 18:00–18:00, min 20 min sessions
- Stages: deep / light / REM / awake
- Structured sleep summary pushed to web UI (`kestrelSleepSummary`)
- Home card shows total + stage breakdown

## Running (Strava-like)
- Manual Start / Stop Run from Run tab
- Workouts stored in SQLite on device
- Distance estimated from steps, calories estimated
- Recent workouts list + Share button

## Story-style sharing
- Generates a themed 1080×1920 PNG card (Kestrel colors)
- Uses Web Share API when available, otherwise downloads

## AI (OpenCode Zen)
- Provider: https://opencode.ai/zen/v1
- Default free model: space-bunny-free
- User pastes API key → saved only on device (SharedPreferences)
- Health chat with local sleep / steps / workout context
- Settings dialog from AI tab

## Offline
- All band data, workouts, sleep, and API key stay on device
- AI only needs network when you send a question

## Files touched
- ble/BandSampleStore.kt (sleep sessions + workouts table)
- ble/MiBandDataSyncEngine.kt (richer push)
- ai/OpenCodeZenClient.kt (new)
- MainActivity.kt (JS bridge + AI dialog + run)
- assets/web/app.js, index.html, styles.css
