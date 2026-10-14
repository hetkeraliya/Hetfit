package com.example.ai

import android.content.Context
import com.example.ble.MiBandManager
import com.example.notify.KestrelCoachNotifications
import com.example.widget.KestrelWidgetManager
import org.json.JSONObject

/**
 * Fully on-device "AI" coach — no API keys, no network.
 * Uses rules + templates over local band / plan / water / mood state.
 * Not a neural LLM; runs offline on every phone.
 */
object LocalKestrelBrain {

  fun answer(context: Context, question: String): String {
    val q = question.lowercase()
    val st = MiBandManager.get(context).uiState.value
    val plan = KestrelCoachNotifications.planProgress(context)
    val life = KestrelWidgetManager.readLife(context)
    val steps = st.liveSteps
    val hr = st.liveHeartRate
    val sleep = life.optInt("sleepMin", 0)
    val water = life.optInt("waterMl", 0)
    val waterGoal = life.optInt("waterGoal", 2500).coerceAtLeast(500)
    val mood = life.optInt("moodScore", 0)
    val planPct = plan.optInt("overallPct", -1)
    val remaining = plan.optInt("remaining", -1)

    return when {
      q.contains("water") || q.contains("hydrat") ->
        waterAdvice(water, waterGoal)
      q.contains("mood") || q.contains("feel") || q.contains("stress") ->
        moodAdvice(mood, hr, sleep)
      q.contains("sleep") ->
        sleepAdvice(sleep)
      q.contains("step") || q.contains("walk") || q.contains("move") ->
        stepAdvice(steps)
      q.contains("plan") || q.contains("workout") || q.contains("study") ->
        planAdvice(planPct, remaining)
      q.contains("heart") || q.contains("hr") || q.contains("pulse") ->
        hrAdvice(hr)
      q.contains("summary") || q.contains("today") || q.contains("how am i") || q.contains("status") ->
        daySummary(steps, hr, water, waterGoal, mood, planPct, remaining)
      else ->
        daySummary(steps, hr, water, waterGoal, mood, planPct, remaining) +
          "\n\nYou can ask about steps, sleep, water, mood, plan, or heart rate — all answered on this phone, offline."
    }
  }

  private fun waterAdvice(ml: Int, goal: Int): String {
    val left = (goal - ml).coerceAtLeast(0)
    val pct = (ml * 100 / goal).coerceIn(0, 100)
    return if (left == 0)
      "Water goal hit ($ml ml). Nice — stay steady, don't chug late at night."
    else
      "Water: $ml / $goal ml ($pct%). About ${left} ml left today. A glass every couple of hours is enough."
  }

  private fun moodAdvice(score: Int, hr: Int, sleepMin: Int): String {
    val moodWord = when (score) {
      1, 2 -> "low"
      3 -> "neutral"
      4, 5 -> "good"
      else -> "unknown"
    }
    val tips = mutableListOf<String>()
    if (score in 1..2) tips += "A short walk or 5 deep breaths often lifts a low mood more than scrolling."
    if (sleepMin in 1..359) tips += "Sleep was short last night — protect bedtime; mood tracks sleep closely."
    if (hr in 90..140) tips += "HR is a bit elevated at rest — ease caffeine and check in later."
    if (tips.isEmpty()) tips += "Log mood again this evening to spot patterns across the week."
    return "Mood reads $moodWord" + (if (score > 0) " ($score/5)" else "") + ". " + tips.joinToString(" ")
  }

  private fun sleepAdvice(sleepMin: Int): String {
    if (sleepMin <= 0) return "No sleep data yet. Sync the band after a night of wear, then ask again."
    val h = sleepMin / 60
    val m = sleepMin % 60
    return when {
      sleepMin >= 420 -> "You logged ${h}h ${m}m — solid. Keep a steady wake time and dim screens in the last hour."
      sleepMin >= 360 -> "You logged ${h}h ${m}m — okay, a bit light. Aim closer to 7–8h if you can."
      else -> "Only ${h}h ${m}m logged. Prioritize an earlier wind-down tonight; one good night helps HR and mood."
    }
  }

  private fun stepAdvice(steps: Int): String = when {
    steps >= 10000 -> "Steps are strong at ${"%,d".format(steps)}. You can ease intensity and still win the day."
    steps >= 8000 -> "You're at ${"%,d".format(steps)} steps — goal range. A short evening walk seals it."
    steps >= 4000 -> "Midday mark: ${"%,d".format(steps)} steps. Another 20–30 minutes of walking gets you close to goal."
    steps > 0 -> "Only ${"%,d".format(steps)} steps so far. A 10-minute walk now is the highest-ROI move."
    else -> "No steps counted yet today. Start with a short walk after your next break."
  }

  private fun planAdvice(pct: Int, remaining: Int): String = when {
    pct >= 100 -> "Plan is complete. Recovery (water, sleep, easy movement) is the right follow-up."
    remaining > 0 -> "Plan is ${pct.coerceAtLeast(0)}% done — $remaining items left. Pick the smallest one and finish it first."
    else -> "Open the Plan tab and tick workout, study, and meals as you go. Progress saves on the phone."
  }

  private fun hrAdvice(hr: Int): String = when {
    hr <= 0 -> "No recent heart-rate sample. Wear the band and wait a minute, or use Measure HR in Settings."
    hr < 50 -> "HR $hr bpm is low — normal for athletes, but if you feel dizzy, rest and recheck."
    hr < 90 -> "HR $hr bpm looks calm. Good baseline for the day."
    hr < 120 -> "HR $hr bpm is a bit active. Fine after movement; if you're sitting, breathe slowly for a minute."
    else -> "HR $hr bpm is high for rest. Pause hard effort, hydrate, and recheck in 10 minutes."
  }

  private fun daySummary(
    steps: Int, hr: Int, water: Int, waterGoal: Int,
    mood: Int, planPct: Int, remaining: Int
  ): String {
    val parts = mutableListOf<String>()
    parts += if (steps > 0) "Steps ${"%,d".format(steps)}" else "Steps not in yet"
    if (hr > 0) parts += "HR $hr"
    parts += "Water $water/$waterGoal ml"
    if (mood > 0) parts += "Mood $mood/5"
    if (planPct >= 0) parts += "Plan $planPct%"
    val head = "Today on-device: " + parts.joinToString(" · ") + "."
    val tip = when {
      remaining > 0 -> " Focus: clear $remaining plan items."
      water < waterGoal / 2 -> " Focus: drink some water."
      steps in 1..4999 -> " Focus: add a short walk."
      else -> " You're in a good place — keep the routine."
    }
    return head + tip
  }
}
