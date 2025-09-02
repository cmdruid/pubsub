package com.cmdruid.pubsub.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cmdruid.pubsub.R
import com.cmdruid.pubsub.data.AppSettings
import com.cmdruid.pubsub.data.BatteryMode

import com.cmdruid.pubsub.data.NotificationFrequency
import com.cmdruid.pubsub.data.PerformanceMetricsSettings
import com.cmdruid.pubsub.data.SettingsManager
import com.cmdruid.pubsub.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    
    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, SettingsActivity::class.java)
        }
    }
    
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsManager: SettingsManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        settingsManager = SettingsManager(this)
        
        setupToolbar()
        setupNotificationFrequencySpinner()
        setupUI()
        loadCurrentSettings()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Settings"
        }
    }
    
    private fun setupNotificationFrequencySpinner() {
        val frequencies = NotificationFrequency.values()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            frequencies.map { it.displayName }
        )
        binding.notificationFrequencySpinner.setAdapter(adapter)
        
        binding.notificationFrequencySpinner.setOnItemClickListener { _, _, position, _ ->
            // Handle selection if needed
        }
    }
    

    
    private fun setupUI() {
        binding.saveSettingsButton.setOnClickListener {
            saveSettings()
        }
        
        setupPerformanceMetricsSection()
    }
    
    private fun setupPerformanceMetricsSection() {
        val performanceMetricsSwitch = binding.performanceMetricsSwitch
        
        // Simple on/off toggle
        performanceMetricsSwitch.setOnCheckedChangeListener { _, isChecked ->
            showMetricsToast(isChecked)
        }
    }
    
    private fun showMetricsToast(enabled: Boolean) {
        val message = if (enabled) {
            "Performance metrics enabled - minimal impact (~0.1% battery)"
        } else {
            "Performance metrics disabled - all data cleared from device"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    private fun loadCurrentSettings() {
        val settings = settingsManager.getSettings()
        
        // Load battery mode
        when (settings.batteryMode) {
            BatteryMode.PERFORMANCE -> binding.performanceRadioButton.isChecked = true
            BatteryMode.BALANCED -> binding.balancedRadioButton.isChecked = true
            BatteryMode.BATTERY_SAVER -> binding.batterySaverRadioButton.isChecked = true
        }
        
        // Load notification frequency
        binding.notificationFrequencySpinner.setText(settings.notificationFrequency.displayName, false)
        
        // Load URLs
        binding.eventViewerEditText.setText(settings.defaultEventViewer)
        binding.relayServerEditText.setText(settings.defaultRelayServer)
        
        // Load debug console setting
        binding.debugConsoleSwitch.isChecked = settings.showDebugConsole
        
        // Load performance metrics setting
        binding.performanceMetricsSwitch.isChecked = settings.performanceMetrics.enabled
    }
    
    private fun saveSettings() {
        // Clear any previous errors
        binding.eventViewerEditText.error = null
        binding.relayServerEditText.error = null
        
        // Validate inputs
        val eventViewer = binding.eventViewerEditText.text.toString().trim()
        val relayServer = binding.relayServerEditText.text.toString().trim()
        
        // Validate event viewer URL
        val eventViewerValidation = validateEventViewerUrl(eventViewer)
        if (eventViewerValidation != null) {
            binding.eventViewerEditText.error = eventViewerValidation
            binding.eventViewerEditText.requestFocus()
            return
        }
        
        // Validate relay server URL
        val relayServerValidation = validateRelayServerUrl(relayServer)
        if (relayServerValidation != null) {
            binding.relayServerEditText.error = relayServerValidation
            binding.relayServerEditText.requestFocus()
            return
        }
        
        // Get battery mode
        val batteryMode = when {
            binding.performanceRadioButton.isChecked -> BatteryMode.PERFORMANCE
            binding.balancedRadioButton.isChecked -> BatteryMode.BALANCED
            binding.batterySaverRadioButton.isChecked -> BatteryMode.BATTERY_SAVER
            else -> BatteryMode.BALANCED // Default fallback
        }
        
        // Get notification frequency
        val selectedFrequencyText = binding.notificationFrequencySpinner.text.toString()
        val notificationFrequency = NotificationFrequency.values().find { 
            it.displayName == selectedFrequencyText 
        } ?: NotificationFrequency.IMMEDIATE
        
        // Create performance metrics settings
        val performanceMetrics = PerformanceMetricsSettings(
            enabled = binding.performanceMetricsSwitch.isChecked
        )
        
        // Create and save settings
        val newSettings = AppSettings(
            batteryMode = batteryMode,
            notificationFrequency = notificationFrequency,
            defaultEventViewer = eventViewer,
            defaultRelayServer = relayServer,
            showDebugConsole = binding.debugConsoleSwitch.isChecked,
            performanceMetrics = performanceMetrics
        )
        
        settingsManager.saveSettingsWithNotification(newSettings)
        
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }
    

    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    /**
     * Validate event viewer URL with specific requirements
     */
    private fun validateEventViewerUrl(url: String): String? {
        if (url.isBlank()) {
            return "Event viewer URL cannot be empty"
        }
        
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "Event viewer URL must start with http:// or https://"
        }
        
        // Basic domain validation
        if (!url.contains(".")) {
            return "Please enter a valid website URL (e.g., https://njump.me)"
        }
        
        return null // Valid
    }
    
    /**
     * Validate relay server URL with specific requirements
     */
    private fun validateRelayServerUrl(url: String): String? {
        if (url.isBlank()) {
            return "Relay server URL cannot be empty"
        }
        
        if (!url.startsWith("wss://") && !url.startsWith("ws://")) {
            return "Relay server URL must start with wss:// or ws://"
        }
        
        if (!url.contains(".")) {
            return "Please enter a valid relay server URL (e.g., wss://relay.damus.io)"
        }
        
        // Check for localhost or IP addresses (which are valid)
        if (url.contains("localhost") || url.matches(Regex(".*\\d+\\.\\d+\\.\\d+\\.\\d+.*"))) {
            return null // Allow localhost and IP addresses
        }
        
        // Check for valid domain pattern
        val domain = url.substringAfter("://").substringBefore("/").substringBefore(":")
        if (domain.length < 3 || !domain.contains(".")) {
            return "Please enter a valid relay server domain"
        }
        
        return null // Valid
    }
    



}
