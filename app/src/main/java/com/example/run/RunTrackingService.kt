package com.example.run

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.ble.BandSampleStore
import com.example.ble.BandWorkoutRow
import com.example.ble.MiBandManager
import com.example.ble.TrackPoint
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Offline GPS activity recorder (run / walk / ride / hike). Works with no internet: GPS fixes are stored on the
 * phone, live heart rate and steps come from the band when it is connected, and a spoken cue is given every km.
 */
class RunTrackingService : Service(), LocationListener {

  companion object {
    const val ACTION_START = "com.example.run.START"
    const val ACTION_PAUSE = "com.example.run.PAUSE"
    const val ACTION_RESUME = "com.example.run.RESUME"
    const val ACTION_FINISH = "com.example.run.FINISH"
    const val ACTION_DISCARD = "com.example.run.DISCARD"
    const val EXTRA_TYPE = "type"
    const val EXTRA_VOICE = "voice"
    private const val CHANNEL_ID = "kestrel_run"
    private const val NOTIF_ID = 4712

    /** Called every second on the main thread with the live state JSON. */
    @Volatile var listener: ((String) -> Unit)? = null
    /** Called on the main thread after a run was saved (start seconds) or discarded (-1). */
    @Volatile var onFinished: ((Long) -> Unit)? = null
    @Volatile var stateJson: String = "{\"active\":false}"
    private val live = ArrayList<DoubleArray>()

    @Synchronized fun liveTrackJson(): String {
      val arr = JSONArray()
      for (p in live) arr.put(JSONArray().put(p[0]).put(p[1]).put(p[2]))
      return arr.toString()
    }
    @Synchronized private fun liveAdd(lat: Double, lon: Double, alt: Double) { live.add(doubleArrayOf(lat, lon, alt)) }
    @Synchronized private fun liveClear() { live.clear() }

    fun send(context: Context, action: String, type: Int = 1, voice: Boolean = false) {
      val i = Intent(context, RunTrackingService::class.java).setAction(action).putExtra(EXTRA_TYPE, type).putExtra(EXTRA_VOICE, voice)
      if (action == ACTION_START) ContextCompat.startForegroundService(context, i) else context.startService(i)
    }
  }

  private val handler = Handler(Looper.getMainLooper())
  private lateinit var store: BandSampleStore
  private var tts: TextToSpeech? = null
  private var ttsReady = false
  private var wakeLock: PowerManager.WakeLock? = null

  private var running = false
  private var paused = false
  private var type = 1
  private var voice = false
  private var gpsOn = false
  private var startMs = 0L
  private var pausedAccumMs = 0L
  private var pauseStartMs = 0L
  private var movingSec = 0
  private var distanceM = 0.0
  private var lastLoc: Location? = null
  private var lastMoveMs = 0L
  private var accuracy = -1f
  private var hrSum = 0L
  private var hrCount = 0
  private var hrMax = 0
  private var stepsAtStart = -1
  private var nextKm = 1
  private var lastKmMoving = 0
  private val recent = ArrayDeque<Pair<Long, Double>>()
  private val points = ArrayList<TrackPoint>()
  private var tickCount = 0
  /** Consecutive accepted GPS fixes before distance starts counting (kills indoor/stationary drift). */
  private var consecutiveGoodFixes = 0
  private var lastGpsSentToBandMs = 0L
  private var gpsSearchingSent = false

  private val tick = object : Runnable {
    override fun run() {
      onTick()
      if (running) handler.postDelayed(this, 1000)
    }
  }

