package com.example.ble

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

/** One minute of band data, exactly as the Mi Band 5 reports it (see Gadgetbridge FetchActivityOperation). */
data class BandSample(
  val ts: Long,        // epoch seconds, minute resolution
  val kind: Int,       // raw kind: 120 = sleep, 115 = not worn, 118 = charging, others = awake
  val intensity: Int,
  val steps: Int,
  val hr: Int,         // 0 or 255 = no reading
  val sleep: Int,
  val deep: Int,
  val rem: Int
)

data class TodayTotals(val steps: Int, val lastHr: Int, val sleepMinutes: Int)

/** A workout summary reported by the band (Gadgetbridge FetchSportsSummaryOperation). */
data class BandWorkout(
  val startSec: Long,
  val endSec: Long,
  val type: Int,
  val steps: Int,
  val activeSec: Int,
  val calories: Int,
  val distanceM: Int,
  val avgHr: Int,
  val maxHr: Int
)

/** Per-day values derived from stress and sleep-stage samples. Null = no data for that day. */
data class DayExtras(
  var stressAvg: Int? = null,
  var stressMax: Int? = null,
  var deepMin: Int? = null,
  var lightMin: Int? = null,
  var remMin: Int? = null,
  var sleepMin: Int? = null,
  var sleepStartTs: Long? = null,
  var sleepEndTs: Long? = null,
  var paiToday: Float? = null,
  var paiTotal: Float? = null
)

/** One GPS fix of a recorded run. */
data class TrackPoint(val ts: Long, val lat: Double, val lon: Double, val alt: Double, val hr: Int)

data class SmartExtras(val stressNow: Int, val stressAvg: Int, val deepMin: Int, val lightMin: Int, val remMin: Int)

