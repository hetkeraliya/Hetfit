package com.example.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.run.RunTrackingService

/**
 * Safe ongoing notification with Pause / Resume / Stop.
 * No MediaSession — avoids crashes on some devices while still feeding
 * third-party Dynamic Island apps that mirror ongoing notifications.
 */
object DynamicIslandSession {
  private const val CHANNEL = "kestrel_dynamic_island"
  const val NOTIF_ID = 77450

  @Volatile private var activeTitle: String = "Kestrel"
  @Volatile private var activeSubtitle: String = ""
  @Volatile private var paused: Boolean = false

  fun start(ctx: Context, title: String, subtitle: String = "In progress") {
    activeTitle = title
    activeSubtitle = subtitle
    paused = false
    post(ctx, title, subtitle, false)
  }

  fun update(
    ctx: Context,
    title: String = activeTitle,
    subtitle: String = activeSubtitle,
    paused: Boolean = this.paused
  ) {
    activeTitle = title
    activeSubtitle = subtitle
    this.paused = paused
    post(ctx, title, subtitle, paused)
  }

  fun end(ctx: Context) {
    try {
      val nm = ctx.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      nm.cancel(NOTIF_ID)
    } catch (_: Exception) {
    }
    activeTitle = "Kestrel"
    activeSubtitle = ""
    paused = false
  }

  private fun post(ctx: Context, title: String, subtitle: String, isPaused: Boolean) {
    try {
      val app = ctx.applicationContext
      val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      if (Build.VERSION.SDK_INT >= 26) {
        val existing = nm.getNotificationChannel(CHANNEL)
        if (existing == null) {
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

      val b = NotificationCompat.Builder(app, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle(title)
        .setContentText(subtitle)
        .setContentIntent(open)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)

      if (isPaused) {
        b.addAction(android.R.drawable.ic_media_play, "Resume", pi(RunTrackingService.ACTION_RESUME, 501))
      } else {
        b.addAction(android.R.drawable.ic_media_pause, "Pause", pi(RunTrackingService.ACTION_PAUSE, 502))
      }
      b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pi(RunTrackingService.ACTION_FINISH, 503))

      nm.notify(NOTIF_ID, b.build())
    } catch (_: Exception) {
      // Never crash the host app
    }
  }
}
