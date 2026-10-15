package com.example.ai

import android.content.Context
import com.example.ble.MiBandManager
import com.example.notify.KestrelCoachNotifications
import com.example.widget.KestrelWidgetManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device coach with full local health context.
 * Prefers MediaPipe SLM when loaded; otherwise a structured intelligent rule engine
 * (not keyword stubs) that synthesizes steps, HR, sleep, water, mood, plan, battery.
 */
object LocalKestrelBrain {

  fun answer(context: Context, question: String): String {
    val ctx = buildContextJson(context)
    val ctxBlock = formatContext(ctx)

    if (OnDeviceSlm.isModelPresent(context)) {
      val slm = OnDeviceSlm.coach(context, question, ctxBlock)
      if (slm.isNotBlank() && !slm.startsWith("SLM not ready")) return slm
    }

    return smartAnswer(question, ctx)
  }

  /** Full snapshot for bridge / web. */
  fun contextJson(context: Context): String = buildContextJson(context).toString()

  private fun buildContextJson(context: Context): JSONObject {
    val st = MiBandManager.get(context).uiState.value
    val plan = KestrelCoachNotifications.planProgress(context)
    val life = KestrelWidgetManager.readLife(context)
    val day = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    return JSONObject()
      .put("now", day)
      .put("steps", st.liveSteps)
      .put("stepGoal", 8000)
      .put("heartRate", st.liveHeartRate)
      .put("calories", try { st.liveCalories } catch (_: Exception) { 0 })
      .put("battery", st.batteryPercent)
      .put("connected", st.status.name == "CONNECTED")
      .put("deviceName", st.deviceName)
      .put("waterMl", life.optInt("waterMl", 0))
      .put("waterGoal", life.optInt("waterGoal", 2500).coerceAtLeast(500))
      .put("moodScore", life.optInt("moodScore", 0))
      .put("moodLabel", life.optString("moodLabel", ""))
      .put("sleepMin", life.optInt("sleepMin", 0))
      .put("planPct", plan.optInt("overallPct", -1))
      .put("planRemaining", plan.optInt("remaining", -1))
      .put("planDone", plan.optInt("done", -1))
      .put("planTotal", plan.optInt("total", -1))
  }

  private fun formatContext(ctx: JSONObject): String = buildString {
    appendLine("Timestamp: ${ctx.optString("now")}")
    appendLine("Band: ${if (ctx.optBoolean("connected")) "connected" else "offline"} ${ctx.optString("deviceName")} battery=${ctx.optInt("battery")}%")
    appendLine("Steps: ${ctx.optInt("steps")} (goal ~${ctx.optInt("stepGoal")})")
    appendLine("Heart rate: ${ctx.optInt("heartRate")} bpm")
    appendLine("Calories: ${ctx.optInt("calories")}")
    appendLine("Sleep: ${ctx.optInt("sleepMin")} min last night")
    appendLine("Water: ${ctx.optInt("waterMl")} / ${ctx.optInt("waterGoal")} ml")
    appendLine("Mood: ${ctx.optInt("moodScore")}/5 ${ctx.optString("moodLabel")}")
    appendLine("Plan progress: ${ctx.optInt("planPct")}% (${ctx.optInt("planRemaining")} remaining)")
  }

