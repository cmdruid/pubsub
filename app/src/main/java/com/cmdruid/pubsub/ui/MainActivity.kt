package com.cmdruid.pubsub.ui

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

import androidx.recyclerview.widget.LinearLayoutManager
import com.cmdruid.pubsub.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.cmdruid.pubsub.data.BatteryMode
import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.data.ConfigurationManager
import com.cmdruid.pubsub.data.NotificationFrequency
import com.cmdruid.pubsub.data.SettingsManager
import com.cmdruid.pubsub.databinding.ActivityMainBinding
import com.cmdruid.pubsub.service.PubSubService
import com.cmdruid.pubsub.ui.adapters.ConfigurationAdapter
import com.cmdruid.pubsub.ui.adapters.DebugLogAdapter
import com.cmdruid.pubsub.utils.DeepLinkHandler
import com.cmdruid.pubsub.logging.*

class MainActivity : AppCompatActivity(), SettingsManager.SettingsChangeListener {
    
    companion object {
        const val ACTION_DEBUG_LOG = "com.cmdruid.pubsub.DEBUG_LOG"
        const val EXTRA_LOG_MESSAGE = "log_message"
        
        // Battery optimization: App state broadcasts
        const val ACTION_APP_STATE_CHANGE = "com.cmdruid.pubsub.APP_STATE_CHANGE"
        const val EXTRA_APP_STATE = "app_state"
        const val EXTRA_STATE_DURATION = "state_duration"
    }
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var configurationManager: ConfigurationManager
    private lateinit var settingsManager: SettingsManager
    private lateinit var configurationAdapter: ConfigurationAdapter
    private lateinit var debugLogAdapter: DebugLogAdapter
    private lateinit var unifiedLogger: UnifiedLogger
    private var currentLogFilter = LogFilter.DEFAULT
    
