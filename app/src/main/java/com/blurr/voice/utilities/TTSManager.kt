package com.blurr.voice.utilities

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.blurr.voice.BuildConfig
import com.blurr.voice.api.GoogleTts
import com.blurr.voice.api.TTSVoice
import com.blurr.voice.overlay.OverlayDispatcher
import com.blurr.voice.overlay.OverlayPriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque

class TTSManager private constructor(private val context: Context) : TextToSpeech.OnInitListener {

    private var nativeTts: TextToSpeech? = null
    private var isNativeTtsInitialized = CompletableDeferred<Unit>()

    private var audioTrack: AudioTrack? = null
    private var googleTtsPlaybackDeferred: CompletableDeferred<Unit>? = null

    var utteranceListener: ((isSpeaking: Boolean) -> Unit)? = null

    private var isDebugMode: Boolean = try {
        BuildConfig.SPEAK_INSTRUCTIONS
    } catch (e: Exception) {
        true
    }

    companion object {
        @Volatile private var INSTANCE: TTSManager? = null
        private const val SAMPLE_RATE = 24000
        private const val TAG = "TTSManager"

        fun getInstance(context: Context): TTSManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TTSManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    init {
        nativeTts = TextToSpeech(context, this)
        setupAudioTrack()
    }

    private fun setupAudioTrack() {
        try {
            val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            audioTrack?.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(track: AudioTrack?) {
                        googleTtsPlaybackDeferred?.complete(Unit)
                    }
                    override fun onPeriodicNotification(track: AudioTrack?) {}
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup AudioTrack: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            nativeTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    utteranceListener?.invoke(true)
                }
                override fun onDone(utteranceId: String?) {
                    utteranceListener?.invoke(false)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    utteranceListener?.invoke(false)
                }
            })
            isNativeTtsInitialized.complete(Unit)
            Log.d(TAG, "Native TTS initialized successfully")
        } else {
            isNativeTtsInitialized.completeExceptionally(Exception("Native TTS init failed"))
            Log.e(TAG, "Native TTS init failed with status: $status")
        }
    }

    fun stop() {
        if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack?.stop()
            audioTrack?.flush()
        }
        if (googleTtsPlaybackDeferred?.isActive == true) {
            googleTtsPlaybackDeferred?.completeExceptionally(
                CancellationException("Playback stopped by new request.")
            )
        }
        nativeTts?.stop()
    }

    suspend fun speakText(text: String) {
        if (!isDebugMode) return
        speak(text)
    }

    suspend fun speakToUser(text: String) {
        speak(text)
    }

    fun getAudioSessionId(): Int {
        return audioTrack?.audioSessionId ?: 0
    }

    /**
     * Main speak function — uses Native Android TTS (Google TTS disabled).
     * Properly awaits completion before returning.
     */
    private suspend fun speak(text: String) {
        try {
            speakWithNativeTts(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "TTS error: ${e.message}")
        }
    }

    /**
     * Speaks text using Android's built-in TextToSpeech and waits for completion.
     */
    private suspend fun speakWithNativeTts(text: String) {
        try {
            // Wait for TTS engine to be ready
            withTimeoutOrNull(5000) {
                isNativeTtsInitialized.await()
            } ?: run {
                Log.e(TAG, "TTS init timeout")
                return
            }

            val deferred = CompletableDeferred<Unit>()
            val utteranceId = System.currentTimeMillis().toString()

            // Set listener for this specific utterance
            nativeTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    utteranceListener?.invoke(true)
                    // Show caption overlay
                    CoroutineScope(Dispatchers.Main).launch {
                        OverlayDispatcher.show(text, OverlayPriority.CAPTION)
                    }
                }
                override fun onDone(id: String?) {
                    utteranceListener?.invoke(false)
                    deferred.complete(Unit)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    utteranceListener?.invoke(false)
                    deferred.complete(Unit)
                }
            })

            val result = nativeTts?.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId
            )

            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "TTS speak() returned ERROR")
                return
            }

            // Wait for speech to complete (max 30 seconds)
            withTimeoutOrNull(30000) {
                deferred.await()
            } ?: Log.w(TAG, "TTS completion timeout for: ${text.take(50)}")

            Log.d(TAG, "Native TTS completed: ${text.take(50)}")

        } catch (e: CancellationException) {
            nativeTts?.stop()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Native TTS error: ${e.message}")
        }
    }

    /**
     * Plays raw PCM audio data (used for cached audio from Google TTS if ever enabled).
     */
    suspend fun playAudioData(audioData: ByteArray) {
        try {
            googleTtsPlaybackDeferred = CompletableDeferred()
            withContext(Dispatchers.Main) {
                utteranceListener?.invoke(true)
            }

            withContext(Dispatchers.IO) {
                audioTrack?.play()
                val numFrames = audioData.size / 2
                audioTrack?.setNotificationMarkerPosition(numFrames)
                audioTrack?.write(audioData, 0, audioData.size)
            }

            withTimeoutOrNull(30000) { googleTtsPlaybackDeferred?.await() }

            withContext(Dispatchers.Main) { utteranceListener?.invoke(false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio data", e)
        } finally {
            if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.stop()
                audioTrack?.flush()
            }
        }
    }

    fun shutdown() {
        stop()
        nativeTts?.shutdown()
        audioTrack?.release()
        audioTrack = null
        INSTANCE = null
    }
}