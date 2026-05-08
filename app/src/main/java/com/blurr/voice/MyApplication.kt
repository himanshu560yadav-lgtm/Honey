package com.blurr.voice

import android.app.Application
import android.content.Context
import com.blurr.voice.intents.IntentRegistry
import com.blurr.voice.intents.impl.DialIntent
import com.blurr.voice.intents.impl.EmailComposeIntent
import com.blurr.voice.intents.impl.ShareTextIntent
import com.blurr.voice.intents.impl.ViewUrlIntent
import com.blurr.voice.utilities.ApiKeyManager
import kotlinx.coroutines.*

class MyApplication : Application() {

    private val applicationScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        lateinit var appContext: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        IntentRegistry.register(DialIntent())
        IntentRegistry.register(ViewUrlIntent())
        IntentRegistry.register(ShareTextIntent())
        IntentRegistry.register(EmailComposeIntent())
        IntentRegistry.init(this)

        // Load runtime Gemini key if user saved one in Settings
        val prefs = getSharedPreferences("BlurrSettings", Context.MODE_PRIVATE)
        val runtimeKey = prefs.getString(SettingsActivity.KEY_GEMINI_API_KEY, "")
        if (!runtimeKey.isNullOrBlank()) {
            ApiKeyManager.setKeys(listOf(runtimeKey))
            android.util.Log.d("MyApplication", "Runtime Gemini key loaded from settings")
        }
    }
}