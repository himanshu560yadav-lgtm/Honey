package com.blurr.voice.v2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.blurr.voice.R
import com.blurr.voice.utilities.ApiKeyManager
import com.blurr.voice.api.Eyes
import com.blurr.voice.api.Finger
import com.blurr.voice.overlay.OverlayDispatcher
import com.blurr.voice.overlay.OverlayManager
import com.blurr.voice.v2.actions.ActionExecutor
import com.blurr.voice.v2.fs.FileSystem
import com.blurr.voice.v2.llm.GeminiApi as LLMGeminiApi
import com.blurr.voice.v2.message_manager.MemoryManager
import com.blurr.voice.v2.perception.Perception
import com.blurr.voice.v2.perception.SemanticParser
import kotlinx.coroutines.*
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue

class AgentService : Service() {

    private val TAG = "AgentService"
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val taskQueue: Queue<String> = ConcurrentLinkedQueue()
    private lateinit var agent: Agent
    private lateinit var settings: AgentSettings
    private lateinit var fileSystem: FileSystem
    private lateinit var perception: Perception
    private lateinit var llmApi: LLMGeminiApi
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var overlayManager: OverlayManager
    private lateinit var eyes: Eyes
    private lateinit var finger: Finger

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "AgentServiceChannelV2"
        private const val NOTIFICATION_ID = 14
        private const val EXTRA_TASK = "com.blurr.voice.v2.EXTRA_TASK"
        private const val ACTION_STOP_SERVICE = "com.blurr.voice.v2.ACTION_STOP_SERVICE"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var currentTask: String? = null
            private set

        fun stop(context: Context) {
            val intent = Intent(context, AgentService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }

        fun start(context: Context, task: String) {
            val intent = Intent(context, AgentService::class.java).apply {
                putExtra(EXTRA_TASK, task)
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        isRunning = true

        eyes = Eyes(this)
        finger = Finger(this)
        settings = AgentSettings()
        fileSystem = FileSystem(this)
        val semanticParser = SemanticParser()
        perception = Perception(eyes, semanticParser)
        llmApi = LLMGeminiApi("gemini-2.0-flash", ApiKeyManager, this)
        actionExecutor = ActionExecutor(finger)
        overlayManager = OverlayManager.getInstance(this)
        val memoryManager = MemoryManager(
            context = this,
            task = "",
            fileSystem = fileSystem,
            settings = settings
        )

        agent = Agent(
            settings = settings,
            memoryManager = memoryManager,
            perception = perception,
            llmApi = llmApi,
            actionExecutor = actionExecutor,
            fileSystem = fileSystem,
            context = this
        )

        Log.d(TAG, "AgentService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            if (it.action == ACTION_STOP_SERVICE) {
                stopSelf()
                return START_NOT_STICKY
            }

            val task = it.getStringExtra(EXTRA_TASK)
            if (!task.isNullOrEmpty()) {
                taskQueue.add(task)
                startForeground(NOTIFICATION_ID, createNotification("Agent is running"))
                processTasks()
            }
        }
        return START_NOT_STICKY
    }

    private fun processTasks() {
        serviceScope.launch {
            while (taskQueue.isNotEmpty()) {
                val task = taskQueue.poll() ?: continue
                currentTask = task

                try {
                    Log.i(TAG, "Executing task: $task")
                    agent.run(task)
                    Log.i(TAG, "Task completed: $task")
                } catch (e: Exception) {
                    Log.e(TAG, "Task failed: $task", e)
                }
            }
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Honey Agent", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Honey AI Agent Service"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(content: String = "Agent is running"): Notification {
        val stopIntent = Intent(this, AgentService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Honey AI")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        OverlayDispatcher.clearAll()
        isRunning = false
        currentTask = null
        Log.d(TAG, "AgentService destroyed")
    }
}