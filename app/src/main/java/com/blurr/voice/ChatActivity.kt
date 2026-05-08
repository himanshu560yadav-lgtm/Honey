package com.blurr.voice

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blurr.voice.utilities.SpeechCoordinator
import com.blurr.voice.v2.AgentService
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

    private val TAG = "ChatActivity"
    private lateinit var recyclerView: RecyclerView
    private lateinit var editText: EditText
    private lateinit var sendButton: Button
    private lateinit var micButton: ImageButton
    private val messages = mutableListOf<Message>()
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var speechCoordinator: SpeechCoordinator
    private var isListening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        speechCoordinator = SpeechCoordinator.getInstance(this)

        initViews()
        setupRecyclerView()
        startListening()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerView)
        editText = findViewById(R.id.editText)
        sendButton = findViewById(R.id.sendButton)
        micButton = findViewById(R.id.micButton)

        sendButton.setOnClickListener {
            val message = editText.text.toString().trim()
            if (message.isNotEmpty()) {
                sendMessage(message)
            }
        }

        micButton.setOnClickListener {
            if (isListening) {
                stopListening()
            } else {
                startListening()
            }
        }
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        chatAdapter = ChatAdapter(messages)
        recyclerView.adapter = chatAdapter

        messages.add(Message("Hello! How can I help you today?", isUserMessage = false))
        chatAdapter.notifyItemInserted(messages.size - 1)
    }

    private fun startListening() {
        isListening = true
        micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
        editText.hint = "Listening..."

        lifecycleScope.launch {
            try {
                speechCoordinator.startListening(
                    onResult = { recognizedText ->
                        runOnUiThread {
                            if (recognizedText.isNotEmpty()) {
                                sendMessage(recognizedText)
                            }
                            isListening = false
                            micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
                            editText.hint = "Type a message"
                        }
                    },
                    onError = { error ->
                        runOnUiThread {
                            Log.e(TAG, "Speech error: $error")
                            isListening = false
                            micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
                            editText.hint = "Type a message"
                            Toast.makeText(this@ChatActivity, "Speech error: $error", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening", e)
                isListening = false
                micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
                editText.hint = "Type a message"
            }
        }
    }

    private fun stopListening() {
        lifecycleScope.launch {
            speechCoordinator.stopListening()
            isListening = false
            micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
            editText.hint = "Type a message"
        }
    }

    private fun sendMessage(message: String) {
        messages.add(Message(message, isUserMessage = true))
        chatAdapter.notifyItemInserted(messages.size - 1)
        recyclerView.scrollToPosition(messages.size - 1)
        editText.text?.clear()

        // Send to agent service
        if (AgentService.isRunning) {
            AgentService.start(this, message)
        } else {
            messages.add(Message("Agent is not running. Please start from Home.", isUserMessage = false))
            chatAdapter.notifyItemInserted(messages.size - 1)
            recyclerView.scrollToPosition(messages.size - 1)
        }

        // Restart listening after sending
        startListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch {
            speechCoordinator.stopListening()
        }
    }
}