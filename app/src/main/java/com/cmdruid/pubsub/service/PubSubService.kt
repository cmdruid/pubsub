package com.cmdruid.pubsub.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.cmdruid.pubsub.data.ConfigurationManager
import com.cmdruid.pubsub.data.SettingsManager
import com.cmdruid.pubsub.ui.MainActivity
import com.cmdruid.pubsub.logging.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class PubSubService : Service() {
    
    companion object {
        private const val TAG = "PubSubService"
        private const val NOTIFICATION_ID = 1001
    }
    
    enum class AppState {
        FOREGROUND,    // App is actively being used
        BACKGROUND,    // App is in background but not in doze
        DOZE,          // Device is in doze mode
        RARE,          // App in rare standby bucket (infrequent usage)
        RESTRICTED     // App in restricted standby bucket (heavily limited)
    }
    
    // Core service components
    private lateinit var configurationManager: ConfigurationManager
    private lateinit var settingsManager: SettingsManager
    private lateinit var metricsCollector: MetricsCollector
    private lateinit var unifiedLogger: UnifiedLogger
    private var serviceJob: Job? = null
    
    // Clean component architecture with proper dependency injection
    private lateinit var batteryPowerManager: BatteryPowerManager
    private lateinit var networkManager: NetworkManager
    private lateinit var eventNotificationManager: EventNotificationManager
    private lateinit var relayConnectionManager: RelayConnectionManager
    private lateinit var messageProcessor: MessageProcessor
    private lateinit var healthMonitor: HealthMonitor
    
    // Core data management
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var eventCache: EventCache
    
    // Broadcast receiver for app state changes
    private val appStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MainActivity.ACTION_APP_STATE_CHANGE -> {
                    val newStateString = intent.getStringExtra(MainActivity.EXTRA_APP_STATE) ?: return
                    val duration = intent.getLongExtra(MainActivity.EXTRA_STATE_DURATION, 0L)
                    
                    val newState = when (newStateString) {
                        "FOREGROUND" -> AppState.FOREGROUND
                        "BACKGROUND" -> AppState.BACKGROUND
                        "DOZE" -> AppState.DOZE
                        "RARE" -> AppState.RARE
                        "RESTRICTED" -> AppState.RESTRICTED
                        else -> return
                    }
                    
                    batteryPowerManager.handleAppStateChange(newState, duration)
                }
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        configurationManager = ConfigurationManager(this)
        settingsManager = SettingsManager(this)
        unifiedLogger = UnifiedLoggerImpl(this, configurationManager)
        
        // NEW: Metrics collector for service data collection
        metricsCollector = MetricsCollector(this, settingsManager)
        
        // Clear metrics data if metrics are disabled
        if (!settingsManager.isMetricsCollectionActive()) {
            metricsCollector.clearAllData()
        }
        
        // Initialize core data management components
        subscriptionManager = SubscriptionManager(this)
        eventCache = EventCache(this)
        
        // Initialize component managers
        setupComponentManagers()
        
        // Update service state when service is actually created
        configurationManager.isServiceRunning = true
        
        // Register for app state change broadcasts
        val filter = IntentFilter(MainActivity.ACTION_APP_STATE_CHANGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(appStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(appStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        }
        
        unifiedLogger.info(LogDomain.SERVICE, "Service created")
    }

    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        when (action) {
            "REFRESH_CONNECTIONS" -> {
                unifiedLogger.info(LogDomain.NETWORK, "Connection refresh requested")
                relayConnectionManager.refreshConnections()
                return START_STICKY
            }
            "SYNC_CONFIGURATIONS" -> {
                unifiedLogger.info(LogDomain.SERVICE, "Configuration sync requested")
                relayConnectionManager.syncConfigurations()
                updateForegroundNotification()
                return START_STICKY
            }
            "TEST_CONNECTION_HEALTH" -> {
                unifiedLogger.debug(LogDomain.HEALTH, "Manual connection health test requested")
                healthMonitor.runHealthCheck()
                return START_STICKY
            }
            "FORCE_RECONNECT_ALL" -> {
                unifiedLogger.debug(LogDomain.HEALTH, "Force reconnect all connections requested")
                relayConnectionManager.refreshConnections()
                return START_STICKY
            }
            "LOG_DETAILED_STATS" -> {
                unifiedLogger.debug(LogDomain.SYSTEM, "Detailed stats logging requested")
                logDetailedServiceStats()
                return START_STICKY
            }
            else -> {
                unifiedLogger.info(LogDomain.SERVICE, "Service started")
                startForeground(NOTIFICATION_ID, createForegroundNotification())
            }
        }

        serviceJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Load persistent state for cross-session continuity
                subscriptionManager.loadPersistedTimestamps()
                eventCache.loadPersistentCache()
                
                // Clean up any orphaned subscriptions before starting
                cleanupOrphanedSubscriptions()
                
                relayConnectionManager.connectToAllRelays()
                
                // Start MODERN health monitoring
                healthMonitor.start()
                
                // Log initial stats
                logServiceStats()
            } catch (e: Exception) {
                unifiedLogger.error(LogDomain.SERVICE, "Service startup error: ${e.message}")
                Log.e(TAG, "Service startup failed", e)
            }
        }
        
        return START_STICKY // Restart if killed
    }
    
    override fun onDestroy() {
        unifiedLogger.info(LogDomain.SERVICE, "Service destroyed")
        
        serviceJob?.cancel()
        // DELETED: healthMonitorJob?.cancel() - now handled by HealthMonitor.stop()
        
        // Clean up component managers
        cleanupComponentManagers()
        
        // Clean up metrics collector
        metricsCollector.cleanup()
        
        // Clean up subscription management
        subscriptionManager.clearAll()
        eventCache.clear()
        
        // Always update the service state when service is actually destroyed
        configurationManager.isServiceRunning = false
        
        // Unregister broadcast receivers
        try {
            unregisterReceiver(appStateReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering app state receiver: ${e.message}")
        }
        
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    /**
     * Initialize all component managers
     */
    private fun setupComponentManagers() {
        // Initialize battery and power management
        batteryPowerManager = BatteryPowerManager(
            context = this,
            metricsCollector = metricsCollector,
            settingsManager = settingsManager,
            onAppStateChange = { newState, duration -> 
                // Handle app state changes that affect other components
                relayConnectionManager.updatePingInterval(batteryPowerManager.getCurrentPingInterval())
            },
            onPingIntervalChange = {
                // Update relay connections when ping interval changes
                relayConnectionManager.updatePingInterval(batteryPowerManager.getCurrentPingInterval())
            },
            sendDebugLog = { message -> unifiedLogger.debug(LogDomain.BATTERY, message) }
        )
        
        // Initialize network management
        networkManager = NetworkManager(
            context = this,
            metricsCollector = metricsCollector,
            onNetworkStateChange = { available, networkType, quality ->
                // Handle network state changes
                if (available) {
                    relayConnectionManager.refreshConnections()
                }
            },
            onRefreshConnections = {
                relayConnectionManager.refreshConnections()
            },
            sendDebugLog = { message -> unifiedLogger.debug(LogDomain.NETWORK, message) }
        )
        
        // Initialize event notification management
        eventNotificationManager = EventNotificationManager(
            context = this,
            settingsManager = settingsManager,
            sendDebugLog = { message -> unifiedLogger.debug(LogDomain.NOTIFICATION, message) },
            unifiedLogger = unifiedLogger
        )
        
        // Initialize relay connection management
        relayConnectionManager = RelayConnectionManager(
            configurationManager = configurationManager,
            subscriptionManager = subscriptionManager,
            batteryPowerManager = batteryPowerManager,
            networkManager = networkManager,
            onMessageReceived = { messageText, subscriptionId, relayUrl ->
                messageProcessor.processMessage(messageText, subscriptionId, relayUrl)
            },
            sendDebugLog = { message -> unifiedLogger.debug(LogDomain.NETWORK, message) }
        )
        
        // Initialize message processor
        messageProcessor = MessageProcessor(
            configurationManager = configurationManager,
            subscriptionManager = subscriptionManager,
            eventCache = eventCache,
            eventNotificationManager = eventNotificationManager,
            unifiedLogger = unifiedLogger,
            sendDebugLog = { message -> unifiedLogger.debug(LogDomain.EVENT, message) }
        )
        
        // Initialize ENHANCED health monitoring component
        healthMonitor = HealthMonitor(
            relayConnectionManager = relayConnectionManager,
            batteryPowerManager = batteryPowerManager,
            metricsCollector = metricsCollector,
            networkManager = networkManager,
            unifiedLogger = unifiedLogger
        )
        
        // Initialize all components
        batteryPowerManager.initialize()
        networkManager.initialize()
        eventNotificationManager.initialize()
        // RelayConnectionManager and HealthMonitor don't need explicit initialization
    }
    
    /**
     * Clean up all component managers
     */
    private fun cleanupComponentManagers() {
        healthMonitor.stop()
        batteryPowerManager.cleanup()
        networkManager.cleanup()
        eventNotificationManager.cleanup()
        relayConnectionManager.disconnectFromAllRelays()
    }
    
    private fun createForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val subscriptionCount = configurationManager.getEnabledConfigurations().size
        return eventNotificationManager.createForegroundNotification(pendingIntent, subscriptionCount)
    }
    
    /**
     * Update the foreground notification with current subscription count
     */
    private fun updateForegroundNotification() {
        val notification = createForegroundNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
        unifiedLogger.info(LogDomain.NOTIFICATION, "Updated foreground notification with current subscription count")
    }
    
    /**
     * Clean up orphaned subscriptions that no longer have valid configurations
     */
    private fun cleanupOrphanedSubscriptions() {
        val validConfigurationIds = configurationManager.getConfigurations()
            .filter { it.isEnabled }
            .map { it.id }
            .toSet()
        
        subscriptionManager.cleanupOrphanedSubscriptions(validConfigurationIds)
        unifiedLogger.info(LogDomain.SUBSCRIPTION, "Cleaned up orphaned subscriptions")
    }
    
    /**
     * Get service statistics for debugging with enhanced connection diagnostics
     */
    private fun logServiceStats() {
        val subscriptionStats = subscriptionManager.getStats()
        val cacheStats = eventCache.getStats()
        val currentTime = System.currentTimeMillis()
        
        unifiedLogger.info(LogDomain.SYSTEM, "Service Stats", mapOf(
            "relay_connections" to relayConnectionManager.getRelayConnections().size,
            "active_subscriptions" to subscriptionStats.activeCount,
            "event_cache" to cacheStats,
            "app_state" to batteryPowerManager.getCurrentAppState().name,
            "ping_interval" to "${batteryPowerManager.getCurrentPingInterval()}s",
            "network_type" to networkManager.getCurrentNetworkType(),
            "network_available" to networkManager.isNetworkAvailable(),
            "battery_level" to "${batteryPowerManager.getCurrentBatteryLevel()}%",
            "charging" to batteryPowerManager.isCharging()
        ))
        
        // Enhanced connection diagnostics using new architecture
        relayConnectionManager.getConnectionHealth().forEach { (relayUrl, health) ->
            val shortUrl = relayUrl.substringAfter("://").take(20)
            
            unifiedLogger.debug(LogDomain.RELAY, "Connection health: $shortUrl", mapOf(
                "state" to health.state.name,
                "healthy" to health.isHealthy(),
                "last_message_age_ms" to health.lastMessageAge,
                "reconnect_attempts" to health.reconnectAttempts,
                "subscription_confirmed" to health.subscriptionConfirmed,
                "status" to health.getShortStatus()
            ))
        }
        
        // Update battery metrics and log effectiveness  
        metricsCollector.trackBatteryOptimization("periodic_update", batteryPowerManager.getCurrentBatteryLevel(), true)
        logOptimizationEffectiveness()
        
        // Check app standby bucket periodically
        batteryPowerManager.checkStandbyBucket()
    }
    
    // DELETED: startHealthMonitor method - replaced by dedicated HealthMonitor component
    
    /**
     * Log battery optimization effectiveness using modern metrics
     */
    private fun logOptimizationEffectiveness() {
        // Use coroutine to avoid blocking main thread
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Create a temporary reader for generating reports
                val metricsReader = MetricsReader(this@PubSubService, settingsManager)
                metricsReader.generateMetricsReport()?.let { report ->
                    unifiedLogger.info(LogDomain.BATTERY, "Modern Metrics Report Generated", mapOf(
                        "generation_time_ms" to report.generationTimeMs,
                        "battery_optimizations" to (report.batteryReport?.optimizationsApplied ?: 0L),
                        "connection_success_rate" to (report.connectionReport?.connectionSuccessRate ?: 0.0),
                        "duplicate_prevention_rate" to (report.duplicateEventReport?.preventionRate ?: 0.0)
                    ))
                }
                metricsReader.cleanup()
            } catch (e: Exception) {
                unifiedLogger.error(LogDomain.BATTERY, "Error generating modern metrics report: ${e.message}")
                Log.e(TAG, "Modern metrics report failed", e)
            }
        }
    }
    

    /**
     * Log connection health for debugging
     */
    private fun logConnectionHealth() {
        unifiedLogger.debug(LogDomain.HEALTH, "=== CONNECTION HEALTH TEST ===")
        
        val healthResults = relayConnectionManager.getConnectionHealth()
        healthResults.forEach { (relayUrl, health) ->
            val shortUrl = relayUrl.substringAfter("://").take(20)
            unifiedLogger.debug(LogDomain.HEALTH, "$shortUrl: ${health.state.name} - ${health.getShortStatus()}", mapOf(
                "healthy" to health.isHealthy(),
                "last_message_age_s" to health.lastMessageAge / 1000,
                "reconnect_attempts" to health.reconnectAttempts,
                "subscription_confirmed" to health.subscriptionConfirmed
            ))
        }
        
        unifiedLogger.debug(LogDomain.HEALTH, "=== END CONNECTION HEALTH TEST ===")
    }
    
    /**
     * Log detailed service statistics
     */
    private fun logDetailedServiceStats() {
        unifiedLogger.debug(LogDomain.SYSTEM, "=== DETAILED SERVICE STATS ===")
        
        // Service state
        unifiedLogger.debug(LogDomain.SYSTEM, "Service State", mapOf(
            "app_state" to batteryPowerManager.getCurrentAppState().name,
            "ping_interval" to "${batteryPowerManager.getCurrentPingInterval()}s",
            "network_type" to networkManager.getCurrentNetworkType(),
            "network_available" to networkManager.isNetworkAvailable(),
            "battery_level" to "${batteryPowerManager.getCurrentBatteryLevel()}%",
            "charging" to batteryPowerManager.isCharging(),
            "doze_mode" to batteryPowerManager.isDozeMode()
        ))
        
        // Subscription stats
        val subscriptionStats = subscriptionManager.getStats()
        unifiedLogger.debug(LogDomain.SYSTEM, "Subscription Stats", mapOf(
            "active_subscriptions" to subscriptionStats.activeCount,
            "relay_count" to subscriptionStats.relayCount,
            "total_events" to subscriptionStats.totalEvents,
            "timestamp_count" to subscriptionStats.timestampCount
        ))
        
        // Connection health
        logConnectionHealth()
        
        unifiedLogger.debug(LogDomain.SYSTEM, "=== END DETAILED STATS ===")
    }
}
