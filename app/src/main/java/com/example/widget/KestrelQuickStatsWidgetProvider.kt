package com.example.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.example.R
import java.text.NumberFormat
import java.util.Locale

class KestrelQuickStatsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val healthData = KestrelWidgetManager.getHealthData(context)
        val numFmt = NumberFormat.getNumberInstance(Locale.US)

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_stats)

            val stepsFmt = numFmt.format(healthData.steps)
            val progressPct = ((healthData.steps.toFloat() / healthData.stepGoal.coerceAtLeast(1)) * 100).toInt()

            views.setTextViewText(R.id.tv_quick_steps, stepsFmt)
            views.setTextViewText(R.id.tv_quick_sub, "steps • $progressPct% goal")
            views.setProgressBar(R.id.pb_quick_steps, 100, progressPct.coerceIn(0, 100), false)

            views.setTextViewText(R.id.tv_quick_hr, healthData.heartRate.toString())
            views.setTextViewText(R.id.tv_quick_cal, numFmt.format(healthData.calories))
            views.setTextViewText(R.id.tv_quick_streak, "🔥 ${healthData.streak}d")

            // Tap anywhere on widget to open Kestrel App
            views.setOnClickPendingIntent(R.id.quick_widget_root, KestrelWidgetManager.buildLaunchIntent(context))

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
