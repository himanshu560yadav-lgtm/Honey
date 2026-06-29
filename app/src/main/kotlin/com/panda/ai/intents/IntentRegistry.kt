package com.panda.ai.intents

import android.content.Context
import android.util.Log
import com.panda.ai.intents.impl.DialIntent
import com.panda.ai.intents.impl.EmailComposeIntent
import com.panda.ai.intents.impl.ShareTextIntent
import com.panda.ai.intents.impl.ViewUrlIntent

object IntentRegistry {
    private const val TAG = "IntentRegistry"
    private val discovered: MutableMap<String, AppIntent> = linkedMapOf()
    @Volatile private var initialized = false

    @Synchronized
    fun init(context: Context) {
        register(DialIntent())
        register(ViewUrlIntent())
        register(ShareTextIntent())
        register(EmailComposeIntent())
        initialized = true
    }

    fun register(intent: AppIntent) {
        val key = intent.name.trim()
        if (discovered.containsKey(key)) {
            Log.w(TAG, "Duplicate intent: ${intent.name}")
        }
        discovered[key] = intent
    }

    fun listIntents(context: Context): List<AppIntent> {
        if (!initialized) init(context)
        return discovered.values.toList()
    }

    fun findByName(context: Context, name: String): AppIntent? {
        if (!initialized) init(context)
        discovered[name]?.let { return it }
        return discovered.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
}