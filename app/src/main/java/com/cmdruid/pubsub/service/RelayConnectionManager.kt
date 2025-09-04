package com.cmdruid.pubsub.service

import android.util.Log
import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.data.ConfigurationManager
import com.cmdruid.pubsub.nostr.NostrFilter
import com.cmdruid.pubsub.nostr.NostrMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Modern relay connection manager with clean separation of concerns
 * 
 * Key features:
 * - Clean separation: One manager per relay
 * - Simple reconnection logic with exponential backoff
 * - Efficient resource sharing with optimized OkHttpClient pooling
 * - Clear state management and health monitoring
 * - Battery-aware connection optimization
 */
class RelayConnectionManager(
    private val configurationManager: ConfigurationManager,
    private val subscriptionManager: SubscriptionManager,
    private val batteryPowerManager: BatteryPowerManager,
    private val networkManager: NetworkManager,
    private val metricsCollector: MetricsCollector,
    private val onMessageReceived: (String, String, String) -> Unit, // message, subscriptionId, relayUrl
    private val sendDebugLog: (String) -> Unit
) {
    
    companion object {
        private const val TAG = "RelayConnectionManager"
    }
    
    // Clean separation: One manager per relay
    private val relayManagers = ConcurrentHashMap<String, SingleRelayManager>()
    
    // Shared resources - more efficient than per-connection clients
    private var okHttpClient = createOptimizedOkHttpClient()
    private var currentPingIntervalSeconds = 30L
    
    /**
     * Connect to all enabled relay configurations
     */
    suspend fun connectToAllRelays() {
        val configurations = configurationManager.getEnabledConfigurations()
        sendDebugLog("🔌 Starting connections for ${configurations.size} configuration(s)")
        
        configurations.forEach { configuration ->
            configuration.relayUrls.forEach { relayUrl ->
                connectToRelay(relayUrl, configuration)
            }
        }
    }
    
    /**
     * Connect to relay with clean, simple logic
     */
    suspend fun connectToRelay(relayUrl: String, configuration: Configuration) {
        val subscriptionId = configuration.subscriptionId
        
        // Get or create relay manager
        val manager = relayManagers.getOrPut(relayUrl) {
            SingleRelayManager(
                relayUrl = relayUrl,
                okHttpClient = okHttpClient,
                batteryPowerManager = batteryPowerManager,
                networkManager = networkManager,
                subscriptionManager = subscriptionManager,
                metricsCollector = metricsCollector,
                onMessageReceived = onMessageReceived,
                sendDebugLog = sendDebugLog
            )
        }
        
        // Create relay-specific filter using new subscription manager
        val filter = subscriptionManager.createRelaySpecificFilter(
            subscriptionId = subscriptionId,
            relayUrl = relayUrl,
            baseFilter = configuration.filter,
            metricsCollector = metricsCollector
        )
        
        // Register subscription
        subscriptionManager.registerSubscription(subscriptionId, configuration.id, filter, relayUrl)
        
        // Connect with filter
        manager.connect(subscriptionId, filter)
        
        sendDebugLog("🔌 Connecting to $relayUrl with subscription $subscriptionId")
    }
    
    /**
     * Disconnect from relay
     */
    fun disconnectFromRelay(relayUrl: String) {
        relayManagers[relayUrl]?.disconnect()
        relayManagers.remove(relayUrl)
        sendDebugLog("📡 Disconnected from $relayUrl")
    }
    
    /**
     * Disconnect from all relays
     */
    fun disconnectFromAllRelays() {
        sendDebugLog("📡 Disconnecting from all relays")
        relayManagers.values.forEach { it.disconnect() }
        relayManagers.clear()
    }
    
    /**
     * Get connection health for all relays
     */
    fun getConnectionHealth(): Map<String, RelayHealth> {
        return relayManagers.mapValues { (_, manager) -> manager.getHealth() }
    }
    
    /**
     * Refresh connections (simplified logic)
     */
    fun refreshConnections() {
        CoroutineScope(Dispatchers.IO).launch {
            sendDebugLog("🔄 Refreshing all connections...")
            
            // ENHANCED approach: Check health with dynamic thresholds
            val healthResults = getConnectionHealth()
            
            // Get current dynamic thresholds
            val batteryLevel = batteryPowerManager.getCurrentBatteryLevel()
            val pingInterval = batteryPowerManager.getCurrentPingInterval()
            val networkQuality = networkManager.getNetworkQuality()
            
            val thresholds = HealthThresholds.forBatteryLevel(
                batteryLevel = batteryLevel,
                pingInterval = pingInterval,
                networkQuality = networkQuality
            )
            
            val unhealthyRelays = healthResults.filterValues { health ->
                // Use same logic as HealthMonitor for consistency
                !(health.state == ConnectionState.CONNECTED &&
                  health.subscriptionConfirmed &&
                  health.lastMessageAge < thresholds.maxSilenceMs &&
                  health.reconnectAttempts < thresholds.maxReconnectAttempts)
            }
            
            if (unhealthyRelays.isNotEmpty()) {
                sendDebugLog("🔧 Reconnecting ${unhealthyRelays.size} unhealthy connections")
                unhealthyRelays.keys.forEach { relayUrl ->
                    val manager = relayManagers[relayUrl]
                    manager?.let {
                        // Reset reconnection attempts if health check triggered this
                        it.resetReconnectionAttempts()
                        it.reconnect()
                    }
                }
            } else {
                sendDebugLog("✅ All connections healthy")
            }
        }
    }
    
    /**
     * Synchronize with current configurations
     */
    fun syncConfigurations() {
        CoroutineScope(Dispatchers.IO).launch {
            sendDebugLog("🔄 Synchronizing configurations...")
            
            val enabledConfigurations = configurationManager.getEnabledConfigurations()
            val expectedRelayUrls = enabledConfigurations.flatMap { it.relayUrls }.toSet()
            val currentRelayUrls = relayManagers.keys.toSet()
            
            // Remove connections for disabled configurations
            val relaysToRemove = currentRelayUrls - expectedRelayUrls
            relaysToRemove.forEach { relayUrl ->
                disconnectFromRelay(relayUrl)
                sendDebugLog("❌ Removed connection: $relayUrl")
            }
            
            // Add connections for newly enabled configurations
            val relaysToAdd = expectedRelayUrls - currentRelayUrls
            enabledConfigurations.forEach { config ->
                config.relayUrls.forEach { relayUrl ->
                    if (relayUrl in relaysToAdd) {
                        connectToRelay(relayUrl, config)
                        sendDebugLog("✅ Added connection: $relayUrl")
                    }
                }
            }
            
            sendDebugLog("✅ Configuration sync completed")
        }
    }
    
    /**
     * Update ping interval for all connections - OPTIMIZED battery-aware implementation
     */
    fun updatePingInterval(newInterval: Long) {
        if (currentPingIntervalSeconds == newInterval) {
            return // No change needed
        }
        
        val oldInterval = currentPingIntervalSeconds
        currentPingIntervalSeconds = newInterval
        
        // BATTERY OPTIMIZATION: Only recreate client if interval changed significantly
        val significantChange = kotlin.math.abs(newInterval - oldInterval) >= 30L
        
        if (significantChange) {
            // Create new optimized client with updated ping interval
            val newClient = createOptimizedOkHttpClient()
            val oldClient = okHttpClient
            okHttpClient = newClient
            
            // Update all existing relay managers with new client
            relayManagers.values.forEach { manager ->
                manager.updateOkHttpClient(newClient)
            }
            
            // Schedule old client cleanup to allow existing connections to complete
            CoroutineScope(Dispatchers.IO).launch {
                delay(5000) // 5 second grace period
                try {
                    oldClient.dispatcher.executorService.shutdown()
                    oldClient.connectionPool.evictAll()
                } catch (e: Exception) {
                    // Ignore cleanup errors
                }
            }
            
            sendDebugLog("🔋 Ping interval updated: ${oldInterval}s → ${newInterval}s (client recreated)")
        } else {
            sendDebugLog("🔋 Ping interval updated: ${oldInterval}s → ${newInterval}s (minor change, client reused)")
        }
    }
    
    /**
     * Get relay connections for compatibility with existing code
     */
    fun getRelayConnections(): Map<String, RelayConnectionInfo> {
        return relayManagers.mapValues { (relayUrl, manager) ->
            val health = manager.getHealth()
            RelayConnectionInfo(
                relayUrl = relayUrl,
                isConnected = health.state == ConnectionState.CONNECTED,
                lastMessageTime = System.currentTimeMillis() - health.lastMessageAge,
                reconnectAttempts = health.reconnectAttempts,
                subscriptionConfirmed = health.subscriptionConfirmed
            )
        }
    }
    
    private fun createOptimizedOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .pingInterval(currentPingIntervalSeconds, TimeUnit.SECONDS) // Dynamic ping interval
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    
    /**
     * Compatibility data class for existing code
     */
    data class RelayConnectionInfo(
        val relayUrl: String,
        val isConnected: Boolean,
        val lastMessageTime: Long,
        val reconnectAttempts: Int,
        val subscriptionConfirmed: Boolean
    )
}

