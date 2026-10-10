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

/** Persistent on-phone history of everything fetched from the band. Never contains generated data. */
class BandSampleStore private constructor(context: Context) :
  SQLiteOpenHelper(context.applicationContext, "band_samples.db", null, 1) {

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE samples (ts INTEGER PRIMARY KEY, kind INTEGER NOT NULL, intensity INTEGER NOT NULL, " +
        "steps INTEGER NOT NULL, hr INTEGER NOT NULL, sleep INTEGER NOT NULL, deep INTEGER NOT NULL, rem INTEGER NOT NULL)"
    )
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

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
