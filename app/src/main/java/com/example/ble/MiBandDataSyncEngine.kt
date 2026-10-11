package com.example.ble

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Sends REAL band history to the Kestrel web UI. Nothing here is generated: every number comes
 * from rows the band itself reported (see HuamiActivityFetcher / BandSampleStore).
 *
 * The web side aggregates the minute rows with the same code path it uses for Gadgetbridge
 * exports (kestrelBandSamplesBegin / Chunk / End in app.js), so charts, sleep and heart-rate
 * series are computed identically for both import routes.
 */
object MiBandDataSyncEngine {
  private val io = Executors.newSingleThreadExecutor()
  private const val CHUNK = 2000

  /**
   * @param fullHistory true = push everything in the store (app start / after reinstall),
   *                    false = only the last few days (after a normal sync).
   */
  fun pushToWebView(webView: WebView?, store: BandSampleStore, state: MiBandState, fullHistory: Boolean) {
    if (webView == null) return
    io.execute {
      try {
        val since = if (fullHistory) 0L else startOfDaysAgo(3) - 8 * 3600
        webView.post { webView.evaluateJavascript("window.kestrelBandSamplesBegin && window.kestrelBandSamplesBegin();", null) }
        store.forEachChunk(since, CHUNK) { rows ->
          val sb = StringBuilder(rows.size * 28)
          sb.append('[')
          rows.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            // [timestamp, rawIntensity, steps, heartRate, rawKind] - the 5 columns the web aggregator reads.
            // Keep every sleep kind (120/121/122/9/11). Everything else that is not a known
            // awake activity is normalised to 1 so not-worn (115) / charging (118) never count as sleep.
            val kind = when {
              isSleepKind(r.kind) -> r.kind
              else -> 1
            }
            val hr = if (r.hr in 1..253) r.hr else 0
            sb.append('[').append(r.ts).append(',').append(r.intensity).append(',')
              .append(r.steps).append(',').append(hr).append(',').append(kind).append(']')
          }
          sb.append(']')
          val js = "window.kestrelBandSamplesChunk && window.kestrelBandSamplesChunk($sb);"
          webView.post { webView.evaluateJavascript(js, null) }
        }
        val meta = deviceMeta(state).toString()
        webView.post { webView.evaluateJavascript("window.kestrelBandSamplesEnd && window.kestrelBandSamplesEnd($meta);", null) }

        // Stress averages + sleep stage minutes per day
        val extras = JSONObject()
        store.dayExtras(since).forEach { (day, e) ->
          val o = JSONObject()
          e.stressAvg?.let { o.put("stressAvg", it) }
          e.stressMax?.let { o.put("stressMax", it) }
          e.paiToday?.let { o.put("paiToday", it.toDouble()) }
          e.paiTotal?.let { o.put("paiTotal", it.toDouble()) }
          if (e.sleepMin != null && e.sleepStartTs != null && e.sleepEndTs != null) {
            o.put("sleepMinutes", e.sleepMin)
            o.put("sleepStart", iso8601(e.sleepStartTs!!))
            o.put("sleepEnd", iso8601(e.sleepEndTs!!))
          }
          if (e.deepMin != null || e.lightMin != null || e.remMin != null) {
            o.put("deepSleepMinutes", e.deepMin ?: 0)
            o.put("lightSleepMinutes", e.lightMin ?: 0)
            o.put("remSleepMinutes", e.remMin ?: 0)
          }
          if (o.length() > 0) extras.put(day, o)
        }
        if (extras.length() > 0) {
          val js = "window.kestrelBandExtras && window.kestrelBandExtras($extras);"
          webView.post { webView.evaluateJavascript(js, null) }
        }

        // Workouts recorded by the band
        val arr = JSONArray()
        val tracked = store.trackStarts()
        store.workouts().forEach { w ->
          arr.put(JSONObject().apply {
            put("id", "band-${w.startSec}")
            put("name", HuamiSportsSummaryParser.name(w.type))
            put("start", w.startSec * 1000)
            put("end", w.endSec * 1000)
            put("kind", HuamiSportsSummaryParser.webKind(w.type))
            put("deviceId", "mi-smart-band-5")
            if (tracked.contains(w.startSec)) { put("track", true); put("source", "phone") }
            put("minutes", maxOf(1, ((w.endSec - w.startSec) / 60).toInt()))
            if (w.activeSec > 0) put("movingSec", w.activeSec)
            if (w.calories > 0) put("calories", w.calories)
            if (w.distanceM > 0) put("distanceMeters", w.distanceM)
            if (w.steps > 0) put("steps", w.steps)
            if (w.avgHr > 0) put("heartRateAvg", w.avgHr)
            if (w.maxHr > 0) put("heartRateMax", w.maxHr)
          })
        }
        if (arr.length() > 0) {
          val js = "window.kestrelBandWorkouts && window.kestrelBandWorkouts($arr);"
          webView.post { webView.evaluateJavascript(js, null) }
        }
      } catch (e: Exception) {
        android.util.Log.e("BandSyncEngine", "push failed: ${e.message}")
      }
    }
  }

  fun pushLiveMetric(webView: WebView?, type: String, value: Number) {
    webView?.post {
      webView.evaluateJavascript(
        "window.kestrelLiveMetric && window.kestrelLiveMetric('$type', ${value});", null
      )
    }
  }

  private fun deviceMeta(state: MiBandState): JSONObject {
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
      timeZone = java.util.TimeZone.getTimeZone("UTC")
    }
    val device = JSONObject().apply {
      put("id", "mi-smart-band-5")
      put("name", if (state.deviceName.isNotEmpty()) state.deviceName else "Mi Smart Band 5")
      put("model", "Mi Smart Band 5")
      put("address", state.deviceAddress)
      if (state.batteryPercent >= 0) put("battery", state.batteryPercent)
      put("lastSync", iso.format(if (state.lastSyncMillis > 0) Date(state.lastSyncMillis) else Date()))
    }
    return JSONObject().apply {
      put("devices", JSONArray().put(device))
      if (state.batteryPercent >= 0) put("battery", state.batteryPercent)
    }
  }

  private fun iso8601(tsSec: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date(tsSec * 1000))

  private fun startOfDaysAgo(days: Int): Long {
    val c = Calendar.getInstance()
    c.add(Calendar.DAY_OF_YEAR, -days)
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis / 1000
  }
}