  override fun onCreate() {
    super.onCreate()
    store = BandSampleStore.get(this)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> if (!running) startRun(intent.getIntExtra(EXTRA_TYPE, 1), intent.getBooleanExtra(EXTRA_VOICE, false)) else Unit
      ACTION_PAUSE -> setPaused(true)
      ACTION_RESUME -> setPaused(false)
      ACTION_FINISH -> finishRun(true)
      ACTION_DISCARD -> finishRun(false)
    }
    return START_NOT_STICKY
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
      val nm = getSystemService(NotificationManager::class.java)
      if (nm.getNotificationChannel(CHANNEL_ID) == null) {
        val ch = NotificationChannel(CHANNEL_ID, "Activity recording", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
      }
    }
  }

  private fun startRun(t: Int, speak: Boolean) {
    type = t
    voice = speak
    createChannel()
    try {
      val n = buildNotification()
      if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(NOTIF_ID, n)
    } catch (e: Exception) {
      android.util.Log.w("RunTracking", "Foreground start refused: ${e.message}")
      stopSelf()
      return
    }
    running = true; paused = false
    startMs = System.currentTimeMillis(); pausedAccumMs = 0; pauseStartMs = 0
    movingSec = 0; distanceM = 0.0; lastLoc = null; lastMoveMs = startMs; accuracy = -1f
    hrSum = 0; hrCount = 0; hrMax = 0; nextKm = 1; lastKmMoving = 0; tickCount = 0
    consecutiveGoodFixes = 0; lastGpsSentToBandMs = 0; gpsSearchingSent = false
    recent.clear(); points.clear(); liveClear()
    stepsAtStart = MiBandManager.get(this).uiState.value.liveSteps.takeIf { it > 0 } ?: -1

    gpsOn = type in setOf(1, 3, 4, 15, 7) && startLocation()
    if (voice) initTts()
    try {
      val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kestrel:run").also { it.acquire(8 * 3600 * 1000L) }
    } catch (_: Exception) { }
    handler.post(tick)
    speak("Recording started")
    try {
      val label = when (type) { 3 -> "Walk"; 4 -> "Ride"; 15 -> "Hike"; else -> "Run" }
      MiBandManager.get(this).bandRunStarted(label, bandType = bandTypeFor(type))
    } catch (_: Exception) { }
  }

  /** Map app run type → Huami band workout type (1 outdoor run, 4 walk, 3 outdoor ride). */
  private fun bandTypeFor(appType: Int): Int = when (appType) { 3 -> 0x04; 4 -> 0x03; else -> 0x01 }

  private fun startLocation(): Boolean {
    val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!granted) return false
    return try {
      val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
      if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) false
      else { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()); true }
    } catch (e: SecurityException) { false } catch (e: Exception) { false }
  }

  private fun stopLocation() {
    try { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this) } catch (_: Exception) { }
  }

  private fun initTts() {
    tts = TextToSpeech(applicationContext) { status ->
      if (status == TextToSpeech.SUCCESS) {
        val r = tts?.setLanguage(Locale.getDefault())
        ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
      }
    }
  }

  private fun speak(text: String) {
    if (!voice || !ttsReady) return
    try { tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "kestrel-${System.nanoTime()}") } catch (_: Exception) { }
  }

  private fun setPaused(p: Boolean) {
    if (!running || p == paused) return
    paused = p
    if (p) pauseStartMs = System.currentTimeMillis()
    else { pausedAccumMs += System.currentTimeMillis() - pauseStartMs; lastLoc = null; lastMoveMs = System.currentTimeMillis() }
    speak(if (p) "Paused" else "Resumed")
    onTick()
  }

  // ---- GPS ----
  // Strict filters so standing still does not invent distance (common phone GPS drift).
  override fun onLocationChanged(loc: Location) {
    if (!running || paused) return
    accuracy = if (loc.hasAccuracy()) loc.accuracy else -1f
    // Reject weak fixes hard (was 30 m — still let a lot of indoor drift through).
    if (!loc.hasAccuracy() || loc.accuracy > 18f) {
      consecutiveGoodFixes = 0
      return
    }
    val last = lastLoc
    if (last == null) {
      lastLoc = loc
      consecutiveGoodFixes = 1
      addPoint(loc)
      maybeSendGpsToBand(loc, first = true)
      return
    }
    val dt = (loc.time - last.time) / 1000.0
    if (dt <= 0.0 || dt > 30.0) {
      // Large time gap — reset consecutive chain, keep last fix as anchor.
      lastLoc = loc
      consecutiveGoodFixes = 1
      return
    }
    val d = last.distanceTo(loc).toDouble()
    val v = d / dt
    val maxV = when (type) { 4 -> 22.0; 3 -> 4.5; else -> 8.5 }
    val minV = when (type) { 4 -> 1.2; 3 -> 0.7; else -> 0.9 }  // m/s — must actually be moving
    val minD = when (type) { 4 -> 6.0; else -> 5.0 }             // metres between fixes
    if (v > maxV) { consecutiveGoodFixes = 0; return }           // teleport / glitch
    if (d < minD || v < minV) {
      // Stationary or tiny jitter — do NOT add distance, keep lastLoc as anchor.
      consecutiveGoodFixes = 0
      return
    }
    consecutiveGoodFixes++
    // Need a short streak of real movement before counting (stops "GPS starts when idle").
    if (consecutiveGoodFixes < 3) {
      lastLoc = loc
      return
    }
    distanceM += d
    lastLoc = loc
    lastMoveMs = System.currentTimeMillis()
    addPoint(loc)
    maybeSendGpsToBand(loc, first = false)
  }

  private fun maybeSendGpsToBand(loc: Location, first: Boolean) {
    val now = System.currentTimeMillis()
    if (!first && now - lastGpsSentToBandMs < 1000) return
    lastGpsSentToBandMs = now
    try {
      val mgr = MiBandManager.get(this)
      if (!gpsSearchingSent) {
        mgr.sendPhoneGps(status = 0x02, location = null) // SEARCHING
        gpsSearchingSent = true
      }
      mgr.sendPhoneGps(status = if (first) 0x01 else null, location = loc) // ACQUIRED on first good fix
    } catch (_: Exception) { }
  }

  private fun addPoint(loc: Location) {
    val hr = MiBandManager.get(this).uiState.value.liveHeartRate
    val p = TrackPoint(loc.time / 1000, loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else 0.0, if (hr in 30..220) hr else 0)
    points.add(p)
    liveAdd(p.lat, p.lon, p.alt)
  }

  @Deprecated("Deprecated in API 29") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
  override fun onProviderEnabled(provider: String) {}
  override fun onProviderDisabled(provider: String) {}

  // ---- timer ----
  private fun elapsedSec(now: Long): Int {
    val pausedNow = if (paused) now - pauseStartMs else 0L
    return ((now - startMs - pausedAccumMs - pausedNow) / 1000).toInt().coerceAtLeast(0)
  }

  private fun onTick() {
    if (!running) return
    val now = System.currentTimeMillis()
    val st = MiBandManager.get(this).uiState.value
    if (!paused) {
      val moving = if (gpsOn) (now - lastMoveMs) < 6000 else true
      if (moving) movingSec++
      val hr = st.liveHeartRate
      if (hr in 30..220) { hrSum += hr; hrCount++; if (hr > hrMax) hrMax = hr }
      recent.addLast(now to distanceM)
      while (recent.isNotEmpty() && now - recent.first().first > 20_000) recent.removeFirst()
      if (distanceM >= nextKm * 1000.0) {
        val km = nextKm; nextKm++
        val split = movingSec - lastKmMoving; lastKmMoving = movingSec
        speak("$km kilometer${if (km > 1) "s" else ""}. Time ${spokenTime(movingSec)}. Last kilometer ${spokenTime(split)}." +
          if (hr in 30..220) " Heart rate $hr." else "")
        try { MiBandManager.get(this).bandRunKm(km, String.format(Locale.US, "%d:%02d", split / 60, split % 60), hr) } catch (_: Exception) { }
      }
    }
    val elapsed = elapsedSec(now)
    val avgPace = if (distanceM > 20) (movingSec / (distanceM / 1000.0)).toInt() else 0
    var curPace = 0
    if (recent.size > 3) {
      val dd = recent.last().second - recent.first().second
      val tt = (recent.last().first - recent.first().first) / 1000.0
      if (dd > 8.0) curPace = (tt / (dd / 1000.0)).toInt()
    }
    val steps = if (stepsAtStart >= 0 && st.liveSteps >= stepsAtStart) st.liveSteps - stepsAtStart else 0
    val last = points.lastOrNull()
    val o = JSONObject().apply {
      put("active", true); put("paused", paused); put("type", type); put("gps", gpsOn)
      put("startMs", startMs); put("elapsedSec", elapsed); put("movingSec", movingSec)
      put("distanceM", Math.round(distanceM)); put("avgPace", avgPace); put("curPace", curPace)
      put("hr", if (st.liveHeartRate in 30..220) st.liveHeartRate else 0)
      put("avgHr", if (hrCount > 0) (hrSum / hrCount).toInt() else 0); put("maxHr", hrMax)
      put("steps", steps); put("accuracy", Math.round(accuracy))
      if (last != null) { put("lat", last.lat); put("lon", last.lon) }
      put("points", points.size)
    }
    stateJson = o.toString()
    listener?.invoke(stateJson)
    tickCount++
    if (tickCount % 2 == 0) {
      try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(elapsed)) } catch (_: Exception) { }
    }
    // Keep continuous HR alive on the band (~every 75 s) so live pace + HR stay fresh.
    if (!paused && tickCount % 75 == 0) {
      try { MiBandManager.get(this).bandRunKeepAlive() } catch (_: Exception) { }
    }
  }

  private fun spokenTime(sec: Int): String {
    val m = sec / 60; val s = sec % 60
    return if (m > 0) "$m minute${if (m != 1) "s" else ""} $s seconds" else "$s seconds"
  }

  private fun buildNotification(elapsed: Int = 0): Notification {
    fun pi(action: String, code: Int) = PendingIntent.getService(
      this, code, Intent(this, RunTrackingService::class.java).setAction(action),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val open = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val km = String.format(Locale.getDefault(), "%.2f km", distanceM / 1000.0)
    val time = String.format(Locale.getDefault(), "%d:%02d", elapsed / 60, elapsed % 60)
    val b = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_menu_mylocation)
      .setContentTitle(if (paused) "Paused · $km" else "Recording · $km")
      .setContentText("$time elapsed")
      .setContentIntent(open)
      .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
      .setCategory(NotificationCompat.CATEGORY_WORKOUT)
    if (paused) b.addAction(0, "Resume", pi(ACTION_RESUME, 31)) else b.addAction(0, "Pause", pi(ACTION_PAUSE, 30))
    b.addAction(0, "Finish", pi(ACTION_FINISH, 32))
    return b.build()
  }

  private fun finishRun(save: Boolean) {
    if (!running) { stopSelf(); return }
    val now = System.currentTimeMillis()
    val elapsed = elapsedSec(now)
    running = false
    handler.removeCallbacks(tick)
    stopLocation()
    var savedStart = -1L
    if (save && elapsed >= 10) {
      val startSec = startMs / 1000
      val steps = if (stepsAtStart >= 0) (MiBandManager.get(this).uiState.value.liveSteps - stepsAtStart).coerceAtLeast(0) else 0
      store.insertWorkout(
        BandWorkoutRow(
          startSec = startSec, endSec = startSec + elapsed, type = type, steps = steps, activeSec = movingSec,
          calories = 0, distanceM = Math.round(distanceM).toInt(),
          avgHr = if (hrCount > 0) (hrSum / hrCount).toInt() else 0, maxHr = hrMax
        )
      )
      store.insertTrack(startSec, points)
      savedStart = startSec
      speak("Saved. ${String.format(Locale.getDefault(), "%.2f", distanceM / 1000.0)} kilometers in ${spokenTime(movingSec)}.")
      try { MiBandManager.get(this).bandRunFinished(String.format(Locale.US, "Saved %.2f km · %d:%02d", distanceM / 1000.0, movingSec / 60, movingSec % 60)) } catch (_: Exception) { }
    }
    stateJson = "{\"active\":false}"
    listener?.invoke(stateJson)
    liveClear()
    try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
    onFinished?.invoke(savedStart)
    handler.postDelayed({ try { tts?.shutdown() } catch (_: Exception) { }; tts = null }, 6000)
    stopForeground(true)
    stopSelf()
  }

  override fun onDestroy() {
    handler.removeCallbacks(tick)
    stopLocation()
    super.onDestroy()
  }
}
