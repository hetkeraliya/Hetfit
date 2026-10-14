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

    const val EXTRA_FROM_BAND = "from_band"

    fun send(context: Context, action: String, type: Int = 1, voice: Boolean = false, fromBand: Boolean = false) {
      val i = Intent(context, RunTrackingService::class.java).setAction(action)
        .putExtra(EXTRA_TYPE, type).putExtra(EXTRA_VOICE, voice).putExtra(EXTRA_FROM_BAND, fromBand)
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
  /** The blended GPS + band distance the live screen shows; this is what gets saved. */
  @Volatile private var lastEffectiveDist = 0.0
  private var lastGpsSentToBandMs = 0L
  private var gpsSearchingSent = false
  /** True when this session was started because the user accepted a band-workout offer. */
  private var fromBand = false
  private var bandStepsAtStart = -1
  private var bandDistAtStart = -1
  private var lastPaceSource = "gps"
  /** Running scale: bandMeters * scale ≈ gpsMeters. Starts at 1, learns while both streams are available. */
  private var bandScale = 1.0
  private var calibSamples = 0

  private fun loadCalibration() {
    val p = getSharedPreferences("kestrel_calib", MODE_PRIVATE)
    bandScale = p.getFloat("band_scale_$type", 1f).toDouble().coerceIn(0.4, 2.5)
    calibSamples = p.getInt("band_scale_n_$type", 0)
  }

  private fun saveCalibration() {
    getSharedPreferences("kestrel_calib", MODE_PRIVATE).edit()
      .putFloat("band_scale_$type", bandScale.toFloat())
      .putInt("band_scale_n_$type", calibSamples)
      .apply()
  }

  /**
   * Soft calibration: no hard pace cutoffs.
   * When GPS and band both report movement, slowly learn a scale factor.
   * Output is a weighted blend — more GPS weight when accuracy is better.
   * When GPS is missing, use band distance × learned scale.
   */
  private fun fuseDistance(gpsM: Double, bandM: Double, acc: Float): Double {
    val bandCal = if (bandM > 0) bandM * bandScale else -1.0

    // Learn: only when both have meaningful distance and GPS lock looks usable.
    if (gpsM > 30 && bandM > 30 && acc in 0f..25f) {
      val ratio = gpsM / bandM
      if (ratio in 0.35..2.8) {
        // Exponential moving average — more trust as samples grow, but never jumps.
        val alpha = if (calibSamples < 8) 0.25 else 0.08
        bandScale = (bandScale * (1 - alpha) + ratio * alpha).coerceIn(0.4, 2.5)
        calibSamples++
        if (calibSamples % 5 == 0) saveCalibration()
      }
    }

    return when {
      gpsM <= 0 && bandCal <= 0 -> 0.0
      gpsM <= 0 -> { lastPaceSource = "band"; bandCal }
      bandCal <= 0 -> { lastPaceSource = "gps"; gpsM }
      else -> {
        // Weight GPS higher when accuracy is better (0 m → full GPS, 30 m → mostly band).
        val gpsW = when {
          acc < 0f -> 0.55
          acc <= 8f -> 0.85
          acc <= 15f -> 0.65
          acc <= 25f -> 0.40
          else -> 0.20
        }
        val blended = gpsM * gpsW + bandCal * (1.0 - gpsW)
        lastPaceSource = if (gpsW >= 0.5) "gps" else "band"
        blended
      }
    }
  }

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
      ACTION_START -> if (!running) startRun(
        intent.getIntExtra(EXTRA_TYPE, 1),
        intent.getBooleanExtra(EXTRA_VOICE, false),
        intent.getBooleanExtra(EXTRA_FROM_BAND, false)
      ) else Unit
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
        val ch = NotificationChannel(CHANNEL_ID, "Activity recording", NotificationManager.IMPORTANCE_DEFAULT)
        ch.setShowBadge(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        ch.setSound(null, null)
        ch.enableVibration(false)
        nm.createNotificationChannel(ch)
        // Migrate older LOW channel so lock screen shows stats
        try { nm.deleteNotificationChannel(CHANNEL_ID + "_legacy") } catch (_: Exception) {}
      }
    }
  }

  private fun startRun(t: Int, speak: Boolean, fromBandSession: Boolean = false) {
    type = t
    voice = speak
    fromBand = fromBandSession
    loadCalibration()
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
    lastEffectiveDist = 0.0
    movingSec = 0; distanceM = 0.0; lastLoc = null; lastMoveMs = startMs; accuracy = -1f
    hrSum = 0; hrCount = 0; hrMax = 0; nextKm = 1; lastKmMoving = 0; tickCount = 0
    consecutiveGoodFixes = 0; lastGpsSentToBandMs = 0; gpsSearchingSent = false
    recent.clear(); points.clear(); liveClear()
    val st0 = MiBandManager.get(this).uiState.value
    stepsAtStart = st0.liveSteps.takeIf { it > 0 } ?: -1
    bandStepsAtStart = st0.liveSteps.takeIf { it > 0 } ?: -1
    bandDistAtStart = st0.liveDistanceMeters.takeIf { it > 0 } ?: -1

    gpsOn = type in setOf(1, 3, 4, 15, 7) && startLocation()
    if (voice) initTts()
    try {
      val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kestrel:run").also { it.acquire(8 * 3600 * 1000L) }
    } catch (_: Exception) { }
    handler.post(tick)
    speak(if (fromBand) "Tracking band workout" else "Recording started")
    // Only push "start" to the band when the phone initiated the run (not when mirroring the band).
    if (!fromBand) {
      try {
        val label = when (type) { 3 -> "Walk"; 4 -> "Ride"; 15 -> "Hike"; else -> "Run" }
        MiBandManager.get(this).bandRunStarted(label, bandType = bandTypeFor(type))
      } catch (_: Exception) { }
    } else {
      // Band already opened the workout; keep HR on and tell the band phone GPS is searching.
      try {
        MiBandManager.get(this).bandRunKeepAlive()
        MiBandManager.get(this).sendPhoneGps(status = 0x02, location = null)
        gpsSearchingSent = true
      } catch (_: Exception) { }
    }
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
  // Share every usable fix with the band (Gadgetbridge / Zepp / Mi Fit style).
  // Distance on the phone still uses stricter filters so idle drift is not counted.
  override fun onLocationChanged(loc: Location) {
    if (!running || paused) return
    accuracy = if (loc.hasAccuracy()) loc.accuracy else -1f

    // Always stream to the band when we have coordinates — band UI needs live speed/position
    // even before the phone accepts a fix for distance. Matches Gadgetbridge 1 Hz GPS service.
    if (loc.latitude != 0.0 || loc.longitude != 0.0) {
      streamGpsToBand(loc)
    }

    // Phone-side distance. The old filter needed 3 consecutive fixes that were each >= 5 m apart AND under 18 m accuracy.
    // At running speed a 1 Hz fix moves only ~3 m, so almost every fix was thrown away: the route became a straight
    // line and the saved distance collapsed. Now the anchor only moves when a fix is clearly away from it, so slow
    // real movement accumulates and standing-still jitter does not.
    if (!loc.hasAccuracy() || loc.accuracy > 35f) return
    val last = lastLoc
    if (last == null) {
      lastLoc = loc
      addPoint(loc)
      return
    }
    val dt = (loc.time - last.time) / 1000.0
    if (dt <= 0.0) return
    val d = last.distanceTo(loc).toDouble()
    val v = d / dt
    val maxV = when (type) { 4 -> 22.0; 3 -> 4.5; else -> 8.5 }
    val minV = when (type) { 4 -> 1.0; 3 -> 0.5; else -> 0.6 }
    if (v > maxV) return                                   // GPS glitch: ignore, keep the anchor
    val noise = (loc.accuracy * 0.6).toDouble().coerceIn(2.5, 12.0)
    if (d < noise || v < minV) return                      // jitter / standing still
    // A long gap (screen off, tunnel) with a plausible speed is bridged instead of being dropped.
    distanceM += d
    lastLoc = loc
    lastMoveMs = System.currentTimeMillis()
    addPoint(loc)
  }

  /**
   * Push phone GPS to the band every ~1 s (Gadgetbridge onSetGpsLocation).
   * Re-sends ACQUIRED if the last packet was > 5 s ago so the band knows the lock is back.
   */
  private fun streamGpsToBand(loc: Location) {
    val now = System.currentTimeMillis()
    if (now - lastGpsSentToBandMs < 900) return
    lastGpsSentToBandMs = now
    try {
      val mgr = MiBandManager.get(this)
      if (!gpsSearchingSent) {
        // Status-only SEARCHING — band shows “getting GPS…”
        mgr.sendPhoneGps(status = 0x02, location = null)
        gpsSearchingSent = true
      }
      val status = if (mgr.needsGpsAcquiredFlag()) 0x01 else null // ACQUIRED after gap
      mgr.sendPhoneGps(status = status, location = loc)
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

    // Band session distance/steps (delta since start) — always tracked for calibration.
    val bandDistM = if (bandDistAtStart >= 0 && st.liveDistanceMeters >= bandDistAtStart)
      (st.liveDistanceMeters - bandDistAtStart).toDouble() else -1.0
    val bandSteps = if (bandStepsAtStart >= 0 && st.liveSteps >= bandStepsAtStart)
      st.liveSteps - bandStepsAtStart else 0

    // Soft calibration blend of GPS + band (learns scale over time, no hard rules).
    val effectiveDist = fuseDistance(distanceM, bandDistM, accuracy)
    lastEffectiveDist = effectiveDist

    if (!paused) {
      val moving = when {
        lastPaceSource == "band" && bandDistM > 0 -> true
        gpsOn -> (now - lastMoveMs) < 6000
        else -> true
      }
      if (moving) movingSec++
      val hr = st.liveHeartRate
      if (hr in 30..220) { hrSum += hr; hrCount++; if (hr > hrMax) hrMax = hr }
      recent.addLast(now to effectiveDist)
      while (recent.isNotEmpty() && now - recent.first().first > 20_000) recent.removeFirst()
      if (effectiveDist >= nextKm * 1000.0) {
        val km = nextKm; nextKm++
        val split = movingSec - lastKmMoving; lastKmMoving = movingSec
        speak("$km kilometer${if (km > 1) "s" else ""}. Time ${spokenTime(movingSec)}. Last kilometer ${spokenTime(split)}." +
          if (hr in 30..220) " Heart rate $hr." else "")
        // Don't buzz the band again if the band itself is already in a workout.
        if (!fromBand) {
          try { MiBandManager.get(this).bandRunKm(km, String.format(Locale.US, "%d:%02d", split / 60, split % 60), hr) } catch (_: Exception) { }
        }
      }
    }
    val elapsed = elapsedSec(now)
    // Pace from band distance when available, else GPS.
    val avgPace = if (effectiveDist > 20) (movingSec / (effectiveDist / 1000.0)).toInt() else 0
    var curPace = 0
    if (recent.size > 3) {
      val dd = recent.last().second - recent.first().second
      val tt = (recent.last().first - recent.first().first) / 1000.0
      if (dd > 8.0) curPace = (tt / (dd / 1000.0)).toInt()
    }
    val steps = if (stepsAtStart >= 0 && st.liveSteps >= stepsAtStart) st.liveSteps - stepsAtStart else bandSteps
    val last = points.lastOrNull()
    val o = JSONObject().apply {
      put("active", true); put("paused", paused); put("type", type); put("gps", gpsOn)
      put("fromBand", fromBand)
      put("startMs", startMs); put("elapsedSec", elapsed); put("movingSec", movingSec)
      put("distanceM", Math.round(effectiveDist))
      put("gpsDistanceM", Math.round(distanceM))
      put("bandDistanceM", if (bandDistM >= 0) Math.round(bandDistM) else 0)
      put("avgPace", avgPace); put("curPace", curPace)
      put("paceSource", lastPaceSource)
      put("bandScale", Math.round(bandScale * 1000) / 1000.0)
      put("calibSamples", calibSamples)
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
      this, 0,
      Intent(this, MainActivity::class.java)
        .setAction("com.example.action.OPEN_LIVE")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val distKm = distanceM / 1000.0
    val km = String.format(Locale.getDefault(), "%.2f", distKm)
    val time = String.format(Locale.getDefault(), "%d:%02d", elapsed / 60, elapsed % 60)
    val paceStr = if (distKm >= 0.05 && elapsed > 0) {
      val secPerKm = (elapsed / distKm).toInt().coerceAtLeast(0)
      String.format(Locale.getDefault(), "%d:%02d /km", secPerKm / 60, secPerKm % 60)
    } else "— /km"
    val hrStr = if (hrCount > 0) {
      val avg = (hrSum / hrCount).toInt()
      "♥ $avg bpm"
    } else ""
    val status = if (paused) "PAUSED" else "RUN"

    // Compact (status bar / collapsed)
    val compact = android.widget.RemoteViews(packageName, com.example.R.layout.notification_run_compact)
    compact.setTextViewText(com.example.R.id.nr_c_title, if (paused) "Paused" else "Recording")
    compact.setTextViewText(com.example.R.id.nr_c_stats, "$km km · $time")

    // Expanded / lock-screen card
    val big = android.widget.RemoteViews(packageName, com.example.R.layout.notification_run_lock)
    big.setTextViewText(com.example.R.id.nr_title, status)
    big.setTextViewText(com.example.R.id.nr_distance, "$km km")
    big.setTextViewText(com.example.R.id.nr_time, time)
    big.setTextViewText(com.example.R.id.nr_pace, paceStr)
    big.setTextViewText(com.example.R.id.nr_hr, hrStr)

    val b = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_menu_mylocation)
      .setContentTitle(if (paused) "Paused · $km km" else "Recording · $km km")
      .setContentText("$time · $paceStr" + if (hrStr.isNotEmpty()) " · $hrStr" else "")
      .setCustomContentView(compact)
      .setCustomBigContentView(big)
      .setStyle(NotificationCompat.DecoratedCustomViewStyle())
      .setContentIntent(open)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setShowWhen(false)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setCategory(NotificationCompat.CATEGORY_WORKOUT)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    if (paused) b.addAction(0, "Resume", pi(ACTION_RESUME, 31)) else b.addAction(0, "Pause", pi(ACTION_PAUSE, 30))
    b.addAction(0, "Finish", pi(ACTION_FINISH, 32))
    return b.build()
  }

  private fun finishRun(save: Boolean) {
    if (!running) { stopSelf(); return }
    val now = System.currentTimeMillis()
    val elapsed = elapsedSec(now)
    val st = MiBandManager.get(this).uiState.value
    val bandDistM = if (bandDistAtStart >= 0 && st.liveDistanceMeters >= bandDistAtStart)
      (st.liveDistanceMeters - bandDistAtStart).toDouble() else -1.0
    // Save exactly what the live screen showed (7.77 km on screen used to be saved as 0.87 km because only raw GPS was stored).
    val fused = fuseDistance(distanceM, bandDistM, accuracy)
    val finalDist = maxOf(lastEffectiveDist, fused, distanceM)
    val wasFromBand = fromBand
    running = false
    fromBand = false
    handler.removeCallbacks(tick)
    stopLocation()
    var savedStart = -1L
    if (save && elapsed >= 10) {
      val startSec = startMs / 1000
      val steps = if (stepsAtStart >= 0) (st.liveSteps - stepsAtStart).coerceAtLeast(0) else 0
      store.insertWorkout(
        BandWorkoutRow(
          startSec = startSec, endSec = startSec + elapsed, type = type, steps = steps, activeSec = movingSec,
          calories = 0, distanceM = Math.round(finalDist).toInt(),
          avgHr = if (hrCount > 0) (hrSum / hrCount).toInt() else 0, maxHr = hrMax
        )
      )
      store.insertTrack(startSec, points)
      savedStart = startSec
      speak("Saved. ${String.format(Locale.getDefault(), "%.2f", finalDist / 1000.0)} kilometers in ${spokenTime(movingSec)}.")
      // Only notify the band if the phone started the session (band already ended its own).
      if (!wasFromBand) {
        try {
          MiBandManager.get(this).bandRunFinished(
            String.format(Locale.US, "Saved %.2f km · %d:%02d", finalDist / 1000.0, movingSec / 60, movingSec % 60)
          )
        } catch (_: Exception) { }
      }
    }
    saveCalibration()
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
