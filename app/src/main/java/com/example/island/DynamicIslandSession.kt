package com.example.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.run.RunTrackingService

/**
 * Live run card for notch / Dynamic Island apps (e.g. Essentials).
 * Shows distance, time, pace, HR with green→red zone color, Pause / Finish.
 * Uses custom RemoteViews only — no MediaSession (stable, no crash).
 */
object DynamicIslandSession {
  private const val CHANNEL = "kestrel_dynamic_island"
  const val NOTIF_ID = 77450

  data class Stats(
    val title: String = "Run",
    val distanceKm: Double = 0.0,
    val elapsedSec: Int = 0,
    val paceSecPerKm: Int = -1,
    val hrBpm: Int = 0,
    val paused: Boolean = false
  )

  @Volatile private var last: Stats = Stats()

  fun start(ctx: Context, title: String, subtitle: String = "In progress") {
    last = Stats(title = title, paused = false)
    post(ctx, last)
  }

  fun update(
    ctx: Context,
    title: String = last.title,
    subtitle: String = "",
    paused: Boolean = last.paused
  ) {
    // Legacy string API — keep working for non-run callers
    last = last.copy(title = title, paused = paused)
    post(ctx, last)
  }

  fun updateStats(ctx: Context, stats: Stats) {
    last = stats
    post(ctx, stats)
  }

  fun end(ctx: Context) {
    try {
      val nm = ctx.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      nm.cancel(NOTIF_ID)
    } catch (_: Exception) {
    }
    last = Stats()
  }

  private fun hrColor(bpm: Int): Int = when {
    bpm <= 0 -> Color.parseColor("#8E8E93")
    bpm < 110 -> Color.parseColor("#34C759") // green — easy
    bpm < 140 -> Color.parseColor("#A8E10C") // lime
    bpm < 160 -> Color.parseColor("#FFD60A") // yellow
    bpm < 175 -> Color.parseColor("#FF9F0A") // orange
    else -> Color.parseColor("#FF3B30")       // red — max
  }

  private fun hrZone(bpm: Int): String = when {
    bpm <= 0 -> ""
    bpm < 110 -> "Easy"
    bpm < 140 -> "Fat burn"
    bpm < 160 -> "Cardio"
    bpm < 175 -> "Hard"
    else -> "Max"
  }

  private fun formatTime(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val r = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, r)
    else String.format("%d:%02d", m, r)
  }

  private fun formatPace(secPerKm: Int): String {
    if (secPerKm <= 0 || secPerKm > 3600) return "— /km"
    return String.format("%d:%02d /km", secPerKm / 60, secPerKm % 60)
  }

  private fun post(ctx: Context, stats: Stats) {
    try {
      val app = ctx.applicationContext
      val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      if (Build.VERSION.SDK_INT >= 26) {
        if (nm.getNotificationChannel(CHANNEL) == null) {
          val ch = NotificationChannel(CHANNEL, "Live activity", NotificationManager.IMPORTANCE_DEFAULT)
          ch.setShowBadge(false)
          ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
          ch.setSound(null, null)
          nm.createNotificationChannel(ch)
        }
      }

      fun pi(action: String, req: Int): PendingIntent {
        val i = Intent(app, RunTrackingService::class.java).setAction(action)
        return PendingIntent.getService(
          app, req, i,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
      }
      val open = PendingIntent.getActivity(
        app, 90,
        Intent(app, MainActivity::class.java)
          .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )

      val km = String.format("%.2f km", stats.distanceKm)
      val time = formatTime(stats.elapsedSec)
      val pace = formatPace(stats.paceSecPerKm)
      val hrText = if (stats.hrBpm > 0) "${stats.hrBpm} bpm" else "— bpm"
      val color = hrColor(stats.hrBpm)
      val zone = hrZone(stats.hrBpm)
      val status = when {
        stats.paused -> "PAUSED"
        else -> stats.title.uppercase().take(12).ifEmpty { "RUN" }
      }

      // Expanded card (notch / shade / lock)
      val big = RemoteViews(app.packageName, R.layout.notification_island)
      big.setTextViewText(R.id.ni_status, status)
      big.setTextViewText(R.id.ni_time, time)
      big.setTextViewText(R.id.ni_distance, km)
      big.setTextViewText(R.id.ni_pace, pace)
      big.setTextViewText(R.id.ni_hr, hrText)
      big.setTextViewText(R.id.ni_hr_zone, zone)
      big.setTextColor(R.id.ni_hr, color)
      big.setTextColor(R.id.ni_hr_dot, color)

      // Compact (status bar / collapsed notch)
      val compact = RemoteViews(app.packageName, R.layout.notification_run_compact)
      compact.setTextViewText(R.id.nr_c_title, if (stats.paused) "Paused" else status)
      compact.setTextViewText(R.id.nr_c_stats, "$km · $time" + if (stats.hrBpm > 0) " · ${stats.hrBpm}" else "")

      val b = NotificationCompat.Builder(app, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle(if (stats.paused) "Paused · $km" else "$status · $km")
        .setContentText("$time · $pace" + if (stats.hrBpm > 0) " · $hrText" else "")
        .setCustomContentView(compact)
        .setCustomBigContentView(big)
        .setContentIntent(open)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setShowWhen(false)

      if (stats.paused) {
        b.addAction(android.R.drawable.ic_media_play, "Resume", pi(RunTrackingService.ACTION_RESUME, 501))
      } else {
        b.addAction(android.R.drawable.ic_media_pause, "Pause", pi(RunTrackingService.ACTION_PAUSE, 502))
      }
      b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Finish", pi(RunTrackingService.ACTION_FINISH, 503))

      nm.notify(NOTIF_ID, b.build())
    } catch (_: Exception) {
      // Never crash the host app
    }
  }
}
