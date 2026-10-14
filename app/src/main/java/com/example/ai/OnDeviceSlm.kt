package com.example.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device small language model (SLM) via MediaPipe GenAI.
 * - No API key for inference
 * - Model file downloaded once into app storage, then fully offline
 * - Preferred model: Gemma 3 1B int4 (~0.5–1 GB depending on build)
 *
 * Falls back gracefully if the model is missing or the device is too weak.
 */
object OnDeviceSlm {
  private const val TAG = "OnDeviceSlm"
  private const val MODEL_FILE = "gemma3-1b-it-int4.task"
  /**
   * Public MediaPipe-ready Gemma 3 1B (update URL if upstream renames the asset).
   * First launch downloads this; after that, zero network.
   */
  private const val DEFAULT_MODEL_URL =
    "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task?download=true"

  private val http = OkHttpClient.Builder()
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(600, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

  private val engine = AtomicReference<LlmInference?>(null)
  @Volatile private var lastError: String? = null
  @Volatile private var downloadProgress: Int = -1 // -1 idle, 0-100, 101 ready

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
    return o.toString()
  }

  fun isModelPresent(ctx: Context): Boolean {
    val f = modelFile(ctx)
    return f.exists() && f.length() > 50_000_000L
  }

  /** Blocking download with progress (0–100). Call from background. */
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
      // unload old engine so next ask reloads new file
      close()
      "Model saved (${dest.length() / (1024 * 1024)} MB). Ready to load."
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
    return try {
      val options = try {
        LlmInference.LlmInferenceOptions.builder()
          .setModelPath(f.absolutePath)
          .setMaxTokens(512)
          .setTopK(40)
          .setTemperature(0.7f)
          .build()
      } catch (_: Throwable) {
        LlmInference.LlmInferenceOptions.builder()
          .setModelPath(f.absolutePath)
          .setMaxTokens(512)
          .build()
      }
      val llm = LlmInference.createFromOptions(ctx, options)
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

  fun generate(ctx: Context, prompt: String): String? {
    if (!ensureLoaded(ctx)) return null
    val llm = engine.get() ?: return null
    return try {
      llm.generateResponse(prompt)?.trim()?.ifBlank { null }
    } catch (e: Exception) {
      Log.e(TAG, "generate", e)
      lastError = e.message
      // engine may be bad after OOM — drop it
      try { llm.close() } catch (_: Exception) {}
      engine.set(null)
      null
    }
  }

  fun close() {
    try { engine.getAndSet(null)?.close() } catch (_: Exception) {}
  }

  /**
   * Build a fitness-coach system prompt with local context, then run SLM.
   */
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
