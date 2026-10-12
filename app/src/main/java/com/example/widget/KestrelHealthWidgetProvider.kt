package com.example.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.example.R
import java.text.NumberFormat
import java.util.Locale

class KestrelHealthWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val healthData = KestrelWidgetManager.getHealthData(context)
        val numFmt = NumberFormat.getNumberInstance(Locale.US)

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_health_dashboard)

            val stepsFmt = numFmt.format(healthData.steps)
            val goalFmt = numFmt.format(healthData.stepGoal)
            val progressPct = ((healthData.steps.toFloat() / healthData.stepGoal.coerceAtLeast(1)) * 100).toInt()

            views.setTextViewText(R.id.tv_widget_steps, stepsFmt)
            views.setTextViewText(R.id.tv_widget_goal_sub, "$progressPct% of $goalFmt goal")
            views.setProgressBar(R.id.pb_widget_steps, 100, progressPct.coerceIn(0, 100), false)

            views.setTextViewText(R.id.tv_widget_hr, if (healthData.heartRate > 0) healthData.heartRate.toString() else "--")
            views.setTextViewText(R.id.tv_widget_cal, numFmt.format(healthData.calories))

            val sleepTxt = if (healthData.sleepMinutes > 0) {
                val h = healthData.sleepMinutes / 60
                val m = healthData.sleepMinutes % 60
                if (h > 0) "${h}h ${m}m" else "${m}m"
            } else "—"
            views.setTextViewText(R.id.tv_widget_sleep, sleepTxt)

            val workoutText = if (healthData.workoutMins > 0) {
                "${healthData.workoutName} ${healthData.workoutMins}m"
            } else {
                if (healthData.workoutName.isNotEmpty()) healthData.workoutName else "No workout"
            }
            views.setTextViewText(R.id.tv_widget_workout, workoutText)
            views.setTextViewText(R.id.tv_widget_streak, "🔥 ${healthData.streak}d")

            val syncLabel = when {
                healthData.lastSyncTime == 0L -> "No data"
                System.currentTimeMillis() - healthData.lastSyncTime < 180000 -> "Live"
                else -> "Synced"
            }
            views.setTextViewText(R.id.tv_widget_sync, syncLabel)

            // Tap anywhere on widget to open Kestrel App
            views.setOnClickPendingIntent(R.id.widget_root, KestrelWidgetManager.buildLaunchIntent(context))

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
