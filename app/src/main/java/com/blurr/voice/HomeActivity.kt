package com.blurr.voice

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.blurr.voice.utilities.ApiKeyManager
import com.blurr.voice.utilities.PermissionManager
import com.blurr.voice.ConversationalAgentService
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var etApiKey: TextInputEditText
    private lateinit var tvStatus: TextView
    private lateinit var btnStartStop: MaterialButton
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvMicStatus: TextView
    private lateinit var tvApiKeyStatus: TextView
    private lateinit var btnManagePermissions: MaterialButton
    private lateinit var btnSettings: MaterialButton

    private lateinit var prefs: SharedPreferences
    private lateinit var permissionManager: PermissionManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        updatePermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        prefs = getSharedPreferences("HoneyPrefs", MODE_PRIVATE)
        permissionManager = PermissionManager(this)

        initViews()
        loadSavedApiKey()
        updatePermissionStatus()
        checkServiceStatus()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        checkServiceStatus()
    }

    private fun initViews() {
        etApiKey = findViewById(R.id.et_api_key)
        tvStatus = findViewById(R.id.tv_status)
        btnStartStop = findViewById(R.id.btn_start_stop)
        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status)
        tvOverlayStatus = findViewById(R.id.tv_overlay_status)
        tvMicStatus = findViewById(R.id.tv_mic_status)
        tvApiKeyStatus = findViewById(R.id.tv_api_key_status)
        btnManagePermissions = findViewById(R.id.btn_manage_permissions)
        btnSettings = findViewById(R.id.btn_settings)

        etApiKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                saveApiKey()
            }
        }

        btnStartStop.setOnClickListener {
            if (ConversationalAgentService.isRunning) {
                stopAgent()
            } else {
                startAgent()
            }
        }

        btnManagePermissions.setOnClickListener {
            openPermissionSettings()
        }

        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
    }

    private fun loadSavedApiKey() {
        val apiKey = prefs.getString("GEMINI_API_KEY", "") ?: ""
        if (apiKey.isNotEmpty()) {
            etApiKey.setText(apiKey)
            tvApiKeyStatus.text = "API key saved"
            tvApiKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
            ApiKeyManager.setKeys(listOf(apiKey))
        }
    }

    private fun saveApiKey() {
        val apiKey = etApiKey.text?.toString()?.trim() ?: ""
        if (apiKey.isNotEmpty()) {
            prefs.edit().putString("GEMINI_API_KEY", apiKey).apply()
            ApiKeyManager.setKeys(listOf(apiKey))
            tvApiKeyStatus.text = "API key saved"
            tvApiKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
        } else {
            tvApiKeyStatus.text = "No API key set"
            tvApiKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.red))
        }
    }

    private fun updatePermissionStatus() {
        // Accessibility Service
        val accessibilityGranted = isAccessibilityServiceEnabled()
        tvAccessibilityStatus.text = if (accessibilityGranted) "Granted" else "Not Granted"
        tvAccessibilityStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (accessibilityGranted) R.color.green else R.color.red
            )
        )

        // Overlay Permission
        val overlayGranted = Settings.canDrawOverlays(this)
        tvOverlayStatus.text = if (overlayGranted) "Granted" else "Not Granted"
        tvOverlayStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (overlayGranted) R.color.green else R.color.red
            )
        )

        // Microphone Permission
        val micGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!micGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }

        tvMicStatus.text = if (micGranted) "Granted" else "Not Granted"
        tvMicStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (micGranted) R.color.green else R.color.red
            )
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(packageName) == true
    }

    private fun checkServiceStatus() {
        if (ConversationalAgentService.isRunning) {
            tvStatus.text = "Running"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
            btnStartStop.text = "Stop Honey"
            btnStartStop.setBackgroundColor(ContextCompat.getColor(this, R.color.red))
        } else {
            tvStatus.text = "Not Running"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.red))
            btnStartStop.text = "Start Honey"
            btnStartStop.setBackgroundColor(ContextCompat.getColor(this, R.color.gold))
        }
    }

    private fun startAgent() {
        saveApiKey()

        val apiKey = etApiKey.text?.toString()?.trim() ?: ""
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Please enter API key first", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Please enable Accessibility Service first", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }

        // Start the ConversationalAgentService - this handles voice chat and can trigger AgentService for tasks
        val intent = Intent(this, ConversationalAgentService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        Toast.makeText(this, "Starting Honey...", Toast.LENGTH_SHORT).show()
        checkServiceStatus()
    }

    private fun stopAgent() {
        val intent = Intent(this, ConversationalAgentService::class.java)
        stopService(intent)
        Toast.makeText(this, "Stopping Honey...", Toast.LENGTH_SHORT).show()
        checkServiceStatus()
    }

    private fun openPermissionSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }
}