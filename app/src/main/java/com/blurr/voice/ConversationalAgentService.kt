package com.blurr.voice

import android.Manifest
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
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.blurr.voice.api.Eyes
import com.blurr.voice.utilities.SpeechCoordinator
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.graphics.toColorInt
import com.blurr.voice.agents.ClarificationAgent
import com.blurr.voice.utilities.TTSManager
import com.blurr.voice.utilities.addResponse
import com.blurr.voice.utilities.getReasoningModelApiResponse
import com.blurr.voice.overlay.OverlayManager
import com.blurr.voice.overlay.OverlayDispatcher
import com.blurr.voice.utilities.PandaState
import com.blurr.voice.utilities.UserProfileManager
import com.blurr.voice.utilities.VisualFeedbackManager
import com.blurr.voice.v2.AgentService
import com.blurr.voice.data.UserMemory
import com.google.ai.client.generativeai.type.TextPart
import com.blurr.voice.utilities.ServicePermissionManager
import com.blurr.voice.utilities.PandaStateManager
import com.blurr.voice.v2.perception.Perception
import com.blurr.voice.v2.perception.SemanticParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

data class ModelDecision(
    val type: String = "Reply",
    val reply: String,
    val instruction: String = "",
    val shouldEnd: Boolean = false
)

class ConversationalAgentService : Service() {

    private val speechCoordinator by lazy { SpeechCoordinator.getInstance(this) }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var conversationHistory = listOf<Pair<String, List<Any>>>()
    private val ttsManager by lazy { TTSManager.getInstance(this) }
    private val overlayManager by lazy { OverlayManager.getInstance(this) }
    private val clarificationQuestionViews = mutableListOf<View>()
    private var transcriptionView: TextView? = null
    private val visualFeedbackManager by lazy { VisualFeedbackManager.getInstance(this) }
    private val pandaStateManager by lazy { PandaStateManager.getInstance(this) }
    private var isTextModeActive = false
    private val servicePermissionManager by lazy { ServicePermissionManager(this) }

    private var clarificationAttempts = 0
    private val maxClarificationAttempts = 1
    private var sttErrorAttempts = 0
    private val maxSttErrorAttempts = 2

    private val clarificationAgent = ClarificationAgent()
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var cachedMemories = listOf<UserMemory>()
    private var hasHeardFirstUtterance = false
    private lateinit var perception: Perception
    private val client = OkHttpClient()

    
    private var conversationId: String? = null


