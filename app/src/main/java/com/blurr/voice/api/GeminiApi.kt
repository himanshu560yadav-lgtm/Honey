package com.blurr.voice.api

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.blurr.voice.BuildConfig
import com.blurr.voice.MyApplication
import com.blurr.voice.utilities.ApiKeyManager
import com.google.ai.client.generativeai.type.ImagePart
import com.google.ai.client.generativeai.type.TextPart
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.blurr.voice.utilities.NetworkConnectivityManager
import com.blurr.voice.utilities.NetworkNotifier
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

object GeminiApi {
    private val proxyUrl: String = BuildConfig.GCLOUD_PROXY_URL
    private val proxyKey: String = BuildConfig.GCLOUD_PROXY_URL_KEY

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun generateContent(
        chat: List<Pair<String, List<Any>>>,
        images: List<Bitmap> = emptyList(),
        modelName: String = "gemini-2.0-flash",
        maxRetry: Int = 3,
        context: Context? = null
    ): String? {
        try {
            val appCtx = context ?: MyApplication.appContext
            val isOnline = NetworkConnectivityManager(appCtx).isNetworkAvailable()
            if (!isOnline) {
                Log.e("GeminiApi", "No internet connection.")
                NetworkNotifier.notifyOffline()
                return null
            }
        } catch (e: Exception) {
            Log.e("GeminiApi", "Network check failed: ${e.message}")
            return null
        }

        val lastUserPrompt = chat.lastOrNull { it.first == "user" }
            ?.second?.filterIsInstance<TextPart>()?.joinToString("\n") { it.text } ?: ""

        var attempts = 0
        while (attempts < maxRetry) {
            val attemptStartTime = System.currentTimeMillis()
            try {
                val apiKey = try { ApiKeyManager.getNextKey() } catch (e: Exception) { 
                    Log.e("GeminiApi", "No API key available")
                    return null 
                }

                val messagesJson = JSONArray()
                for ((role, parts) in chat) {
                    val messageObj = JSONObject()
                    messageObj.put("role", if (role == "model") "model" else "user")
                    
                    val partsArray = JSONArray()
                    for (part in parts) {
                        when (part) {
                            is TextPart -> {
                                val textObj = JSONObject()
                                textObj.put("text", part.text)
                                partsArray.put(textObj)
                            }
                            is ImagePart -> {
                                // For images, we'll need to handle separately
                            }
                        }
                    }
                    messageObj.put("parts", partsArray)
                    messagesJson.put(messageObj)
                }

                val requestBody = JSONObject().apply {
                    put("contents", messagesJson)
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.7)
                        put("maxOutputTokens", 2048)
                    })
                }

                val mediaType = "application/json".toMediaType()
                val body = requestBody.toString().toRequestBody(mediaType)

                val url = if (proxyUrl.isNotEmpty()) {
                    "$proxyUrl?key=$proxyKey"
                } else {
                    "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
                }

                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()
                val requestTime = System.currentTimeMillis() - attemptStartTime

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val content = candidates.getJSONObject(0).optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            return parts.getJSONObject(0).optString("text")
                        }
                    }
                    return null
                } else {
                    Log.e("GeminiApi", "API error: ${response.code} - $responseBody")
                }
            } catch (e: Exception) {
                Log.e("GeminiApi", "Error on attempt ${attempts + 1}: ${e.message}")
                attempts++
                if (attempts < maxRetry) {
                    delay(1000L * attempts)
                }
            }
        }
        return null
    }
}