/**
 * Manages a single relay connection with clean state management
 * Each relay connection is completely independent with its own WebSocket and state
 */
class SingleRelayManager(
    private val relayUrl: String,
    private var okHttpClient: OkHttpClient,
    private val batteryPowerManager: BatteryPowerManager,
    private val networkManager: NetworkManager,
    private val subscriptionManager: SubscriptionManager,
    private val metricsCollector: MetricsCollector,
    private val onMessageReceived: (String, String, String) -> Unit, // message, subscriptionId, relayUrl
    private val sendDebugLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "SingleRelayManager"
    }
    
    private var webSocket: WebSocket? = null
    private var currentSubscriptionId: String? = null
    private var connectionState = ConnectionState.DISCONNECTED
    private var lastMessageTime = 0L
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var subscriptionConfirmed = false
    
    /**
     * Connect to relay with subscription
     */
    suspend fun connect(subscriptionId: String, filter: NostrFilter) {
        if (connectionState == ConnectionState.CONNECTED && 
            currentSubscriptionId == subscriptionId && 
            subscriptionConfirmed) {
            sendDebugLog("📡 $relayUrl already connected with confirmed subscription")
            return
        }
        
        // Cancel any existing reconnection
        reconnectJob?.cancel()
        
        connectionState = ConnectionState.CONNECTING
        currentSubscriptionId = subscriptionId
        subscriptionConfirmed = false
        
        val shortUrl = relayUrl.substringAfter("://").take(20)
        sendDebugLog("🔌 Connecting to $shortUrl...")
        
        // SMART wake lock acquisition for connection establishment
        val wakeLockAcquired = batteryPowerManager.acquireSmartWakeLock(
            operation = "connect_$shortUrl",
            estimatedDuration = 15000L,
            importance = BatteryPowerManager.WakeLockImportance.HIGH
        )
        
        val request = Request.Builder().url(relayUrl).build()
        
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connectionState = ConnectionState.CONNECTED
                reconnectAttempts = 0
                lastMessageTime = System.currentTimeMillis()
                
                // Release wake lock on successful connection (only if acquired)
                if (wakeLockAcquired) {
                    batteryPowerManager.releaseWakeLock()
                }
                
                sendDebugLog("✅ $shortUrl connected")
                
                // Send subscription immediately with updated filter
                val updatedFilter = subscriptionManager.createRelaySpecificFilter(subscriptionId, relayUrl, filter, metricsCollector)
                val subscriptionMessage = NostrMessage.createSubscription(subscriptionId, updatedFilter)
                val success = webSocket.send(subscriptionMessage)
                
                if (success) {
                    sendDebugLog("📋 Subscription sent to $shortUrl (since: ${updatedFilter.since})")
                } else {
                    sendDebugLog("❌ Failed to send subscription to $shortUrl - this will cause missing events!")
                }
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                lastMessageTime = System.currentTimeMillis()
                
                // TRACE level: Very verbose - message received (could be many per second)
                // Only enable for deep debugging
                // sendDebugLog("[TRACE] 📨 Message received from $shortUrl: ${text.take(100)}...")
                
                // CRITICAL: Capture subscription ID to prevent race conditions
                val capturedSubscriptionId = currentSubscriptionId
                if (capturedSubscriptionId != null) {
                    // Validate the message is for our subscription before routing
                    if (text.contains("\"$capturedSubscriptionId\"") || text.startsWith("[\"EVENT\",\"$capturedSubscriptionId\"")) {
                        onMessageReceived(text, capturedSubscriptionId, relayUrl)
                    } else {
                        // This could indicate a cross-subscription issue
                        sendDebugLog("⚠️ Message received but doesn't match subscription $capturedSubscriptionId: ${text.take(50)}...")
                    }
                } else {
                    sendDebugLog("⚠️ Message received but no active subscription for $shortUrl")
                }
                
                // Mark subscription as confirmed when we receive any message
                if (!subscriptionConfirmed) {
                    subscriptionConfirmed = true
                    sendDebugLog("✅ Subscription confirmed for $shortUrl")
                }
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                sendDebugLog("⚠️ $shortUrl closing: $code - $reason")
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connectionState = ConnectionState.DISCONNECTED
                sendDebugLog("📡 $shortUrl closed: $code - $reason")
                
                // Auto-reconnect if this wasn't intentional
                if (currentSubscriptionId != null) {
                    scheduleReconnect(subscriptionId, filter)
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connectionState = ConnectionState.FAILED
                reconnectAttempts++
                
                // Release wake lock on connection failure (only if acquired)
                if (wakeLockAcquired) {
                    batteryPowerManager.releaseWakeLock()
                }
                
                sendDebugLog("❌ $shortUrl failed (attempt $reconnectAttempts): ${t.message}")
                
                // Smart reconnection based on network conditions
                if (shouldAttemptReconnect()) {
                    scheduleReconnect(subscriptionId, filter)
                } else {
                    sendDebugLog("⏸️ Not reconnecting to $shortUrl (network/battery conditions)")
                }
            }
        })
    }
    
    /**
     * Reset reconnection attempts (called during health check recovery)
     */
    fun resetReconnectionAttempts() {
        reconnectAttempts = 0
        val shortUrl = relayUrl.substringAfter("://").take(20)
        sendDebugLog("🔄 Reset reconnection attempts for $shortUrl")
    }
    
    /**
     * Reconnect with current subscription
     */
    fun reconnect() {
        currentSubscriptionId?.let { subId ->
            // Get current filter from subscription manager  
            val subscriptionInfo = subscriptionManager.getSubscriptionInfo(subId)
            if (subscriptionInfo != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    connect(subId, subscriptionInfo.filter)
                }
            } else {
                sendDebugLog("❌ Cannot reconnect: No subscription info found for $subId")
            }
        } ?: run {
            sendDebugLog("❌ Cannot reconnect: No current subscription ID")
        }
    }
    
    /**
     * Disconnect from relay
     */
    fun disconnect() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "Disconnecting")
        connectionState = ConnectionState.DISCONNECTED
        currentSubscriptionId = null
        subscriptionConfirmed = false
    }
    
    /**
     * Update OkHttpClient for battery optimization
     */
    fun updateOkHttpClient(newClient: OkHttpClient) {
        okHttpClient = newClient
        // Note: Existing WebSocket connections will continue with old client
        // New connections will use the updated client with optimized ping interval
    }
    
    /**
     * Get current health status
     */
    fun getHealth(): RelayHealth {
        return RelayHealth(
            state = connectionState,
            lastMessageAge = System.currentTimeMillis() - lastMessageTime,
            reconnectAttempts = reconnectAttempts,
            subscriptionConfirmed = subscriptionConfirmed
        )
    }
    
    /**
     * Schedule reconnection with exponential backoff
     */
    private fun scheduleReconnect(subscriptionId: String, filter: NostrFilter) {
        reconnectJob?.cancel()
        
        val delay = calculateReconnectDelay()
        val shortUrl = relayUrl.substringAfter("://").take(20)
        
        sendDebugLog("🔄 Reconnecting to $shortUrl in ${delay}ms (attempt $reconnectAttempts)")
        
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            delay(delay)
            
            // Check network availability before reconnecting
            if (networkManager.isNetworkAvailable()) {
                connect(subscriptionId, filter)
            } else {
                sendDebugLog("❌ Network unavailable, skipping reconnection to $shortUrl")
            }
        }
    }
    
    /**
     * Calculate smart reconnection delay
     */
    private fun calculateReconnectDelay(): Long {
        val baseDelay = 5000L * reconnectAttempts // Simple exponential backoff
        val maxDelay = 60000L
        
        // Adjust based on app state
        val stateMultiplier = when (batteryPowerManager.getCurrentAppState()) {
            PubSubService.AppState.FOREGROUND -> 1.0
            PubSubService.AppState.BACKGROUND -> 1.5
            PubSubService.AppState.DOZE -> 3.0
            PubSubService.AppState.RARE -> 2.5
            PubSubService.AppState.RESTRICTED -> 4.0
        }
        
        return minOf((baseDelay * stateMultiplier).toLong(), maxDelay)
    }
    
    /**
     * Determine if we should attempt reconnection
     */
    private fun shouldAttemptReconnect(): Boolean {
        // Don't reconnect if too many attempts
        if (reconnectAttempts >= 10) return false
        
        // Don't reconnect if network unavailable
        if (!networkManager.isNetworkAvailable()) return false
        
        // Be conservative in low battery states
        val batteryLevel = batteryPowerManager.getCurrentBatteryLevel()
        val appState = batteryPowerManager.getCurrentAppState()
        
        return when {
            batteryLevel <= 15 && reconnectAttempts >= 2 -> false
            appState == PubSubService.AppState.RESTRICTED && reconnectAttempts >= 2 -> false
            appState == PubSubService.AppState.DOZE && reconnectAttempts >= 3 -> false
            else -> true
        }
    }
}

/**
 * Connection states for clear state management
 */
enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, FAILED
}

/**
 * Relay health information
 */
data class RelayHealth(
    val state: ConnectionState,
    val lastMessageAge: Long,
    val reconnectAttempts: Int,
    val subscriptionConfirmed: Boolean
) {
    fun isHealthy(maxSilenceMs: Long = 300000): Boolean {
        return state == ConnectionState.CONNECTED && 
               subscriptionConfirmed && 
               lastMessageAge < maxSilenceMs
    }
    
    fun getShortStatus(): String {
        return when {
            !isHealthy() -> "❌"
            lastMessageAge < 60000 -> "✅" // Active (< 1 min)
            lastMessageAge < 300000 -> "⚠️" // Quiet (1-5 min)
            else -> "💔" // Stale (> 5 min)
        }
    }
}
