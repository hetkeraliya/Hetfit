package com.example.ble

data class BandWorkout(
  val startSec: Long,
  val endSec: Long,
  val type: Int,
  val steps: Int = 0,
  val activeSec: Int = 0,
  val calories: Int = 0,
  val distanceM: Float = 0f,
  val avgHr: Int = 0,
  val maxHr: Int = 0
) {
  val durationMin: Int get() = ((endSec - startSec) / 60).toInt().coerceAtLeast(0)
}
