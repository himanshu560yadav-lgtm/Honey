package com.panda.ai.utilities

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class TTSManager private constructor(private val context: Context) : TextToSpeech.OnInitListener {

    private var nativeTts: TextToSpeech? = null
    private var isInitialized = CompletableDeferred<Unit>()
    var utteranceListener: ((isSpeaking: Boolean) -> Unit)? = null

    companion object {
        @Volatile private var INSTANCE: TTSManager? = null

        fun getInstance(context: Context): TTSManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TTSManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    init {
        nativeTts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            nativeTts?.language = Locale.getDefault()
            nativeTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { utteranceListener?.invoke(true) }
                override fun onDone(utteranceId: String?) { utteranceListener?.invoke(false) }
                override fun onError(utteranceId: String?) { utteranceListener?.invoke(false) }
            })
            isInitialized.complete(Unit)
            Log.d("TTSManager", "Android TTS initialized successfully")
        } else {
            isInitialized.completeExceptionally(Exception("TTS init failed"))
        }
    }

    suspend fun speakText(text: String) = speak(text)

    suspend fun speakToUser(text: String) = speak(text)

    private suspend fun speak(text: String) {
        try {
            isInitialized.await()
            withContext(Dispatchers.Main) {
                nativeTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, hashCode().toString())
            }
        } catch (e: Exception) {
            Log.e("TTSManager", "TTS failed: ${e.message}")
        }
    }

    fun stop() {
        nativeTts?.stop()
    }

    fun getAudioSessionId(): Int = 0

    fun shutdown() {
        stop()
        nativeTts?.shutdown()
        INSTANCE = null
    }
}