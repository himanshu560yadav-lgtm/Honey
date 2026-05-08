package com.blurr.voice

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.blurr.voice.api.GoogleTts
import com.blurr.voice.api.TTSVoice
import com.blurr.voice.utilities.SpeechCoordinator
import com.blurr.voice.utilities.VoicePreferenceManager
import com.blurr.voice.utilities.UserProfileManager
import com.blurr.voice.utilities.WakeWordManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class SettingsActivity : BaseNavigationActivity() {

    private lateinit var ttsVoicePicker: NumberPicker
    private lateinit var switchShowThoughts: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var permissionsInfoButton: TextView
    private lateinit var batteryOptimizationHelpButton: TextView
    private lateinit var appVersionText: TextView
    private lateinit var editUserName: android.widget.EditText
    private lateinit var editUserEmail: android.widget.EditText
    private lateinit var editWakeWordKey: android.widget.EditText
    private lateinit var textGetPicovoiceKeyLink: TextView
    private lateinit var wakeWordButton: TextView
    private lateinit var buttonSignOut: Button
    private lateinit var wakeWordManager: WakeWordManager
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    private lateinit var sc: SpeechCoordinator
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var availableVoices: List<TTSVoice>
    private var voiceTestJob: Job? = null

    companion object {
        private const val PREFS_NAME = "BlurrSettings"
        private const val KEY_SELECTED_VOICE = "selected_voice"
        private const val TEST_TEXT = "Hello, I am Honey, your personal AI assistant."
        private val DEFAULT_VOICE = TTSVoice.CHIRP_PUCK
        const val KEY_SHOW_THOUGHTS = "show_thoughts"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                Toast.makeText(this, "Permission granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permission denied.", Toast.LENGTH_SHORT).show()
            }
        }

        initialize()
        setupUI()
        loadAllSettings()
        setupAutoSavingListeners()
    }

    override fun onStop() {
        super.onStop()
        sc.stop()
        voiceTestJob?.cancel()
    }

    private fun initialize() {
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sc = SpeechCoordinator.getInstance(this)
        availableVoices = GoogleTts.getAvailableVoices()
        wakeWordManager = WakeWordManager(this)
    }

    private fun setupUI() {
        ttsVoicePicker = findViewById(R.id.ttsVoicePicker)
        switchShowThoughts = findViewById(R.id.switchShowThoughts)
        permissionsInfoButton = findViewById(R.id.permissionsInfoButton)
        appVersionText = findViewById(R.id.appVersionText)
        batteryOptimizationHelpButton = findViewById(R.id.batteryOptimizationHelpButton)
        editWakeWordKey = findViewById(R.id.editWakeWordKey)
        wakeWordButton = findViewById(R.id.wakeWordButton)
        buttonSignOut = findViewById(R.id.buttonSignOut)
        editUserName = findViewById(R.id.editUserName)
        editUserEmail = findViewById(R.id.editUserEmail)
        textGetPicovoiceKeyLink = findViewById(R.id.textGetPicovoiceKeyLink)

        setupClickListeners()
        setupVoicePicker()

        runCatching {
            val pm = UserProfileManager(this)
            editUserName.setText(pm.getName() ?: "")
            editUserEmail.setText(pm.getEmail() ?: "")
        }

        val versionName = BuildConfig.VERSION_NAME
        appVersionText.text = "Version $versionName"
    }

    private fun setupVoicePicker() {
        val voiceDisplayNames = availableVoices.map { it.displayName }.toTypedArray()
        ttsVoicePicker.minValue = 0
        ttsVoicePicker.maxValue = voiceDisplayNames.size - 1
        ttsVoicePicker.displayedValues = voiceDisplayNames
        ttsVoicePicker.wrapSelectorWheel = false
    }

    private fun setupClickListeners() {
        permissionsInfoButton.setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }

        batteryOptimizationHelpButton.setOnClickListener {
            showBatteryOptimizationDialog()
        }

        wakeWordButton.setOnClickListener {
            Toast.makeText(this, "Wake word is disabled in this version", Toast.LENGTH_SHORT).show()
        }
        textGetPicovoiceKeyLink.setOnClickListener {
            Toast.makeText(this, "Wake word is disabled in this version", Toast.LENGTH_SHORT).show()
        }
        wakeWordButton.isEnabled = false
        editWakeWordKey.isEnabled = false
        textGetPicovoiceKeyLink.isEnabled = false

        buttonSignOut.setOnClickListener {
            showSignOutConfirmationDialog()
        }

        findViewById<TextView>(R.id.viewTaskLogsButton).setOnClickListener {
            startActivity(Intent(this, TaskLogsListActivity::class.java))
        }
    }

    private fun setupAutoSavingListeners() {
        var isInitialLoad = true

        ttsVoicePicker.setOnValueChangedListener { _, _, newVal ->
            val selectedVoice = availableVoices[newVal]
            saveSelectedVoice(selectedVoice)

            if (!isInitialLoad) {
                voiceTestJob?.cancel()
                voiceTestJob = lifecycleScope.launch {
                    delay(400L)
                    sc.stop()
                    Toast.makeText(
                        this@SettingsActivity,
                        "Voice: ${selectedVoice.displayName} selected (preview unavailable — Google TTS disabled)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        ttsVoicePicker.post {
            isInitialLoad = false
        }

        switchShowThoughts.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(KEY_SHOW_THOUGHTS, isChecked).apply()
        }
    }

    private fun loadAllSettings() {
        editWakeWordKey.setText("")

        val savedVoiceName = sharedPreferences.getString(KEY_SELECTED_VOICE, DEFAULT_VOICE.name)
        val savedVoice = availableVoices.find { it.name == savedVoiceName } ?: DEFAULT_VOICE
        ttsVoicePicker.value = availableVoices.indexOf(savedVoice)

        switchShowThoughts.isChecked = sharedPreferences.getBoolean(KEY_SHOW_THOUGHTS, false)
    }

    private fun saveSelectedVoice(voice: TTSVoice) {
        VoicePreferenceManager.saveSelectedVoice(this, voice)
        Log.d("SettingsActivity", "Saved voice: ${voice.displayName}")
    }

    private fun showBatteryOptimizationDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.battery_optimization_title))
            .setMessage(getString(R.string.battery_optimization_message))
            .setPositiveButton(getString(R.string.learn_how)) { _, _ ->
                val url = "https://tasker.joaoapps.com/userguide/en/faqs/faq-problem.html#00"
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse(url)
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open link.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setTextColor(ContextCompat.getColor(this, R.color.white))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            .setTextColor(ContextCompat.getColor(this, R.color.white))
    }

    private fun showSignOutConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reset App")
            .setMessage("This will clear all your saved settings and profile data. Continue?")
            .setPositiveButton("Reset") { _, _ -> signOut() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun signOut() {
        UserProfileManager(this).clearProfile()
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("HoneyPrefs", Context.MODE_PRIVATE).edit().clear().apply()

        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun getContentLayoutId(): Int = R.layout.activity_settings

    override fun getCurrentNavItem(): BaseNavigationActivity.NavItem =
        BaseNavigationActivity.NavItem.SETTINGS
}