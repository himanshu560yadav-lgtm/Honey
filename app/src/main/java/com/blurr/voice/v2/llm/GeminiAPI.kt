package com.blurr.voice.v2.llm

import android.util.Log
import com.blurr.voice.BuildConfig
import com.blurr.voice.utilities.ApiKeyManager
import com.blurr.voice.v2.AgentOutput
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import com.blurr.voice.v2.logging.TaskLogger
import android.content.Context
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiApi(
    private val modelName: String,
    private val apiKeyManager: ApiKeyManager,
    private val context: Context,
    private val maxRetry: Int = 3
) {

    companion object {
        private const val TAG = "GeminiV2Api"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val GEMINI_REST_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
    }

    private val proxyUrl: String = BuildConfig.GCLOUD_PROXY_URL
    private val proxyKey: String = BuildConfig.GCLOUD_PROXY_URL_KEY

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun generateAgentOutput(messages: List<GeminiMessage>): AgentOutput? {
        val jsonString = retryWithBackoff(times = maxRetry) {
            performApiCall(messages)
        } ?: return null

        try {
            val input = jsonParser.encodeToString(messages)
            TaskLogger.log(context, input, jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log task: ${e.message}")
        }

        return try {
            Log.d(TAG, "Raw LLM response: $jsonString")
            val cleanJson = jsonString
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            Log.d("GEMINIAPITEMP_OUTPUT", cleanJson)
            jsonParser.decodeFromString<AgentOutput>(cleanJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON into AgentOutput. Error: ${e.message}. Raw: $jsonString", e)
            null
        }
    }

    private suspend fun performApiCall(messages: List<GeminiMessage>): String {
        return if (proxyUrl.isNotBlank() && proxyKey.isNotBlank()) {
            Log.i(TAG, "Proxy config found. Using proxy.")
            performProxyApiCall(messages)
        } else {
            Log.i(TAG, "No proxy config. Using direct Gemini REST API.")
            performDirectRestApiCall(messages)
        }
    }

    private suspend fun performProxyApiCall(messages: List<GeminiMessage>): String {
        val contentsArray = buildContentsJson(messages)
        val requestBody = JSONObject().apply {
            put("modelName", modelName)
            put("messages", contentsArray)
        }

        val request = Request.Builder()
            .url(proxyUrl)
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Content-Type", "application/json")
            .addHeader("X-API-Key", proxyKey)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body.isNullOrBlank()) {
                throw IOException("Proxy failed: ${response.code} - $body")
            }
            Log.d(TAG, "Proxy response received.")
            return extractTextFromGeminiResponse(body)
        }
    }

    private suspend fun performDirectRestApiCall(messages: List<GeminiMessage>): String {
        val apiKey = apiKeyManager.getNextKey()
        val url = "$GEMINI_REST_BASE/$modelName:generateContent?key=$apiKey"

        val contentsArray = buildContentsJson(messages)
        val requestBody = JSONObject().apply {
            put("contents", contentsArray)
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.7)
                put("maxOutputTokens", 4096)
            })
        }

        Log.d(TAG, "Calling Gemini REST: $modelName, messages count: ${messages.size}")

        val request = Request.Builder()
            .url(url)
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Content-Type", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body.isNullOrBlank()) {
                throw IOException("Gemini REST API failed: ${response.code} - $body")
            }
            Log.d(TAG, "Direct REST response received.")
            return extractTextFromGeminiResponse(body)
        }
    }

    private fun buildContentsJson(messages: List<GeminiMessage>): JSONArray {
        val contentsArray = JSONArray()
        for (message in messages) {
            val role = when (message.role) {
                MessageRole.USER -> "user"
                MessageRole.MODEL -> "model"
                MessageRole.TOOL -> "user"
            }
            val partsArray = JSONArray()
            for (part in message.parts) {
                if (part is TextPart) {
                    partsArray.put(JSONObject().apply { put("text", part.text) })
                }
            }
            contentsArray.put(JSONObject().apply {
                put("role", role)
                put("parts", partsArray)
            })
        }
        return contentsArray
    }

    private fun extractTextFromGeminiResponse(responseBody: String): String {
        return try {
            val json = JSONObject(responseBody)
            val candidates = json.getJSONArray("candidates")
            val content = candidates.getJSONObject(0).getJSONObject("content")
            val parts = content.getJSONArray("parts")
            parts.getJSONObject(0).getString("text")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract text from Gemini response: $responseBody", e)
            throw IOException("Could not parse Gemini response: ${e.message}")
        }
    }

}

class ContentBlockedException(message: String) : Exception(message)

private suspend fun <T> retryWithBackoff(
    times: Int,
    initialDelay: Long = 1000L,
    maxDelay: Long = 16000L,
    factor: Double = 2.0,
    block: suspend () -> T
): T? {
    var currentDelay = initialDelay
    repeat(times) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            Log.e("RetryUtil", "Attempt ${attempt + 1}/$times failed: ${e.message}", e)
            if (attempt == times - 1) {
                Log.e("RetryUtil", "All $times retry attempts failed.")
                return null
            }
            delay(currentDelay)
            currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
        }
    }
    return null
}