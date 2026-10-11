package com.example.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Port of Gadgetbridge HuamiActivitySummaryParser (the numeric fields we show). */
object HuamiSportsSummaryParser {

  fun parse(bytes: ByteArray, startSec: Long): BandWorkout? {
    if (bytes.size < 40) return null
    return try {
      val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      val version = b.short.toInt() and 0xFFFF
      val type = b.short.toInt() and 0xFFFF
      val tsStart = b.int.toLong() and 0xFFFFFFFFL
      val tsEnd = b.int.toLong() and 0xFFFFFFFFL
      val duration = (tsEnd - tsStart).coerceIn(0L, 24L * 3600)
      b.int; b.int; b.int // base longitude, latitude, altitude

      val steps: Int
      val activeSec: Int
      val calories: Float
      val distance: Float
      val avgHr: Int
      var maxHr = 0

      if (version >= 512) {
        if (version == 519) {
          b.get(); b.short
          b.position(0x8c)
        } else if (version == 516) {
          b.position(b.position() + 4)
        }
        steps = b.int
        activeSec = b.int
        repeat(4) { b.int }          // max/min latitude/longitude
        calories = b.float
        distance = b.float
        repeat(5) { b.float }        // ascent, descent, max/min/avg altitude
        repeat(6) { b.float }        // speeds and paces
        repeat(3) { b.float }        // cadence
        repeat(3) { b.float }        // stride
        b.float; b.int               // distance2, unknown
        avgHr = b.short.toInt()
        b.short; b.short             // km pace, stride
        maxHr = b.short.toInt()
      } else {
        distance = b.float
        repeat(4) { b.float }        // ascent, descent, min/max altitude
        repeat(4) { b.int }          // lat/long bounds
        steps = b.int
        activeSec = b.int
        calories = b.float
        repeat(4) { b.float }        // max speed, max/min pace, total stride
        b.int                        // unknown
        b.position(b.position() + 28) // swim / ascent-descent block (28 bytes either way)
        avgHr = b.short.toInt()
      }

      fun hr(v: Int) = if (v in 30..220) v else 0
      BandWorkout(
        startSec = startSec,
        endSec = startSec + duration,
        type = type,
        steps = steps.coerceIn(0, 200_000),
        activeSec = activeSec.coerceIn(0, 24 * 3600),
        calories = calories.takeIf { it.isFinite() && it >= 0 }?.toInt() ?: 0,
        distanceM = distance.takeIf { it.isFinite() && it >= 0 } ?: 0f,
        avgHr = hr(avgHr),
        maxHr = hr(maxHr)
      )
    } catch (e: Exception) {
      null
    }
  }

  fun name(type: Int): String = when (type) {
    1 -> "Outdoor Running"; 2 -> "Treadmill"; 3 -> "Walking"; 4 -> "Cycling"; 5 -> "Exercise"
    6 -> "Pool Swimming"; 7 -> "Open Water Swimming"; 8 -> "Indoor Cycling"; 9 -> "Elliptical"
    10 -> "Climbing"; 15 -> "Hiking"; 0x12 -> "Soccer"; 0x15 -> "Jump Rope"; 0x17 -> "Rowing Machine"
    0x34 -> "Strength Training"; 0x3c -> "Yoga"; 0x4e -> "Cricket"; 0x55 -> "Basketball"
    0x59 -> "Table Tennis"; 0x5c -> "Badminton"
    else -> "Workout"
  }

  /** Gadgetbridge ActivityKind codes understood by the web UI (ACTIVITY_KIND in app.js). */
  fun webKind(type: Int): Int = when (type) {
    1 -> 16; 2 -> 17; 3 -> 32; 4 -> 128; 6 -> 65; 7 -> 66; 8 -> 129; 9 -> 2048; 15 -> 1024
    0x15 -> 8192; 0x17 -> 4096; 0x34 -> 768; 5 -> 256
    else -> 512
  }
}
