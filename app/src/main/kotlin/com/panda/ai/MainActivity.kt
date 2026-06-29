package com.panda.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.panda.ai.databinding.ActivityMainBinding
import com.panda.ai.utilities.ApiKeyManager
import com.panda.ai.utilities.MemoryStore
import com.panda.ai.v2.AgentService
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isListening = false

    // Speech recognition launcher
    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                binding.etTask.setText(matches[0])
                // Auto start agent after voice input
                startAgent()
            }
        }
        isListening = false
        updateMicButton()
    }

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "Permissions granted!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Some permissions denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkPermissions()
        checkAccessibilityService()
    }

    private fun setupUI() {
        // Load saved API key
        val savedKey = ApiKeyManager.getApiKey(this)
        if (savedKey.isNotEmpty()) {
            binding.etApiKey.setText(savedKey)
        }

        // Save API key on change
        binding.btnSaveKey.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "API key cannot be empty!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ApiKeyManager.saveApiKey(this, key)
            Toast.makeText(this, "API key saved!", Toast.LENGTH_SHORT).show()
        }

        // Start button
        binding.btnStart.setOnClickListener {
            startAgent()
        }

        // Stop button
        binding.btnStop.setOnClickListener {
            stopAgent()
        }

        // Mic button
        binding.btnMic.setOnClickListener {
            if (isListening) {
                isListening = false
                updateMicButton()
            } else {
                startVoiceInput()
            }
        }

        // Update UI based on agent state
        updateAgentUI()
    }

    private fun startAgent() {
        val apiKey = ApiKeyManager.getApiKey(this)
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Please save your Gemini API key first!", Toast.LENGTH_SHORT).show()
            return
        }

        val task = binding.etTask.text.toString().trim()
        if (task.isEmpty()) {
            Toast.makeText(this, "Please enter a task!", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Please enable Accessibility Service first!", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant overlay permission!", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }

        // Save task to memory
        MemoryStore.saveLastTask(this, task)

        // Start agent service
        AgentService.start(this, task)

        binding.etTask.setText("")
        Toast.makeText(this, "Agent started!", Toast.LENGTH_SHORT).show()
        updateAgentUI()
    }

    private fun stopAgent() {
        AgentService.stop(this)
        Toast.makeText(this, "Agent stopped!", Toast.LENGTH_SHORT).show()
        updateAgentUI()
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Bol do task...")
        }

        try {
            isListening = true
            updateMicButton()
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            isListening = false
            updateMicButton()
            Toast.makeText(this, "Voice input not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMicButton() {
        if (isListening) {
            binding.btnMic.text = "🔴 Sun raha hun..."
        } else {
            binding.btnMic.text = "🎤 Bolo"
        }
    }

    private fun updateAgentUI() {
        val isRunning = AgentService.isRunning
        binding.btnStart.isEnabled = !isRunning
        binding.btnStop.isEnabled = isRunning
        binding.tvStatus.text = if (isRunning) "✅ Agent Running..." else "⏹ Agent Stopped"
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun checkAccessibilityService() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(
                this,
                "Accessibility Service enable karo settings mein!",
                Toast.LENGTH_LONG
            ).show()
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Overlay permission do settings mein!",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "${packageName}/${ScreenInteractionService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(service)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        updateAgentUI()
    }
}