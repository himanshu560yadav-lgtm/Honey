package com.panda.ai.utilities

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SpeechCoordinator private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SpeechCoordinator"
        @Volatile private var INSTANCE: SpeechCoordinator? = null

        fun getInstance(context: Context): SpeechCoordinator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpeechCoordinator(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val ttsManager = TTSManager.getInstance(context)
    private val sttManager = STTManager(context)
    private val speechMutex = Mutex()
    private var isSpeaking = false
    private var isListening = false

    suspend fun speakText(text: String) {
        val cleanedText = text.replace("*", "")
        speechMutex.withLock {
            try {
                if (isListening) { sttManager.stopListening(); isListening = false; delay(250) }
                isSpeaking = true
                ttsManager.speakText(cleanedText)
            } finally {
                isSpeaking = false
            }
        }
    }

    suspend fun speakToUser(text: String) {
        val cleanedText = text.replace("*", "")
        speechMutex.withLock {
            try {
                if (isListening) { sttManager.stopListening(); isListening = false; delay(250) }
                isSpeaking = true
                ttsManager.speakToUser(cleanedText)
            } finally {
                isSpeaking = false
            }
        }
    }

    suspend fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onListeningStateChange: (Boolean) -> Unit,
        onPartialResult: (String) -> Unit
    ) {
        stop()
        speechMutex.withLock {
            try {
                if (isSpeaking) { while (isSpeaking) { delay(100) }; delay(250) }
                isListening = true
                sttManager.startListening(
                    onResult = { onResult(it) },
                    onError = { onError(it) },
                    onListeningStateChange = { listening -> isListening = listening; onListeningStateChange(listening) },
                    onPartialResult = { onPartialResult(it) }
                )
            } catch (e: Exception) {
                isListening = false
                onError("Failed to start speech recognition: ${e.message}")
            }
        }
    }

    fun stop() { ttsManager.stop() }
    fun stopListening() { if (isListening) { sttManager.stopListening(); isListening = false } }
    fun stopSpeaking() { ttsManager.stop() }
    fun isCurrentlySpeaking(): Boolean = isSpeaking
    fun isCurrentlyListening(): Boolean = isListening
    fun isSpeechActive(): Boolean = isSpeaking || isListening
    suspend fun waitForSpeechCompletion() { while (isSpeechActive()) { delay(100) } }
    fun shutdown() { stopListening(); sttManager.shutdown() }
}