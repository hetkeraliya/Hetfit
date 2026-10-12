package com.example.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.content.SharedPreferences
import android.os.Handler
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.UUID

private const val TAG = "HuamiFetch"

/** fee0 service: 0x0004 = fetch control, 0x0005 = activity data (Gadgetbridge HuamiService). */
val FETCH_CONTROL_UUID: UUID = UUID.fromString("00000004-0000-3512-2118-0009af100700")
val FETCH_DATA_UUID: UUID = UUID.fromString("00000005-0000-3512-2118-0009af100700")

/** Thin GATT facade implemented by MiBandManager so the fetcher shares its operation queue. */
interface BandGattIo {
  fun characteristic(uuid: UUID): BluetoothGattCharacteristic?
  fun setNotify(ch: BluetoothGattCharacteristic, enable: Boolean)
  fun write(ch: BluetoothGattCharacteristic, value: ByteArray)
}

/** What we pull from the band, in order. Each type keeps its own "last synced" timestamp. */
private enum class FetchKind(val code: Byte, val prefKey: String, val label: String) {
  ACTIVITY(0x01, "fetch_last_sync_ms", "activity"),
  STRESS(0x13, "fetch_last_stress_ms", "stress"),
  SPORTS(0x05, "fetch_last_sports_ms", "workouts"),
  PAI(0x0d, "fetch_last_pai_ms", "PAI")
}

/**
 * Port of Gadgetbridge's AbstractFetchOperation + FetchActivityOperation for the Mi Band 5
 * (legacy Huami protocol, 8-byte samples, one per minute).
 *
 * Protocol:
 *  1. write  [0x01, 0x01, <since: yyyy(le) MM dd HH mm> , 0x00, tz]   to 0x0004
 *  2. notify [0x10, 0x01, 0x01, len(u32 le), <start: yyyy(le) MM dd HH mm ss tz>] on 0x0004
 *  3. enable notifications on 0x0005, write [0x02] to 0x0004
 *  4. 0x0005 streams [counter, data...] packets
 *  5. notify [0x10, 0x02, 0x01, (crc)] on 0x0004 = transfer finished
 *  Repeats from the last saved timestamp until the band has nothing newer, for activity (0x01),
 *  automatic stress (0x13) and workout summaries (0x05).
 *  Data is NOT acknowledged, so it stays on the band (same as Gadgetbridge "keep data on device").
 */