/** Persistent on-phone history of everything fetched from the band. Never contains generated data. */
class BandSampleStore private constructor(context: Context) :
  SQLiteOpenHelper(context.applicationContext, "band_samples.db", null, 2) {

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE samples (ts INTEGER PRIMARY KEY, kind INTEGER NOT NULL, intensity INTEGER NOT NULL, " +
        "steps INTEGER NOT NULL, hr INTEGER NOT NULL, sleep INTEGER NOT NULL, deep INTEGER NOT NULL, rem INTEGER NOT NULL)"
    )
  }

  override fun onOpen(db: SQLiteDatabase) {
    super.onOpen(db)
    createExtraTables(db)
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    createExtraTables(db)
  }

  private fun createExtraTables(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS stress (ts INTEGER PRIMARY KEY, value INTEGER NOT NULL)")
    db.execSQL("CREATE TABLE IF NOT EXISTS pai (ts INTEGER PRIMARY KEY, today REAL NOT NULL, total REAL NOT NULL)")
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS tracks (start_ts INTEGER NOT NULL, seq INTEGER NOT NULL, ts INTEGER NOT NULL, lat REAL NOT NULL, " +
        "lon REAL NOT NULL, alt REAL NOT NULL, hr INTEGER NOT NULL, PRIMARY KEY (start_ts, seq))"
    )
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS workouts (start_ts INTEGER PRIMARY KEY, end_ts INTEGER NOT NULL, type INTEGER NOT NULL, " +
        "steps INTEGER NOT NULL, active_s INTEGER NOT NULL, calories INTEGER NOT NULL, distance_m INTEGER NOT NULL, " +
        "avg_hr INTEGER NOT NULL, max_hr INTEGER NOT NULL)"
    )
  }

  @Synchronized
  fun insertStress(list: List<Pair<Long, Int>>) {
    if (list.isEmpty()) return
    val db = writableDatabase
    db.beginTransaction()
    try {
      val cv = ContentValues()
      for ((ts, v) in list) {
        cv.clear(); cv.put("ts", ts); cv.put("value", v)
        db.insertWithOnConflict("stress", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
      }
      db.setTransactionSuccessful()
    } finally { db.endTransaction() }
  }

  @Synchronized
  fun insertPai(list: List<Triple<Long, Float, Float>>) {
    if (list.isEmpty()) return
    val db = writableDatabase
    db.beginTransaction()
    try {
      val cv = ContentValues()
      for ((ts, today, total) in list) {
        cv.clear(); cv.put("ts", ts); cv.put("today", today); cv.put("total", total)
        db.insertWithOnConflict("pai", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
      }
      db.setTransactionSuccessful()
    } finally { db.endTransaction() }
  }

  @Synchronized
  fun insertWorkout(w: BandWorkout) {
    val cv = ContentValues()
    cv.put("start_ts", w.startSec); cv.put("end_ts", w.endSec); cv.put("type", w.type)
    cv.put("steps", w.steps); cv.put("active_s", w.activeSec); cv.put("calories", w.calories)
    cv.put("distance_m", w.distanceM); cv.put("avg_hr", w.avgHr); cv.put("max_hr", w.maxHr)
    writableDatabase.insertWithOnConflict("workouts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
  }

  @Synchronized
  fun workouts(limit: Int = 300): List<BandWorkout> {
    val out = ArrayList<BandWorkout>()
    readableDatabase.rawQuery(
      "SELECT start_ts, end_ts, type, steps, active_s, calories, distance_m, avg_hr, max_hr FROM workouts ORDER BY start_ts DESC LIMIT $limit", null
    ).use { c ->
      while (c.moveToNext()) out.add(BandWorkout(c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7), c.getInt(8)))
    }
    return out
  }

  private fun dateKey(cal: Calendar, tsSec: Long, nightShift: Boolean): String {
    cal.timeInMillis = tsSec * 1000
    if (nightShift && cal.get(Calendar.HOUR_OF_DAY) >= 18) cal.add(Calendar.DAY_OF_YEAR, 1)
    return "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
  }

  /** Stress average/max per day and deep/light/REM sleep minutes per night, from rows newer than [sinceTs]. */
  @Synchronized
  fun dayExtras(sinceTs: Long): Map<String, DayExtras> {
    val out = HashMap<String, DayExtras>()
    val cal = Calendar.getInstance()
    val db = readableDatabase
    val sum = HashMap<String, IntArray>() // [sum, count, max]
    db.rawQuery("SELECT ts, value FROM stress WHERE ts >= ? AND value BETWEEN 1 AND 100", arrayOf(sinceTs.toString())).use { c ->
      while (c.moveToNext()) {
        val k = dateKey(cal, c.getLong(0), false)
        val a = sum.getOrPut(k) { intArrayOf(0, 0, 0) }
        val v = c.getInt(1)
        a[0] += v; a[1]++; if (v > a[2]) a[2] = v
      }
    }
    for ((k, a) in sum) {
      val e = out.getOrPut(k) { DayExtras() }
      e.stressAvg = Math.round(a[0].toFloat() / a[1]); e.stressMax = a[2]
    }
    db.rawQuery("SELECT ts, today, total FROM pai WHERE ts >= ? ORDER BY ts", arrayOf(sinceTs.toString())).use { c ->
      while (c.moveToNext()) {
        val e = out.getOrPut(dateKey(cal, c.getLong(0), false)) { DayExtras() }
        e.paiToday = c.getFloat(1); e.paiTotal = c.getFloat(2)   // later rows of the same day win
      }
    }
    db.rawQuery("SELECT ts, sleep, deep, rem FROM samples WHERE kind = 120 AND ts >= ?", arrayOf(sinceTs.toString())).use { c ->
      while (c.moveToNext()) {
        val k = dateKey(cal, c.getLong(0), true)
        val e = out.getOrPut(k) { DayExtras() }
        val deep = c.getInt(2) and 127
        val rem = c.getInt(3) and 127
        val t = c.getLong(0)
        e.sleepMin = (e.sleepMin ?: 0) + 1
        if (e.sleepStartTs == null || t < e.sleepStartTs!!) e.sleepStartTs = t
        if (e.sleepEndTs == null || t + 60 > e.sleepEndTs!!) e.sleepEndTs = t + 60
        when {
          rem > 55 -> e.remMin = (e.remMin ?: 0) + 1
          deep > 42 -> e.deepMin = (e.deepMin ?: 0) + 1
          else -> e.lightMin = (e.lightMin ?: 0) + 1
        }
      }
    }
    return out
  }

  @Synchronized
  fun insertTrack(startSec: Long, pts: List<TrackPoint>) {
    if (pts.isEmpty()) return
    val db = writableDatabase
    db.beginTransaction()
    try {
      val cv = ContentValues()
      pts.forEachIndexed { i, p ->
        cv.clear()
        cv.put("start_ts", startSec); cv.put("seq", i); cv.put("ts", p.ts)
        cv.put("lat", p.lat); cv.put("lon", p.lon); cv.put("alt", p.alt); cv.put("hr", p.hr)
        db.insertWithOnConflict("tracks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
      }
      db.setTransactionSuccessful()
    } finally { db.endTransaction() }
  }

  @Synchronized
  fun getTrack(startSec: Long, maxPoints: Int = 2500): List<TrackPoint> {
    val all = ArrayList<TrackPoint>()
    readableDatabase.rawQuery(
      "SELECT ts, lat, lon, alt, hr FROM tracks WHERE start_ts = ? ORDER BY seq", arrayOf(startSec.toString())
    ).use { c ->
      while (c.moveToNext()) all.add(TrackPoint(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3), c.getInt(4)))
    }
    if (all.size <= maxPoints) return all
    val step = Math.ceil(all.size / maxPoints.toDouble()).toInt()
    val out = ArrayList<TrackPoint>()
    for (i in all.indices step step) out.add(all[i])
    if (out.last() !== all.last()) out.add(all.last())
    return out
  }

  @Synchronized
  fun trackStarts(): Set<Long> {
    val out = HashSet<Long>()
    readableDatabase.rawQuery("SELECT DISTINCT start_ts FROM tracks", null).use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
    return out
  }

  @Synchronized
  fun deleteWorkout(startSec: Long) {
    val db = writableDatabase
    db.delete("workouts", "start_ts = ?", arrayOf(startSec.toString()))
    db.delete("tracks", "start_ts = ?", arrayOf(startSec.toString()))
  }

  /** Cleared so the next sync downloads everything the band still holds (sleep repair). */
  @Synchronized
  fun resetForResync() {
    val db = writableDatabase
    db.delete("samples", null, null)
    db.delete("stress", null, null)
  }

  @Synchronized
  fun diagnostics(): String {
    val db = readableDatabase
    fun one(sql: String): Long = db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    val total = one("SELECT COUNT(*) FROM samples")
    val sleepRows = one("SELECT COUNT(*) FROM samples WHERE kind = 120")
    val first = one("SELECT COALESCE(MIN(ts),0) FROM samples")
    val last = one("SELECT COALESCE(MAX(ts),0) FROM samples")
    val lastSleep = one("SELECT COALESCE(MAX(ts),0) FROM samples WHERE kind = 120")
    val stressRows = one("SELECT COUNT(*) FROM stress")
    val workouts = one("SELECT COUNT(*) FROM workouts")
    return "{\"samples\":$total,\"sleepRows\":$sleepRows,\"first\":$first,\"last\":$last,\"lastSleep\":$lastSleep," +
      "\"stress\":$stressRows,\"workouts\":$workouts}"
  }

  /** Sleep segments (start, end, stage 0=light 1=deep 2=REM) between two timestamps, for the sleep chart. */
  @Synchronized
  fun sleepSegments(fromTs: Long, toTs: Long): List<LongArray> {
    val out = ArrayList<LongArray>()
    readableDatabase.rawQuery(
      "SELECT ts, deep, rem FROM samples WHERE kind = 120 AND ts >= ? AND ts < ? ORDER BY ts", arrayOf(fromTs.toString(), toTs.toString())
    ).use { c ->
      var cur: LongArray? = null
      while (c.moveToNext()) {
        val t = c.getLong(0)
        val d = c.getInt(1) and 127; val r = c.getInt(2) and 127
        val stage = when { r > 55 -> 2L; d > 42 -> 1L; else -> 0L }
        val seg = cur
        if (seg != null && seg[2] == stage && t - seg[1] <= 60) { seg[1] = t + 60 }
        else { val n = longArrayOf(t, t + 60, stage); out.add(n); cur = n }
      }
    }
    return out
  }

  /** For widgets / notification: latest stress, today's average, last night's sleep stages. */
  @Synchronized
  fun smartExtras(): SmartExtras {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    val dayStart = cal.timeInMillis / 1000
    val db = readableDatabase
    val now = db.rawQuery("SELECT value FROM stress WHERE value BETWEEN 1 AND 100 ORDER BY ts DESC LIMIT 1", null)
      .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    val avg = db.rawQuery("SELECT COALESCE(AVG(value),0) FROM stress WHERE value BETWEEN 1 AND 100 AND ts >= ?", arrayOf(dayStart.toString()))
      .use { if (it.moveToFirst()) Math.round(it.getFloat(0)) else 0 }
    var deep = 0; var light = 0; var rem = 0
    db.rawQuery(
      "SELECT deep, rem FROM samples WHERE kind = 120 AND ts >= ? AND ts < ?",
      arrayOf((dayStart - 6 * 3600).toString(), (dayStart + 18 * 3600).toString())
    ).use { c ->
      while (c.moveToNext()) {
        val d = c.getInt(0) and 127; val r = c.getInt(1) and 127
        when { r > 55 -> rem++; d > 42 -> deep++; else -> light++ }
      }
    }
    return SmartExtras(now, avg, deep, light, rem)
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

  /**
   * Streams rows newer than [sinceTs] (epoch seconds) in chunks. Rows that carry no information
   * (no steps, no heart rate, not sleeping) are skipped to keep the transfer to the web UI small.
   */
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
  fun todayTotals(): TodayTotals {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    val dayStart = cal.timeInMillis / 1000
    val db = readableDatabase
    val steps = db.rawQuery("SELECT COALESCE(SUM(steps),0) FROM samples WHERE ts >= ?", arrayOf(dayStart.toString()))
      .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    val hr = db.rawQuery("SELECT hr FROM samples WHERE hr BETWEEN 30 AND 220 AND ts >= ? ORDER BY ts DESC LIMIT 1", arrayOf((dayStart - 3600).toString()))
      .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    // Last night = yesterday 18:00 .. today 18:00
    val sleepFrom = dayStart - 6 * 3600
    val sleepTo = dayStart + 18 * 3600
    val sleep = db.rawQuery("SELECT COUNT(*) FROM samples WHERE kind = 120 AND ts >= ? AND ts < ?", arrayOf(sleepFrom.toString(), sleepTo.toString()))
      .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    return TodayTotals(steps, hr, sleep)
  }

  companion object {
    @Volatile private var instance: BandSampleStore? = null
    fun get(context: Context): BandSampleStore =
      instance ?: synchronized(this) { instance ?: BandSampleStore(context).also { instance = it } }
  }
}
