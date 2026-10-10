package com.example.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.widget.KestrelWidgetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the band connection alive in the background and shows a Gadgetbridge-style notification:
 *  - connected:    [Sync] [Disconnect]
 *  - disconnected: [Connect]
 * with today's steps / heart rate / battery as base data.
 */
class BandSyncService : Service() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private lateinit var manager: MiBandManager
  private lateinit var store: BandSampleStore
  private var collector: Job? = null

  override fun onCreate() {
    super.onCreate()
    manager = MiBandManager.get(this)
    store = BandSampleStore.get(this)
    createChannel()
    val n = buildNotification(manager.uiState.value)
    try {
      if (Build.VERSION.SDK_INT >= 29) {
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
      } else {
        startForeground(NOTIF_ID, n)
      }
    } catch (e: Exception) {
      android.util.Log.w("BandSyncService", "Foreground start refused (Bluetooth permission?): ${e.message}")
      stopSelf()
      return
    }
    collector = scope.launch {
      manager.uiState.collect { st ->
        notifyUpdate(st)
        delay(1500) // collapse bursts of live heart-rate / step updates
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_SYNC -> manager.syncNow()
      ACTION_DISCONNECT -> manager.disconnect()
      ACTION_CONNECT -> manager.connectSaved()
    }
    notifyUpdate(manager.uiState.value)
    return START_STICKY
  }

  override fun onDestroy() {
    collector?.cancel()
    scope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
      val nm = getSystemService(NotificationManager::class.java)
      if (nm.getNotificationChannel(CHANNEL_ID) == null) {
        val ch = NotificationChannel(CHANNEL_ID, "Band connection", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Shows your band status with Sync and Disconnect buttons"
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
      }
    }
  }

  private fun notifyUpdate(st: MiBandState) {
    try {
      val nm = getSystemService(NotificationManager::class.java)
      nm.notify(NOTIF_ID, buildNotification(st))
    } catch (_: Exception) {
    }
  }

  private fun actionIntent(action: String, code: Int): PendingIntent {
    val i = Intent(this, BandSyncService::class.java).setAction(action)
    return PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  private fun buildNotification(st: MiBandState): Notification {
    val totals = try { store.todayTotals() } catch (_: Exception) { TodayTotals(0, 0, 0) }
    val nf = NumberFormat.getNumberInstance(Locale.getDefault())
    val steps = maxOf(totals.steps, st.liveSteps)
    val hr = if (st.liveHeartRate > 0) st.liveHeartRate else totals.lastHr
    val name = if (st.deviceName.isNotEmpty()) st.deviceName else "Mi Smart Band"

    val title = when (st.status) {
      ConnectionStatus.CONNECTED -> "$name · Connected"
      ConnectionStatus.CONNECTING, ConnectionStatus.AUTHENTICATING -> "$name · Connecting…"
      ConnectionStatus.SCANNING -> "Searching for band…"
      ConnectionStatus.DISCONNECTED -> "$name · Disconnected"
    }

    val parts = ArrayList<String>()
    parts.add("${nf.format(steps)} steps")
    parts.add(if (hr > 0) "$hr bpm" else "-- bpm")
    parts.add(if (st.batteryPercent >= 0) "${st.batteryPercent}% battery" else "-- battery")
    val base = parts.joinToString("  ·  ")

    val sub = when {
      st.syncing -> st.syncMessage ?: "Syncing…"
      st.lastSyncMillis > 0 -> "Last sync " + SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(st.lastSyncMillis))
      st.errorMessage != null -> st.errorMessage
      else -> "Not synced yet"
    }

    val open = PendingIntent.getActivity(
      this, 0,
      Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val b = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
      .setContentTitle(title)
      .setContentText(base)
      .setStyle(NotificationCompat.BigTextStyle().bigText("$base\n$sub"))
      .setSubText(if (st.syncing) "Syncing" else null)
      .setContentIntent(open)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setShowWhen(false)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    if (st.syncing) b.setProgress(100, st.syncPercent.coerceIn(0, 100), st.syncPercent <= 0)

    when (st.status) {
      ConnectionStatus.CONNECTED -> {
        if (!st.syncing) b.addAction(0, "Sync", actionIntent(ACTION_SYNC, 1))
        b.addAction(0, "Disconnect", actionIntent(ACTION_DISCONNECT, 2))
      }
      ConnectionStatus.CONNECTING, ConnectionStatus.AUTHENTICATING, ConnectionStatus.SCANNING ->
        b.addAction(0, "Cancel", actionIntent(ACTION_DISCONNECT, 2))
      ConnectionStatus.DISCONNECTED ->
        if (manager.hasSavedDevice()) b.addAction(0, "Connect", actionIntent(ACTION_CONNECT, 3))
    }

    // Keep the home-screen widgets in step with what the notification shows.
    if (steps > 0 || hr > 0 || totals.sleepMinutes > 0) {
      KestrelWidgetManager.saveHealthData(
        this,
        steps = steps.takeIf { it > 0 },
        heartRate = hr.takeIf { it > 0 },
        sleepMinutes = totals.sleepMinutes.takeIf { it > 0 }
      )
    }
    return b.build()
  }

  companion object {
    const val CHANNEL_ID = "kestrel_band_connection"
    const val NOTIF_ID = 4711
    const val ACTION_START = "com.example.action.BAND_START"
    const val ACTION_SYNC = "com.example.action.BAND_SYNC"
    const val ACTION_DISCONNECT = "com.example.action.BAND_DISCONNECT"
    const val ACTION_CONNECT = "com.example.action.BAND_CONNECT"

    fun start(context: Context) {
      try {
        ContextCompat.startForegroundService(
          context,
          Intent(context, BandSyncService::class.java).setAction(ACTION_START)
        )
      } catch (e: Exception) {
        android.util.Log.w("BandSyncService", "Could not start service: ${e.message}")
      }
    }
  }
}
