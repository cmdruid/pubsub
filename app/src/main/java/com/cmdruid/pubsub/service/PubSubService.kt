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
import com.cmdruid.pubsub.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.WebSocket
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
    private lateinit var batteryOptimizationLogger: BatteryOptimizationLogger
    private lateinit var batteryMetricsCollector: BatteryMetricsCollector
    private lateinit var networkOptimizationLogger: NetworkOptimizationLogger
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
            registerReceiver(appStateReceiver, filter)
        }
        
        sendDebugLog("⚡ Service created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        when (action) {
            "REFRESH_CONNECTIONS" -> {
                sendDebugLog("🔄 Connection refresh requested")
                webSocketConnectionManager.refreshConnections()
                return START_STICKY
            }
            "SYNC_CONFIGURATIONS" -> {
                sendDebugLog("🔄 Configuration sync requested")
                webSocketConnectionManager.syncConfigurations()
                return START_STICKY
            }
            "TEST_CONNECTION_HEALTH" -> {
                sendDebugLog("🧪 Manual connection health test requested")
                connectionHealthTester.testConnectionHealth()
                return START_STICKY
            }
            "FORCE_RECONNECT_ALL" -> {
                sendDebugLog("🧪 Force reconnect all connections requested")
                connectionHealthTester.forceReconnectAll()
                return START_STICKY
            }
            "LOG_DETAILED_STATS" -> {
                sendDebugLog("🧪 Detailed stats logging requested")
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
                sendDebugLog("🚀 Service started")
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
                sendDebugLog("❌ Service startup error: ${e.message}")
                Log.e(TAG, "Service startup failed", e)
            }
        }
        
        return START_STICKY // Restart if killed
    }
    
    override fun onDestroy() {
        sendDebugLog("🛑 Service destroyed")
        
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
            onAppStateChange = { newState, duration -> 
                // Handle app state changes that affect other components
                webSocketConnectionManager.updateOkHttpClient()
            },
            onPingIntervalChange = {
                // Update WebSocket client when ping interval changes
                webSocketConnectionManager.updateOkHttpClient()
            },
            sendDebugLog = { message -> sendDebugLog(message) }
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
            sendDebugLog = { message -> sendDebugLog(message) }
        )
        
        // Initialize event notification management
        eventNotificationManager = EventNotificationManager(
            context = this,
            sendDebugLog = { message -> sendDebugLog(message) }
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
            sendDebugLog = { message -> sendDebugLog(message) }
        )
        
        // Initialize message handler
        messageHandler = MessageHandler(
            configurationManager = configurationManager,
            subscriptionManager = subscriptionManager,
            eventCache = eventCache,
            eventNotificationManager = eventNotificationManager,
            relayConnections = webSocketConnectionManager.getRelayConnections(),
            sendDebugLog = { message -> sendDebugLog(message) }
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
            sendDebugLog = { message -> sendDebugLog(message) }
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
        
        return eventNotificationManager.createForegroundNotification(pendingIntent)
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
        sendDebugLog("🧹 Cleaned up orphaned subscriptions")
    }
    
    /**
     * Get service statistics for debugging with enhanced connection diagnostics
     */
    private fun logServiceStats() {
        val subscriptionStats = subscriptionManager.getStats()
        val cacheStats = eventCache.getStats()
        val currentTime = System.currentTimeMillis()
        
        sendDebugLog("📊 Service Stats:")
        sendDebugLog("   🔗 Relay connections: ${webSocketConnectionManager.getRelayConnections().size}")
        sendDebugLog("   📋 Active subscriptions: ${subscriptionStats.activeCount}")
        sendDebugLog("   💾 Event cache: ${cacheStats}")
        sendDebugLog("   🔋 App state: ${batteryPowerManager.getCurrentAppState().name} (ping: ${batteryPowerManager.getCurrentPingInterval()}s)")
        sendDebugLog("   🌐 Network: ${networkManager.getCurrentNetworkType()} (available: ${networkManager.isNetworkAvailable()})")
        sendDebugLog("   🔋 Battery: ${batteryPowerManager.getCurrentBatteryLevel()}% (charging: ${batteryPowerManager.isCharging()})")
        
        // Enhanced connection diagnostics
        sendDebugLog("🔍 Connection Details:")
        webSocketConnectionManager.getRelayConnections().forEach { (relayUrl, connection) ->
            val shortUrl = relayUrl.substringAfter("://").take(20)
            val timeSinceLastMessage = currentTime - connection.lastMessageTime
            val timeSinceLastPing = if (connection.lastPingTime > 0) currentTime - connection.lastPingTime else -1
            val timeSinceSubscription = if (connection.subscriptionSentTime > 0) currentTime - connection.subscriptionSentTime else -1
            
            sendDebugLog("   📡 $shortUrl:")
            sendDebugLog("      WebSocket: ${if (connection.webSocket != null) "✅" else "❌"}")
            sendDebugLog("      Subscription: ${if (connection.subscriptionConfirmed) "✅ confirmed" else "⏳ pending"}")
            sendDebugLog("      Last message: ${if (timeSinceLastMessage < 60000) "${timeSinceLastMessage / 1000}s" else "${timeSinceLastMessage / 60000}min"} ago")
            sendDebugLog("      Last ping: ${if (timeSinceLastPing >= 0) "${timeSinceLastPing / 1000}s ago" else "never"}")
            sendDebugLog("      Reconnect attempts: ${connection.reconnectAttempts}")
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
                    
                    sendDebugLog("🩺 Periodic health check starting...")
                    
                    val currentTime = System.currentTimeMillis()
                    val staleConnections = mutableListOf<String>()
                    
                    webSocketConnectionManager.getRelayConnections().forEach { (relayUrl, connection) ->
                        val webSocket = connection.webSocket
                        val timeSinceLastMessage = currentTime - connection.lastMessageTime
                        val maxSilenceMs = (batteryPowerManager.getCurrentPingInterval() * 1000 * 2.5).toLong() // 2.5x ping interval
                        
                        if (webSocket == null) {
                            sendDebugLog("🩺 $relayUrl: No WebSocket - marking stale")
                            staleConnections.add(relayUrl)
                        } else if (timeSinceLastMessage > maxSilenceMs) {
                            sendDebugLog("🩺 $relayUrl: Silent for ${timeSinceLastMessage / 1000}s - checking health")
                            if (!webSocketConnectionManager.isWebSocketHealthy(webSocket, relayUrl)) {
                                sendDebugLog("🩺 $relayUrl: Health check failed - marking stale")
                                staleConnections.add(relayUrl)
                            }
                        } else {
                            sendDebugLog("🩺 $relayUrl: Healthy (last message ${timeSinceLastMessage / 1000}s ago)")
                        }
                    }
                    
                    // Trigger reconnection for stale connections
                    if (staleConnections.isNotEmpty()) {
                        sendDebugLog("🩺 Health check found ${staleConnections.size} stale connections - triggering refresh")
                        webSocketConnectionManager.refreshConnections()
                    } else {
                        sendDebugLog("🩺 Health check complete - all connections healthy")
                    }
                    
                } catch (e: Exception) {
                    sendDebugLog("🩺 Health monitor error: ${e.message}")
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
            
            sendDebugLog("🔋 Optimization Effectiveness: ${report.optimizationEffectiveness}")
            sendDebugLog("   Ping frequency reduction: ${String.format("%.1f", report.pingFrequencyReduction)}%")
            sendDebugLog("   Connection stability: ${String.format("%.1f", report.connectionStability)}%")
            sendDebugLog("   Battery improvement: ${String.format("%.1f", report.batteryDrainImprovement)}%")
            sendDebugLog("   Network activity reduction: ${String.format("%.1f", report.networkActivityReduction)}%")
            
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
            sendDebugLog("❌ Error generating optimization effectiveness report: ${e.message}")
            Log.e(TAG, "Battery optimization effectiveness report failed", e)
        }
    }
    

    
    private fun sendDebugLog(message: String) {
        Log.d(TAG, message)
        
        // Store in ConfigurationManager for persistent debug console
        configurationManager.addDebugLog(message)
        
        // Send broadcast to MainActivity for real-time display
        val intent = Intent(MainActivity.ACTION_DEBUG_LOG).apply {
            putExtra(MainActivity.EXTRA_LOG_MESSAGE, message)
        }
        sendBroadcast(intent)
    }
}
