package com.example.ai

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenCode Zen client (https://opencode.ai/zen/v1).
 * Default free model: space-bunny-free
 * API key is stored only on-device in SharedPreferences.
 */
class OpenCodeZenClient(context: Context) {

  private val prefs: SharedPreferences =
    context.getSharedPreferences("kestrel_ai", Context.MODE_PRIVATE)

  private val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

  var apiKey: String
    get() = prefs.getString(KEY_API, "") ?: ""
    set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

  var model: String
    get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

  fun isConfigured(): Boolean = apiKey.isNotBlank()

  /**
   * Send a chat request to OpenCode Zen (OpenAI-compatible chat/completions).
   * @param systemPrompt optional system message (e.g. health context)
   * @param userMessage the question
   * @return assistant reply text or error message
   */
  suspend fun chat(systemPrompt: String?, userMessage: String): Result<String> = withContext(Dispatchers.IO) {
    if (!isConfigured()) {
      return@withContext Result.failure(IllegalStateException("API key not set. Open AI settings and paste your OpenCode Zen key."))
    }
    try {
      val messages = JSONArray()
      if (!systemPrompt.isNullOrBlank()) {
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
      }
      messages.put(JSONObject().put("role", "user").put("content", userMessage))

      val body = JSONObject()
        .put("model", model)
        .put("messages", messages)
        .put("temperature", 0.6)
        .put("max_tokens", 1024)
        .toString()

      val req = Request.Builder()
        .url(BASE_URL)
        .addHeader("Authorization", "Bearer $apiKey")
        .addHeader("Content-Type", "application/json")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()

      client.newCall(req).execute().use { resp ->
        val text = resp.body?.string() ?: ""
        if (!resp.isSuccessful) {
          return@withContext Result.failure(RuntimeException("HTTP ${resp.code}: ${text.take(200)}"))
        }
        val json = JSONObject(text)
        val content = json.optJSONArray("choices")
          ?.optJSONObject(0)
          ?.optJSONObject("message")
          ?.optString("content")
          ?: json.optString("output_text", "")
        if (content.isBlank()) {
          Result.failure(RuntimeException("Empty response from model"))
        } else {
          Result.success(content.trim())
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  companion object {
    private const val KEY_API = "opencode_zen_api_key"
    private const val KEY_MODEL = "opencode_zen_model"
    const val DEFAULT_MODEL = "space-bunny-free"
    const val BASE_URL = "https://opencode.ai/zen/v1/chat/completions"

    @Volatile private var instance: OpenCodeZenClient? = null
    fun get(context: Context): OpenCodeZenClient =
      instance ?: synchronized(this) {
        instance ?: OpenCodeZenClient(context.applicationContext).also { instance = it }
      }
  }
}
