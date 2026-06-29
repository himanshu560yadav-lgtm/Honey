package com.panda.ai.utilities

import android.content.Context
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class UserInputManager(private val context: Context) {

    companion object {
        private const val TAG = "UserInputManager"
        private const val SPEECH_TIMEOUT_MS = 30000L
        private const val FALLBACK_TIMEOUT_MS = 5000L
        private const val MAX_SPEECH_ATTEMPTS = 3
        private var responseCallback: ((String) -> Unit)? = null
    }

    private val speechCoordinator = SpeechCoordinator.getInstance(context)

    fun isSpeechRecognitionAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    suspend fun askQuestion(question: String): String {
        return suspendCancellableCoroutine { continuation ->
            try {
                responseCallback = { response -> continuation.resume(response) }
                Log.d(TAG, "Agent asked: $question")

                if (!isSpeechRecognitionAvailable()) {
                    useFallbackResponse(question)
                    return@suspendCancellableCoroutine
                }

                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        var response: String? = null
                        var attempt = 1
                        while (attempt <= MAX_SPEECH_ATTEMPTS && (response == null || response.isEmpty())) {
                            if (attempt > 1) {
                                delay(2000)
                                speechCoordinator.speakToUser("Please try again. $question")
                                delay(1000)
                            }
                            response = suspendCancellableCoroutine<String> { speechContinuation ->
                                CoroutineScope(Dispatchers.Main).launch {
                                    try {
                                        withTimeoutOrNull(SPEECH_TIMEOUT_MS) {
                                            speechCoordinator.startListening(
                                                onResult = { speechContinuation.resume(it) },
                                                onError = { speechContinuation.resume("") },
                                                onListeningStateChange = { },
                                                onPartialResult = { }
                                            )
                                        } ?: speechContinuation.resume("")
                                    } catch (e: Exception) {
                                        speechContinuation.resume("")
                                    }
                                }
                            }
                            if (!response.isNullOrEmpty()) break
                            attempt++
                        }
                        if (!response.isNullOrEmpty()) responseCallback?.invoke(response)
                        else useFallbackResponse(question)
                    } catch (e: Exception) {
                        useFallbackResponse(question)
                    } finally {
                        speechCoordinator.stopListening()
                    }
                }
            } catch (e: Exception) {
                continuation.resume("Error: Could not get user response")
            }
        }
    }

    private fun useFallbackResponse(question: String) {
        CoroutineScope(Dispatchers.Main).launch {
            delay(FALLBACK_TIMEOUT_MS)
            responseCallback?.invoke("No response received for: $question")
        }
    }
}