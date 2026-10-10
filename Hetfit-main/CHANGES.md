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