class HuamiActivityFetcher(
  private val io: BandGattIo,
  private val store: BandSampleStore,
  private val prefs: SharedPreferences,
  private val handler: Handler,
  private val listener: Listener
) {
  interface Listener {
    fun onProgress(percent: Int, message: String)
    fun onFinished(success: Boolean, newSamples: Int, message: String)
  }

  private val buffer = ByteArrayOutputStream(4096)
  private var control: BluetoothGattCharacteristic? = null
  private var data: BluetoothGattCharacteristic? = null

  @Volatile var running = false
    private set
  private var waitingData = false
  private var operationValid = true
  private var lastCounter = -1
  private var expectedLen = 0
  private var chunkStartTs = 0L
  private var rounds = 0
  private var total = 0
  private var workoutsSaved = 0
  private var stressSaved = 0
  private val queue = ArrayDeque<FetchKind>()
  private var current = FetchKind.ACTIVITY

  private val watchdog = Runnable {
    if (!running) return@Runnable
    if (total > 0 || current != FetchKind.ACTIVITY) {
      // Activity already saved (or an optional type is unsupported on this band): carry on with the next type.
      Log.w(TAG, "No answer for ${current.label}, skipping")
      waitingData = false
      nextTask()
    } else {
      finish(false, "Band stopped responding. Check the auth key and press Sync again.")
    }
  }

  fun start(): Boolean {
    if (running) return false
    control = io.characteristic(FETCH_CONTROL_UUID)
    data = io.characteristic(FETCH_DATA_UUID)
    if (control == null || data == null) {
      listener.onFinished(false, 0, "This band does not expose the history service yet.")
      return false
    }
    running = true
    total = 0
    workoutsSaved = 0
    stressSaved = 0
    queue.clear()
    queue.addAll(FetchKind.values())
    listener.onProgress(0, "Syncing history…")
    io.setNotify(control!!, true)
    nextTask()
    return true
  }

  fun cancel() { if (running) finish(false, "Sync cancelled") }

  private fun nextTask() {
    if (queue.isEmpty()) {
      val extra = buildString {
        if (stressSaved > 0) append(", stress")
        if (workoutsSaved > 0) append(", $workoutsSaved workout(s)")
      }
      finish(true, "Synced $total minutes$extra")
      return
    }
    current = queue.removeFirst()
    rounds = 0
    beginRound()
  }

  private fun lastSyncMs(): Long {
    val saved = prefs.getLong(current.prefKey, 0L)
    if (saved > 0) return saved
    // First ever sync: ask for everything the band still has (Gadgetbridge default is 100 days).
    return System.currentTimeMillis() - 100L * 24 * 3600 * 1000
  }

  private fun beginRound() {
    rounds++
    buffer.reset()
    lastCounter = -1
    operationValid = true
    expectedLen = 0
    waitingData = false
    io.setNotify(data!!, false)
    // The band labels a night as "sleep" only after you wake up. Always re-read the last 36 hours
    // so last night (and naps) are pulled even if a previous sync already advanced the cursor.
    // Rows are replaced by timestamp, so nothing gets duplicated.
    val lookback = if (current == FetchKind.ACTIVITY && rounds == 1) 36L * 3600 * 1000 else 0L
    val cal = GregorianCalendar().apply { timeInMillis = lastSyncMs() - lookback }
    val year = cal.get(Calendar.YEAR)
    val tz = (cal.timeZone.getOffset(cal.timeInMillis) / (1000 * 60 * 15)).toByte()
    val cmd = byteArrayOf(
      0x01, current.code,
      (year and 0xFF).toByte(), ((year shr 8) and 0xFF).toByte(),
      (cal.get(Calendar.MONTH) + 1).toByte(),
      cal.get(Calendar.DAY_OF_MONTH).toByte(),
      cal.get(Calendar.HOUR_OF_DAY).toByte(),
      cal.get(Calendar.MINUTE).toByte(),
      0x00, tz
    )
    io.write(control!!, cmd)
    arm()
  }

  /** True when there is no auth key: try once, give up fast and never show an error. */
  @Volatile var quiet = false

  private fun arm() {
    handler.removeCallbacks(watchdog)
    handler.postDelayed(watchdog, if (quiet) 8_000 else 30_000)
  }

  /** Called for every notification on 0x0004. */
  fun onControlNotification(v: ByteArray) {
    if (!running) return
    arm()
    if (v.size < 3 || v[0] != 0x10.toByte()) { finish(total > 0, "Unexpected band response"); return }
    when (v[1].toInt()) {
      0x01 -> onStartResponse(v)
      0x02 -> onFetchFinished(v)
      0x03 -> finish(true, "Done")
      else -> finish(total > 0, "Unexpected band response")
    }
  }

  private fun onStartResponse(v: ByteArray) {
    if (v[2] != 0x01.toByte()) {
      Log.w(TAG, "Band refused ${current.label} request")
      if (current == FetchKind.ACTIVITY && total == 0) { finish(false, "Band refused the history request (auth key?)"); return }
      nextTask(); return
    }
    if (v.size < 15) { Log.w(TAG, "Malformed ${current.label} response"); nextTask(); return }
    expectedLen = u32(v, 3)
    if (expectedLen == 0) { Log.i(TAG, "Nothing newer for ${current.label}"); nextTask(); return }
    chunkStartTs = parseBandTime(v, 7)
    Log.i(TAG, "Round $rounds: expecting $expectedLen from ${chunkStartTs}")
    waitingData = true
    io.setNotify(data!!, true)
    io.write(control!!, byteArrayOf(0x02))
  }

  /** Called for every notification on 0x0005. */
  fun onDataNotification(v: ByteArray) {
    if (!running || !waitingData || v.isEmpty()) return
    arm()
    val expected = (lastCounter + 1) and 0xFF
    if ((v[0].toInt() and 0xFF) == expected) {
      lastCounter = expected
      buffer.write(v, 1, v.size - 1)
      val pct = if (expectedLen > 0) (buffer.size() * 100L / expectedLen).toInt().coerceIn(0, 99) else 0
      listener.onProgress(pct, "Downloading ${current.label}… $pct%")
    } else {
      Log.w(TAG, "Bad packet counter ${v[0]} expected $expected")
      operationValid = false
    }
  }

  private fun onFetchFinished(v: ByteArray) {
    if (v[2] != 0x01.toByte()) { Log.w(TAG, "Band error while sending ${current.label}"); nextTask(); return }
    waitingData = false
    if (!operationValid) { finish(total > 0, "Transfer was interrupted. It will resume on the next sync."); return }
    val bytes = buffer.toByteArray()
    val prevSync = lastSyncMs()
    val newSyncMs: Long = when (current) {
      FetchKind.ACTIVITY -> saveActivity(bytes) ?: run { finish(total > 0, "Unexpected data size ${bytes.size}"); return }
      FetchKind.STRESS -> saveStress(bytes)
      FetchKind.SPORTS -> saveSports(bytes)
      FetchKind.PAI -> savePai(bytes)
    }
    prefs.edit().putLong(current.prefKey, maxOf(prevSync, newSyncMs)).apply()
    Log.i(TAG, "${current.label}: next since $newSyncMs")

    val progressed = newSyncMs > prevSync
    val maxRounds = if (current == FetchKind.SPORTS) 25 else 30
    if (progressed && rounds < maxRounds && newSyncMs < System.currentTimeMillis() - 60_000) {
      beginRound()
    } else {
      nextTask()
    }
  }

  /** 8 bytes per minute. Returns the next "since" in ms, or null if the size is wrong. */
  private fun saveActivity(bytes: ByteArray): Long? {
    if (bytes.size % 8 != 0) return null
    val n = bytes.size / 8
    val samples = ArrayList<BandSample>(n)
    for (i in 0 until n) {
      val o = i * 8
      samples.add(
        BandSample(
          ts = chunkStartTs + i * 60L,
          kind = bytes[o].toInt() and 0xFF,
          intensity = bytes[o + 1].toInt() and 0xFF,
          steps = bytes[o + 2].toInt() and 0xFF,
          hr = bytes[o + 3].toInt() and 0xFF,
          sleep = bytes[o + 5].toInt() and 0xFF,
          deep = bytes[o + 6].toInt() and 0xFF,
          rem = bytes[o + 7].toInt() and 0xFF
        )
      )
    }
    store.insertAll(samples)
    total += n
    return (chunkStartTs + n * 60L) * 1000L
  }

  /** 1 byte per minute, 0xFF = no reading (Gadgetbridge FetchStressAutoOperation). */
  private fun saveStress(bytes: ByteArray): Long {
    val list = ArrayList<Pair<Long, Int>>()
    var ts = chunkStartTs
    for (b in bytes) {
      val v = b.toInt() and 0xFF
      if (v != 0xFF && v in 1..100) list.add(ts to v)
      ts += 60
    }
    store.insertStress(list)
    stressSaved += list.size
    return ts * 1000L
  }

  /** 102-byte records (Gadgetbridge FetchPaiOperation): we keep PAI today / total per record timestamp. */
  private fun savePai(bytes: ByteArray): Long {
    val list = ArrayList<Triple<Long, Float, Float>>()
    var lastTs = chunkStartTs
    try {
      val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      while (b.remaining() >= 102) {
        val type = b.get().toInt() and 0xFF
        if (type != 5) break
        val ts = b.int.toLong() and 0xFFFFFFFFL
        b.get()                              // utc offset (quarter hours)
        b.position(b.position() + 31)        // unknown
        b.float; b.float; b.float            // PAI low / moderate / high
        b.short; b.short; b.short            // minutes low / moderate / high
        val today = b.float
        val total = b.float
        b.position(b.position() + 39)        // unknown
        if (today.isFinite() && total.isFinite() && ts > 1_000_000_000L) { list.add(Triple(ts, today, total)); lastTs = ts }
      }
    } catch (e: Exception) {
      Log.w(TAG, "PAI parse failed: ${e.message}")
    }
    store.insertPai(list)
    return (lastTs + 60) * 1000L
  }

  /** One workout summary per round (Gadgetbridge FetchSportsSummaryOperation). */
  private fun saveSports(bytes: ByteArray): Long {
    val w = HuamiSportsSummaryParser.parse(bytes, chunkStartTs)
    if (w == null) {
      Log.w(TAG, "Could not parse workout summary (${bytes.size} bytes)")
      return (chunkStartTs + 60) * 1000L // skip it so we never loop on the same record
    }
    store.insertWorkout(w)
    workoutsSaved++
    return (w.endSec + 60) * 1000L
  }

  private fun finish(success: Boolean, message: String) {
    if (!running) return
    running = false
    waitingData = false
    handler.removeCallbacks(watchdog)
    try {
      data?.let { io.setNotify(it, false) }
      control?.let { io.setNotify(it, false) }
    } catch (e: Exception) {
      Log.w(TAG, "notify off failed: ${e.message}")
    }
    listener.onFinished(success, total, if (quiet && !success) "" else message)
  }

  private fun u32(v: ByteArray, o: Int): Int =
    (v[o].toInt() and 0xFF) or ((v[o + 1].toInt() and 0xFF) shl 8) or
      ((v[o + 2].toInt() and 0xFF) shl 16) or ((v[o + 3].toInt() and 0xFF) shl 24)

  /** year(le16) month day hour minute second tz(signed, quarter hours) -> epoch seconds. */
  private fun parseBandTime(v: ByteArray, o: Int): Long {
    val year = (v[o].toInt() and 0xFF) or ((v[o + 1].toInt() and 0xFF) shl 8)
    val cal = GregorianCalendar(TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(
      year, (v[o + 2].toInt() and 0xFF) - 1, v[o + 3].toInt() and 0xFF,
      v[o + 4].toInt() and 0xFF, v[o + 5].toInt() and 0xFF, v[o + 6].toInt() and 0xFF
    )
    val tzSeconds = v[o + 7].toInt() * 15 * 60
    return cal.timeInMillis / 1000 - tzSeconds
  }
}
