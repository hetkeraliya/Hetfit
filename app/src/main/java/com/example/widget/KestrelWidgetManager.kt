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
    val steps: Int = 0,
    val stepGoal: Int = 8000,
    val calories: Int = 0,
    val heartRate: Int = 0,
    val sleepMinutes: Int = 0,
    val deepMin: Int = 0,
    val lightMin: Int = 0,
    val remMin: Int = 0,
    val stressNow: Int = 0,
    val stressAvg: Int = 0,
    val workoutName: String = "",
    val workoutMins: Int = 0,
    val workoutDistanceM: Float = 0f,
    val workoutCalories: Int = 0,
    val workoutAvgHr: Int = 0,
    val workoutStart: Long = 0L,
    val streak: Int = 0,
    val lastSyncTime: Long = 0L,
    val deviceName: String = "",
    val connected: Boolean = false,
    val battery: Int = -1
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
    private const val KEY_DEEP = "widget_deep_min"
    private const val KEY_LIGHT = "widget_light_min"
    private const val KEY_REM = "widget_rem_min"
    private const val KEY_STRESS_NOW = "widget_stress_now"
    private const val KEY_STRESS_AVG = "widget_stress_avg"
    private const val KEY_WO_DIST = "widget_wo_dist"
    private const val KEY_WO_CAL = "widget_wo_cal"
    private const val KEY_WO_HR = "widget_wo_hr"
    private const val KEY_WO_START = "widget_wo_start"
    private const val KEY_DEVICE = "widget_device"
    private const val KEY_CONNECTED = "widget_connected"
    private const val KEY_BATTERY = "widget_battery"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveHealthData(
        context: Context,
        steps: Int? = null,
        calories: Int? = null,
        heartRate: Int? = null,
        sleepMinutes: Int? = null,
        deepMin: Int? = null,
        lightMin: Int? = null,
        remMin: Int? = null,
        stressNow: Int? = null,
        stressAvg: Int? = null,
        workoutName: String? = null,
        workoutMins: Int? = null,
        workoutDistanceM: Float? = null,
        workoutCalories: Int? = null,
        workoutAvgHr: Int? = null,
        workoutStart: Long? = null,
        streak: Int? = null,
        deviceName: String? = null,
        connected: Boolean? = null,
        battery: Int? = null
    ) {
        val editor = getPrefs(context).edit()
        if (steps != null && steps >= 0) editor.putInt(KEY_STEPS, steps)
        if (calories != null && calories >= 0) editor.putInt(KEY_CALORIES, calories)
        if (heartRate != null && heartRate > 0) editor.putInt(KEY_HR, heartRate)
        if (sleepMinutes != null && sleepMinutes >= 0) editor.putInt(KEY_SLEEP, sleepMinutes)
        if (deepMin != null && deepMin >= 0) editor.putInt(KEY_DEEP, deepMin)
        if (lightMin != null && lightMin >= 0) editor.putInt(KEY_LIGHT, lightMin)
        if (remMin != null && remMin >= 0) editor.putInt(KEY_REM, remMin)
        if (stressNow != null && stressNow >= 0) editor.putInt(KEY_STRESS_NOW, stressNow)
        if (stressAvg != null && stressAvg >= 0) editor.putInt(KEY_STRESS_AVG, stressAvg)
        if (!workoutName.isNullOrEmpty()) editor.putString(KEY_WORKOUT_NAME, workoutName)
        if (workoutMins != null && workoutMins >= 0) editor.putInt(KEY_WORKOUT_MINS, workoutMins)
        if (workoutDistanceM != null && workoutDistanceM >= 0) editor.putFloat(KEY_WO_DIST, workoutDistanceM)
        if (workoutCalories != null && workoutCalories >= 0) editor.putInt(KEY_WO_CAL, workoutCalories)
        if (workoutAvgHr != null && workoutAvgHr > 0) editor.putInt(KEY_WO_HR, workoutAvgHr)
        if (workoutStart != null && workoutStart > 0) editor.putLong(KEY_WO_START, workoutStart)
        if (streak != null && streak >= 0) editor.putInt(KEY_STREAK, streak)
        if (deviceName != null) editor.putString(KEY_DEVICE, deviceName)
        if (connected != null) editor.putBoolean(KEY_CONNECTED, connected)
        if (battery != null) editor.putInt(KEY_BATTERY, battery)
        editor.putLong(KEY_LAST_SYNC, System.currentTimeMillis())
        editor.apply()
        updateAllWidgets(context)
    }

    fun getHealthData(context: Context): HealthWidgetData {
        val prefs = getPrefs(context)
        return HealthWidgetData(
            steps = prefs.getInt(KEY_STEPS, 0),
            stepGoal = prefs.getInt(KEY_GOAL, 8000),
            calories = prefs.getInt(KEY_CALORIES, 0),
            heartRate = prefs.getInt(KEY_HR, 0),
            sleepMinutes = prefs.getInt(KEY_SLEEP, 0),
            deepMin = prefs.getInt(KEY_DEEP, 0),
            lightMin = prefs.getInt(KEY_LIGHT, 0),
            remMin = prefs.getInt(KEY_REM, 0),
            stressNow = prefs.getInt(KEY_STRESS_NOW, 0),
            stressAvg = prefs.getInt(KEY_STRESS_AVG, 0),
            workoutName = prefs.getString(KEY_WORKOUT_NAME, "") ?: "",
            workoutMins = prefs.getInt(KEY_WORKOUT_MINS, 0),
            workoutDistanceM = prefs.getFloat(KEY_WO_DIST, 0f),
            workoutCalories = prefs.getInt(KEY_WO_CAL, 0),
            workoutAvgHr = prefs.getInt(KEY_WO_HR, 0),
            workoutStart = prefs.getLong(KEY_WO_START, 0L),
            streak = prefs.getInt(KEY_STREAK, 0),
            lastSyncTime = prefs.getLong(KEY_LAST_SYNC, 0L),
            deviceName = prefs.getString(KEY_DEVICE, "") ?: "",
            connected = prefs.getBoolean(KEY_CONNECTED, false),
            battery = prefs.getInt(KEY_BATTERY, -1)
        )
    }

    fun refresh(context: Context) {
        updateAllWidgets(context)
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

        val glassProviders = listOf(
            KestrelStepsRingWidgetProvider::class.java,
            KestrelHeartWidgetProvider::class.java,
            KestrelSleepWidgetProvider::class.java,
            KestrelStressWidgetProvider::class.java,
            KestrelWorkoutWidgetProvider::class.java,
            KestrelBandWidgetProvider::class.java
        )
        for (cls in glassProviders) {
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, cls))
            if (ids.isNotEmpty()) {
                val intent = Intent(context, cls).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }

    }
}