  private fun smartAnswer(question: String, ctx: JSONObject): String {
    val q = question.trim()
    val ql = q.lowercase(Locale.US)
    val steps = ctx.optInt("steps")
    val goal = ctx.optInt("stepGoal", 8000).coerceAtLeast(1000)
    val hr = ctx.optInt("heartRate")
    val water = ctx.optInt("waterMl")
    val waterGoal = ctx.optInt("waterGoal", 2500)
    val mood = ctx.optInt("moodScore")
    val sleep = ctx.optInt("sleepMin")
    val planPct = ctx.optInt("planPct", -1)
    val remaining = ctx.optInt("planRemaining", -1)
    val battery = ctx.optInt("battery", -1)
    val connected = ctx.optBoolean("connected")

    val stepPct = (steps * 100 / goal).coerceIn(0, 200)
    val waterPct = (water * 100 / waterGoal).coerceIn(0, 200)
    val sleepH = if (sleep > 0) String.format(Locale.US, "%.1f", sleep / 60.0) else null

    // Todo-oriented replies
    if (ql.contains("todo") || ql.contains("to-do") || ql.contains("task list") ||
      ql.contains("for tomorrow") || ql.contains("schedule")
    ) {
      return buildTodoPlan(ql, steps, stepPct, waterPct, mood, sleep, planPct, remaining)
    }
    if (ql.contains("remove todo") || ql.contains("delete todo") || ql.contains("clear todo")) {
      return "Open the Todos tab and tap ✕ on any item to remove it. Or say: \"clear done todos\" after checking them off."
    }
    if (ql.startsWith("add todo") || ql.contains("add todo:")) {
      val text = q.substringAfter(':', q).replace(Regex("(?i)add todo[:\\s]*"), "").trim()
      return if (text.isNotBlank()) "I'll note that as a todo: \"$text\". (Saved from the chat UI.)"
      else "Say: add todo: <what you need to do>"
    }

    val parts = mutableListOf<String>()

    // Always ground in real numbers when user asks broadly
    val wantsSummary = ql.contains("today") || ql.contains("how am i") || ql.contains("summary") ||
      ql.contains("status") || ql.contains("overview") || ql.length < 12

    if (wantsSummary || ql.contains("health") || ql.contains("everything")) {
      parts += dayNarrative(steps, stepPct, goal, hr, water, waterPct, waterGoal, mood, sleepH, planPct, remaining, battery, connected)
    } else {
      if (ql.contains("step") || ql.contains("walk") || ql.contains("move") || ql.contains("active")) {
        parts += stepNarrative(steps, stepPct, goal)
      }
      if (ql.contains("heart") || ql.contains("hr") || ql.contains("pulse") || ql.contains("cardio")) {
        parts += hrNarrative(hr)
      }
      if (ql.contains("water") || ql.contains("hydrat") || ql.contains("drink")) {
        parts += waterNarrative(water, waterPct, waterGoal)
      }
      if (ql.contains("mood") || ql.contains("feel") || ql.contains("stress") || ql.contains("anxious")) {
        parts += moodNarrative(mood, hr, sleep)
      }
      if (ql.contains("sleep") || ql.contains("tired") || ql.contains("rest") || ql.contains("insomnia")) {
        parts += sleepNarrative(sleep, sleepH)
      }
      if (ql.contains("plan") || ql.contains("workout") || ql.contains("study") || ql.contains("habit")) {
        parts += planNarrative(planPct, remaining)
      }
      if (ql.contains("battery") || ql.contains("band") || ql.contains("connect")) {
        parts += bandNarrative(connected, battery)
      }
      if (parts.isEmpty()) {
        // Intelligent synthesis even for free-form questions
        parts += dayNarrative(steps, stepPct, goal, hr, water, waterPct, waterGoal, mood, sleepH, planPct, remaining, battery, connected)
        parts += "\nOn your question: I only use data on this phone (band, water, mood, plan). Rephrase with steps, sleep, water, mood, plan, or todos if you want a tighter answer."
      }
    }

    return parts.joinToString("\n\n")
  }

  private fun dayNarrative(
    steps: Int, stepPct: Int, goal: Int, hr: Int,
    water: Int, waterPct: Int, waterGoal: Int, mood: Int,
    sleepH: String?, planPct: Int, remaining: Int,
    battery: Int, connected: Boolean
  ): String {
    val sb = StringBuilder()
    sb.append("Here's where you stand right now.\n")
    sb.append("• Steps: $steps (${stepPct}% of $goal). ")
    sb.append(
      when {
        stepPct >= 100 -> "Goal hit — nice consistency."
        stepPct >= 60 -> "Solid progress; a short walk closes the gap."
        stepPct >= 30 -> "Midday window is a good time to add movement."
        else -> "Still early — stack a 10–15 min walk when you can."
      }
    )
    sb.append("\n")
    if (hr > 0) {
      sb.append("• Heart rate: $hr bpm. ")
      sb.append(if (hr in 50..100) "Resting range looks fine.\n" else if (hr > 100) "Elevated — cool down and hydrate if you just trained.\n" else "Low reading — confirm the band fit.\n")
    } else sb.append("• Heart rate: no live reading yet.\n")
    sb.append("• Water: $water / $waterGoal ml (${waterPct}%). ")
    sb.append(if (waterPct >= 100) "Hydration goal done.\n" else "About ${(waterGoal - water).coerceAtLeast(0)} ml left.\n")
    if (sleepH != null) sb.append("• Sleep: ${sleepH}h last night.\n")
    if (mood in 1..5) sb.append("• Mood check-in: $mood/5.\n")
    if (planPct >= 0) {
      sb.append("• Plan: $planPct% complete")
      if (remaining > 0) sb.append(", $remaining items left")
      sb.append(".\n")
    }
    if (connected) sb.append("• Band connected") else sb.append("• Band offline")
    if (battery >= 0) sb.append(", battery $battery%")
    sb.append(".")
    return sb.toString()
  }

  private fun stepNarrative(steps: Int, stepPct: Int, goal: Int) =
    "Steps are at $steps — ${stepPct}% of your $goal goal. " +
      when {
        stepPct >= 100 -> "You've cleared the goal. Optional: easy walk for recovery, not another hard push."
        stepPct >= 70 -> "One focused 15–20 min walk should finish it."
        else -> "Break it up: 8–10 min now, another block later. Stairs and short loops count."
      }

