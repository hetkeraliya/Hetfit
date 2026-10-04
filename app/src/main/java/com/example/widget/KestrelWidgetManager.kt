package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HealthWidgetData(
    val steps: Int,
    val stepGoal: Int,
    val calories: Int,
    val heartRate: Int,
    val sleepMinutes: Int,
    val workoutName: String,
    val workoutMins: Int,
    val streak: Int,
    val lastSyncTime: Long
)

object KestrelWidgetManager {
    private const val PREFS_NAME = "kestrel_health_widget_prefs"
    private const val KEY_STEPS = "widget_steps"
    private const val KEY_GOAL = "widget_step_goal"
    private const val KEY_CALORIES = "widget_calories"
    private const val KEY_HR = "widget_heart_rate"
    private const val KEY_SLEEP = "widget_sleep_minutes"
    private const val KEY_WORKOUT_NAME = "widget_workout_name"
    private const val KEY_WORKOUT_MINS = "widget_workout_mins"
    private const val KEY_STREAK = "widget_streak"
    private const val KEY_LAST_SYNC = "widget_last_sync"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveHealthData(
        context: Context,
        steps: Int? = null,
        calories: Int? = null,
        heartRate: Int? = null,
        sleepMinutes: Int? = null,
        workoutName: String? = null,
        workoutMins: Int? = null,
        streak: Int? = null
    ) {
        val editor = getPrefs(context).edit()
        if (steps != null && steps >= 0) editor.putInt(KEY_STEPS, steps)
        if (calories != null && calories >= 0) editor.putInt(KEY_CALORIES, calories)
        if (heartRate != null && heartRate > 0) editor.putInt(KEY_HR, heartRate)
        if (sleepMinutes != null && sleepMinutes >= 0) editor.putInt(KEY_SLEEP, sleepMinutes)
        if (!workoutName.isNullOrEmpty()) editor.putString(KEY_WORKOUT_NAME, workoutName)
        if (workoutMins != null && workoutMins >= 0) editor.putInt(KEY_WORKOUT_MINS, workoutMins)
        if (streak != null && streak >= 0) editor.putInt(KEY_STREAK, streak)
        editor.putLong(KEY_LAST_SYNC, System.currentTimeMillis())
        editor.apply()

        updateAllWidgets(context)
    }

    fun getHealthData(context: Context): HealthWidgetData {
        val prefs = getPrefs(context)
        val steps = prefs.getInt(KEY_STEPS, 8420)
        val goal = prefs.getInt(KEY_GOAL, 10000)
        val calories = prefs.getInt(KEY_CALORIES, 385)
        val hr = prefs.getInt(KEY_HR, 72)
        val sleep = prefs.getInt(KEY_SLEEP, 460)
        val workoutName = prefs.getString(KEY_WORKOUT_NAME, "Walking") ?: "Walking"
        val workoutMins = prefs.getInt(KEY_WORKOUT_MINS, 34)
        val streak = prefs.getInt(KEY_STREAK, 5)
        val lastSync = prefs.getLong(KEY_LAST_SYNC, System.currentTimeMillis())

        return HealthWidgetData(
            steps = steps,
            stepGoal = goal,
            calories = calories,
            heartRate = hr,
            sleepMinutes = sleep,
            workoutName = workoutName,
            workoutMins = workoutMins,
            streak = streak,
            lastSyncTime = lastSync
        )
    }

    fun buildLaunchIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, 0, intent, flags)
    }

    fun updateAllWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)

        // 1. Dashboard Widget
        val dashIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, KestrelHealthWidgetProvider::class.java)
        )
        if (dashIds.isNotEmpty()) {
            val intent = Intent(context, KestrelHealthWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, dashIds)
            }
            context.sendBroadcast(intent)
        }

        // 2. Quick Stats Widget
        val quickIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, KestrelQuickStatsWidgetProvider::class.java)
        )
        if (quickIds.isNotEmpty()) {
            val intent = Intent(context, KestrelQuickStatsWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, quickIds)
            }
            context.sendBroadcast(intent)
        }
    }
}
