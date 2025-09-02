package com.cmdruid.pubsub.ui

import android.Manifest
import com.cmdruid.pubsub.BuildConfig
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.cmdruid.pubsub.data.BatteryMode
import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.data.ConfigurationManager
import com.cmdruid.pubsub.data.ImportExportManager
import com.cmdruid.pubsub.data.NotificationFrequency
import com.cmdruid.pubsub.data.SettingsManager
import com.cmdruid.pubsub.databinding.ActivityMainBinding
import com.cmdruid.pubsub.service.MetricsReader
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
    private lateinit var importExportManager: ImportExportManager
    private lateinit var configurationAdapter: ConfigurationAdapter
    private lateinit var debugLogAdapter: DebugLogAdapter
    private lateinit var unifiedLogger: UnifiedLogger
    private lateinit var metricsReader: MetricsReader
    private var currentLogFilter = LogFilter.DEFAULT
    private var metricsUpdateJob: Job? = null
    
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
    
    private val exportMetricsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { 
            exportMetricsToFile(it)
        }
    }
    
    private val exportSubscriptionsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { 
            exportSubscriptionsToFile(it)
        }
    }
    
    private val importSubscriptionsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { 
            importSubscriptionsFromFile(it)
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
        importExportManager = ImportExportManager(this, configurationManager)
        unifiedLogger = UnifiedLoggerImpl(this, configurationManager)
        metricsReader = MetricsReader(this, settingsManager)
        
        // Load saved filter preferences
        currentLogFilter = settingsManager.getLogFilter()
        
        // Set initial filter for performance filtering
        (unifiedLogger as? UnifiedLoggerImpl)?.setFilter(currentLogFilter)
        
        setupToolbar()
        setupConfigurationsRecyclerView()
        setupDebugLogsRecyclerView()
        setupMetricsCard()
        setupUI()
        setupVersionDisplay()
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
        
        // Clean up metrics updates and manager
        stopMetricsUpdates()
        metricsReader.cleanup()
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
            
            // Metrics card button handlers
            saveMetricsButton.setOnClickListener {
                saveMetricsData()
            }
            
            clearMetricsButton.setOnClickListener {
                clearMetricsData()
            }
            
            // Add sample deep link generation for testing (long press on clear logs button)
            clearLogsButton.setOnLongClickListener {
                val sampleDeepLink = DeepLinkHandler.generateSampleDeepLink()
                unifiedLogger.info(LogDomain.UI, "Sample deep link: $sampleDeepLink")
                // No need to refresh - the unified logger will broadcast the entry automatically
                Toast.makeText(this@MainActivity, "Sample deep link added to debug logs", Toast.LENGTH_SHORT).show()
                true
            }
            
            // Export and import subscription button handlers
            exportSubscriptionButton.setOnClickListener {
                exportSubscriptions()
            }
            
            importSubscriptionButton.setOnClickListener {
                importSubscriptions()
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
        
        // Update save button state based on subscription count
        updateSaveButtonState(configurations.isEmpty())
    }
    
    private fun refreshDebugLogs() {
        val logs = configurationManager.structuredLogs
        debugLogAdapter.setLogs(logs)
        debugLogAdapter.setFilter(currentLogFilter)
        updateDebugStats()
    }
    
    private fun updateSaveButtonState(isEmpty: Boolean) {
        val saveButton = binding.exportSubscriptionButton
        val context = saveButton.context
        
        saveButton.isEnabled = !isEmpty
        
        if (isEmpty) {
            // Inactive state: Use Widget.App.Button.Outlined style (like Load button)
            saveButton.backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.transparent)
            saveButton.strokeColor = ContextCompat.getColorStateList(context, com.cmdruid.pubsub.R.color.terminal_orange_secondary)
            saveButton.setTextColor(ContextCompat.getColor(context, com.cmdruid.pubsub.R.color.terminal_orange_secondary))
            saveButton.alpha = 0.6f // Slightly dimmed when disabled
        } else {
            // Active state: Use Widget.App.Button style - "LIGHT UP"!
            saveButton.backgroundTintList = ContextCompat.getColorStateList(context, com.cmdruid.pubsub.R.color.terminal_orange_secondary)
            saveButton.strokeColor = null // Remove stroke for filled style
            saveButton.setTextColor(ContextCompat.getColor(context, android.R.color.black))
            saveButton.alpha = 1.0f // Full opacity when enabled
        }
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
        
        // IMPORTANT: Invalidate settings cache to pick up changes from settings screen
        settingsManager.invalidateCache()
        
        // Refresh configurations in case they were modified in other activities
        refreshConfigurations()
        
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
        // Load debug logs asynchronously to avoid blocking UI on resume
        loadDebugLogsAsync()
        
        // Update card visibility (in case settings changed)
        updateDebugConsoleVisibility()
        updateMetricsCardVisibility()
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
    
    /**
     * Setup metrics card functionality
     */
    private fun setupMetricsCard() {
        // Show/hide metrics card based on settings
        updateMetricsCardVisibility()
        
        // Start metrics updates if enabled
        if (settingsManager.isMetricsCollectionActive()) {
            startMetricsUpdates()
        }
    }
    
    /**
     * Update metrics card visibility based on settings
     */
    private fun updateMetricsCardVisibility() {
        val isEnabled = settingsManager.isMetricsCollectionActive()
        binding.metricsCard.visibility = if (isEnabled) View.VISIBLE else View.GONE
        
        if (!isEnabled) {
            // Stop updates when disabled
            stopMetricsUpdates()
        } else {
            // Start updates when enabled
            startMetricsUpdates()
        }
    }
    
    /**
     * Start periodic metrics updates (non-blocking)
     */
    private fun startMetricsUpdates() {
        // Cancel any existing job
        metricsUpdateJob?.cancel()
        
        metricsUpdateJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && settingsManager.isMetricsCollectionActive()) {
                try {
                    updateMetricsDisplay()
                    delay(5000) // Update every 5 seconds
                } catch (e: Exception) {
                    // Silently handle errors to avoid disrupting UI
                    delay(10000) // Wait longer on error
                }
            }
        }
    }
    
    /**
     * Stop metrics updates
     */
    private fun stopMetricsUpdates() {
        metricsUpdateJob?.cancel()
        metricsUpdateJob = null
    }
    
    /**
     * Update metrics display (runs in background, updates UI on main thread)
     */
    private suspend fun updateMetricsDisplay() = withContext(Dispatchers.IO) {
        // Generate report in background thread
        val report = metricsReader.generateMetricsReport()
        
        // Update UI on main thread
        withContext(Dispatchers.Main) {
            report?.let { updateMetricsUI(it) }
        }
    }
    
    /**
     * Update metrics UI elements (main thread only)
     */
    private fun updateMetricsUI(report: MetricsReader.MetricsReport) {
        try {
            // Update battery metrics
            report.batteryReport?.let { battery ->
                binding.batteryOptimizationsText.text = 
                    "${battery.optimizationsApplied} / ${battery.batteryChecks} (${String.format("%.1f", battery.optimizationRate)}%)"
            } ?: run {
                binding.batteryOptimizationsText.text = "-- / -- (---%)"
            }
            
            // Update connection metrics
            report.connectionReport?.let { connection ->
                binding.connectionSuccessText.text = 
                    "${connection.successfulConnections} / ${connection.connectionAttempts} (${String.format("%.1f", connection.connectionSuccessRate)}%)"
            } ?: run {
                binding.connectionSuccessText.text = "-- / -- (---%)"
            }
            
            // Update duplicate event metrics
            report.duplicateEventReport?.let { duplicate ->
                binding.duplicatesPreventedText.text = 
                    "${duplicate.duplicatesPrevented} / ${duplicate.duplicatesDetected} (${String.format("%.1f", duplicate.preventionRate)}%)"
                
                // Format network data saved
                val dataSavedKB = duplicate.networkDataSavedBytes / 1024.0
                binding.networkDataSavedText.text = when {
                    dataSavedKB >= 1024 -> "${String.format("%.1f", dataSavedKB / 1024)} MB"
                    dataSavedKB >= 1 -> "${String.format("%.1f", dataSavedKB)} KB"
                    else -> "${duplicate.networkDataSavedBytes} B"
                }
            } ?: run {
                binding.duplicatesPreventedText.text = "-- / -- (---%)"
                binding.networkDataSavedText.text = "-- KB"
            }
            
        } catch (e: Exception) {
            // Silently handle UI update errors
            unifiedLogger.warn(LogDomain.UI, "Error updating metrics UI: ${e.message}")
        }
    }
    
    /**
     * Save metrics data to file
     */
    private fun saveMetricsData() {
        if (!settingsManager.isMetricsCollectionActive()) {
            Toast.makeText(this, "Metrics are disabled", Toast.LENGTH_SHORT).show()
            return
        }
        
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val filename = "pubsub_metrics_$timestamp.json"
        exportMetricsLauncher.launch(filename)
    }
    
    /**
     * Clear all metrics data
     */
    private fun clearMetricsData() {
        if (!settingsManager.isMetricsCollectionActive()) {
            Toast.makeText(this, "Metrics are disabled", Toast.LENGTH_SHORT).show()
            return
        }
        
        AlertDialog.Builder(this)
            .setTitle("Clear Metrics Data")
            .setMessage("This will delete all collected performance metrics. This action cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    metricsReader.clearAllData()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "All metrics data cleared", Toast.LENGTH_SHORT).show()
                        // Reset UI to show empty state
                        updateMetricsUI(MetricsReader.MetricsReport(
                            generatedAt = System.currentTimeMillis(),
                            generationTimeMs = 0,
                            batteryReport = null,
                            connectionReport = null,
                            duplicateEventReport = null
                        ))
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Export metrics to file
     */
    private fun exportMetricsToFile(uri: android.net.Uri) {
        if (!settingsManager.isMetricsCollectionActive()) {
            Toast.makeText(this, "Metrics are disabled", Toast.LENGTH_SHORT).show()
            return
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val report = metricsReader.generateMetricsReport()
                if (report == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "No metrics data to export", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                val metricsJson = com.google.gson.Gson().toJson(report)
                
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(metricsJson.toByteArray())
                    outputStream.flush()
                }
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Metrics data exported successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to export metrics: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error exporting metrics: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun exportSubscriptions() {
        // Check if there are subscriptions to save
        val configurations = configurationManager.getConfigurations()
        if (configurations.isEmpty()) {
            Toast.makeText(this, "No subscriptions to save", Toast.LENGTH_SHORT).show()
            return
        }
        
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val timestamp = dateFormat.format(Date())
        val filename = "pubsub_subscriptions_$timestamp.json"
        
        exportSubscriptionsLauncher.launch(filename)
    }
    
    private fun importSubscriptions() {
        importSubscriptionsLauncher.launch(arrayOf("application/json"))
    }
    
    private fun exportSubscriptionsToFile(uri: android.net.Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = importExportManager.exportConfigurations(uri)
                withContext(Dispatchers.Main) {
                    when (result) {
                        is com.cmdruid.pubsub.data.ExportResult.Success -> {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.subscription_saved_success),
                                Toast.LENGTH_SHORT
                            ).show()
                            unifiedLogger.info(LogDomain.UI, "Subscriptions exported: ${result.subscriptionCount} subscriptions to ${result.filename}")
                        }
                        is com.cmdruid.pubsub.data.ExportResult.Error -> {
                            Toast.makeText(
                                this@MainActivity,
                                "Export failed: ${result.message}",
                                Toast.LENGTH_LONG
                            ).show()
                            unifiedLogger.error(LogDomain.UI, "Subscription export failed: ${result.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error exporting subscriptions: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun importSubscriptionsFromFile(uri: android.net.Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = importExportManager.importConfigurations(uri, com.cmdruid.pubsub.data.ImportMode.ADD_NEW_ONLY)
                withContext(Dispatchers.Main) {
                    when (result) {
                        is com.cmdruid.pubsub.data.ImportResult.Success -> {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.subscription_loaded_success),
                                Toast.LENGTH_SHORT
                            ).show()
                            unifiedLogger.info(LogDomain.UI, "Subscriptions imported: ${result.importedCount} new, ${result.duplicateCount} duplicates, ${result.skippedCount} skipped")
                            refreshConfigurations()
                        }
                        is com.cmdruid.pubsub.data.ImportResult.Error -> {
                            Toast.makeText(
                                this@MainActivity,
                                "Import failed: ${result.message}",
                                Toast.LENGTH_LONG
                            ).show()
                            unifiedLogger.error(LogDomain.UI, "Subscription import failed: ${result.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error importing subscriptions: ${e.message}", Toast.LENGTH_LONG).show()
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
    
    /**
     * Setup version display at bottom of main screen
     */
    private fun setupVersionDisplay() {
        binding.versionText.text = "Version ${BuildConfig.VERSION_NAME}"
    }
}
