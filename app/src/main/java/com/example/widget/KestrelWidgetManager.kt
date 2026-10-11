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
import com.example.ble.BandSampleStore
import com.example.ble.HuamiSportsSummaryParser
import com.example.ble.ConnectionStatus
import com.example.ble.MiBandManager
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
    val lastSyncTime: Long,
    val battery: Int = -1,
    val connected: Boolean = false,
    val deviceName: String = "",
    val stressNow: Int = 0,
    val stressAvg: Int = 0,
    val deepMin: Int = 0,
    val lightMin: Int = 0,
    val remMin: Int = 0,
    val workoutStart: Long = 0L,
    val workoutCalories: Int = 0,
    val workoutDistanceM: Int = 0,
    val workoutAvgHr: Int = 0
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
    private const val KEY_BATTERY = "widget_battery"
    private const val KEY_CONNECTED = "widget_connected"
    private const val KEY_DEVICE = "widget_device"
    private const val KEY_STRESS_NOW = "widget_stress_now"
    private const val KEY_STRESS_AVG = "widget_stress_avg"
    private const val KEY_DEEP = "widget_deep"
    private const val KEY_LIGHT = "widget_light"
    private const val KEY_REM = "widget_rem"
    private const val KEY_W_START = "widget_workout_start"
    private const val KEY_W_CAL = "widget_workout_cal"
    private const val KEY_W_DIST = "widget_workout_dist"
    private const val KEY_W_HR = "widget_workout_hr"

    private val providers = listOf(
        KestrelHealthWidgetProvider::class.java,
        KestrelQuickStatsWidgetProvider::class.java,
        KestrelStepsRingWidgetProvider::class.java,
        KestrelHeartWidgetProvider::class.java,
        KestrelSleepWidgetProvider::class.java,
        KestrelStressWidgetProvider::class.java,
        KestrelWorkoutWidgetProvider::class.java,
        KestrelBandWidgetProvider::class.java
    )

    /**
     * Rebuilds every widget value from what the band really reported (local store + live connection state).
     * Streak / calories that come from the web UI are left untouched.
     */
    fun refresh(context: Context) {
        try {
            val store = BandSampleStore.get(context)
            val st = MiBandManager.get(context).uiState.value
            val t = store.todayTotals()
            val x = store.smartExtras()
            val w = store.workouts(1).firstOrNull()
            val e = getPrefs(context).edit()
            val steps = maxOf(t.steps, st.liveSteps)
            val hr = if (st.liveHeartRate > 0) st.liveHeartRate else t.lastHr
            e.putInt(KEY_STEPS, steps)
            if (hr > 0) e.putInt(KEY_HR, hr)
            e.putInt(KEY_SLEEP, t.sleepMinutes)
            e.putInt(KEY_BATTERY, st.batteryPercent)
            e.putBoolean(KEY_CONNECTED, st.status == ConnectionStatus.CONNECTED)
            e.putString(KEY_DEVICE, st.deviceName)
            e.putInt(KEY_STRESS_NOW, x.stressNow)
            e.putInt(KEY_STRESS_AVG, x.stressAvg)
            e.putInt(KEY_DEEP, x.deepMin); e.putInt(KEY_LIGHT, x.lightMin); e.putInt(KEY_REM, x.remMin)
            if (w != null) {
                e.putString(KEY_WORKOUT_NAME, HuamiSportsSummaryParser.name(w.type))
                e.putInt(KEY_WORKOUT_MINS, maxOf(1, ((w.endSec - w.startSec) / 60).toInt()))
                e.putLong(KEY_W_START, w.startSec * 1000)
                e.putInt(KEY_W_CAL, w.calories); e.putInt(KEY_W_DIST, w.distanceM); e.putInt(KEY_W_HR, w.avgHr)
            }
            if (st.lastSyncMillis > 0) e.putLong(KEY_LAST_SYNC, st.lastSyncMillis)
            e.apply()
        } catch (ex: Exception) {
            android.util.Log.w("KestrelWidgets", "refresh failed: ${ex.message}")
        }
        updateAllWidgets(context)
    }

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
        val steps = prefs.getInt(KEY_STEPS, 0)
        val goal = prefs.getInt(KEY_GOAL, 8000)
        val calories = prefs.getInt(KEY_CALORIES, 0)
        val hr = prefs.getInt(KEY_HR, 0)
        val sleep = prefs.getInt(KEY_SLEEP, 0)
        val workoutName = prefs.getString(KEY_WORKOUT_NAME, "") ?: ""
        val workoutMins = prefs.getInt(KEY_WORKOUT_MINS, 0)
        val streak = prefs.getInt(KEY_STREAK, 0)
        val lastSync = prefs.getLong(KEY_LAST_SYNC, 0L)

        return HealthWidgetData(
            steps = steps,
            stepGoal = goal,
            calories = calories,
            heartRate = hr,
            sleepMinutes = sleep,
            workoutName = workoutName,
            workoutMins = workoutMins,
            streak = streak,
            lastSyncTime = lastSync,
            battery = prefs.getInt(KEY_BATTERY, -1),
            connected = prefs.getBoolean(KEY_CONNECTED, false),
            deviceName = prefs.getString(KEY_DEVICE, "") ?: "",
            stressNow = prefs.getInt(KEY_STRESS_NOW, 0),
            stressAvg = prefs.getInt(KEY_STRESS_AVG, 0),
            deepMin = prefs.getInt(KEY_DEEP, 0),
            lightMin = prefs.getInt(KEY_LIGHT, 0),
            remMin = prefs.getInt(KEY_REM, 0),
            workoutStart = prefs.getLong(KEY_W_START, 0L),
            workoutCalories = prefs.getInt(KEY_W_CAL, 0),
            workoutDistanceM = prefs.getInt(KEY_W_DIST, 0),
            workoutAvgHr = prefs.getInt(KEY_W_HR, 0)
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
        val mgr = AppWidgetManager.getInstance(context)
        for (cls in providers) {
            val ids = mgr.getAppWidgetIds(ComponentName(context, cls))
            if (ids.isEmpty()) continue
            val intent = Intent(context, cls).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}
