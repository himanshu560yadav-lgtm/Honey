package com.panda.ai.v2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.panda.ai.R
import com.panda.ai.api.Eyes
import com.panda.ai.api.Finger
import com.panda.ai.overlay.OverlayDispatcher
import com.panda.ai.overlay.OverlayManager
import com.panda.ai.v2.actions.ActionExecutor
import com.panda.ai.v2.fs.FileSystem
import com.panda.ai.v2.llm.GeminiApi
import com.panda.ai.v2.message_manager.MemoryManager
import com.panda.ai.v2.perception.Perception
import com.panda.ai.v2.perception.SemanticParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue

class AgentService : Service() {

    private val TAG = "AgentService"
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val taskQueue: Queue<String> = ConcurrentLinkedQueue()

    private lateinit var agent: Agent
    private lateinit var settings: AgentSettings
    private lateinit var fileSystem: FileSystem
    private lateinit var memoryManager: MemoryManager
    private lateinit var perception: Perception
    private lateinit var llmApi: GeminiApi
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var overlayManager: OverlayManager

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "HoneyAgentChannel"
        private const val NOTIFICATION_ID = 14
        const val EXTRA_TASK = "com.panda.ai.EXTRA_TASK"
        const val ACTION_STOP_SERVICE = "com.panda.ai.ACTION_STOP_SERVICE"

        @Volatile var isRunning: Boolean = false
            private set

        @Volatile var currentTask: String? = null
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

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        try {
            overlayManager = OverlayManager.getInstance(this)
            OverlayDispatcher.clearAll()
            overlayManager.startObserving()
            createNotificationChannel()

            settings = AgentSettings()
            fileSystem = FileSystem(this)
            memoryManager = MemoryManager(this, "", fileSystem, settings)
            perception = Perception(Eyes(this), SemanticParser())
            llmApi = GeminiApi(modelName = "gemini-2.5-flash", context = this, maxRetry = 10)
            actionExecutor = ActionExecutor(Finger(this))
            agent = Agent(settings, memoryManager, perception, llmApi, actionExecutor, fileSystem, this)

            Log.d(TAG, "onCreate: All components initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate FAILED", e)
            showToast("Init error: ${e.message}")
            stopSelf()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")

        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        intent?.getStringExtra(EXTRA_TASK)?.let {
            if (it.isNotBlank()) taskQueue.add(it)
        }

        if (!isRunning && taskQueue.isNotEmpty()) {
            serviceScope.launch {
                processTaskQueue()
            }
        }

        return START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun processTaskQueue() {
        if (isRunning) return
        isRunning = true

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        startForeground(NOTIFICATION_ID, createNotification("Agent is starting..."))

        while (taskQueue.isNotEmpty()) {
            val task = taskQueue.poll() ?: continue
            currentTask = task
            notificationManager.notify(NOTIFICATION_ID, createNotification("Running: $task"))

            try {
                Log.i(TAG, "Executing task: $task")
                agent.run(task)
                Log.i(TAG, "Task completed: $task")
                showToast("Task complete: $task")
            } catch (e: Exception) {
                Log.e(TAG, "Task FAILED: $task", e)
                showToast("Task failed: ${e.message}")
            }
        }

        Log.i(TAG, "Queue empty. Stopping.")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        OverlayDispatcher.clearAll()
        overlayManager.stopObserving()
        isRunning = false
        currentTask = null
        taskQueue.clear()
        serviceScope.cancel()
        Log.d(TAG, "onDestroy")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Honey Agent Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val stopIntent = Intent(this, AgentService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Honey AI")
            .setContentText(contentText)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }
}