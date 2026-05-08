package com.blurr.voice.utilities

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.util.Log
import com.blurr.voice.MyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object NetworkNotifier {

    private const val TAG = "NetworkNotifier"
    private const val MIN_INTERVAL_MS = 10000L
    @Volatile private var lastNotifiedAt: Long = 0L

    suspend fun notifyOffline(message: String = defaultMessage) {
        val now = System.currentTimeMillis()
        if (now - lastNotifiedAt < MIN_INTERVAL_MS) {
            Log.d(TAG, "Skipping offline notify due to debounce interval")
            return
        }
        lastNotifiedAt = now

        val context = try {
            MyApplication.appContext
        } catch (e: Exception) {
            Log.e(TAG, "MyApplication context not available")
            return
        }

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context,
                "No internet connection. Please check your network.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private const val defaultMessage = "It looks like the internet is offline. Please try again later."
}