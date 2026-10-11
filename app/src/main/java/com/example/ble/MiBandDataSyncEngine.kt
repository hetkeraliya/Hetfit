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
 * Sends REAL band history to the Kestrel web UI.
 * Also pushes structured sleep sessions and workouts for the new dashboard.
 */
object MiBandDataSyncEngine {
  private val io = Executors.newSingleThreadExecutor()
  private const val CHUNK = 2000

  fun pushToWebView(webView: WebView?, store: BandSampleStore, state: MiBandState, fullHistory: Boolean) {
    if (webView == null) return
    io.execute {
      try {
        val since = if (fullHistory) 0L else startOfDaysAgo(3) - 8 * 3600
        webView.post { webView.evaluateJavascript("window.kestrelBandSamplesBegin && window.kestrelBandSamplesBegin();", null) }
        store.forEachChunk(since, CHUNK) { rows ->
          val sb = StringBuilder(rows.size * 36)
          sb.append('[')
          rows.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            // Extended: [ts, intensity, steps, hr, kind, deep, rem]
            val kind = if (r.kind == 120) 120 else 1
            val hr = if (r.hr in 1..253) r.hr else 0
            sb.append('[').append(r.ts).append(',').append(r.intensity).append(',')
              .append(r.steps).append(',').append(hr).append(',').append(kind)
              .append(',').append(r.deep).append(',').append(r.rem).append(']')
          }
          sb.append(']')
          val js = "window.kestrelBandSamplesChunk && window.kestrelBandSamplesChunk($sb);"
          webView.post { webView.evaluateJavascript(js, null) }
        }
        val meta = deviceMeta(state).toString()
        webView.post { webView.evaluateJavascript("window.kestrelBandSamplesEnd && window.kestrelBandSamplesEnd($meta);", null) }

        // Push structured sleep + workouts
        val sleepJson = store.sleepSummaryJson().toString()
        webView.post {
          webView.evaluateJavascript(
            "window.kestrelSleepSummary && window.kestrelSleepSummary($sleepJson);", null
          )
        }
        val workouts = store.getWorkouts(30).toString()
        webView.post {
          webView.evaluateJavascript(
            "window.kestrelWorkouts && window.kestrelWorkouts($workouts);", null
          )
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

  fun pushSleepAndWorkouts(webView: WebView?, store: BandSampleStore) {
    if (webView == null) return
    io.execute {
      val sleepJson = store.sleepSummaryJson().toString()
      val workouts = store.getWorkouts(30).toString()
      webView.post {
        webView.evaluateJavascript("window.kestrelSleepSummary && window.kestrelSleepSummary($sleepJson);", null)
        webView.evaluateJavascript("window.kestrelWorkouts && window.kestrelWorkouts($workouts);", null)
      }
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

  private fun startOfDaysAgo(days: Int): Long {
    val c = Calendar.getInstance()
    c.add(Calendar.DAY_OF_YEAR, -days)
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis / 1000
  }
}