    companion object {
        const val NOTIFICATION_ID = 3
        const val CHANNEL_ID = "ConversationalAgentChannel"
        const val ACTION_STOP_SERVICE = "com.blurr.voice.ACTION_STOP_SERVICE"
        var isRunning = false
        const val MEMORY_ENABLED = true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate() {
        super.onCreate()
        Log.d("ConvAgent", "Service onCreate")
        
        
        
        isRunning = true
        createNotificationChannel()
        initializeConversation()
        clarificationAttempts = 0
        sttErrorAttempts = 0
        hasHeardFirstUtterance = false

        fetchMemories()

        OverlayDispatcher.clearAll()
        overlayManager.startObserving()
        visualFeedbackManager.showSpeakingOverlay()
        visualFeedbackManager.showTtsWave()

        showInputBoxIfNeeded()
        visualFeedbackManager.showSmallDeltaGlow()

        pandaStateManager.startMonitoring()
        pandaStateManager.setState(PandaState.IDLE)


    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun showInputBoxIfNeeded() {
        visualFeedbackManager.showInputBox(
            onActivated = {
                enterTextMode()
            },
            onSubmit = { submittedText ->
                processUserInput(submittedText)
            },
            onOutsideTap = {
                serviceScope.launch {
                    instantShutdown()
                }
            }
        )
    }

    private fun enterTextMode() {
        if (isTextModeActive) return
        Log.d("ConvAgent", "Entering Text Mode. Stopping STT/TTS.")
        
        
        isTextModeActive = true
        pandaStateManager.setState(PandaState.IDLE)
        speechCoordinator.stopListening()
        speechCoordinator.stopSpeaking()
        visualFeedbackManager.hideTranscription()
    }


    @RequiresApi(Build.VERSION_CODES.R)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("ConvAgent", "Service onStartCommand")

        if (intent?.action == ACTION_STOP_SERVICE) {
            Log.i("ConvAgent", "Received stop action. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("ConvAgent", "RECORD_AUDIO permission not granted. Cannot start foreground service.")
            Toast.makeText(this, "Microphone permission required for voice assistant", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: SecurityException) {
            serviceScope.launch {
                speechCoordinator.speakText("Hello, please give microphone permission or some other type of permission you have not given me! My code is open source, so you can check that out if you have any doubts.")
                delay(2000)
                stopSelf()
            }
            Log.e("ConvAgent", "Failed to start foreground service: ${e.message}")
            Toast.makeText(this, "Cannot start voice assistant - permission missing", Toast.LENGTH_LONG).show()
            return START_NOT_STICKY
        }

        if (!servicePermissionManager.isMicrophonePermissionGranted()) {
            Log.e("ConvAgent", "RECORD_AUDIO permission not granted. Shutting down.")
            serviceScope.launch {
                ttsManager.speakText(getString(R.string.microphone_permission_not_granted))
                delay(2000)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        trackConversationStart()

        serviceScope.launch {
            Log.d("ConvAgent", "Starting immediate listening (no greeting)")
            pandaStateManager.setState(PandaState.LISTENING)
            startImmediateListening()
        }
        return START_STICKY
    }

    private fun getPersonalizedGreeting(): String {
        try {
            val userProfile = UserProfileManager(this@ConversationalAgentService)
            Log.d("ConvAgent", "No name found in memories, using generic greeting")
            return "Hey ${userProfile.getName()}!"
        } catch (e: Exception) {
            Log.e("ConvAgent", "Error getting personalized greeting", e)
            return "Hey!"
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun startImmediateListening() {
        Log.d("ConvAgent", "Starting immediate listening without greeting")
        
        if (isTextModeActive) {
            Log.d("ConvAgent", "In text mode, ensuring input box is visible and skipping voice listening.")
            mainHandler.post {
                showInputBoxIfNeeded()
            }
            return
        }



        speechCoordinator.startListening(
            onResult = { recognizedText ->
                if (isTextModeActive) return@startListening
                Log.d("ConvAgent", "Final user transcription: $recognizedText")
                pandaStateManager.setState(PandaState.PROCESSING)
                visualFeedbackManager.updateTranscription(recognizedText)
                mainHandler.postDelayed({
                    visualFeedbackManager.hideTranscription()
                }, 500)

                
                processUserInput(recognizedText)
            },
            onError = { error ->
                Log.e("ConvAgent", "STT Error: $error")
                if (isTextModeActive) return@startListening

                if (error == "No speech match") {
                    Log.d("ConvAgent", "No speech match detected. Silently resetting to IDLE.")
                    visualFeedbackManager.hideTranscription()
                    pandaStateManager.setState(PandaState.IDLE)
                    return@startListening
                }
                pandaStateManager.triggerErrorState()

                
                visualFeedbackManager.hideTranscription()
                sttErrorAttempts++
                serviceScope.launch {
                    if (sttErrorAttempts >= maxSttErrorAttempts) {
                        val exitMessage = "I'm having trouble understanding you clearly. Please try calling later!"
                        trackMessage("model", exitMessage, "error_message")
                        gracefulShutdown(exitMessage, "stt_errors")
                    } else {
                        val retryMessage = "I'm sorry, I didn't catch that. Could you please repeat?"
                        speakAndThenListen(retryMessage)
                    }
                }
            },
            onPartialResult = { partialText ->
                if (isTextModeActive) return@startListening
                visualFeedbackManager.updateTranscription(partialText)
            },
            onListeningStateChange = { listening ->
                Log.d("ConvAgent", "Listening state: $listening")
                if (listening) {
                    if (isTextModeActive) return@startListening
                    pandaStateManager.setState(PandaState.LISTENING)
                    visualFeedbackManager.showTranscription()
                } else {
                    if (!isTextModeActive) {
                        pandaStateManager.setState(PandaState.IDLE)
                    }
                }
            }
        )
    }


    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun speakAndThenListen(text: String, draw: Boolean = true) {
        updateSystemPromptWithTime()
        pandaStateManager.setState(PandaState.SPEAKING)
        speechCoordinator.speakText(text)
        Log.d("ConvAgent", "Panda said: $text")
        if (isTextModeActive) {
            Log.d("ConvAgent", "In text mode, ensuring input box is visible and skipping voice listening.")
            mainHandler.post {
                showInputBoxIfNeeded()
            }
            return
        }
        speechCoordinator.startListening(
            onResult = { recognizedText ->
                if (isTextModeActive) return@startListening
                Log.d("ConvAgent", "Final user transcription: $recognizedText")
                pandaStateManager.setState(PandaState.PROCESSING)
                visualFeedbackManager.updateTranscription(recognizedText)
                mainHandler.postDelayed({
                    visualFeedbackManager.hideTranscription()
                }, 500)
                
                if (!hasHeardFirstUtterance) {
                    hasHeardFirstUtterance = true
                    Log.d("ConvAgent", "First utterance received, triggering memory extraction")
                    serviceScope.launch {
                        try {
                            updateSystemPromptWithScreenContext()
                        } catch (e: Exception) {
                            Log.e("ConvAgent", "Error during first utterance memory extraction", e)
                        }
                    }
                }
                
                processUserInput(recognizedText)

            },
            onError = { error ->
                Log.e("ConvAgent", "STT Error: $error")
                if (isTextModeActive) return@startListening
                
                pandaStateManager.triggerErrorState()
                
                
                visualFeedbackManager.hideTranscription()
                sttErrorAttempts++
                serviceScope.launch {
                    if (sttErrorAttempts >= maxSttErrorAttempts) {
                        val exitMessage = "I'm having trouble understanding you clearly. Please try calling later!"
                        trackMessage("model", exitMessage, "error_message")
                        gracefulShutdown(exitMessage, "stt_errors")
                    } else {
                        speakAndThenListen("I'm sorry, I didn't catch that. Could you please repeat?")
                    }
                }
            },
            onPartialResult = { partialText ->
                if (isTextModeActive) return@startListening
                visualFeedbackManager.updateTranscription(partialText)
            },
            onListeningStateChange = { listening ->
                Log.d("ConvAgent", "Listening state: $listening")
                if (listening) {
                    if (isTextModeActive) return@startListening
                    pandaStateManager.setState(PandaState.LISTENING)
                    visualFeedbackManager.showTranscription()
                } else {
                    if (!isTextModeActive) {
                        pandaStateManager.setState(PandaState.IDLE)
                    }
                }
            }
        )
    }

    private fun showTranscriptionView() {
        if (transcriptionView != null) return

        mainHandler.post {
            transcriptionView = TextView(this).apply {
                text = "Listening..."
                val glassBackground = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xDD0D0D2E.toInt(), 0xDD2A0D45.toInt())
                ).apply {
                    cornerRadius = 28f
                    setStroke(1, 0x80FFFFFF.toInt())
                }
                background = glassBackground
                setTextColor(0xFFE0E0E0.toInt())
                textSize = 16f
                setPadding(40, 24, 40, 24)
                typeface = Typeface.MONOSPACE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 250
            }

            try {
                windowManager.addView(transcriptionView, params)
            } catch (e: Exception) {
                Log.e("ConvAgent", "Failed to add transcription view.", e)
                transcriptionView = null
            }
        }
    }

    private fun updateTranscriptionView(text: String) {
        transcriptionView?.text = text
    }

    private fun hideTranscriptionView() {
        mainHandler.post {
            transcriptionView?.let {
                if (it.isAttachedToWindow) {
                    try {
                        windowManager.removeView(it)
                    } catch (e: Exception) {
                        Log.e("ConvAgent", "Error removing transcription view.", e)
                    }
                }
            }
            transcriptionView = null
        }
    }


    @RequiresApi(Build.VERSION_CODES.R)
    private fun processUserInput(userInput: String) {
        serviceScope.launch {
            removeClarificationQuestions()
            updateSystemPromptWithAgentStatus()
            updateSystemPromptWithScreenContext()
            updateSystemPromptWithTime()
            if (!hasHeardFirstUtterance) {
                hasHeardFirstUtterance = true
                Log.d("ConvAgent", "First utterance received via processUserInput, triggering memory extraction")
                try {
                    updateSystemPromptWithScreenContext()
                } catch (e: Exception) {
                    Log.e("ConvAgent", "Error during first utterance memory extraction", e)
                }
            }

            conversationHistory = addResponse("user", userInput, conversationHistory)
            
            trackMessage("user", userInput, "input")


            try {
                if (userInput.equals("stop", ignoreCase = true) || userInput.equals("exit", ignoreCase = true)) {
                    trackMessage("model", "Goodbye!", "farewell")
                    gracefulShutdown("Goodbye!", "command")
                    return@launch
                }
                pandaStateManager.setState(PandaState.PROCESSING)
                visualFeedbackManager.showThinkingIndicator()
                
                Log.d("ConvAgent", "Calling Gemini API...")
                val defaultJsonResponse = """{"Type": "Reply", "Reply": "Hello! I am working. Say something to me!", "Instruction": "", "Should End": "Continue"}"""
                val rawModelResponse = getReasoningModelApiResponse(conversationHistory) ?: defaultJsonResponse
                
                Log.d("ConvAgent", "API Response: $rawModelResponse")
                visualFeedbackManager.hideThinkingIndicator()
                val decision = parseModelResponse(rawModelResponse)
                Log.d("TTS_DEBUG", "Reply received from GeminiApi: -->${rawModelResponse}<--")
                when (decision.type) {
                    "Task" -> {
                        
                        if (AgentService.isRunning) {
                            val busyMessage = "I'm already working on '${AgentService.currentTask}'. Please let me finish that first, or you can ask me to stop it."
                            speakAndThenListen(busyMessage)
                            conversationHistory = addResponse("model", busyMessage, conversationHistory)
                            return@launch
                        }

                        if (!servicePermissionManager.isAccessibilityServiceEnabled()) {
                            speakAndThenListen(getString(R.string.accessibility_permission_needed_for_task))
                            conversationHistory = addResponse("model", R.string.accessibility_permission_needed_for_task.toString(), conversationHistory)
                            return@launch
                        }

                        Log.d("ConvAgent", "Model identified a task. Checking for clarification...")
                        removeClarificationQuestions()
                        
                        run {
                            Log.d("ConvAgent", "Proceeding with task.")

                            if (clarificationAttempts < maxClarificationAttempts) {
                                val (needsClarification, questions) = checkIfClarificationNeeded(
                                    decision.instruction
                                )
                                Log.d("ConcAgent", needsClarification.toString())
                                Log.d("ConcAgent", questions.toString())

                                if (needsClarification) {
                                    
                                    clarificationAttempts++
                                    displayClarificationQuestions(questions)
                                    val questionToAsk =
                                        "I can help with that, but first: ${questions.joinToString(" and ")}"
                                    Log.d(
                                        "ConvAgent",
                                        "Task needs clarification. Asking: '$questionToAsk' (Attempt $clarificationAttempts/$maxClarificationAttempts)"
                                    )
                                    conversationHistory = addResponse(
                                        "model",
                                        "Clarification needed for task: ${decision.instruction}",
                                        conversationHistory
                                    )
                                    trackMessage("model", questionToAsk, "clarification")
                                    speakAndThenListen(questionToAsk, false)
                                } else {
                                    Log.d(
                                        "ConvAgent",
                                        "Task is clear. Executing: ${decision.instruction}"
                                    )
                                    
                                    
                                    val originalInstruction = decision.instruction
                                    AgentService.start(applicationContext, originalInstruction)
                                    trackMessage("model", decision.reply, "task_confirmation")
                                    gracefulShutdown(decision.reply, "task_executed")
                                }
                            } else {
                                Log.d(
                                    "ConvAgent",
                                    "Max clarification attempts reached ($maxClarificationAttempts). Proceeding with task execution."
                                )
                                
                                
                                AgentService.start(applicationContext, decision.instruction)
                                trackMessage("model", decision.reply, "task_confirmation")
                                gracefulShutdown(decision.reply, "task_executed")
                            }
                        }
                    }
                    "KillTask" -> {
                        Log.d("ConvAgent", "Model requested to kill the running agent service.")
                        
                        
                        if (AgentService.isRunning) {
                            AgentService.stop(applicationContext)
                            trackMessage("model", decision.reply, "kill_task_response")
                            gracefulShutdown(decision.reply, "task_killed")
                        } else {
                            val noTaskMessage = "There was no automation running, but I can help with something else."
                            trackMessage("model", noTaskMessage, "kill_task_response")
                            speakAndThenListen(noTaskMessage)
                        }
                    }
                    else -> { 
                        
                        if (decision.shouldEnd) {
                            Log.d("ConvAgent", "Model decided to end the conversation.")
                            trackMessage("model", decision.reply, "farewell")
                            gracefulShutdown(decision.reply, "model_ended")
                        } else {
                            conversationHistory = addResponse("model", rawModelResponse, conversationHistory)
                            trackMessage("model", decision.reply, "reply")
                            speakAndThenListen(decision.reply)
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("ConvAgent", "Error processing user input: ${e.message}", e)
                
                pandaStateManager.triggerErrorState()
                
                
                speakAndThenListen("closing voice mode")
            }
        }
    }

    private suspend fun checkIfClarificationNeeded(instruction: String): Pair<Boolean, List<String>> {
        Log.d("ConvAgent", "Checking for clarification on instruction: '$instruction'")
        return Pair(false, listOf())
    }

    private fun initializeConversation() {
        val memoryContextSection = if (MEMORY_ENABLED) {
            """
            Use these memories to answer the user's question with his personal data
            ### Memory Context Start ###
            {memory_context}
            ### Memory Context Ends ###
            """
        } else {
            """
            ### Memory Status ###
            Memory system is temporarily disabled. Panda cannot remember or learn from previous conversations at this time.
            some memories added by developers
            {memory_context}
            ### End Memory Status ###
            """
        }

        val systemPrompt = """
            You are a helpful voice assistant called Panda that can either have a conversation or ask an executor to execute tasks on the user's phone.
            The executor can speak, listen, see the screen, tap the screen, and basically use the phone as a normal human would.

            {agent_status_context}

            ### Current Screen Context ###
            {screen_context}
            ### End Screen Context ###

            Some Guideline:
            1. If the user ask you to do something creative, you do this task and be the most creative person in the world.
            2. If you know the user's name from the memories, refer to them by their name to make the conversation more personal and friendly as often as possible.
            3. Use the current screen context to better understand what the user is looking at and provide more relevant responses.
            4. If the user asks about something on the screen, you can reference the screen content directly.
            5. Always ask for clarification if the user's request is ambiguous or unclear.
            6. When the user ask to sing, shout or produce any sound, just generate text, we will sing it for you.
            7. Your code is opensource so you can tell tell that to user. repo is ayush0chaudhary/blurr
            8. Give a warning for the tasks related to banking, games, shopping and app with Canvas (no a11y tree) that you wont be able to do them properly but you will try your best.
            
            Use these memories to answer the user's question with his personal data
            ### Memory Context Start ###
            {memory_context}
            ### Memory Context Ends ###
            
            Analyze the user's request and respond ONLY with a single, valid JSON object.
            Do not include any text, notes, or explanations outside of the JSON object.
            The JSON object must have the following structure:
            
            {
              "Type": "String",
              "Reply": "String",
              "Instruction": "String",
              "Should End": "String"
            }

            Here are the rules for the JSON values:
            - "Type": Must be one of "Task", "Reply", or "KillTask".
              - Use "Task" if the user is asking you to DO something on the device (e.g., "open settings", "send a text to Mom").
              - Use "Reply" for conversational questions (e.g., "what's the weather?", "tell me a joke").
              - Use "KillTask" ONLY if an automation task is running and the user wants to stop it.
            - "Reply": The text to speak to the user. This is a confirmation for a "Task", or the direct answer for a "Reply".
            - "Instruction": The precise, literal instruction for the task agent. This field should be an empty string "" if the "Type" is not "Task".
            - "Should End": Must be either "Continue" or "Finished". Use "Finished" only when the conversation is naturally over.
        
            Current Time : {time_context}
        """.trimIndent()

        conversationHistory = addResponse("user", systemPrompt, emptyList())
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updateSystemPromptWithTime() {
        val currentPromptText = conversationHistory.firstOrNull()?.second
            ?.filterIsInstance<TextPart>()?.firstOrNull()?.text ?: return

        val currentTime = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault())
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
        val formattedTime = currentTime.format(formatter)

        val timeRegex = Regex("Current Time : (\\{time_context\\}|.*)")
        val newTimeLine = "Current Time : $formattedTime"

        val updatedPromptText = timeRegex.replace(currentPromptText, newTimeLine)

        conversationHistory = conversationHistory.toMutableList().apply {
            set(0, "user" to listOf(TextPart(updatedPromptText)))
        }
        Log.d("ConvAgent", "System prompt updated with time: $formattedTime")
    }
    private fun updateSystemPromptWithAgentStatus() {
        val currentPromptText = conversationHistory.firstOrNull()?.second
            ?.filterIsInstance<TextPart>()?.firstOrNull()?.text ?: return

        val agentStatusContext = if (AgentService.isRunning) {
            """
            IMPORTANT CONTEXT: An automation task is currently running in the background.
            Task Description: "${AgentService.currentTask}".
            If the user asks to stop, cancel, or kill this task, you MUST use the "KillTask" type.
            """.trimIndent()
        } else {
            "CONTEXT: No automation task is currently running."
        }

        val updatedPromptText = currentPromptText.replace("{agent_status_context}", agentStatusContext)

        conversationHistory = conversationHistory.toMutableList().apply {
            set(0, "user" to listOf(TextPart(updatedPromptText)))
        }
        Log.d("ConvAgent", "System prompt updated with agent status: ${AgentService.isRunning}")
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun updateSystemPromptWithScreenContext() {
        try {

            perception = Perception(Eyes(this), SemanticParser())
            val analysis = perception.analyze(all = true)
            Log.d("ConvAgent", "Screen analysis: ${analysis.uiRepresentation}")
            val currentPrompt = conversationHistory.firstOrNull()?.second
                ?.filterIsInstance<TextPart>()?.firstOrNull()?.text ?: return

            var updatedPrompt = currentPrompt.replace("{screen_context}", analysis.uiRepresentation)

            if (!MEMORY_ENABLED) {
                var userProfile = UserProfileManager(this@ConversationalAgentService)

                Log.d("ConvAgent", "Memory is disabled, skipping memory operations")
                Log.d("ConvAgent", "User name is ${userProfile.getName()}")

                updatedPrompt = updatedPrompt.replace("{memory_context}", "User name is ${userProfile.getName()}")
            } else {
                if (cachedMemories.isNotEmpty()) {
                    Log.d("ConvAgent", "Injecting ${cachedMemories.size} cached memories into context")
                    
                    val topMemories = cachedMemories.take(100)
                    
                    val memoryContext = topMemories.joinToString("\n") { memory -> 
                        "- ${memory.text} (Source: ${memory.source})" 
                    }
                    updatedPrompt = updatedPrompt.replace("{memory_context}", memoryContext)
                } else {
                     Log.d("ConvAgent", "No cached memories available yet")
                     updatedPrompt = updatedPrompt.replace("{memory_context}", "No memories available yet.")
                }
            }

            if (updatedPrompt.isNotEmpty()) {
                conversationHistory = conversationHistory.toMutableList().apply {
                    set(0, "user" to listOf(TextPart(updatedPrompt)))
                }
                Log.d("ConvAgent", "Updated system prompt with screen context and memories")
            }
        } catch (e: Exception) {
            Log.e("ConvAgent", "Error updating system prompt with memories and screen context", e)
        }
    }

    private fun extractCurrentMemoryContext(prompt: String): List<String> {
        return try {
            val memorySection = prompt.substringAfter("##### MEMORY CONTEXT #####")
                .substringBefore("##### END MEMORY CONTEXT #####")
                .trim()

            if (memorySection.isNotEmpty() && !memorySection.contains("{memory_context}")) {
                memorySection.lines()
                    .filter { it.trim().startsWith("- ") }
                    .map { it.trim().substring(2) }
                    .filter { it.isNotEmpty() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("ConvAgent", "Error extracting current memory context", e)
            emptyList()
        }
    }
    private fun parseModelResponse(response: String): ModelDecision {
        try {
            val json = JSONObject(response)
            Log.d("justchecking", json.toString())
            val type = json.optString("Type", "Reply")
            val reply = json.optString("Reply", "")
            val instruction = json.optString("Instruction", "")
            val shouldEndStr = json.optString("Should End", "Continue")
            val shouldEnd = shouldEndStr.equals("Finished", ignoreCase = true)
            
            val finalReply = if (reply.isEmpty() && type.equals("Reply", ignoreCase = true)) {
                "I'm not sure how to respond to that."
            } else {
                reply
            }

            return ModelDecision(type, finalReply, instruction, shouldEnd)
        } catch (e: org.json.JSONException) {
            Log.e("ConvAgent", "Error parsing JSON response, falling back. Response: $response", e)
            return ModelDecision(reply = "I seem to have gotten my thoughts tangled. Could you repeat that?")
        } catch (e: Exception) {
            Log.e("ConvAgent", "Generic error parsing model response, falling back. Response: $response", e)
            return ModelDecision(reply = "I had a minor issue processing that. Could you try again?")
        }
    }
    private fun createNotification(): Notification {
        val stopIntent = Intent(this, ConversationalAgentService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Honey Assistant")
            .setContentText("Listening for your commands...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Conversational Agent Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun displayClarificationQuestions(questions: List<String>) {
        mainHandler.post {

            val topMargin = 100
            val verticalSpacing = 20
            var accumulatedHeight = 0

            questions.forEachIndexed { index, questionText ->
                val textView = TextView(this).apply {
                    text = questionText
                    val glowEffect = GradientDrawable(
                        GradientDrawable.Orientation.BL_TR,
                        intArrayOf("#BE63F3".toColorInt(), "#5880F7".toColorInt())
                    ).apply { cornerRadius = 32f }

                    val glassBackground = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(0xEE0D0D2E.toInt(), 0xEE2A0D45.toInt())
                    ).apply {
                        cornerRadius = 28f
                        setStroke(1, 0x80FFFFFF.toInt())
                    }

                    val layerDrawable = LayerDrawable(arrayOf(glowEffect, glassBackground)).apply {
                        setLayerInset(1, 4, 4, 4, 4)
                    }
                    background = layerDrawable
                    setTextColor(0xFFE0E0E0.toInt())
                    textSize = 15f
                    setPadding(40, 24, 40, 24)
                    typeface = Typeface.MONOSPACE
                }

                textView.measure(
                    View.MeasureSpec.makeMeasureSpec((windowManager.defaultDisplay.width * 0.9).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val viewHeight = textView.measuredHeight

                val finalYPosition = topMargin + accumulatedHeight

                accumulatedHeight += viewHeight + verticalSpacing


                val params = WindowManager.LayoutParams(
                    (windowManager.defaultDisplay.width * 0.9).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = -viewHeight
                    alpha = 0f
                }

                try {
                    windowManager.addView(textView, params)
                    clarificationQuestionViews.add(textView)

                    val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 500L
                        startDelay = (index * 150).toLong()

                        addUpdateListener { animation ->
                            val progress = animation.animatedValue as Float
                            params.y = (finalYPosition * progress - viewHeight * (1 - progress)).toInt()
                            params.alpha = progress
                            windowManager.updateViewLayout(textView, params)
                        }
                    }
                    animator.start()

                } catch (e: Exception) {
                    Log.e("ConvAgent", "Failed to display futuristic clarification question.", e)
                }
            }
        }
    }

    private fun removeClarificationQuestions() {
        mainHandler.post {
            clarificationQuestionViews.forEach { view ->
                if (view.isAttachedToWindow) {
                    try {
                        windowManager.removeView(view)
                    } catch (e: Exception) {
                        Log.e("ConvAgent", "Error removing clarification view.", e)
                    }
                }
            }
            clarificationQuestionViews.clear()
        }
    }

    private suspend fun gracefulShutdown(exitMessage: String? = null, endReason: String = "graceful") {
        

        trackConversationEnd(endReason)
        
        visualFeedbackManager.hideTtsWave()
        visualFeedbackManager.hideTranscription()
        visualFeedbackManager.hideSpeakingOverlay()
        visualFeedbackManager.hideInputBox()

        if (exitMessage != null) {
                speechCoordinator.speakText(exitMessage)
                delay(2000)
            }
            
            triggerMemoryGeneration()
            
            stopSelf()

    }

    private suspend fun instantShutdown() {
        
        trackConversationEnd("instant")
        
        Log.d("ConvAgent", "Instant shutdown triggered by user.")
        withContext(Dispatchers.Main) {
            speechCoordinator.stopSpeaking()
            speechCoordinator.stopListening()
            visualFeedbackManager.hideTtsWave()
            visualFeedbackManager.hideTranscription()
            visualFeedbackManager.hideSpeakingOverlay()
            visualFeedbackManager.hideInputBox()
            removeClarificationQuestions()
        }

        removeClarificationQuestions()
        triggerMemoryGeneration()
        
        serviceScope.cancel("User tapped outside, forcing instant shutdown.")

        stopSelf()
    }

    private fun trackConversationStart() {
        conversationId = "${System.currentTimeMillis()}"
        Log.d("ConvAgent", "Conversation started: $conversationId")
    }

    private fun trackMessage(role: String, message: String, messageType: String = "text") {
        Log.d("ConvAgent", "[$role/$messageType] ${message.take(80)}")
    }
    private fun trackConversationEnd(endReason: String, tasksRequested: Int = 0, tasksExecuted: Int = 0) {
        Log.d("ConvAgent", "Conversation ended: $conversationId ($endReason)")
    }
    override fun onDestroy() {
        super.onDestroy()
        Log.d("ConvAgent", "Service onDestroy")
        
        overlayManager.stopObserving()
        
        if (conversationId != null) {
            trackConversationEnd("service_destroyed")
        }
        
        removeClarificationQuestions()
        serviceScope.cancel()
        isRunning = false
        
        pandaStateManager.setState(PandaState.IDLE)
        pandaStateManager.stopMonitoring()
        visualFeedbackManager.hideSmallDeltaGlow()
        visualFeedbackManager.hideSpeakingOverlay()
        visualFeedbackManager.hideTtsWave()
        visualFeedbackManager.hideTranscription()
        visualFeedbackManager.hideInputBox()

    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun fetchMemories() {
        serviceScope.launch {
            try {
                val db = com.blurr.voice.data.AppDatabase.getInstance(applicationContext)
                cachedMemories = db.memoryDao().getAllMemories().map { mem ->
                    UserMemory(id = mem.id.toString(), text = mem.content, source = mem.source, createdAt = java.util.Date(mem.createdAt))
                }.sortedByDescending { it.createdAt }
                Log.d("ConvAgent", "Loaded ${cachedMemories.size} memories from local DB")
            } catch (e: Exception) {
                Log.e("ConvAgent", "Error loading memories", e)
                cachedMemories = emptyList()
            }
        }
    }
    private fun triggerMemoryGeneration() {
        serviceScope.launch {
            try {
                if (conversationHistory.size < 3) return@launch
                val db = com.blurr.voice.data.AppDatabase.getInstance(applicationContext)
                conversationHistory.filter { it.first == "user" }.takeLast(3)
                    .mapNotNull { pair ->
                        pair.second.filterIsInstance<com.google.ai.client.generativeai.type.TextPart>()
                            .firstOrNull()?.text
                    }
                    .filter { it.length > 20 && !it.contains("{") }
                    .forEach { text ->
                        db.memoryDao().insertMemory(
                            com.blurr.voice.data.Memory(content = text.take(300), source = "conversation")
                        )
                    }
                Log.d("ConvAgent", "Memories saved locally")
            } catch (e: Exception) {
                Log.e("ConvAgent", "Error saving memories", e)
            }
        }
    }
}