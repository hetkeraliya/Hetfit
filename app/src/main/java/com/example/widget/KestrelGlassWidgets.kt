package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.example.R
import com.example.ble.BandSyncService
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared plumbing for the frosted-glass widgets. */
abstract class GlassWidgetProvider : AppWidgetProvider() {
    abstract val layoutRes: Int
    abstract fun bind(context: Context, v: RemoteViews, d: HealthWidgetData)

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val data = KestrelWidgetManager.getHealthData(context)
        for (id in appWidgetIds) {
            val v = RemoteViews(context.packageName, layoutRes)
            bind(context, v, data)
            v.setOnClickPendingIntent(R.id.glass_root, KestrelWidgetManager.buildLaunchIntent(context))
            appWidgetManager.updateAppWidget(id, v)
        }
    }

    override fun onEnabled(context: Context) {
        KestrelWidgetManager.refresh(context)
    }

    protected fun num(n: Int): String = NumberFormat.getNumberInstance(Locale.getDefault()).format(n)

    protected fun hm(minutes: Int): String = if (minutes <= 0) "--" else "${minutes / 60}h ${minutes % 60}m"
}

class KestrelStepsRingWidgetProvider : GlassWidgetProvider() {
    override val layoutRes = R.layout.widget_glass_steps
    override fun bind(context: Context, v: RemoteViews, d: HealthWidgetData) {
        val pct = ((d.steps.toFloat() / d.stepGoal.coerceAtLeast(1)) * 100).toInt()
        v.setProgressBar(R.id.pb_ring, 100, pct.coerceIn(0, 100), false)
        v.setTextViewText(R.id.tv_value, num(d.steps))
        v.setTextViewText(R.id.tv_sub, "steps \u00b7 $pct%")
    }
}

class KestrelHeartWidgetProvider : GlassWidgetProvider() {
    override val layoutRes = R.layout.widget_glass_heart
    override fun bind(context: Context, v: RemoteViews, d: HealthWidgetData) {
        v.setTextViewText(R.id.tv_value, if (d.heartRate > 0) d.heartRate.toString() else "--")
        v.setTextViewText(
            R.id.tv_sub,
            when {
                d.heartRate <= 0 -> "No reading yet"
                d.heartRate < 60 -> "Resting"
                d.heartRate < 100 -> "Normal"
                d.heartRate < 140 -> "Active"
                else -> "High"
            }
        )
        v.setTextViewText(
            R.id.tv_foot,
            if (d.lastSyncTime > 0) "Updated " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(d.lastSyncTime)) else ""
        )
    }
}

class KestrelSleepWidgetProvider : GlassWidgetProvider() {
    override val layoutRes = R.layout.widget_glass_sleep
    override fun bind(context: Context, v: RemoteViews, d: HealthWidgetData) {
        v.setTextViewText(R.id.tv_value, hm(d.sleepMinutes))
        v.setProgressBar(R.id.pb_bar, 100, ((d.sleepMinutes / 480f) * 100).toInt().coerceIn(0, 100), false)
        v.setTextViewText(
            R.id.tv_sub,
            if (d.deepMin + d.lightMin + d.remMin > 0)
                "Deep ${hm(d.deepMin)} \u00b7 Light ${hm(d.lightMin)} \u00b7 REM ${hm(d.remMin)}"
            else "Goal 8h"
        )
    }
}

class KestrelStressWidgetProvider : GlassWidgetProvider() {
    override val layoutRes = R.layout.widget_glass_stress
    override fun bind(context: Context, v: RemoteViews, d: HealthWidgetData) {
        val now = d.stressNow
        v.setTextViewText(R.id.tv_value, if (now > 0) now.toString() else "--")
        v.setTextViewText(
            R.id.tv_sub,
            when {
                now <= 0 -> "No reading yet"
                now < 40 -> "Relaxed"
                now < 60 -> "Mild"
                now < 80 -> "Moderate"
                else -> "High"
            }
        )
        v.setProgressBar(R.id.pb_bar, 100, now.coerceIn(0, 100), false)
        v.setTextViewText(R.id.tv_foot, if (d.stressAvg > 0) "Average today ${d.stressAvg}" else "")
    }
}

class KestrelWorkoutWidgetProvider : GlassWidgetProvider() {
    override val layoutRes = R.layout.widget_glass_workout
    override fun bind(context: Context, v: RemoteViews, d: HealthWidgetData) {
        if (d.workoutName.isEmpty()) {
            v.setTextViewText(R.id.tv_value, "No workout yet")
            v.setTextViewText(R.id.tv_sub, "Start one on your band, then sync")
            v.setTextViewText(R.id.tv_foot, "")
            return
        }
        v.setTextViewText(R.id.tv_value, d.workoutName)
        val parts = ArrayList<String>()
        if (d.workoutMins > 0) parts.add("${d.workoutMins} min")
        if (d.workoutDistanceM > 0) parts.add(String.format(Locale.getDefault(), "%.2f km", d.workoutDistanceM / 1000f))
        if (d.workoutCalories > 0) parts.add("${d.workoutCalories} kcal")
        if (d.workoutAvgHr > 0) parts.add("${d.workoutAvgHr} bpm")
        v.setTextViewText(R.id.tv_sub, parts.joinToString("  \u00b7  "))
        v.setTextViewText(
            R.id.tv_foot,
            if (d.workoutStart > 0) SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(d.workoutStart)) else ""
        )
    }
}

class KestrelBandWidgetProvider : GlassWidgetProvider() {
    override val layoutRes = R.layout.widget_glass_band
    override fun bind(context: Context, v: RemoteViews, d: HealthWidgetData) {
        v.setTextViewText(R.id.tv_value, if (d.deviceName.isNotEmpty()) d.deviceName else "Mi Smart Band")
        v.setTextViewText(R.id.tv_sub, if (d.connected) "Connected" else "Disconnected")
        val foot = ArrayList<String>()
        if (d.battery >= 0) foot.add("${d.battery}% battery")
        if (d.lastSyncTime > 0) foot.add("synced " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(d.lastSyncTime)))
        v.setTextViewText(R.id.tv_foot, foot.joinToString("  \u00b7  "))
        v.setTextViewText(R.id.tv_action, if (d.connected) "Sync" else "Connect")
        val action = if (d.connected) BandSyncService.ACTION_SYNC else BandSyncService.ACTION_CONNECT
        val i = Intent(context, BandSyncService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(context, 21, i, flags)
        else PendingIntent.getService(context, 21, i, flags)
        v.setOnClickPendingIntent(R.id.tv_action, pi)
    }
}
