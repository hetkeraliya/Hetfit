package com.example.ble

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import kotlin.reflect.KClass
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Pull health data that **Zepp / Zepp Life** (and other apps) already wrote onto this phone
 * via Android Health Connect — no root, no reading Zepp's private database.
 *
 * Setup:
 *  1. Zepp → Profile → 3rd-party account linking → Health Connect → allow Steps, HR, Sleep, etc.
 *  2. Sync the band in Zepp once so data is written to Health Connect.
 *  3. In Hetfit, grant the same read permissions and tap "Import from Zepp / Health Connect".
 */
object ZeppHealthConnectImporter {
  private const val TAG = "ZeppHC"

  val ZEPP_PACKAGES = listOf(
    "com.huami.watch.hmwatchmanager", // Zepp
    "com.xiaomi.hm.health",           // Zepp Life / Mi Fit
    "com.huami.midong"                // older Huami
  )

  private val PERMISSIONS = setOf(
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(SleepSessionRecord::class),
    HealthPermission.getReadPermission(DistanceRecord::class),
    HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    HealthPermission.getReadPermission(ExerciseSessionRecord::class)
  )

  fun status(context: Context): String {
    val o = org.json.JSONObject()
    val availability = try {
      HealthConnectClient.getSdkStatus(context)
    } catch (e: Exception) {
      o.put("available", false)
      o.put("message", "Health Connect not available: ${e.message}")
      return o.toString()
    }
    o.put("available", availability == HealthConnectClient.SDK_AVAILABLE)
    o.put("sdkStatus", availability)
    o.put("zeppInstalled", ZEPP_PACKAGES.any { isInstalled(context, it) })
    o.put("zeppPackages", org.json.JSONArray(ZEPP_PACKAGES.filter { isInstalled(context, it) }))
    when (availability) {
      HealthConnectClient.SDK_AVAILABLE -> o.put("message", "Health Connect ready")
      HealthConnectClient.SDK_UNAVAILABLE -> o.put("message", "Install Health Connect from Play Store (Android 9–13) or use Android 14+")
      HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> o.put("message", "Update Health Connect from Play Store")
      else -> o.put("message", "Health Connect status $availability")
    }
    return o.toString()
  }

  fun permissionStrings(): List<String> = PERMISSIONS.toList()

