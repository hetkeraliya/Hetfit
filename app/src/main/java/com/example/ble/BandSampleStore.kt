package com.example.ble

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** One minute of band data (Gadgetbridge / Huami 8-byte sample). */
data class BandSample(
  val ts: Long,        // epoch seconds, minute resolution
  val kind: Int,       // 120 = sleep, 115 = not worn, 118 = charging, others = awake/activity
  val intensity: Int,
  val steps: Int,
  val hr: Int,         // 0 or 255 = no reading
  val sleep: Int,
  val deep: Int,
  val rem: Int
)

data class SleepStage(val startTs: Long, val endTs: Long, val stage: String) // deep / light / rem / awake

data class SleepSession(
  val startTs: Long,
  val endTs: Long,
  val totalMinutes: Int,
  val deepMinutes: Int,
  val lightMinutes: Int,
  val remMinutes: Int,
  val awakeMinutes: Int,
  val stages: List<SleepStage>
)

data class TodayTotals(
  val steps: Int,
  val lastHr: Int,
  val sleepMinutes: Int,
  val deepMinutes: Int = 0,
  val lightMinutes: Int = 0,
  val remMinutes: Int = 0
)

/** Persistent on-phone history of everything fetched from the band. Never contains generated data. */
class BandSampleStore private constructor(context: Context) :
  SQLiteOpenHelper(context.applicationContext, "band_samples.db", null, 2) {

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE samples (ts INTEGER PRIMARY KEY, kind INTEGER NOT NULL, intensity INTEGER NOT NULL, " +
        "steps INTEGER NOT NULL, hr INTEGER NOT NULL, sleep INTEGER NOT NULL, deep INTEGER NOT NULL, rem INTEGER NOT NULL)"
    )
    db.execSQL(
      "CREATE TABLE workouts (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, start_ts INTEGER NOT NULL, " +
        "end_ts INTEGER, distance_m REAL, steps INTEGER, avg_hr INTEGER, max_hr INTEGER, calories INTEGER, " +
        "notes TEXT, source TEXT DEFAULT 'manual')"
    )
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
      db.execSQL(
        "CREATE TABLE IF NOT EXISTS workouts (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, start_ts INTEGER NOT NULL, " +
          "end_ts INTEGER, distance_m REAL, steps INTEGER, avg_hr INTEGER, max_hr INTEGER, calories INTEGER, " +
          "notes TEXT, source TEXT DEFAULT 'manual')"
      )
    }
  }

  @Synchronized
  fun insertAll(list: List<BandSample>): Int {
    if (list.isEmpty()) return 0
    val db = writableDatabase
    db.beginTransaction()
    try {
      val cv = ContentValues()
      for (s in list) {
        cv.clear()
        cv.put("ts", s.ts); cv.put("kind", s.kind); cv.put("intensity", s.intensity)
        cv.put("steps", s.steps); cv.put("hr", s.hr); cv.put("sleep", s.sleep)
        cv.put("deep", s.deep); cv.put("rem", s.rem)
        db.insertWithOnConflict("samples", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    return list.size
  }

  @Synchronized
  fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM samples", null).use {
    if (it.moveToFirst()) it.getInt(0) else 0
  }

  @Synchronized
  fun clear() { writableDatabase.delete("samples", null, null) }

  fun forEachChunk(sinceTs: Long, chunkSize: Int, block: (List<BandSample>) -> Unit) {
    val chunk = ArrayList<BandSample>(chunkSize)
    synchronized(this) {
      readableDatabase.rawQuery(
        "SELECT ts, kind, intensity, steps, hr, sleep, deep, rem FROM samples WHERE ts >= ? ORDER BY ts",
        arrayOf(sinceTs.toString())
      ).use { c ->
        while (c.moveToNext()) {
          val s = BandSample(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7))
          val hasHr = s.hr in 1..253
          if (s.kind != 120 && s.steps == 0 && !hasHr) continue
          chunk.add(s)
          if (chunk.size >= chunkSize) { block(ArrayList(chunk)); chunk.clear() }
        }
      }
    }
    if (chunk.isNotEmpty()) block(chunk)
  }

  @Synchronized
  fun getSleepSessions(fromTs: Long, toTs: Long): List<SleepSession> {
    val samples = mutableListOf<BandSample>()
    readableDatabase.rawQuery(
      "SELECT ts, kind, intensity, steps, hr, sleep, deep, rem FROM samples WHERE ts >= ? AND ts < ? ORDER BY ts",
      arrayOf(fromTs.toString(), toTs.toString())
    ).use { c ->
      while (c.moveToNext()) {
        samples.add(BandSample(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7)))
      }
    }
    if (samples.isEmpty()) return emptyList()

    val sessions = mutableListOf<SleepSession>()
    var i = 0
    while (i < samples.size) {
      if (samples[i].kind != 120 && samples[i].deep == 0 && samples[i].rem == 0) {
        i++
        continue
      }
      val start = samples[i].ts
      var end = start
      var deep = 0
      var light = 0
      var rem = 0
      var awake = 0
      val stages = mutableListOf<SleepStage>()
      var stageStart = start
      var currentStage = classifyStage(samples[i])

      while (i < samples.size) {
        val s = samples[i]
        val isSleep = s.kind == 120 || s.deep > 0 || s.rem > 0
        if (!isSleep && (s.ts - end) > 8 * 60) break
        if (isSleep) {
          end = s.ts
          when (classifyStage(s)) {
            "deep" -> deep++
            "rem" -> rem++
            "light" -> light++
            else -> light++
          }
          val st = classifyStage(s)
          if (st != currentStage) {
            stages.add(SleepStage(stageStart, s.ts, currentStage))
            stageStart = s.ts
            currentStage = st
          }
        } else {
          awake++
        }
        i++
      }
      stages.add(SleepStage(stageStart, end + 60, currentStage))
      val total = ((end - start) / 60).toInt() + 1
      if (total >= 20) {
        sessions.add(
          SleepSession(start, end + 60, total, deep, light, rem, awake, stages)
        )
      }
    }
    return sessions
  }

  private fun classifyStage(s: BandSample): String {
    return when {
      s.deep > 0 || (s.kind == 120 && s.intensity <= 10) -> "deep"
      s.rem > 0 -> "rem"
      s.kind == 120 -> "light"
      else -> "awake"
    }
  }

  @Synchronized
  fun lastNightSleep(): SleepSession? {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 18); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    val toTs = cal.timeInMillis / 1000
    cal.add(Calendar.DAY_OF_YEAR, -1)
    cal.set(Calendar.HOUR_OF_DAY, 18)
    val fromTs = cal.timeInMillis / 1000
    return getSleepSessions(fromTs, toTs).maxByOrNull { it.totalMinutes }
  }

  @Synchronized
  fun todayTotals(): TodayTotals {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    val dayStart = cal.timeInMillis / 1000
    val db = readableDatabase
    val steps = db.rawQuery("SELECT COALESCE(SUM(steps),0) FROM samples WHERE ts >= ?", arrayOf(dayStart.toString()))
      .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    val hr = db.rawQuery(
      "SELECT hr FROM samples WHERE hr BETWEEN 30 AND 220 AND ts >= ? ORDER BY ts DESC LIMIT 1",
      arrayOf((dayStart - 3600).toString())
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    val session = lastNightSleep()
    return if (session != null) {
      TodayTotals(steps, hr, session.totalMinutes, session.deepMinutes, session.lightMinutes, session.remMinutes)
    } else {
      val sleepFrom = dayStart - 6 * 3600
      val sleepTo = dayStart + 18 * 3600
      val sleep = db.rawQuery(
        "SELECT COUNT(*) FROM samples WHERE kind = 120 AND ts >= ? AND ts < ?",
        arrayOf(sleepFrom.toString(), sleepTo.toString())
      ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
      TodayTotals(steps, hr, sleep)
    }
  }

  @Synchronized
  fun insertWorkout(
    type: String, startTs: Long, endTs: Long? = null,
    distanceM: Double? = null, steps: Int? = null,
    avgHr: Int? = null, maxHr: Int? = null, calories: Int? = null,
    notes: String? = null, source: String = "manual"
  ): Long {
    val cv = ContentValues().apply {
      put("type", type); put("start_ts", startTs); put("end_ts", endTs)
      put("distance_m", distanceM); put("steps", steps); put("avg_hr", avgHr)
      put("max_hr", maxHr); put("calories", calories); put("notes", notes); put("source", source)
    }
    return writableDatabase.insert("workouts", null, cv)
  }

  @Synchronized
  fun updateWorkoutEnd(id: Long, endTs: Long, distanceM: Double?, steps: Int?, avgHr: Int?, maxHr: Int?, calories: Int?) {
    val cv = ContentValues().apply {
      put("end_ts", endTs); put("distance_m", distanceM); put("steps", steps)
      put("avg_hr", avgHr); put("max_hr", maxHr); put("calories", calories)
    }
    writableDatabase.update("workouts", cv, "id = ?", arrayOf(id.toString()))
  }

  @Synchronized
  fun getWorkouts(limit: Int = 50): JSONArray {
    val arr = JSONArray()
    readableDatabase.rawQuery(
      "SELECT id, type, start_ts, end_ts, distance_m, steps, avg_hr, max_hr, calories, notes, source FROM workouts ORDER BY start_ts DESC LIMIT ?",
      arrayOf(limit.toString())
    ).use { c ->
      while (c.moveToNext()) {
        arr.put(JSONObject().apply {
          put("id", c.getLong(0)); put("type", c.getString(1)); put("startTs", c.getLong(2))
          if (!c.isNull(3)) put("endTs", c.getLong(3))
          if (!c.isNull(4)) put("distanceM", c.getDouble(4))
          if (!c.isNull(5)) put("steps", c.getInt(5))
          if (!c.isNull(6)) put("avgHr", c.getInt(6))
          if (!c.isNull(7)) put("maxHr", c.getInt(7))
          if (!c.isNull(8)) put("calories", c.getInt(8))
          if (!c.isNull(9)) put("notes", c.getString(9))
          put("source", c.getString(10))
        })
      }
    }
    return arr
  }

  fun sleepSummaryJson(): JSONObject {
    val session = lastNightSleep()
    return JSONObject().apply {
      if (session != null) {
        put("hasData", true)
        put("startTs", session.startTs); put("endTs", session.endTs)
        put("totalMinutes", session.totalMinutes)
        put("deepMinutes", session.deepMinutes); put("lightMinutes", session.lightMinutes)
        put("remMinutes", session.remMinutes); put("awakeMinutes", session.awakeMinutes)
        val stages = JSONArray()
        session.stages.forEach { st ->
          stages.put(JSONObject().apply {
            put("start", st.startTs); put("end", st.endTs); put("stage", st.stage)
          })
        }
        put("stages", stages)
      } else {
        put("hasData", false)
      }
    }
  }

  companion object {
    @Volatile private var instance: BandSampleStore? = null
    fun get(context: Context): BandSampleStore =
      instance ?: synchronized(this) { instance ?: BandSampleStore(context).also { instance = it } }
  }
}
