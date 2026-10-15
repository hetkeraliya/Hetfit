package com.example.ai

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device SLM via MediaPipe GenAI (reflection-based).
 * No API key for inference after the model file is on the phone.
 */
object OnDeviceSlm {
  private const val TAG = "OnDeviceSlm"
  private const val MODEL_FILE = "gemma3-1b-it-int4.task"

  /** Public mirrors (gated HF repos return 401 without accepting license). */
  private val MODEL_URLS = listOf(
    "https://huggingface.co/MiCkSoftware/Gemma3-1B-IT-LiteRT/resolve/main/gemma3-1b-it-int4.task?download=true",
    "https://huggingface.co/MiCkSoftware/Gemma3-1B-IT-LiteRT/resolve/main/gemma3-1b-it-int4.task",
    "https://huggingface.co/ravikadam/running-coach-gemma3-1b-LiteRT/resolve/main/running-coach-gemma3-1b.task?download=true"
  )

  private val http = OkHttpClient.Builder()
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(900, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

  private val engine = AtomicReference<Any?>(null)
  @Volatile private var lastError: String? = null
  @Volatile private var downloadProgress: Int = -1

  private fun modelFile(ctx: Context): File =
    File(File(ctx.filesDir, "models").apply { mkdirs() }, MODEL_FILE)

  fun statusJson(ctx: Context): String {
    val f = modelFile(ctx)
    return JSONObject()
      .put("modelFile", f.absolutePath)
      .put("present", f.exists() && f.length() > 50_000_000L)
      .put("bytes", if (f.exists()) f.length() else 0L)
      .put("ready", engine.get() != null || (f.exists() && f.length() > 50_000_000L))
      .put("loaded", engine.get() != null)
      .put("downloadProgress", downloadProgress)
      .put("lastError", lastError ?: JSONObject.NULL)
      .put("engine", "mediapipe-tasks-genai")
      .put("modelName", "Gemma 3 1B int4")
      .put("mediapipeAvailable", isMediaPipePresent())
      .toString()
  }

  fun isModelPresent(ctx: Context): Boolean {
    val f = modelFile(ctx)
    return f.exists() && f.length() > 50_000_000L
  }

  private fun isMediaPipePresent(): Boolean = try {
    Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
    true
  } catch (_: Throwable) {
    false
  }

  /** Try each mirror until one succeeds. */
  fun downloadModel(ctx: Context, url: String? = null): String {
    val urls = if (!url.isNullOrBlank()) listOf(url) else MODEL_URLS
    var lastFail = "No URL tried"
    for (u in urls) {
      val result = downloadOne(ctx, u)
      if (result.startsWith("Model saved")) return result
      lastFail = result
      Log.w(TAG, "mirror failed: $u → $result")
    }
    return lastFail
  }

  private fun downloadOne(ctx: Context, url: String): String {
    val dest = modelFile(ctx)
    val tmp = File(dest.absolutePath + ".part")
    return try {
      downloadProgress = 0
      lastError = null
      val req = Request.Builder()
        .url(url)
        .header("User-Agent", "KestrelAndroid/1.0 (Android; on-device SLM)")
        .header("Accept", "*/*")
        .get()
        .build()
      http.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
          val code = resp.code
          lastError = when (code) {
            401, 403 -> "HTTP $code — model needs a public mirror (gated repo). Trying next…"
            404 -> "HTTP 404 — file not found on this mirror"
            else -> "HTTP $code — ${resp.message}"
          }
          downloadProgress = -1
          return lastError!!
        }
        val body = resp.body ?: run {
          lastError = "Empty response body"
          downloadProgress = -1
          return lastError!!
        }
        val total = body.contentLength()
        body.byteStream().use { input ->
          FileOutputStream(tmp).use { out ->
            val buf = ByteArray(256 * 1024)
            var read: Int
            var sum = 0L
            while (input.read(buf).also { read = it } != -1) {
              out.write(buf, 0, read)
              sum += read
              if (total > 0) {
                downloadProgress = ((sum * 100) / total).toInt().coerceIn(0, 99)
              } else if (sum > 0) {
                // unknown length — pulse progress based on MB
                downloadProgress = ((sum / (1024 * 1024)) % 90).toInt()
              }
            }
            out.flush()
          }
        }
        if (tmp.length() < 50_000_000L) {
          lastError = "Download too small (${tmp.length()} bytes) — not a real model file"
          downloadProgress = -1
          tmp.delete()
          return lastError!!
        }
      }
      if (dest.exists()) dest.delete()
      if (!tmp.renameTo(dest)) {
        tmp.copyTo(dest, overwrite = true)
        tmp.delete()
      }
      downloadProgress = 100
      close()
      "Model saved (${dest.length() / (1024 * 1024)} MB). Tap Load model."
    } catch (e: Exception) {
      Log.e(TAG, "download", e)
      lastError = e.message ?: "Download error"
      downloadProgress = -1
      try { tmp.delete() } catch (_: Exception) {}
      lastError!!
    }
  }

  /** Copy a user-picked .task file into model storage (path from FileProvider / absolute). */
  fun importFromPath(ctx: Context, path: String): String {
    return try {
      val src = File(path)
      if (!src.exists() || src.length() < 50_000_000L) {
        return "Invalid model file"
      }
      val dest = modelFile(ctx)
      src.copyTo(dest, overwrite = true)
      close()
      downloadProgress = 100
      "Imported (${dest.length() / (1024 * 1024)} MB). Tap Load model."
    } catch (e: Exception) {
      lastError = e.message
      "Import failed: ${e.message}"
    }
  }

  @Synchronized
  fun ensureLoaded(ctx: Context): Boolean {
    if (engine.get() != null) return true
    val f = modelFile(ctx)
    if (!f.exists() || f.length() < 50_000_000L) {
      lastError = "Model not downloaded yet"
      return false
    }
    if (!isMediaPipePresent()) {
      lastError = "MediaPipe tasks-genai not on classpath"
      return false
    }
    return try {
      val llmClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
      val optionsClass = try {
        Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions")
      } catch (_: Throwable) {
        Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInferenceOptions")
      }
      val builder = optionsClass.getMethod("builder").invoke(null)
      val bClass = builder.javaClass
      bClass.getMethod("setModelPath", String::class.java).invoke(builder, f.absolutePath)
      tryInvoke(builder, "setMaxTokens", 512)
      tryInvoke(builder, "setMaxTopK", 64)
      tryInvoke(builder, "setTopK", 40)
      tryInvoke(builder, "setTemperature", 0.7f)
      val options = bClass.getMethod("build").invoke(builder)
      val create = llmClass.methods.first {
        it.name == "createFromOptions" && it.parameterTypes.size == 2
      }
      engine.set(create.invoke(null, ctx, options))
      lastError = null
      downloadProgress = 101
      true
    } catch (e: Exception) {
      Log.e(TAG, "load", e)
      lastError = "Load failed: ${e.message}"
      engine.set(null)
      false
    }
  }

  private fun tryInvoke(builder: Any, name: String, value: Any) {
    try {
      val cls = when (value) {
        is Int -> Int::class.javaPrimitiveType
        is Float -> Float::class.javaPrimitiveType
        else -> value.javaClass
      }
      builder.javaClass.getMethod(name, cls).invoke(builder, value)
    } catch (_: Throwable) {}
  }

  fun generate(ctx: Context, prompt: String): String? {
    if (!ensureLoaded(ctx)) return null
    val llm = engine.get() ?: return null
    return try {
      val m = llm.javaClass.methods.first {
        it.name == "generateResponse" && it.parameterTypes.size == 1 &&
          it.parameterTypes[0] == String::class.java
      }
      (m.invoke(llm, prompt) as? String)?.trim()?.ifBlank { null }
    } catch (e: Exception) {
      Log.e(TAG, "generate", e)
      lastError = e.message
      close()
      null
    }
  }

  fun close() {
    val llm = engine.getAndSet(null) ?: return
    try {
      llm.javaClass.methods.firstOrNull { it.name == "close" && it.parameterTypes.isEmpty() }
        ?.invoke(llm)
    } catch (_: Exception) {}
  }

  fun coach(ctx: Context, userQuestion: String, contextBlock: String): String {
    val prompt = buildString {
      appendLine("You are Kestrel, an intelligent on-device health coach with access to this user's phone data only.")
      appendLine("Rules:")
      appendLine("- Use the numeric data below; never invent steps, HR, sleep, or water.")
      appendLine("- Be specific and practical. 3–8 sentences max unless they ask for a todo list.")
      appendLine("- For todo/schedule requests: output a short intro then a numbered list with tags [study]/[health],[life].")
      appendLine("- No markdown headings. No APIs or cloud.")
      appendLine()
      appendLine("LIVE DATA ON THIS PHONE:")
      appendLine(contextBlock.trim())
      appendLine()
      appendLine("USER:")
      appendLine(userQuestion.trim())
      appendLine()
      append("KESTREL:")
    }
    return generate(ctx, prompt)
      ?: "SLM not ready. ${lastError ?: "Download the on-device model first, or use the rule-based coach."}"
  }
}