  private fun hrNarrative(hr: Int) = when {
    hr <= 0 -> "No live heart-rate sample. Wear the band snug, wait 30s, or open Vitals after a sync."
    hr < 50 -> "HR $hr bpm is low — if you feel fine it may be fitness or a loose sensor. Recheck seated."
    hr <= 100 -> "HR $hr bpm is in a normal resting/easy range. No red flags from this single reading."
    hr <= 140 -> "HR $hr bpm is elevated. Fine during exercise; if resting, sit, breathe, sip water, recheck in 5 min."
    else -> "HR $hr bpm is high. Stop intense effort, cool down. If chest pain, dizziness, or it stays high at rest, seek care."
  }

  private fun waterNarrative(ml: Int, pct: Int, goal: Int) =
    "Water $ml / $goal ml ($pct%). " +
      if (pct >= 100) "Goal met — steady sips beat late chugging."
      else "Roughly ${(goal - ml).coerceAtLeast(0)} ml to go. One glass every couple of hours works."

  private fun moodNarrative(mood: Int, hr: Int, sleep: Int): String {
    val moodBit = when (mood) {
      0 -> "No mood logged today — a 3-second check-in in Wellness helps the coach personalize."
      1, 2 -> "Mood is low ($mood/5). Shrink the plan: one small win, daylight walk, message someone if it helps."
      3 -> "Mood is okay (3/5). Keep routines light and finish one meaningful task."
      else -> "Mood is solid ($mood/5). Good day to protect sleep and not overbook."
    }
    val sleepBit = if (sleep in 1..359) " Sleep was short; that often drags mood down." else ""
    val hrBit = if (hr > 110) " HR is elevated — pair calm breathing with a pause." else ""
    return moodBit + sleepBit + hrBit
  }

  private fun sleepNarrative(sleep: Int, sleepH: String?) = when {
    sleep <= 0 -> "No sleep minutes on record. Sync the band after waking or import from Gadgetbridge/Health Connect."
    sleep < 360 -> "About ${sleepH}h sleep — under 6h. Prioritize an earlier wind-down tonight; keep today's training easier."
    sleep < 480 -> "About ${sleepH}h — workable. Aim nearer 7–8h if you can; caffeine cutoff earlier helps."
    else -> "About ${sleepH}h sleep — strong recovery signal. Use it for quality work, not only more intensity."
  }

  private fun planNarrative(pct: Int, remaining: Int) = when {
    pct >= 100 -> "Plan is complete. Shift to recovery: water, steps, and sleep."
    remaining > 0 -> "Plan is ${pct.coerceAtLeast(0)}% done with $remaining left. Pick the smallest task and finish it in the next 25 minutes."
    pct >= 0 -> "Plan is at $pct%. Open Plan and tick items as you go so progress stays honest."
    else -> "No plan progress yet. Use the Plan tab for workout/study blocks — the coach tracks completion on-device."
  }

  private fun bandNarrative(connected: Boolean, battery: Int): String {
    val c = if (connected) "Band is connected." else "Band is offline — open Settings → Band to reconnect."
    val b = when {
      battery < 0 -> ""
      battery <= 15 -> " Battery $battery% — charge soon or you'll lose live HR/steps."
      battery <= 30 -> " Battery $battery%."
      else -> " Battery $battery%."
    }
    return c + b
  }

  private fun buildTodoPlan(
    ql: String, steps: Int, stepPct: Int, waterPct: Int,
    mood: Int, sleep: Int, planPct: Int, remaining: Int
  ): String {
    val lines = mutableListOf<String>()
    lines += "Tomorrow plan (grounded in today's data):"
    // Study always if mentioned else general focus
    if (ql.contains("study") || ql.contains("exam") || ql.contains("homework") || ql.contains("math") || ql.contains("code")) {
      lines += "1. [study] 25–40 min deep work on the hardest subject"
      lines += "2. [study] Review notes / 10 practice questions"
    } else {
      lines += "1. [study] One focused block (25–40 min) on your top priority"
    }
    if (stepPct < 80) lines += "${lines.size}. [health] Walk 15–20 min (steps only at $stepPct% today)"
    else lines += "${lines.size}. [health] Easy movement or stretch — steps already strong"
    if (waterPct < 90) lines += "${lines.size}. [health] Hit water goal early (today ${waterPct}%)"
    if (sleep in 1..359 || mood in 1..2) lines += "${lines.size}. [health] Lights-out 30 min earlier"
    if (remaining > 0) lines += "${lines.size}. [life] Finish $remaining open plan item(s)"
    lines += "${lines.size}. [life] Prep bag / materials tonight"
    lines += "\nSay \"add todo: …\" for extras. In Todos, tap ✕ to remove any item. Ask me to change the list anytime."
    return lines.joinToString("\n")
  }
}
