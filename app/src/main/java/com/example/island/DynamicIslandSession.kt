package com.example.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import com.example.MainActivity
import com.example.run.RunTrackingService

/**
 * Ongoing MediaSession + MediaStyle notification so third-party Dynamic Island
 * apps (and the system media indicator) can show live workout / run controls.
 */
object DynamicIslandSession {
  private const val CHANNEL = "kestrel_dynamic_island"
  const val NOTIF_ID = 77450

  @Volatile private var session: MediaSessionCompat? = null
  @Volatile private var activeTitle: String = "Kestrel"
  @Volatile private var activeSubtitle: String = ""
  @Volatile private var paused: Boolean = false

  @Synchronized
  fun ensureSession(ctx: Context): MediaSessionCompat {
    session?.let { return it }
    val app = ctx.applicationContext
    val s = MediaSessionCompat(app, "KestrelIsland")
    s.isActive = true
    s.setCallback(object : MediaSessionCompat.Callback() {
      override fun onPause() {
        RunTrackingService.send(app, RunTrackingService.ACTION_PAUSE)
        update(app, paused = true)
      }
      override fun onPlay() {
        RunTrackingService.send(app, RunTrackingService.ACTION_RESUME)
        update(app, paused = false)
      }
      override fun onStop() {
        RunTrackingService.send(app, RunTrackingService.ACTION_FINISH)
        end(app)
      }
    })
    session = s
    return s
  }

  fun start(ctx: Context, title: String, subtitle: String = "In progress") {
    activeTitle = title
    activeSubtitle = subtitle
    paused = false
    ensureSession(ctx)
    update(ctx, title, subtitle, paused = false)
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
    val app = ctx.applicationContext
    val s = ensureSession(app)

    val state = PlaybackStateCompat.Builder()
      .setActions(
        PlaybackStateCompat.ACTION_PLAY or
          PlaybackStateCompat.ACTION_PAUSE or
          PlaybackStateCompat.ACTION_STOP or
          PlaybackStateCompat.ACTION_PLAY_PAUSE
      )
      .setState(
        if (paused) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING,
        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
        1f
      )
      .build()
    s.setPlaybackState(state)
    s.setMetadata(
      MediaMetadataCompat.Builder()
        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
        .build()
    )

    val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= 26) {
      val ch = NotificationChannel(CHANNEL, "Live activity", NotificationManager.IMPORTANCE_DEFAULT)
      ch.setShowBadge(false)
      ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
      ch.setSound(null, null)
      nm.createNotificationChannel(ch)
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

    val builder = NotificationCompat.Builder(app, CHANNEL)
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle(title)
      .setContentText(subtitle)
      .setContentIntent(open)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .setStyle(
        MediaNotificationCompat.MediaStyle()
          .setMediaSession(s.sessionToken)
          .setShowActionsInCompactView(0, 1)
      )

    if (paused) {
      builder.addAction(android.R.drawable.ic_media_play, "Resume", pi(RunTrackingService.ACTION_RESUME, 501))
    } else {
      builder.addAction(android.R.drawable.ic_media_pause, "Pause", pi(RunTrackingService.ACTION_PAUSE, 502))
    }
    builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pi(RunTrackingService.ACTION_FINISH, 503))

    try {
      nm.notify(NOTIF_ID, builder.build())
    } catch (_: Exception) {
    }
  }

  fun end(ctx: Context) {
    try {
      val nm = ctx.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      nm.cancel(NOTIF_ID)
    } catch (_: Exception) {
    }
    try {
      session?.isActive = false
      session?.release()
    } catch (_: Exception) {
    }
    session = null
    activeTitle = "Kestrel"
    activeSubtitle = ""
    paused = false
  }
}