  fun permissionContractIntent(context: Context): Intent? {
    return try {
      // Permission controller is launched via Activity Result API in the UI layer.
      // This helper is for opening the Health Connect data screen.
      Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS).apply {
        if (Build.VERSION.SDK_INT >= 34) {
          // System Health Connect settings
        } else {
          setPackage("com.google.android.apps.healthdata")
        }
      }
    } catch (e: Exception) {
      Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
    }
  }

  private fun isInstalled(context: Context, pkg: String): Boolean =
    try {
      context.packageManager.getPackageInfo(pkg, 0)
      true
    } catch (_: PackageManager.NameNotFoundException) {
      false
    }

  /** Reads every page (Health Connect returns at most 1000 records per call). */
  private suspend fun <T : Record> readAll(
    client: HealthConnectClient, type: KClass<T>, range: TimeRangeFilter, origins: Set<DataOrigin>
  ): List<T> {
    val out = ArrayList<T>()
    var token: String? = null
    var pages = 0
    do {
      val resp = client.readRecords(
        ReadRecordsRequest(recordType = type, timeRangeFilter = range, dataOriginFilter = origins, pageSize = 1000, pageToken = token)
      )
      out.addAll(resp.records)
      token = resp.pageToken
      pages++
    } while (token != null && pages < 300)
    return out
  }

  /**
   * Read last [days] of data from Health Connect and merge into [BandSampleStore].
   * Returns a short human-readable summary.
   */
  suspend fun importLastDays(context: Context, days: Int = 14): String = withContext(Dispatchers.IO) {
    if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
      return@withContext "Health Connect is not available on this phone. Install/update it, then enable Zepp → Health Connect."
    }
    val client = HealthConnectClient.getOrCreate(context)
    val granted = try {
      client.permissionController.getGrantedPermissions()
    } catch (e: Exception) {
      return@withContext "Could not read permissions: ${e.message}"
    }
    val missing = PERMISSIONS - granted
    if (missing.isNotEmpty()) {
      return@withContext "Permission needed. Allow Steps, Heart rate and Sleep for Hetfit in Health Connect, then try again."
    }

    val end = Instant.now()
    val start = end.minus(days.toLong(), ChronoUnit.DAYS)
    val range = TimeRangeFilter.between(start, end)
    val store = BandSampleStore.get(context)
    val zone = ZoneId.systemDefault()

    // Prefer records written by Zepp itself (avoids double counting with the phone's own step counter).
    val zeppOrigins: Set<DataOrigin> = ZEPP_PACKAGES.map { DataOrigin(it) }.toSet()
    val hasZepp = try {
      readAll(client, StepsRecord::class, range, zeppOrigins).isNotEmpty() ||
        readAll(client, HeartRateRecord::class, range, zeppOrigins).isNotEmpty() ||
        readAll(client, SleepSessionRecord::class, range, zeppOrigins).isNotEmpty()
    } catch (e: Exception) { false }
    val origins: Set<DataOrigin> = if (hasZepp) zeppOrigins else emptySet()

    var stepsChunks = 0
    var hrPoints = 0
    var sleepSessions = 0
    var workouts = 0
    val samples = ArrayList<BandSample>(4096)

    // ---- Steps (interval records → distribute into minutes) ----
    try {
      val steps = readAll(client, StepsRecord::class, range, origins)
      for (r in steps) {
        val count = r.count.toInt().coerceAtLeast(0)
        if (count <= 0) continue
        val startSec = r.startTime.epochSecond
        val endSec = r.endTime.epochSecond.coerceAtLeast(startSec + 60)
        val minutes = ((endSec - startSec) / 60).toInt().coerceAtLeast(1)
        val perMin = (count / minutes).coerceAtLeast(0)
        var rem = count - perMin * minutes
        var t = (startSec / 60) * 60
        repeat(minutes) {
          val s = perMin + if (rem > 0) { rem--; 1 } else 0
          if (s > 0) samples.add(BandSample(t, kind = 1, intensity = 0, steps = s, hr = 0, sleep = 0, deep = 0, rem = 0))
          t += 60
        }
        stepsChunks++
      }
    } catch (e: Exception) {
      Log.w(TAG, "steps: ${e.message}")
    }

    // ---- Heart rate ----
    try {
      val hrs = readAll(client, HeartRateRecord::class, range, origins)
      for (r in hrs) {
        for (s in r.samples) {
          val bpm = s.beatsPerMinute.toInt()
          if (bpm in 30..220) {
            val ts = (s.time.epochSecond / 60) * 60
            samples.add(BandSample(ts, kind = 1, intensity = 0, steps = 0, hr = bpm, sleep = 0, deep = 0, rem = 0))
            hrPoints++
          }
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "hr: ${e.message}")
    }

    // ---- Sleep sessions → minute kinds (120 light / 121 deep / 122 REM) ----
    try {
      val sleeps = readAll(client, SleepSessionRecord::class, range, origins)
      for (sess in sleeps) {
        sleepSessions++
        val stages = sess.stages
        if (stages.isEmpty()) {
          // Whole session as light sleep
          var t = (sess.startTime.epochSecond / 60) * 60
          val endM = (sess.endTime.epochSecond / 60) * 60
          while (t < endM) {
            samples.add(BandSample(t, kind = 120, intensity = 0, steps = 0, hr = 0, sleep = 0, deep = 0, rem = 0))
            t += 60
          }
        } else {
          for (st in stages) {
            val kind = when (st.stage) {
              SleepSessionRecord.STAGE_TYPE_DEEP -> 121
              SleepSessionRecord.STAGE_TYPE_REM -> 122
              SleepSessionRecord.STAGE_TYPE_LIGHT -> 120
              SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> 1
              else -> 120
            }
            var t = (st.startTime.epochSecond / 60) * 60
            val endM = (st.endTime.epochSecond / 60) * 60
            while (t < endM) {
              samples.add(
                BandSample(
                  t, kind = kind, intensity = 0, steps = 0, hr = 0, sleep = 0,
                  deep = if (kind == 121) 50 else 0,
                  rem = if (kind == 122) 60 else 0
                )
              )
              t += 60
            }
          }
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "sleep: ${e.message}")
    }

    // ---- Workouts ----
    try {
      val ex = readAll(client, ExerciseSessionRecord::class, range, origins)
      for (e in ex) {
        val type = when (e.exerciseType) {
          ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> 1
          ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> 2
          ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> 3
          ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> 4
          ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> 8
          ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> 15
          ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> 6
          ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> 7
          ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> 0x34
          ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> 0x3c
          else -> 5
        }
        var wDist = 0; var wCal = 0; var wAvg = 0; var wMax = 0; var wSteps = 0
        try {
          val agg = client.aggregate(
            AggregateRequest(
              metrics = setOf(
                DistanceRecord.DISTANCE_TOTAL, TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX, StepsRecord.COUNT_TOTAL
              ),
              timeRangeFilter = TimeRangeFilter.between(e.startTime, e.endTime),
              dataOriginFilter = origins
            )
          )
          wDist = agg[DistanceRecord.DISTANCE_TOTAL]?.inMeters?.toInt() ?: 0
          wCal = agg[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.toInt() ?: 0
          wAvg = agg[HeartRateRecord.BPM_AVG]?.toInt() ?: 0
          wMax = agg[HeartRateRecord.BPM_MAX]?.toInt() ?: 0
          wSteps = agg[StepsRecord.COUNT_TOTAL]?.toInt() ?: 0
        } catch (ex: Exception) {
          Log.w(TAG, "workout totals: ${ex.message}")
        }
        store.insertWorkout(
          BandWorkoutRow(
            startSec = e.startTime.epochSecond,
            endSec = e.endTime.epochSecond,
            type = type,
            steps = wSteps,
            activeSec = (e.endTime.epochSecond - e.startTime.epochSecond).toInt().coerceAtLeast(0),
            calories = wCal,
            distanceM = wDist,
            avgHr = if (wAvg in 30..220) wAvg else 0,
            maxHr = if (wMax in 30..220) wMax else 0
          )
        )
        workouts++
      }
    } catch (e: Exception) {
      Log.w(TAG, "exercise: ${e.message}")
    }

    // Merge samples that share the same minute (HR + steps)
    val merged = LinkedHashMap<Long, BandSample>()
    for (s in samples) {
      val prev = merged[s.ts]
      if (prev == null) {
        merged[s.ts] = s
      } else {
        val kind = if (isSleepKind(s.kind)) s.kind else if (isSleepKind(prev.kind)) prev.kind else maxOf(prev.kind, s.kind)
        merged[s.ts] = BandSample(
          ts = s.ts,
          kind = kind,
          intensity = maxOf(prev.intensity, s.intensity),
          steps = maxOf(prev.steps, s.steps),
          hr = when {
            s.hr in 30..220 -> s.hr
            prev.hr in 30..220 -> prev.hr
            else -> 0
          },
          sleep = maxOf(prev.sleep, s.sleep),
          deep = maxOf(prev.deep, s.deep),
          rem = maxOf(prev.rem, s.rem)
        )
      }
    }
    val n = store.insertAll(merged.values.toList())

    buildString {
      append("Imported from phone (Health Connect / Zepp): ")
      append("$n minutes")
      if (sleepSessions > 0) append(", $sleepSessions sleep session(s)")
      if (hrPoints > 0) append(", $hrPoints HR samples")
      if (workouts > 0) append(", $workouts workout(s)")
      if (n == 0 && sleepSessions == 0 && hrPoints == 0) {
        append(". No data found — open Zepp, sync the band, enable Health Connect write, then try again.")
      }
    }
  }
}