    // Battery optimization: Lifecycle state tracking
    private var appResumedTime: Long = 0
    private var appPausedTime: Long = 0
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission is required for the app to work properly", Toast.LENGTH_LONG).show()
        }
    }
    
    private val exportLogsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { 
            exportDebugLogsToFile(it)
        }
    }
    
    private val debugLogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_DEBUG_LOG -> {
                    val message = intent.getStringExtra(EXTRA_LOG_MESSAGE) ?: return
                    // Parse structured log entry from JSON
                    val entry = StructuredLogEntry.fromJson(message)
                    if (entry != null) {
                        // Add to adapter immediately for real-time display
                        debugLogAdapter.addLog(entry)
                        
                        // Update stats efficiently without re-reading all logs
                        updateDebugStatsQuick()
                        
                        // Store in background to avoid blocking UI
                        CoroutineScope(Dispatchers.IO).launch {
                            configurationManager.addStructuredLog(entry)
                        }
                    }
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // Install the modern splash screen
        installSplashScreen()
        
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        configurationManager = ConfigurationManager(this)
        settingsManager = SettingsManager(this)
        unifiedLogger = UnifiedLoggerImpl(this, configurationManager)
        
        // Load saved filter preferences
        currentLogFilter = settingsManager.getLogFilter()
        
        // Set initial filter for performance filtering
        (unifiedLogger as? UnifiedLoggerImpl)?.setFilter(currentLogFilter)
        
        setupToolbar()
        setupConfigurationsRecyclerView()
        setupDebugLogsRecyclerView()
        setupUI()
        requestNotificationPermission()
        updateServiceStatus()
        refreshConfigurations()
        
        // Load debug logs asynchronously to avoid blocking UI
        loadDebugLogsAsync()
        
        // Register for settings changes and apply initial debug console visibility
        settingsManager.addSettingsChangeListener(this)
        updateDebugConsoleVisibility()
        
        // Register for debug log broadcasts
        registerReceiver(
            debugLogReceiver,
            IntentFilter(ACTION_DEBUG_LOG),
            RECEIVER_NOT_EXPORTED
        )
        
        // Handle deep link if launched via intent
        handleDeepLinkIntent(intent)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(debugLogReceiver)
        settingsManager.removeSettingsChangeListener(this)
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleDeepLinkIntent(it) }
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
    }
    
    private fun setupConfigurationsRecyclerView() {
        configurationAdapter = ConfigurationAdapter(
            onEditClick = { configuration ->
                val intent = ConfigurationEditorActivity.createIntent(this, configuration.id)
                startActivity(intent)
            },
            onDeleteClick = { configuration ->
                showDeleteConfirmationDialog(configuration)
            },
            onEnabledChanged = { configuration, enabled ->
                unifiedLogger.info(LogDomain.UI, "Toggle: ${configuration.name} → ${if (enabled) "ON" else "OFF"}")
                
                val updatedConfiguration = configuration.copy(isEnabled = enabled)
                configurationManager.updateConfiguration(updatedConfiguration)
                
                // Update the adapter with the new configuration
                configurationAdapter.updateConfiguration(updatedConfiguration)
                
                // Notify service to sync configurations if it's running
                if (configurationManager.isServiceRunning) {
                    unifiedLogger.info(LogDomain.SERVICE, "Syncing service configurations...")
                    syncServiceConfigurations()
                } else {
                    unifiedLogger.warn(LogDomain.SERVICE, "Service not running, toggle saved but won't take effect until service starts")
                }
                
                updateServiceStatus()
            }
        )
        
        binding.configurationsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = configurationAdapter
        }
    }
    
    private fun setupDebugLogsRecyclerView() {
        debugLogAdapter = DebugLogAdapter()
        binding.debugLogsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = debugLogAdapter
            
            // Disable all animations for fastest possible display
            itemAnimator = null
            
            // Optimize for performance
            setHasFixedSize(true)
            setItemViewCacheSize(20)
        }
    }
    
    private fun setupUI() {
        binding.apply {
            toggleServiceButton.setOnClickListener {
                if (configurationManager.isServiceRunning) {
                    stopPubSubService()
                } else {
                    startPubSubService()
                }
            }
            
            addConfigButton.setOnClickListener {
                val intent = ConfigurationEditorActivity.createIntent(this@MainActivity)
                startActivity(intent)
            }
            
            closeButton.setOnClickListener {
                finish()
            }
            
            settingsButton.setOnClickListener {
                val intent = SettingsActivity.createIntent(this@MainActivity)
                startActivity(intent)
            }
            
            exportLogsButton.setOnClickListener {
                exportDebugLogs()
            }
            
            filterLogsButton.setOnClickListener {
                showLogFilterDialog()
            }
            
            clearLogsButton.setOnClickListener {
                // Clear adapter immediately for instant UI response
                debugLogAdapter.clearLogs()
                updateDebugStatsQuick()
                
                // Clear storage in background without blocking UI
                CoroutineScope(Dispatchers.IO).launch {
                    configurationManager.clearStructuredLogs()
                }
            }
            
            // Add sample deep link generation for testing (long press on clear logs button)
            clearLogsButton.setOnLongClickListener {
                val sampleDeepLink = DeepLinkHandler.generateSampleDeepLink()
                unifiedLogger.info(LogDomain.UI, "Sample deep link: $sampleDeepLink")
                // No need to refresh - the unified logger will broadcast the entry automatically
                Toast.makeText(this@MainActivity, "Sample deep link added to debug logs", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }
    
    private fun startPubSubService() {
        if (!configurationManager.hasValidEnabledConfigurations()) {
            Toast.makeText(this, "Please add and enable at least one valid subscription", Toast.LENGTH_LONG).show()
            return
        }
        
        val serviceIntent = Intent(this, PubSubService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        // Wait a bit for service to start, then update UI
        binding.root.postDelayed({
            updateServiceStatus()
        }, 100)
        
        Toast.makeText(this, "Service started with ${configurationManager.getEnabledConfigurations().size} subscription(s)", Toast.LENGTH_SHORT).show()
    }
    
    private fun stopPubSubService() {
        val serviceIntent = Intent(this, PubSubService::class.java)
        stopService(serviceIntent)
        
        // Wait a bit for service to stop, then update UI
        binding.root.postDelayed({
            updateServiceStatus()
        }, 200)
        
        Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
    }
    
    private fun updateServiceStatus() {
        val isRunning = configurationManager.isServiceRunning
        val enabledConfigs = configurationManager.getEnabledConfigurations().size
        
        binding.statusText.text = if (isRunning) {
            "Status: Running ($enabledConfigs subscription${if (enabledConfigs != 1) "s" else ""})"
        } else {
            "Status: Stopped"
        }
        
        binding.toggleServiceButton.text = if (isRunning) {
            getString(R.string.stop_service)
        } else {
            getString(R.string.start_service)
        }
    }
    
    private fun refreshConfigurations() {
        val configurations = configurationManager.getConfigurations()
        configurationAdapter.setConfigurations(configurations)
        
        binding.noConfigurationsText.visibility = if (configurations.isEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }
    
    private fun refreshDebugLogs() {
        val logs = configurationManager.structuredLogs
        debugLogAdapter.setLogs(logs)
        debugLogAdapter.setFilter(currentLogFilter)
        updateDebugStats()
    }
    
    private fun loadDebugLogsAsync() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Load logs on background thread
                val logs = configurationManager.structuredLogs
                
                // Switch back to main thread for UI updates
                withContext(Dispatchers.Main) {
                    debugLogAdapter.setLogs(logs)
                    debugLogAdapter.setFilter(currentLogFilter)
                    updateDebugStats(logs)
                }
            } catch (e: Exception) {
                // Handle any errors gracefully
                withContext(Dispatchers.Main) {
                    binding.debugStatsText.text = "0/${currentLogFilter.maxLogs} logs"
                    binding.noLogsText.visibility = View.VISIBLE
                }
            }
        }
    }
    
    private fun updateDebugStats(logs: List<StructuredLogEntry>? = null) {
        val logsList = logs ?: configurationManager.structuredLogs
        val filteredCount = logsList.filter { currentLogFilter.passes(it) }.take(currentLogFilter.maxLogs).size
        
        // Update debug stats: current/max format
        binding.debugStatsText.text = "$filteredCount/${currentLogFilter.maxLogs} logs"
        
        binding.noLogsText.visibility = if (filteredCount == 0) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }
    
    private fun updateDebugStatsQuick() {
        // Quick stats update using adapter's current count
        val currentCount = debugLogAdapter.itemCount
        binding.debugStatsText.text = "$currentCount/${currentLogFilter.maxLogs} logs"
        
        binding.noLogsText.visibility = if (currentCount == 0) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }
    
    private fun showLogFilterDialog() {
        val dialog = LogFilterDialog(this, currentLogFilter) { newFilter ->
            currentLogFilter = newFilter
            
            // Save filter preferences for persistence
            settingsManager.saveLogFilter(newFilter)
            
            // Update both adapter and logger for performance filtering
            debugLogAdapter.setFilter(newFilter)
            (unifiedLogger as? UnifiedLoggerImpl)?.setFilter(newFilter)
            
            updateDebugStats()
        }
        dialog.show()
    }
    
    private fun showDeleteConfirmationDialog(configuration: Configuration) {
        AlertDialog.Builder(this)
            .setTitle("@string/delete_subscription")
            .setMessage("Are you sure you want to delete \"${configuration.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                configurationManager.deleteConfiguration(configuration.id)
                
                // Notify service to sync configurations if it's running
                if (configurationManager.isServiceRunning) {
                    syncServiceConfigurations()
                }
                
                refreshConfigurations()
                updateServiceStatus()
                Toast.makeText(this, "@string/subscription_deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission already granted
                }
                else -> {
                    // Request permission
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        
        // Battery optimization: Track app resume time and send state change
        val currentTime = System.currentTimeMillis()
        val backgroundDuration = if (appPausedTime > 0) currentTime - appPausedTime else 0L
        appResumedTime = currentTime
        
        // Log app state transition
        unifiedLogger.info(LogDomain.UI, "App resumed (background for ${backgroundDuration}ms)")
        
        // Notify service about app state change
        sendAppStateChangeToService("FOREGROUND", backgroundDuration)
        
        // Check for stale service state and fix it
        checkAndFixServiceState()
        
        updateServiceStatus()
        refreshConfigurations()
        // Load debug logs asynchronously to avoid blocking UI on resume
        loadDebugLogsAsync()
    }
    
    override fun onPause() {
        super.onPause()
        
        // Battery optimization: Track app pause time and send state change
        val currentTime = System.currentTimeMillis()
        val foregroundDuration = if (appResumedTime > 0) currentTime - appResumedTime else 0L
        appPausedTime = currentTime
        
        // Log app state transition
        unifiedLogger.info(LogDomain.UI, "App paused (foreground for ${foregroundDuration}ms)")
        
        // Notify service about app state change
        sendAppStateChangeToService("BACKGROUND", foregroundDuration)
    }
    
    /**
     * Check if the service is actually running and fix stale state
     */
    private fun checkAndFixServiceState() {
        val storedState = configurationManager.isServiceRunning
        val actuallyRunning = isServiceActuallyRunning()
        
        if (storedState != actuallyRunning) {
            unifiedLogger.warn(LogDomain.SERVICE, "Service state mismatch detected - stored: $storedState, actual: $actuallyRunning")
            
            if (storedState && !actuallyRunning) {
                // App thinks service is running but it's not (service was killed)
                configurationManager.isServiceRunning = false
                unifiedLogger.error(LogDomain.SERVICE, "Service was killed, state corrected")
                
                if (configurationManager.hasValidEnabledConfigurations()) {
                    unifiedLogger.info(LogDomain.SERVICE, "Auto-restarting service after it was killed")
                    // Give the system a moment, then restart the service
                    binding.root.postDelayed({
                        if (!isServiceActuallyRunning()) {
                            startPubSubService()
                            Toast.makeText(this@MainActivity, "Service restarted after system kill", Toast.LENGTH_SHORT).show()
                        }
                    }, 1000)
                }
            } else if (!storedState && actuallyRunning) {
                // Service is running but app doesn't think it is (service auto-restarted)
                configurationManager.isServiceRunning = true
                unifiedLogger.info(LogDomain.SERVICE, "Found running service, updated state")
                Toast.makeText(this, "Service automatically restarted", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Check if PubSubService is actually running
     */
    private fun isServiceActuallyRunning(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in activityManager.getRunningServices(Integer.MAX_VALUE)) {
            if (PubSubService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    /**
     * Sync service configurations with current enabled configurations
     */
    private fun syncServiceConfigurations() {
        try {
            val serviceIntent = Intent(this, PubSubService::class.java).apply {
                action = "SYNC_CONFIGURATIONS"
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            unifiedLogger.info(LogDomain.SERVICE, "Requested configuration sync")
        } catch (e: Exception) {
            unifiedLogger.error(LogDomain.SERVICE, "Failed to sync configurations: ${e.message}")
        }
    }
    
    /**
     * Send app state change broadcast to service for battery optimization
     */
    private fun sendAppStateChangeToService(appState: String, duration: Long) {
        try {
            val intent = Intent(ACTION_APP_STATE_CHANGE).apply {
                putExtra(EXTRA_APP_STATE, appState)
                putExtra(EXTRA_STATE_DURATION, duration)
                setPackage(packageName)
            }
            sendBroadcast(intent)
            
            unifiedLogger.info(LogDomain.UI, "Sent app state change: $appState (duration: ${duration}ms)")
        } catch (e: Exception) {
            unifiedLogger.error(LogDomain.UI, "Failed to send app state change: ${e.message}")
        }
    }


    
    /**
     * Handle deep link intents for registering filter configurations
     */
    private fun handleDeepLinkIntent(intent: Intent) {
        val data = intent.data
        
        // Only log when there's actually intent data to process
        if (data == null) {
            // Normal app launch - no need to log anything
            return
        }
        
        unifiedLogger.debug(LogDomain.UI, "handleDeepLinkIntent called with data: $data")
        
        if (!DeepLinkHandler.isPubSubDeepLink(data)) {
            unifiedLogger.debug(LogDomain.UI, "Not a pubsub deep link: $data")
            return
        }
        
        unifiedLogger.info(LogDomain.UI, "Processing pubsub deep link: $data")
        
        val result = DeepLinkHandler.parseRegisterDeepLink(data)
        
        if (result.success && result.configuration != null) {
            unifiedLogger.info(LogDomain.UI, "Deep link parsed successfully: ${result.configuration.name}")
            showRegisterConfigurationDialog(result.configuration)
        } else {
            val errorMessage = result.errorMessage ?: "Unknown error parsing deep link"
            unifiedLogger.error(LogDomain.UI, "Deep link parsing failed: $errorMessage")
            showDeepLinkErrorDialog(errorMessage)
        }
    }
    
    /**
     * Show dialog to confirm registration of a new configuration from deep link
     */
    private fun showRegisterConfigurationDialog(configuration: Configuration) {
        val message = buildString {
            append("Register new subscription?\n\n")
            append("Name: ${configuration.name}\n")
            append("Relays: ${configuration.relayUrls.size} relay(s)\n")
            append("Target URI: ${configuration.targetUri}\n")
            append("Filter: ${configuration.filter.getSummary()}")
        }
        
        AlertDialog.Builder(this)
            .setTitle("Register Subscription")
            .setMessage(message)
            .setPositiveButton("Register") { _, _ ->
                registerConfigurationFromDeepLink(configuration)
            }
            .setNegativeButton("Cancel") { _, _ ->
                unifiedLogger.info(LogDomain.UI, "Deep link subscription registration cancelled")
            }
            .show()
    }
    
    /**
     * Register the configuration from deep link
     */
    private fun registerConfigurationFromDeepLink(configuration: Configuration) {
        try {
            // Check if a configuration with the same name already exists
            val existingConfigurations = configurationManager.getConfigurations()
            val existingConfig = existingConfigurations.find { it.name == configuration.name }
            
            if (existingConfig != null) {
                // Show dialog to ask if they want to update the existing configuration
                showUpdateConfigurationDialog(configuration, existingConfig)
            } else {
                // Add new configuration
                configurationManager.addConfiguration(configuration)
                refreshConfigurations()
                updateServiceStatus()
                
                unifiedLogger.info(LogDomain.SUBSCRIPTION, "Registered new subscription: ${configuration.name}")
                Toast.makeText(this, "Subscription '${configuration.name}' registered successfully!", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            unifiedLogger.error(LogDomain.SUBSCRIPTION, "Error registering subscription: ${e.message}")
            Toast.makeText(this, "Error registering subscription: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * Show dialog to ask if user wants to update existing configuration
     */
    private fun showUpdateConfigurationDialog(newConfiguration: Configuration, existingConfiguration: Configuration) {
        AlertDialog.Builder(this)
            .setTitle("Subscription Exists")
            .setMessage("A subscription named '${newConfiguration.name}' already exists. Do you want to update it?")
            .setPositiveButton("Update") { _, _ ->
                // Update existing configuration with new data but keep the same ID
                val updatedConfiguration = newConfiguration.copy(id = existingConfiguration.id)
                configurationManager.updateConfiguration(updatedConfiguration)
                refreshConfigurations()
                updateServiceStatus()
                
                unifiedLogger.info(LogDomain.SUBSCRIPTION, "Updated existing subscription: ${newConfiguration.name}")
                Toast.makeText(this, "Subscription '${newConfiguration.name}' updated successfully!", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Keep Existing") { _, _ ->
                unifiedLogger.info(LogDomain.SUBSCRIPTION, "Kept existing subscription: ${newConfiguration.name}")
            }
            .show()
    }
    
    /**
     * Show error dialog for deep link parsing errors
     */
    private fun showDeepLinkErrorDialog(errorMessage: String) {
        unifiedLogger.error(LogDomain.UI, "Deep link error: $errorMessage")
        
        AlertDialog.Builder(this)
            .setTitle("Deep Link Error")
            .setMessage("Error processing registration link:\n\n$errorMessage")
            .setPositiveButton("OK", null)
            .show()
    }
    
    /**
     * Update debug console visibility based on settings
     */
    private fun updateDebugConsoleVisibility() {
        val shouldShow = settingsManager.shouldShowDebugConsole()
        
        // Find the debug console card view by ID
        val debugConsoleCard = findViewById<View>(R.id.debugConsoleCard)
        debugConsoleCard?.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }
    
    /**
     * Export debug logs to a text file
     */
    private fun exportDebugLogs() {
        // Check current adapter count for quick validation
        if (debugLogAdapter.itemCount == 0) {
            Toast.makeText(this, "No debug logs to export", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Generate filename with current date/time
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val filename = "pubsub_debug_logs_$timestamp.txt"
        
        // Launch file picker
        exportLogsLauncher.launch(filename)
    }
    
    /**
     * Write debug logs to the selected file
     */
    private fun exportDebugLogsToFile(uri: android.net.Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    val content = configurationManager.getFormattedDebugLogsForExport(currentLogFilter)
                    outputStream.write(content.toByteArray())
                    outputStream.flush()
                }
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Debug logs exported successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to export debug logs: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error exporting debug logs: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    // SettingsChangeListener implementation
    override fun onBatteryModeChanged(newMode: BatteryMode) {
        // MainActivity doesn't need to handle battery mode changes directly
        // This is handled by the service components
    }
    
    override fun onNotificationFrequencyChanged(newFrequency: NotificationFrequency) {
        // MainActivity doesn't need to handle notification frequency changes directly
        // This is handled by the service components
    }
    
    override fun onDebugConsoleVisibilityChanged(visible: Boolean) {
        // Update debug console visibility when settings change
        updateDebugConsoleVisibility()
    }
}
