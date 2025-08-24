package com.cmdruid.pubsub.ui

import android.Manifest
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

import androidx.recyclerview.widget.LinearLayoutManager
import com.cmdruid.pubsub.R
import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.data.ConfigurationManager
import com.cmdruid.pubsub.databinding.ActivityMainBinding
import com.cmdruid.pubsub.service.PubSubService
import com.cmdruid.pubsub.ui.adapters.ConfigurationAdapter
import com.cmdruid.pubsub.ui.adapters.DebugLogAdapter
import com.cmdruid.pubsub.utils.DeepLinkHandler

class MainActivity : AppCompatActivity() {
    
    companion object {
        const val ACTION_DEBUG_LOG = "com.cmdruid.pubsub.DEBUG_LOG"
        const val EXTRA_LOG_MESSAGE = "log_message"
    }
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var configurationManager: ConfigurationManager
    private lateinit var configurationAdapter: ConfigurationAdapter
    private lateinit var debugLogAdapter: DebugLogAdapter
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission is required for the app to work properly", Toast.LENGTH_LONG).show()
        }
    }
    
    private val debugLogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_DEBUG_LOG -> {
                    val message = intent.getStringExtra(EXTRA_LOG_MESSAGE) ?: return
                    configurationManager.addDebugLog(message)
                    refreshDebugLogs()
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
        
        setupToolbar()
        setupConfigurationsRecyclerView()
        setupDebugLogsRecyclerView()
        setupUI()
        requestNotificationPermission()
        updateServiceStatus()
        refreshConfigurations()
        refreshDebugLogs()
        
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
                val updatedConfiguration = configuration.copy(isEnabled = enabled)
                configurationManager.updateConfiguration(updatedConfiguration)
                
                // Notify service to sync configurations if it's running
                if (configurationManager.isServiceRunning) {
                    syncServiceConfigurations()
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
            

            
            refreshConnectionsButton.setOnClickListener {
                if (configurationManager.isServiceRunning) {
                    refreshServiceConnections()
                    Toast.makeText(this@MainActivity, "Refreshing connections...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Service is not running", Toast.LENGTH_SHORT).show()
                }
            }
            
            clearLogsButton.setOnClickListener {
                configurationManager.clearDebugLogs()
                refreshDebugLogs()
            }
            
            // Add sample deep link generation for testing (long press on clear logs button)
            clearLogsButton.setOnLongClickListener {
                val sampleDeepLink = DeepLinkHandler.generateSampleDeepLink()
                configurationManager.addDebugLog("Sample deep link: $sampleDeepLink")
                refreshDebugLogs()
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
        
        configurationManager.isServiceRunning = true
        updateServiceStatus()
        Toast.makeText(this, "Service started with ${configurationManager.getEnabledConfigurations().size} subscription(s)", Toast.LENGTH_SHORT).show()
    }
    
    private fun stopPubSubService() {
        val serviceIntent = Intent(this, PubSubService::class.java)
        stopService(serviceIntent)
        
        configurationManager.isServiceRunning = false
        updateServiceStatus()
        Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
    }
    
    private fun updateServiceStatus() {
        val isRunning = configurationManager.isServiceRunning
        val enabledConfigs = configurationManager.getEnabledConfigurations().size
        
        binding.statusText.text = if (isRunning) {
            "Service Status: Running ($enabledConfigs subscription${if (enabledConfigs != 1) "s" else ""})"
        } else {
            "Service Status: Stopped"
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
        val logs = configurationManager.debugLogs
        debugLogAdapter.setLogs(logs)
        
        // Update debug stats
        binding.debugStatsText.text = configurationManager.getDebugLogStats()
        
        binding.noLogsText.visibility = if (logs.isEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
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
        updateServiceStatus()
        refreshConfigurations()
        refreshDebugLogs()
        
        // Refresh WebSocket connections if service is running
        if (configurationManager.isServiceRunning) {
            refreshServiceConnections()
        }
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
            
            configurationManager.addDebugLog("🔄 Requested configuration sync")
        } catch (e: Exception) {
            configurationManager.addDebugLog("❌ Failed to sync configurations: ${e.message}")
        }
    }

    /**
     * Refresh service WebSocket connections to ensure they're active
     */
    private fun refreshServiceConnections() {
        try {
            val serviceIntent = Intent(this, PubSubService::class.java).apply {
                action = "REFRESH_CONNECTIONS"
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            configurationManager.addDebugLog("🔄 Requested connection refresh")
        } catch (e: Exception) {
            configurationManager.addDebugLog("❌ Failed to refresh connections: ${e.message}")
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
        
        configurationManager.addDebugLog("handleDeepLinkIntent called with data: $data")
        
        if (!DeepLinkHandler.isPubSubDeepLink(data)) {
            configurationManager.addDebugLog("Not a pubsub deep link: $data")
            return
        }
        
        configurationManager.addDebugLog("Processing pubsub deep link: $data")
        
        val result = DeepLinkHandler.parseRegisterDeepLink(data)
        
        if (result.success && result.configuration != null) {
            configurationManager.addDebugLog("Deep link parsed successfully: ${result.configuration.name}")
            showRegisterConfigurationDialog(result.configuration)
        } else {
            val errorMessage = result.errorMessage ?: "Unknown error parsing deep link"
            configurationManager.addDebugLog("Deep link parsing failed: $errorMessage")
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
                configurationManager.addDebugLog("Deep link subscription registration cancelled")
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
                
                configurationManager.addDebugLog("Registered new subscription: ${configuration.name}")
                Toast.makeText(this, "Subscription '${configuration.name}' registered successfully!", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            configurationManager.addDebugLog("Error registering subscription: ${e.message}")
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
                
                configurationManager.addDebugLog("Updated existing subscription: ${newConfiguration.name}")
                Toast.makeText(this, "Subscription '${newConfiguration.name}' updated successfully!", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Keep Existing") { _, _ ->
                configurationManager.addDebugLog("Kept existing subscription: ${newConfiguration.name}")
            }
            .show()
    }
    
    /**
     * Show error dialog for deep link parsing errors
     */
    private fun showDeepLinkErrorDialog(errorMessage: String) {
        configurationManager.addDebugLog("Deep link error: $errorMessage")
        
        AlertDialog.Builder(this)
            .setTitle("Deep Link Error")
            .setMessage("Error processing registration link:\n\n$errorMessage")
            .setPositiveButton("OK", null)
            .show()
    }
}
