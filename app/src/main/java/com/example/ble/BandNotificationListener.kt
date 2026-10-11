package com.example.ble

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Mirrors phone notifications to the band. Needs "Notification access" for Kestrel in Android settings.
 * Config lives in SharedPreferences "band_notify": enabled, all (every app), packages (allow list).
 */
class BandNotificationListener : NotificationListenerService() {

  private var lastKey = ""
  private var lastAt = 0L

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    try {
      val ctx = applicationContext
      val prefs = ctx.getSharedPreferences("band_notify", Context.MODE_PRIVATE)
      if (!prefs.getBoolean("enabled", false)) return
      if (sbn.packageName == packageName) return
      val n = sbn.notification ?: return
      if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
      if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
      if (!prefs.getBoolean("all", false)) {
        val allowed = prefs.getStringSet("packages", DEFAULT_PACKAGES) ?: DEFAULT_PACKAGES
        if (!allowed.contains(sbn.packageName)) return
      }
      val mgr = MiBandManager.get(ctx)
      if (mgr.uiState.value.status != ConnectionStatus.CONNECTED) return

      val extras = n.extras
      val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
      val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
      if (title.isEmpty() && text.isEmpty()) return
      val key = sbn.packageName + "|" + title + "|" + text
      val now = System.currentTimeMillis()
      if (key == lastKey && now - lastAt < 10_000) return
      lastKey = key; lastAt = now

      val app = try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (e: Exception) { sbn.packageName }
      mgr.sendBandNotification(if (title.isNotEmpty()) title else app, text, iconFor(sbn.packageName))
    } catch (e: Exception) {
      android.util.Log.w("BandNotify", "failed: ${e.message}")
    }
  }

  companion object {
    val DEFAULT_PACKAGES: Set<String> = setOf(
      "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.instagram.android", "com.facebook.orca",
      "com.google.android.gm", "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
      "com.google.android.dialer", "com.android.server.telecom", "com.twitter.android", "com.snapchat.android"
    )

    /** Huami icon ids (Gadgetbridge HuamiIcon). 11 = generic app. */
    fun iconFor(pkg: String): Int = when (pkg) {
      "com.whatsapp", "com.whatsapp.w4b" -> 7
      "org.telegram.messenger" -> 25
      "com.instagram.android" -> 12
      "com.facebook.katana" -> 3
      "com.facebook.orca" -> 22
      "com.twitter.android" -> 4
      "com.snapchat.android" -> 6
      "com.google.android.gm" -> 34
      else -> 11
    }
  }
}
