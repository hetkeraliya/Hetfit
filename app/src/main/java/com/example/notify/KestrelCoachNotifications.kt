package com.example.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.example.R
import com.example.ble.MiBandManager
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Branded Kestrel coach notifications — Moonlight custom layout, useful daily tips.
 */
object KestrelCoachNotifications {
  const val CHANNEL_ID = "kestrel_coach"
  private const val NOTIF_DAILY = 7101
  private const val NOTIF_PLAN = 7102
  private const val PREFS = "kestrel_coach"
  private const val KEY_PLAN = "plan_progress_json"

  fun ensureChannel(ctx: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
    if (nm.getNotificationChannel(CHANNEL_ID) != null) return
    val ch = NotificationChannel(
      CHANNEL_ID,
      "Kestrel coach",
      NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
      description = "Daily health tips and plan reminders"
      setShowBadge(true)
      lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
    }
    nm.createNotificationChannel(ch)
  }

  fun savePlanProgress(ctx: Context, json: String) {
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PLAN, json).apply()
  }

  fun planProgress(ctx: Context): JSONObject {
    val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PLAN, null)
    return try { JSONObject(s ?: "{}") } catch (_: Exception) { JSONObject() }
  }

  private fun openApp(ctx: Context): PendingIntent {
    val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
    return PendingIntent.getActivity(ctx, 88, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  private fun barText(pct: Int): String {
    val filled = (pct / 10).coerceIn(0, 10)
    return "█".repeat(filled) + "░".repeat(10 - filled) + "  $pct%"
  }

  fun showCoach(ctx: Context, title: String, body: String, badge: String, pct: Int = -1, id: Int = NOTIF_DAILY) {
    ensureChannel(ctx)
    val big = RemoteViews(ctx.packageName, R.layout.notification_kestrel_coach)
    big.setTextViewText(R.id.kc_badge, badge)
    big.setTextViewText(R.id.kc_title, title)
    big.setTextViewText(R.id.kc_body, body)
    if (pct >= 0) {
      big.setTextViewText(R.id.kc_progress_label, "Today's plan")
      big.setTextViewText(R.id.kc_progress_bar, barText(pct))
    } else {
      big.setTextViewText(R.id.kc_progress_label, "")
      big.setTextViewText(R.id.kc_progress_bar, "")
    }

    val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_menu_myplaces)
      .setContentTitle(title)
      .setContentText(body)
      .setCustomContentView(big)
      .setCustomBigContentView(big)
      .setStyle(NotificationCompat.DecoratedCustomViewStyle())
      .setContentIntent(openApp(ctx))
      .setAutoCancel(true)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setCategory(NotificationCompat.CATEGORY_REMINDER)
      .setColor(0xFF454B5C.toInt())
      .build()
    ctx.getSystemService(NotificationManager::class.java)?.notify(id, n)
  }

  /** Build a useful message from band + plan state. */
  fun buildAndNotify(ctx: Context) {
    val plan = planProgress(ctx)
    val remaining = plan.optInt("remaining", -1)
    val pct = plan.optInt("overallPct", -1)
    val st = MiBandManager.get(ctx).uiState.value
    val steps = st.liveSteps
    val hr = st.liveHeartRate
    val battery = st.batteryPercent

    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val title: String
    val body: String
    val badge: String

    when {
      remaining > 0 && pct in 0..99 && hour in 16..21 -> {
        badge = "Plan"
        title = "$remaining items left on today's plan"
        body = "Workout ${plan.optInt("workoutDone")}/${plan.optInt("workoutTotal")} · " +
          "Study ${plan.optInt("studyDone")}/${plan.optInt("studyTotal")} · " +
          "Nutrition ${plan.optInt("nutritionDone")}/${plan.optInt("nutritionTotal")}. A little more closes the day."
      }
      pct >= 100 -> {
        badge = "Done"
        title = "Today's plan is complete"
        body = "Nice work — every checklist item is checked. Recovery and sleep will lock it in."
      }
      steps > 0 && steps < 4000 && hour >= 12 -> {
        badge = "Steps"
        title = "You're at ${"%,d".format(steps)} steps"
        body = "Still room to move today. Even a short walk lifts the daily score."
      }
      steps >= 8000 -> {
        badge = "Health"
        title = "Your health looks good today"
        body = buildString {
          append("Steps ${"%,d".format(steps)}")
          if (hr in 40..180) append(" · HR $hr bpm")
          if (battery in 0..100) append(" · Band $battery%")
          append(". Keep the rhythm.")
        }
      }
      hour in 7..10 -> {
        badge = "Morning"
        title = "Good morning — here's the day"
        body = if (remaining > 0)
          "Plan ready: $remaining items. Open Plan to tick workout, study, and meals."
        else
          "Open Plan when you're ready. Small checkmarks add up."
      }
      hour in 21..23 -> {
        badge = "Evening"
        title = "Wind-down check"
        body = if (pct in 0..99 && remaining > 0)
          "Still $remaining plan items open. Finish one, or rest — tomorrow is another shot."
        else
          "Solid day. Sleep is the highest-ROI recovery tool you have."
      }
      else -> {
        badge = "Kestrel"
        title = "Your health looks solid today"
        body = buildString {
          if (steps > 0) append("Steps ${"%,d".format(steps)}. ")
          if (pct >= 0) append("Plan $pct% done. ")
          append("Open Kestrel for the full picture.")
        }
      }
    }
    showCoach(ctx, title, body, badge, if (pct >= 0) pct else -1, NOTIF_DAILY)
  }

  fun schedule(ctx: Context) {
    ensureChannel(ctx)
    // Morning ~8h and evening ~18h via 12h periodic with flex is imperfect;
    // use 6h periodic and let buildAndNotify pick the right tone by hour.
    val req = PeriodicWorkRequestBuilder<CoachWorker>(6, TimeUnit.HOURS)
      .setInitialDelay(15, TimeUnit.MINUTES)
      .build()
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
      "kestrel_coach_daily",
      ExistingPeriodicWorkPolicy.KEEP,
      req
    )
  }
}

class CoachWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
  override fun doWork(): Result {
    return try {
      KestrelCoachNotifications.buildAndNotify(applicationContext)
      Result.success()
    } catch (_: Exception) {
      Result.retry()
    }
  }
}
