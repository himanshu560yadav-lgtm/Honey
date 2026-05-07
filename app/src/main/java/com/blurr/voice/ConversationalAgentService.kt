package com.blurr.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.blurr.voice.api.Eyes
import com.blurr.voice.utilities.SpeechCoordinator
import com.blurr.voice.utilities.TTSManager
import com.blurr.voice.utilities.addResponse
import com.blurr.voice.utilities.getReasoningModelApiResponse
import com.blurr.voice.overlay.OverlayManager
import com.blurr.voice.overlay.OverlayDispatcher
import com.blurr.voice.utilities.PandaState
import com.blurr.voice.utilities.UserProfileManager
import com.blurr.voice.utilities.VisualFeedbackManager
import com.blurr.voice.v2.AgentService
import com.blurr.voice.utilities.ServicePermissionManager
import com.blurr.voice.utilities.PandaStateManager
import com.blurr.voice.v2.perception.Perception
import com.blurr.voice.v2.perception.SemanticParser
import kotlinx.coroutines.*
import okhttp3.OkHttpClient

class ConversationalAgentService : Service() {

    private val TAG = "ConvAgent"
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var conversationId: String? = null

    companion object {
        const val NOTIFICATION_ID = 3
        const val CHANNEL_ID = "ConversationalAgentChannel"
        const val ACTION_STOP_SERVICE = "com.blurr.voice.ACTION_STOP_SERVICE"
        var isRunning = false
    }

    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private lateinit var perception: Perception
    private val client = OkHttpClient()

    private var isTextModeActive = false
    private var hasHeardFirstUtterance = false
    private var clarificationAttempts = 0
    private val maxClarificationAttempts = 1
    private var sttErrorAttempts = 0
    private val maxSttErrorAttempts = 2
    private val clarificationAgent = ClarificationAgent()
    private val pandaStateManager by lazy { PandaStateManager.getInstance(this) }
    private val servicePermissionManager by lazy { ServicePermissionManager(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        isRunning = true
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Conversational Agent", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Honey Conversational Agent Service"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, ConversationalAgentService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Honey AI")
            .setContentText("Conversational agent is running")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun trackConversationStart() {
        conversationId = "${System.currentTimeMillis()}"
        Log.d(TAG, "Conversation started: $conversationId")
    }

    private fun trackMessage(role: String, message: String, messageType: String = "text") {
        Log.d(TAG, "Message tracked: $role - ${message.take(50)}")
    }

    private fun trackConversationEnd(endReason: String, tasksRequested: Int = 0, tasksExecuted: Int = 0) {
        Log.d(TAG, "Conversation ended: $conversationId ($endReason)")
    }

    private fun initializeConversation() {
        trackConversationStart()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        serviceScope.cancel()
        isRunning = false
    }
}