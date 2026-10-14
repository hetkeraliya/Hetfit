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
 * On-device SLM via MediaPipe GenAI (reflection-based so the project still compiles
 * if the tasks-genai API surface differs slightly across versions).
 *
 * No API key for inference. Model is downloaded once into app storage.
 */
object OnDeviceSlm {
  private const val TAG = "OnDeviceSlm"
  private const val MODEL_FILE = "gemma3-1b-it-int4.task"
  private const val DEFAULT_MODEL_URL =
    "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task?download=true"

  private val http = OkHttpClient.Builder()
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(600, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

  /** Holds MediaPipe LlmInference instance (typed as Any to avoid hard API coupling). */
  private val engine = AtomicReference<Any?>(null)
  @Volatile private var lastError: String? = null
  @Volatile private var downloadProgress: Int = -1

  private fun modelFile(ctx: Context): File =
    File(File(ctx.filesDir, "models").apply { mkdirs() }, MODEL_FILE)

  fun statusJson(ctx: Context): String {
    val f = modelFile(ctx)
    val o = JSONObject()
    o.put("modelFile", f.absolutePath)
    o.put("present", f.exists() && f.length() > 50_000_000L)
    o.put("bytes", if (f.exists()) f.length() else 0L)
    o.put("ready", engine.get() != null || (f.exists() && f.length() > 50_000_000L))
    o.put("loaded", engine.get() != null)
    o.put("downloadProgress", downloadProgress)
    o.put("lastError", lastError ?: JSONObject.NULL)
    o.put("engine", "mediapipe-tasks-genai")
    o.put("modelName", "Gemma-class SLM (int4)")
    o.put("mediapipeAvailable", isMediaPipePresent())
    return o.toString()
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

  fun downloadModel(ctx: Context, url: String = DEFAULT_MODEL_URL): String {
    val dest = modelFile(ctx)
    val tmp = File(dest.absolutePath + ".part")
    return try {
      downloadProgress = 0
      lastError = null
      val req = Request.Builder().url(url).get().build()
      http.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
          lastError = "Download failed HTTP ${resp.code}"
          downloadProgress = -1
          return lastError!!
        }
        val body = resp.body ?: run {
          lastError = "Empty body"
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
              }
            }
            out.flush()
          }
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
      // Prefer nested LlmInferenceOptions, else top-level LlmInferenceOptions
      val optionsClass = try {
        Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions")
      } catch (_: Throwable) {
        Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInferenceOptions")
      }
      val builderMethod = optionsClass.getMethod("builder")
      val builder = builderMethod.invoke(null)
      val bClass = builder.javaClass
      // Required
      bClass.getMethod("setModelPath", String::class.java).invoke(builder, f.absolutePath)
      // Optional knobs — ignore if missing
      tryInvoke(builder, "setMaxTokens", 512)
      tryInvoke(builder, "setMaxTopK", 64)
      tryInvoke(builder, "setTopK", 40)
      tryInvoke(builder, "setTemperature", 0.7f)
      val options = bClass.getMethod("build").invoke(builder)
      val create = llmClass.methods.first {
        it.name == "createFromOptions" && it.parameterTypes.size == 2
      }
      val llm = create.invoke(null, ctx, options)
      engine.set(llm)
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
    } catch (_: Throwable) {
      // optional
    }
  }

  fun generate(ctx: Context, prompt: String): String? {
    if (!ensureLoaded(ctx)) return null
    val llm = engine.get() ?: return null
    return try {
      val m = llm.javaClass.methods.first {
        it.name == "generateResponse" && it.parameterTypes.size == 1 &&
          it.parameterTypes[0] == String::class.java
      }
      val result = m.invoke(llm, prompt)
      (result as? String)?.trim()?.ifBlank { null }
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
      append("You are Kestrel, a concise on-device fitness and wellness coach. ")
      append("Answer in 2–5 short sentences. Be practical. No markdown.\n\n")
      append("User data (on this phone):\n")
      append(contextBlock.trim())
      append("\n\nUser question: ")
      append(userQuestion.trim())
      append("\n\nAnswer:")
    }
    return generate(ctx, prompt)
      ?: "SLM not ready. ${lastError ?: "Download the on-device model first, or use the rule-based coach."}"
  }
}
