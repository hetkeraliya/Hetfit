# Band sync rewrite (Gadgetbridge protocol)

NEW  ble/HuamiActivityFetcher.kt   real history fetch (ported from Gadgetbridge AbstractFetchOperation/FetchActivityOperation)
NEW  ble/BandSampleStore.kt        local SQLite store of every minute sample from the band
NEW  ble/BandSyncService.kt        foreground service + notification: Sync / Disconnect (-> Connect), base data
EDIT ble/MiBandManager.kt          shared instance, auto-sync on connect, reconnect, Sync, API 33 callbacks
EDIT ble/MiBandDataSyncEngine.kt   sends REAL stored rows to the web UI (all fake generation removed)
EDIT MainActivity.kt               uses shared manager, no disconnect on close, pushes real data, notif permission
EDIT AndroidManifest.xml           FGS permissions, service, singleTask
EDIT widget/*, res/layout/widget_*.xml   plain <View> (crashes RemoteViews = "can't load widget") -> FrameLayout; no fake defaults
EDIT assets/web/app.js             kestrelBandSamples*/kestrelLiveMetric, purge old fake days, no demo seed
Workouts are NOT synced over Bluetooth. Gadgetbridge zip import is unchanged.

# Round 2
NEW  ble/HuamiSportsSummaryParser.kt   workout summary parser (port of Gadgetbridge HuamiActivitySummaryParser)
EDIT ble/HuamiActivityFetcher.kt       now fetches activity + automatic stress (0x13) + workout summaries (0x05)
EDIT ble/BandSampleStore.kt            stress + workouts tables, per-day stress / sleep-stage (deep/light/REM) stats
NEW  widget/KestrelGlassWidgets.kt     6 new frosted-glass widgets: steps ring, heart rate, sleep, stress, last workout, band status (Sync/Connect)
EDIT drawables bg_widget_*, progress_*  frosted-glass cool theme for all widgets
EDIT assets/web/app.js                 kestrelBandExtras / kestrelBandWorkouts
CI   .github/workflows/build-apk.yml   creates debug.keystore + placeholder google-services.json

# Round 3
SLEEP  fetcher re-reads the last 14 h every sync (band labels sleep only after you wake), native per-night sleep totals,
       "Re-sync everything" + data diagnostics in More > Sleep
RUN    run/RunTrackingService.kt  offline GPS recorder (run/walk/ride/hike), live pace + band HR, voice cue per km, route stored on phone
       run/ShareHelper.kt         Instagram Story / WhatsApp / share sheet / save-to-gallery, GPX export, Downloads saving
       assets/web/run.js, run.css Run tab: start screen, week strip + streak, activities, stats + PRs, detail (route, splits, elevation, HR), share cards
MORE   assets/web/extras.js       sleep stages + timeline, water, mood, backup / restore, band-minutes CSV

# Round 4
BAND   ble/BandNotificationListener.kt  phone notifications -> band (chunked Huami alert, per-app allow list)
       MiBandManager: band settings (HR interval, sleep HR, raise-to-wake, goal notice, sedentary reminder), 5 band alarms, PAI fetch (0x0d)
       assets/web/extras.js  More > Band tab: PAI, settings, alarms, notifications

## Auth hang fix
- Auth now follows Gadgetbridge's reconnect flow: enable notifications, then request the random number (0x02 0x00). The key (0x01) is only sent as a fallback.
- Auth watchdog: no reply in 8 s -> retry key-first once, then fail with a clear message instead of hanging.
- GATT queue watchdog: an operation whose callback never arrives is released after 4 s.
- Auth key cleanup is case-insensitive and strips 0x, spaces, colons, dashes.

## Auth fix 2
- Android bonding now completes BEFORE the GATT link opens (Gadgetbridge order); previously both ran at once.
- Handshake trace (services > cccd > sent-random > reply ...) shown in Settings > Band connection while not connected.
- If the band never answers auth, the link is kept in limited live mode instead of disconnecting.
