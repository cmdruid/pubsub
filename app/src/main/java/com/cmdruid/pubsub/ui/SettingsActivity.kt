package com.cmdruid.pubsub.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.cmdruid.pubsub.R
import com.cmdruid.pubsub.data.AppSettings
import com.cmdruid.pubsub.data.BatteryMode
import com.cmdruid.pubsub.data.ConfigurationManager
import com.cmdruid.pubsub.data.ExportResult
import com.cmdruid.pubsub.data.ImportExportManager
import com.cmdruid.pubsub.data.ImportMode
import com.cmdruid.pubsub.data.ImportResult
import com.cmdruid.pubsub.data.NotificationFrequency
import com.cmdruid.pubsub.data.SettingsManager
import com.cmdruid.pubsub.data.ValidationResult
import com.cmdruid.pubsub.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    
    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, SettingsActivity::class.java)
        }
    }
    
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsManager: SettingsManager
    private lateinit var configurationManager: ConfigurationManager
    private lateinit var importExportManager: ImportExportManager
    
    // File picker launchers
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { exportToFile(it) }
    }
    
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importFromFile(it) }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        settingsManager = SettingsManager(this)
        configurationManager = ConfigurationManager(this)
        importExportManager = ImportExportManager(this, configurationManager)
        
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
        
        binding.exportButton.setOnClickListener {
            startExport()
        }
        
        binding.importButton.setOnClickListener {
            startImport()
        }
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
        
        // Create and save settings
        val newSettings = AppSettings(
            batteryMode = batteryMode,
            notificationFrequency = notificationFrequency,
            defaultEventViewer = eventViewer,
            defaultRelayServer = relayServer,
            showDebugConsole = binding.debugConsoleSwitch.isChecked
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
    
    /**
     * Start the export process
     */
    private fun startExport() {
        val configurations = configurationManager.getConfigurations()
        
        if (configurations.isEmpty()) {
            Toast.makeText(
                this, 
                "No subscriptions to export. Create some subscriptions first.", 
                Toast.LENGTH_LONG
            ).show()
            return
        }
        
        // Generate filename and start file picker
        val filename = importExportManager.generateExportFilename()
        exportLauncher.launch(filename)
    }
    
    /**
     * Export configurations to the selected file
     */
    private fun exportToFile(uri: Uri) {
        lifecycleScope.launch {
            try {
                when (val result = importExportManager.exportConfigurations(uri)) {
                    is ExportResult.Success -> {
                        Toast.makeText(
                            this@SettingsActivity,
                            "✅ Exported ${result.subscriptionCount} subscriptions to ${result.filename}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is ExportResult.Error -> {
                        showErrorDialog("Export Failed", result.message)
                    }
                }
            } catch (e: Exception) {
                showErrorDialog("Export Error", "Unexpected error during export: ${e.message}")
            }
        }
    }
    
    /**
     * Start the import process
     */
    private fun startImport() {
        importLauncher.launch(arrayOf("application/json", "text/plain"))
    }
    
    /**
     * Import configurations from the selected file
     */
    private fun importFromFile(uri: Uri) {
        lifecycleScope.launch {
            try {
                // First, validate the file
                when (val validationResult = importExportManager.validateImportFile(uri)) {
                    is ValidationResult.Invalid -> {
                        showErrorDialog(
                            "Invalid Import File", 
                            "The selected file is not a valid PubSub backup:\n\n${validationResult.errors.joinToString("\n")}"
                        )
                        return@launch
                    }
                    ValidationResult.Valid -> {
                        // File is valid, show preview and import options
                        showImportPreviewDialog(uri)
                    }
                }
            } catch (e: Exception) {
                showErrorDialog("Import Error", "Could not read import file: ${e.message}")
            }
        }
    }
    
    /**
     * Show import preview dialog with options
     */
    private fun showImportPreviewDialog(uri: Uri) {
        lifecycleScope.launch {
            val preview = importExportManager.getImportPreview(uri)
            
            if (preview == null) {
                showErrorDialog("Preview Error", "Could not preview import file")
                return@launch
            }
            
            val message = buildString {
                appendLine("Import ${preview.totalSubscriptions} subscriptions from backup?")
                appendLine()
                
                if (preview.newSubscriptions.isNotEmpty()) {
                    appendLine("📋 New subscriptions (${preview.newSubscriptions.size}):")
                    preview.newSubscriptions.take(5).forEach { name ->
                        appendLine("• $name")
                    }
                    if (preview.newSubscriptions.size > 5) {
                        appendLine("• ... and ${preview.newSubscriptions.size - 5} more")
                    }
                    appendLine()
                }
                
                if (preview.duplicateSubscriptions.isNotEmpty()) {
                    appendLine("⚠️ Duplicates (${preview.duplicateSubscriptions.size}):")
                    preview.duplicateSubscriptions.take(3).forEach { name ->
                        appendLine("• $name (will be skipped)")
                    }
                    if (preview.duplicateSubscriptions.size > 3) {
                        appendLine("• ... and ${preview.duplicateSubscriptions.size - 3} more")
                    }
                    appendLine()
                }
                
                appendLine("📅 Backup created: ${preview.date}")
                appendLine("📱 App version: ${preview.version}")
            }
            
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Import Subscriptions")
                .setMessage(message)
                .setPositiveButton("Import") { _, _ ->
                    performImport(uri, ImportMode.ADD_NEW_ONLY)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    
    /**
     * Perform the actual import
     */
    private fun performImport(uri: Uri, importMode: ImportMode) {
        lifecycleScope.launch {
            try {
                when (val result = importExportManager.importConfigurations(uri, importMode)) {
                    is ImportResult.Success -> {
                        val message = buildString {
                            appendLine("✅ Import completed successfully!")
                            appendLine()
                            appendLine("📊 Results:")
                            appendLine("• Imported: ${result.importedCount} subscriptions")
                            if (result.duplicateCount > 0) {
                                appendLine("• Skipped duplicates: ${result.duplicateCount}")
                            }
                        }
                        
                        AlertDialog.Builder(this@SettingsActivity)
                            .setTitle("Import Successful")
                            .setMessage(message)
                            .setPositiveButton("OK") { _, _ ->
                                // Return to main screen to see imported subscriptions
                                finish()
                            }
                            .show()
                    }
                    is ImportResult.Error -> {
                        showErrorDialog("Import Failed", result.message)
                    }
                }
            } catch (e: Exception) {
                showErrorDialog("Import Error", "Unexpected error during import: ${e.message}")
            }
        }
    }
    
    /**
     * Show error dialog with consistent styling
     */
    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

}
