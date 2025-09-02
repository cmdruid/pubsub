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
import okhttp3.WebSocket
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class PubSubService : Service() {
    
    companion object {
        private const val TAG = "PubSubService"
        private const val NOTIFICATION_ID = 1001
        
        // Legacy constant for backward compatibility  
        private const val PING_INTERVAL_SECONDS = BatteryPowerManager.PING_INTERVAL_FOREGROUND_SECONDS
    }
    
    enum class AppState {
        FOREGROUND,    // App is actively being used
        BACKGROUND,    // App is in background but not in doze
        DOZE,          // Device is in doze mode
        RARE,          // App in rare standby bucket (infrequent usage)
        RESTRICTED     // App in restricted standby bucket (heavily limited)
    }
    
    data class RelayConnection(
        val relayUrl: String,
        val configurationId: String,
        var webSocket: WebSocket? = null,
        var subscriptionId: String? = null,
        var reconnectAttempts: Int = 0,
        var reconnectJob: Job? = null,
        var lastMessageTime: Long = System.currentTimeMillis(),
        var lastPingTime: Long = 0L,
        var subscriptionSentTime: Long = 0L,
        var subscriptionConfirmed: Boolean = false
    )
    
    private lateinit var configurationManager: ConfigurationManager
    private lateinit var settingsManager: SettingsManager
    private lateinit var batteryOptimizationLogger: BatteryOptimizationLogger
    private lateinit var batteryMetricsCollector: BatteryMetricsCollector
    private lateinit var networkOptimizationLogger: NetworkOptimizationLogger
    private lateinit var unifiedLogger: UnifiedLogger
    private var serviceJob: Job? = null
    private var healthMonitorJob: Job? = null
    
    // Component managers
    private lateinit var batteryPowerManager: BatteryPowerManager
    private lateinit var networkManager: NetworkManager
    private lateinit var eventNotificationManager: EventNotificationManager
    private lateinit var webSocketConnectionManager: WebSocketConnectionManager
    private lateinit var messageHandler: MessageHandler
    private lateinit var connectionHealthTester: ConnectionHealthTester
    
    // Enhanced subscription and event management
    private val subscriptionManager = SubscriptionManager()
    private val eventCache = EventCache()
    
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
        batteryOptimizationLogger = BatteryOptimizationLogger(this)
        batteryMetricsCollector = BatteryMetricsCollector(this)
        networkOptimizationLogger = NetworkOptimizationLogger(this)
        
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
                webSocketConnectionManager.refreshConnections()
                return START_STICKY
            }
            "SYNC_CONFIGURATIONS" -> {
                unifiedLogger.info(LogDomain.SERVICE, "Configuration sync requested")
                webSocketConnectionManager.syncConfigurations()
                updateForegroundNotification()
                return START_STICKY
            }
            "TEST_CONNECTION_HEALTH" -> {
                unifiedLogger.debug(LogDomain.HEALTH, "Manual connection health test requested")
                connectionHealthTester.testConnectionHealth()
                return START_STICKY
            }
            "FORCE_RECONNECT_ALL" -> {
                unifiedLogger.debug(LogDomain.HEALTH, "Force reconnect all connections requested")
                connectionHealthTester.forceReconnectAll()
                return START_STICKY
            }
            "LOG_DETAILED_STATS" -> {
                unifiedLogger.debug(LogDomain.SYSTEM, "Detailed stats logging requested")
                connectionHealthTester.logDetailedConnectionStats(
                    batteryPowerManager.getCurrentAppState(), 
                    batteryPowerManager.getCurrentPingInterval(), 
                    networkManager.getCurrentNetworkType(),
                    networkManager.isNetworkAvailable(), 
                    batteryPowerManager.getCurrentBatteryLevel(), 
                    batteryPowerManager.isCharging(), 
                    batteryPowerManager.isDozeMode()
                )
                return START_STICKY
            }
            else -> {
                unifiedLogger.info(LogDomain.SERVICE, "Service started")
                startForeground(NOTIFICATION_ID, createForegroundNotification())
            }
        }

        serviceJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Clean up any orphaned subscriptions before starting
                cleanupOrphanedSubscriptions()
                
                webSocketConnectionManager.connectToAllRelays()
                
                // Start periodic health monitoring
                startHealthMonitor()
                
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
        healthMonitorJob?.cancel()
        
        // Clean up component managers
        cleanupComponentManagers()
        
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
            batteryOptimizationLogger = batteryOptimizationLogger,
            batteryMetricsCollector = batteryMetricsCollector,
            settingsManager = settingsManager,
            onAppStateChange = { newState, duration -> 
                // Handle app state changes that affect other components
                webSocketConnectionManager.updateOkHttpClient()
            },
            onPingIntervalChange = {
                // Update WebSocket client when ping interval changes
                webSocketConnectionManager.updateOkHttpClient()
            },
            sendDebugLog = { message -> unifiedLogger.debug(LogDomain.BATTERY, message) }
        )
        
        // Initialize network management
        networkManager = NetworkManager(
            context = this,
            batteryOptimizationLogger = batteryOptimizationLogger,
            networkOptimizationLogger = networkOptimizationLogger,
            batteryMetricsCollector = batteryMetricsCollector,
            onNetworkStateChange = { available, networkType, quality ->
                // Handle network state changes
                if (available) {
                    webSocketConnectionManager.refreshConnections()
                }
            },
            onRefreshConnections = {
                webSocketConnectionManager.refreshConnections()
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
        
        // Initialize WebSocket connection management
        webSocketConnectionManager = WebSocketConnectionManager(
            configurationManager = configurationManager,
            subscriptionManager = subscriptionManager,
            batteryOptimizationLogger = batteryOptimizationLogger,
            batteryMetricsCollector = batteryMetricsCollector,
            networkOptimizationLogger = networkOptimizationLogger,
            networkManager = networkManager,
            batteryPowerManager = batteryPowerManager,
            onMessageReceived = { messageText, configuration, relayUrl ->
                messageHandler.handleWebSocketMessage(messageText, configuration, relayUrl)
            },
            sendDebugLog = { message -> unifiedLogger.debug(LogDomain.NETWORK, message) }
        )
        
        // Initialize message handler
        messageHandler = MessageHandler(
            configurationManager = configurationManager,
            subscriptionManager = subscriptionManager,
            eventCache = eventCache,
            eventNotificationManager = eventNotificationManager,
            relayConnections = webSocketConnectionManager.getRelayConnections(),
            sendDebugLog = { message -> unifiedLogger.debug(LogDomain.EVENT, message) },
            unifiedLogger = unifiedLogger
        )
        
        // Initialize connection health tester
        connectionHealthTester = ConnectionHealthTester(
            relayConnections = webSocketConnectionManager.getRelayConnections(),
            subscriptionManager = subscriptionManager,
            configurationManager = configurationManager,
            isWebSocketHealthy = { webSocket, relayUrl -> 
                webSocketConnectionManager.isWebSocketHealthy(webSocket, relayUrl) 
            },
            connectToRelay = { relayUrl, configuration -> 
                webSocketConnectionManager.connectToRelay(relayUrl, configuration) 
            },
            logServiceStats = { logServiceStats() },
            sendDebugLog = { message -> unifiedLogger.debug(LogDomain.HEALTH, message) }
        )
        
        // Initialize all components
        batteryPowerManager.initialize()
        networkManager.initialize()
        eventNotificationManager.initialize()
        webSocketConnectionManager.initialize()
    }
    
    /**
     * Clean up all component managers
     */
    private fun cleanupComponentManagers() {
        batteryPowerManager.cleanup()
        networkManager.cleanup()
        eventNotificationManager.cleanup()
        webSocketConnectionManager.cleanup()
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
            "relay_connections" to webSocketConnectionManager.getRelayConnections().size,
            "active_subscriptions" to subscriptionStats.activeCount,
            "event_cache" to cacheStats,
            "app_state" to batteryPowerManager.getCurrentAppState().name,
            "ping_interval" to "${batteryPowerManager.getCurrentPingInterval()}s",
            "network_type" to networkManager.getCurrentNetworkType(),
            "network_available" to networkManager.isNetworkAvailable(),
            "battery_level" to "${batteryPowerManager.getCurrentBatteryLevel()}%",
            "charging" to batteryPowerManager.isCharging()
        ))
        
        // Enhanced connection diagnostics
        webSocketConnectionManager.getRelayConnections().forEach { (relayUrl, connection) ->
            val shortUrl = relayUrl.substringAfter("://").take(20)
            val timeSinceLastMessage = currentTime - connection.lastMessageTime
            val timeSinceLastPing = if (connection.lastPingTime > 0) currentTime - connection.lastPingTime else -1
            val timeSinceSubscription = if (connection.subscriptionSentTime > 0) currentTime - connection.subscriptionSentTime else -1
            
            unifiedLogger.debug(LogDomain.RELAY, "Connection details: $shortUrl", mapOf(
                "websocket_connected" to (connection.webSocket != null),
                "subscription_confirmed" to connection.subscriptionConfirmed,
                "last_message_ago_ms" to timeSinceLastMessage,
                "last_ping_ago_ms" to timeSinceLastPing,
                "reconnect_attempts" to connection.reconnectAttempts,
                "subscription_sent_ago_ms" to timeSinceSubscription
            ))
        }
        
        // Update battery metrics and log effectiveness
        batteryMetricsCollector.updateBatteryMetrics()
        logOptimizationEffectiveness()
        
        // Check app standby bucket periodically
        batteryPowerManager.checkStandbyBucket()
    }
    
    /**
     * Start periodic health monitoring to detect silent connection failures
     */
    private fun startHealthMonitor() {
        healthMonitorJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    // Wait for the current ping interval before checking health
                    val checkIntervalMs = (batteryPowerManager.getCurrentPingInterval() * 1000 * 1.5).toLong() // 1.5x ping interval
                    delay(checkIntervalMs)
                    
                    unifiedLogger.debug(LogDomain.HEALTH, "Periodic health check starting...")
                    
                    val currentTime = System.currentTimeMillis()
                    val staleConnections = mutableListOf<String>()
                    
                    webSocketConnectionManager.getRelayConnections().forEach { (relayUrl, connection) ->
                        val webSocket = connection.webSocket
                        val timeSinceLastMessage = currentTime - connection.lastMessageTime
                        val maxSilenceMs = (batteryPowerManager.getCurrentPingInterval() * 1000 * 2.5).toLong() // 2.5x ping interval
                        
                        if (webSocket == null) {
                            unifiedLogger.warn(LogDomain.HEALTH, "$relayUrl: No WebSocket - marking stale")
                            staleConnections.add(relayUrl)
                        } else if (timeSinceLastMessage > maxSilenceMs) {
                            unifiedLogger.debug(LogDomain.HEALTH, "$relayUrl: Silent for ${timeSinceLastMessage / 1000}s - checking health")
                            if (!webSocketConnectionManager.isWebSocketHealthy(webSocket, relayUrl)) {
                                unifiedLogger.warn(LogDomain.HEALTH, "$relayUrl: Health check failed - marking stale")
                                staleConnections.add(relayUrl)
                            }
                        } else {
                            unifiedLogger.debug(LogDomain.HEALTH, "$relayUrl: Healthy (last message ${timeSinceLastMessage / 1000}s ago)")
                        }
                    }
                    
                    // Trigger reconnection for stale connections
                    if (staleConnections.isNotEmpty()) {
                        unifiedLogger.warn(LogDomain.HEALTH, "Health check found ${staleConnections.size} stale connections - triggering refresh")
                        webSocketConnectionManager.refreshConnections()
                    } else {
                        unifiedLogger.debug(LogDomain.HEALTH, "Health check complete - all connections healthy")
                    }
                    
                } catch (e: CancellationException) {
                    // Expected when the coroutine is cancelled - don't log as an error
                    unifiedLogger.info(LogDomain.HEALTH, "Health monitor stopped (service shutting down)")
                    throw e // Re-throw to properly cancel the coroutine
                } catch (e: Exception) {
                    unifiedLogger.error(LogDomain.HEALTH, "Health monitor error: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Log battery optimization effectiveness
     */
    private fun logOptimizationEffectiveness() {
        try {
            val report = batteryMetricsCollector.generateOptimizationReport()
            
            unifiedLogger.info(LogDomain.BATTERY, "Optimization Effectiveness: ${report.optimizationEffectiveness}", mapOf(
                "ping_frequency_reduction" to "${String.format(Locale.ROOT, "%.1f", report.pingFrequencyReduction)}%",
                "connection_stability" to "${String.format(Locale.ROOT, "%.1f", report.connectionStability)}%",
                "battery_improvement" to "${String.format(Locale.ROOT, "%.1f", report.batteryDrainImprovement)}%",
                "network_activity_reduction" to "${String.format(Locale.ROOT, "%.1f", report.networkActivityReduction)}%"
            ))
            
            // Log detailed metrics for analysis
            batteryOptimizationLogger.logOptimization(
                category = BatteryOptimizationLogger.LogCategory.OPTIMIZATION_DECISIONS,
                message = "Battery optimization effectiveness report",
                data = mapOf(
                    "effectiveness" to report.optimizationEffectiveness,
                    "ping_reduction_percent" to report.pingFrequencyReduction,
                    "connection_stability_percent" to report.connectionStability,
                    "battery_improvement_percent" to report.batteryDrainImprovement,
                    "network_reduction_percent" to report.networkActivityReduction,
                    "app_state_transitions" to report.appStateTransitions,
                    "collection_duration_ms" to report.collectionDuration
                )
            )
        } catch (e: Exception) {
            unifiedLogger.error(LogDomain.BATTERY, "Error generating optimization effectiveness report: ${e.message}")
            Log.e(TAG, "Battery optimization effectiveness report failed", e)
        }
    }
    

    

}
