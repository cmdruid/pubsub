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
 * Manages WebSocket connections, reconnection logic, and subscription management
 * Extracted from PubSubService to improve maintainability
 */
class WebSocketConnectionManager(
    private val configurationManager: ConfigurationManager,
    private val subscriptionManager: SubscriptionManager,
    private val batteryOptimizationLogger: BatteryOptimizationLogger,
    private val batteryMetricsCollector: BatteryMetricsCollector,
    private val networkOptimizationLogger: NetworkOptimizationLogger,
    private val networkManager: NetworkManager,
    private val batteryPowerManager: BatteryPowerManager,
    private val onMessageReceived: (String, Configuration, String) -> Unit,
    private val sendDebugLog: (String) -> Unit
) {
    
    companion object {
        private const val TAG = "WebSocketConnectionManager"
        private const val RECONNECT_DELAY_MS = 5000L
        private const val MAX_RECONNECT_DELAY_MS = 60000L
    }
    
    // Map of relay URL to RelayConnection
    private val relayConnections = ConcurrentHashMap<String, PubSubService.RelayConnection>()
    
    // OkHttp client for WebSocket connections
    private var okHttpClient: OkHttpClient? = null
    
    /**
     * Initialize the connection manager
     */
    fun initialize() {
        setupOkHttpClient()
    }
    
    /**
     * Clean up all connections
     */
    fun cleanup() {
        disconnectFromAllRelays()
        okHttpClient = null
    }
    
    /**
     * Get relay connections map (for external access)
     */
    fun getRelayConnections(): ConcurrentHashMap<String, PubSubService.RelayConnection> = relayConnections
    
    /**
     * Setup OkHttp client with current ping interval
     */
    fun setupOkHttpClient() {
        okHttpClient = createOkHttpClient(batteryPowerManager.getCurrentPingInterval())
    }
    
    /**
     * Update OkHttp client with new ping interval
     */
    fun updateOkHttpClient() {
        val oldClient = okHttpClient
        okHttpClient = createOkHttpClient(batteryPowerManager.getCurrentPingInterval())
        
        // Schedule connection refresh to use new client
        CoroutineScope(Dispatchers.IO).launch {
            refreshConnections()
        }
        
        sendDebugLog("🔋 OkHttp client updated with new ping interval: ${batteryPowerManager.getCurrentPingInterval()}s")
    }
    
    /**
     * Create OkHttp client with specified ping interval for battery optimization
     */
    private fun createOkHttpClient(pingIntervalSeconds: Long): OkHttpClient {
        batteryOptimizationLogger.logOptimization(
            category = BatteryOptimizationLogger.LogCategory.PING_INTERVAL,
            message = "Creating OkHttp client",
            data = mapOf(
                "ping_interval" to "${pingIntervalSeconds}s",
                "app_state" to batteryPowerManager.getCurrentAppState().name
            )
        )
        
        // Track ping frequency for metrics
        batteryMetricsCollector.trackPingFrequency(pingIntervalSeconds, batteryPowerManager.getCurrentAppState())
        
        return OkHttpClient.Builder()
            .pingInterval(pingIntervalSeconds, TimeUnit.SECONDS)
            .build()
    }
    
    /**
     * Connect to all enabled relay configurations
     */
    suspend fun connectToAllRelays() {
        try {
            val configurations = configurationManager.getEnabledConfigurations()
            sendDebugLog("🔌 Starting connections for ${configurations.size} subscription(s)")
            
            for (configuration in configurations) {
                for (relayUrl in configuration.relayUrls) {
                    try {
                        connectToRelay(relayUrl, configuration)
                    } catch (e: Exception) {
                        sendDebugLog("❌ Failed to connect to $relayUrl: ${e.message}")
                        Log.e(TAG, "Connection failed for $relayUrl", e)
                    }
                }
            }
        } catch (e: Exception) {
            sendDebugLog("❌ Error in connectToAllRelays: ${e.message}")
            Log.e(TAG, "connectToAllRelays failed", e)
        }
    }
    
    /**
     * Connect to a specific relay
     */
    suspend fun connectToRelay(relayUrl: String, configuration: Configuration) {
        sendDebugLog("🔌 Connecting: ${relayUrl.substringAfter("://").take(20)}... (${configuration.name})")
        
        // Acquire wake lock for connection establishment
        batteryPowerManager.acquireWakeLock("connection_${relayUrl.substringAfter("://").take(10)}", 15000L)
        
        val connection = PubSubService.RelayConnection(relayUrl, configuration.id)
        relayConnections[relayUrl] = connection
        
        val request = Request.Builder()
            .url(relayUrl)
            .build()
        
        connection.webSocket = okHttpClient?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendDebugLog("✅ Connected: ${relayUrl.substringAfter("://").take(20)}...")
                connection.reconnectAttempts = 0
                
                // Release wake lock - connection established successfully
                batteryPowerManager.releaseWakeLock()
                
                // Track successful connection
                batteryMetricsCollector.trackConnectionEvent("connect", relayUrl, success = true)
                batteryOptimizationLogger.logConnectionHealth(relayUrl, "connected", 0)
                
                subscribeToEvents(connection, configuration)
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received from $relayUrl: $text")
                
                // Update last message time for connection health tracking
                connection.lastMessageTime = System.currentTimeMillis()
                
                onMessageReceived(text, configuration, relayUrl)
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                sendDebugLog("⚠️ $relayUrl closing: $code - $reason")
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                sendDebugLog("📡 $relayUrl closed: $code - $reason")
                scheduleReconnect(connection, configuration)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                sendDebugLog("❌ $relayUrl failed: ${t.message}")
                
                // Release wake lock - connection failed
                batteryPowerManager.releaseWakeLock()
                
                // Track connection failure
                batteryMetricsCollector.trackConnectionEvent("connect", relayUrl, success = false)
                batteryOptimizationLogger.logConnectionHealth(relayUrl, "failed: ${t.message}", connection.reconnectAttempts)
                
                scheduleReconnect(connection, configuration)
            }
        })
    }
    
    /**
     * Subscribe to events for a specific connection
     */
    private fun subscribeToEvents(connection: PubSubService.RelayConnection, configuration: Configuration) {
        if (configuration.filter.isEmpty()) {
            sendDebugLog("⚠️ No filter configured for ${configuration.name}, cannot subscribe")
            return
        }
        
        // Use the configuration's permanent subscription ID
        val subscriptionId = configuration.subscriptionId
        
        // Update the "since" timestamp - use stored timestamp if recent, otherwise use safety buffer to prevent gaps
        val filterToUse = if (connection.subscriptionId != null && connection.subscriptionId == subscriptionId) {
            // This is a resubscription with the same ID - check if stored timestamp is recent enough
            val resubFilter = subscriptionManager.createResubscriptionFilter(subscriptionId)
            if (resubFilter != null && resubFilter.since != null) {
                val currentTimestamp = System.currentTimeMillis() / 1000
                val lastEventAge = currentTimestamp - resubFilter.since!!
                
                // If last event was more than 2x the current ping interval ago, use safety buffer instead
                // This handles cases where connection was down longer than expected
                if (lastEventAge < batteryPowerManager.getCurrentPingInterval() * 2) {
                    sendDebugLog("📋 Using stored timestamp (${lastEventAge}s ago)")
                    resubFilter
                } else {
                    sendDebugLog("📋 Stored timestamp too old (${lastEventAge}s), using safety buffer")
                    createInitialFilter(configuration.filter)
                }
            } else {
                // No stored timestamp available, use safety buffer
                createInitialFilter(configuration.filter)
            }
        } else {
            // New subscription or subscription ID changed - use safety buffer to prevent gaps
            createInitialFilter(configuration.filter)
        }
        
        // Register subscription with the subscription manager
        subscriptionManager.registerSubscription(
            subscriptionId = subscriptionId,
            configurationId = configuration.id,
            filter = filterToUse,
            relayUrl = connection.relayUrl
        )
        
        // Update connection with the configuration's subscription ID
        connection.subscriptionId = subscriptionId
        connection.subscriptionConfirmed = false // Reset confirmation status
        
        val subscriptionMessage = NostrMessage.createSubscription(subscriptionId, filterToUse)
        
        sendDebugLog("📋 Subscribing to ${connection.relayUrl} with filter: ${filterToUse.getSummary()}")
        sendDebugLog("🆔 Subscription ID: $subscriptionId")
        
        val success = connection.webSocket?.send(subscriptionMessage) ?: false
        if (success) {
            connection.subscriptionSentTime = System.currentTimeMillis()
            sendDebugLog("✅ Subscription message sent to ${connection.relayUrl}")
            
            // Schedule verification check
            CoroutineScope(Dispatchers.IO).launch {
                delay(30000) // Wait 30 seconds
                if (!connection.subscriptionConfirmed) {
                    sendDebugLog("⚠️ Subscription not confirmed after 30s for ${connection.relayUrl}, may need reconnection")
                    // Could trigger a reconnection here if needed
                }
            }
        } else {
            sendDebugLog("❌ Failed to send subscription message to ${connection.relayUrl}")
        }
    }
    
    /**
     * Create initial filter with "since" set to a safe timestamp to prevent gaps
     */
    private fun createInitialFilter(baseFilter: NostrFilter): NostrFilter {
        // Use a safety buffer to catch events that might have been missed
        // during connection downtime. Use the longest possible ping interval
        // plus extra buffer to ensure we don't miss notifications if the
        // websocket was killed before keepalive detection occurred.
        val safetyBufferSeconds = BatteryPowerManager.PING_INTERVAL_RESTRICTED_SECONDS + 300L // 10 minutes total (5 + 5 extra)
        val currentTimestamp = System.currentTimeMillis() / 1000 // Convert to Unix timestamp
        val safeTimestamp = currentTimestamp - safetyBufferSeconds
        
        sendDebugLog("📋 Using safety buffer: ${safetyBufferSeconds / 60}min lookback to prevent gaps")
        
        return baseFilter.copy(since = safeTimestamp)
    }
    
    /**
     * Schedule reconnection for a failed connection
     */
    private fun scheduleReconnect(connection: PubSubService.RelayConnection, configuration: Configuration) {
        connection.reconnectJob?.cancel()
        connection.reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            // Network-aware reconnection logic
            val networkAwareDecision = networkManager.makeNetworkAwareReconnectionDecision(
                connection.reconnectAttempts,
                batteryPowerManager.getCurrentAppState()
            )
            
            if (!networkAwareDecision.shouldReconnect) {
                batteryOptimizationLogger.logOptimization(
                    category = BatteryOptimizationLogger.LogCategory.CONNECTION_HEALTH,
                    message = "Reconnection skipped",
                    data = mapOf(
                        "reason" to networkAwareDecision.reason,
                        "relay" to connection.relayUrl.substringAfter("://").take(20),
                        "attempts" to connection.reconnectAttempts,
                        "network_available" to networkManager.isNetworkAvailable()
                    )
                )
                sendDebugLog("⏸️ Skipping reconnection: ${networkAwareDecision.reason}")
                
                // Log reconnection skip for monitoring
                networkOptimizationLogger.logReconnectionDecision(
                    relayUrl = connection.relayUrl,
                    decision = "skipped",
                    reason = networkAwareDecision.reason,
                    attemptNumber = connection.reconnectAttempts + 1,
                    delayMs = 0L,
                    networkType = networkManager.getCurrentNetworkType(),
                    networkQuality = networkManager.getNetworkQuality(),
                    appState = batteryPowerManager.getCurrentAppState().name,
                    batteryLevel = batteryOptimizationLogger.getBatteryLevel()
                )
                
                return@launch
            }
            
            val baseDelay = minOf(
                RECONNECT_DELAY_MS * (1 shl connection.reconnectAttempts),
                MAX_RECONNECT_DELAY_MS
            )
            
            val delayMs = networkManager.calculateOptimalReconnectDelay(
                baseDelay,
                batteryPowerManager.getCurrentAppState()
            )
            
            batteryOptimizationLogger.logOptimization(
                category = BatteryOptimizationLogger.LogCategory.CONNECTION_HEALTH,
                message = "Reconnection scheduled",
                data = mapOf(
                    "relay" to connection.relayUrl.substringAfter("://").take(20),
                    "delay_ms" to delayMs,
                    "attempt" to connection.reconnectAttempts + 1,
                    "network_type" to networkManager.getCurrentNetworkType(),
                    "network_quality" to networkManager.getNetworkQuality(),
                    "app_state" to batteryPowerManager.getCurrentAppState().name
                )
            )
            
            sendDebugLog("🔄 Reconnecting to ${connection.relayUrl.substringAfter("://").take(20)}... in ${delayMs}ms (attempt ${connection.reconnectAttempts + 1}, ${networkManager.getCurrentNetworkType()})")
            
            // Log reconnection attempt for monitoring
            networkOptimizationLogger.logReconnectionDecision(
                relayUrl = connection.relayUrl,
                decision = "attempted",
                reason = networkAwareDecision.reason,
                attemptNumber = connection.reconnectAttempts + 1,
                delayMs = delayMs,
                networkType = networkManager.getCurrentNetworkType(),
                networkQuality = networkManager.getNetworkQuality(),
                appState = batteryPowerManager.getCurrentAppState().name,
                batteryLevel = batteryOptimizationLogger.getBatteryLevel()
            )
            
            delay(delayMs)
            
            // Double-check network availability before attempting reconnection
            if (!networkManager.isNetworkAvailable()) {
                sendDebugLog("❌ Network unavailable during reconnect attempt, aborting")
                return@launch
            }
            
            connection.reconnectAttempts++
            batteryMetricsCollector.trackConnectionEvent("reconnect", connection.relayUrl, success = true)
            connectToRelay(connection.relayUrl, configuration)
        }
    }
    
    /**
     * Disconnect from all relays
     */
    fun disconnectFromAllRelays() {
        sendDebugLog("📡 Disconnecting from all relays")
        
        relayConnections.values.forEach { connection ->
            connection.reconnectJob?.cancel()
            connection.subscriptionId?.let { subId ->
                connection.webSocket?.send(NostrMessage.createClose(subId))
            }
            connection.webSocket?.close(1000, "Service stopping")
        }
        
        relayConnections.clear()
        sendDebugLog("📡 All relays disconnected")
    }
    
    /**
     * Synchronize active subscriptions with current enabled configurations
     */
    fun syncConfigurations() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendDebugLog("🔄 Synchronizing configurations with active subscriptions...")
                
                val enabledConfigurations = configurationManager.getEnabledConfigurations()
                val currentRelayUrls = relayConnections.keys.toSet()
                
                // Build set of relay URLs that should be active based on enabled configurations
                val expectedRelayUrls = mutableSetOf<String>()
                enabledConfigurations.forEach { config ->
                    expectedRelayUrls.addAll(config.relayUrls)
                }
            
                // Remove connections for disabled configurations
                val relaysToRemove = currentRelayUrls - expectedRelayUrls
                if (relaysToRemove.isNotEmpty()) {
                    sendDebugLog("🗑️ Removing ${relaysToRemove.size} disabled subscription(s)")
                    relaysToRemove.forEach { relayUrl ->
                        try {
                            val connection = relayConnections[relayUrl]
                            if (connection != null) {
                                // Send close message if subscription exists
                                connection.subscriptionId?.let { subId ->
                                    try {
                                        connection.webSocket?.send(NostrMessage.createClose(subId))
                                        subscriptionManager.removeSubscription(subId)
                                        sendDebugLog("🛑 Closed subscription: ${subId.take(8)}... for $relayUrl")
                                    } catch (e: Exception) {
                                        sendDebugLog("⚠️ Error closing subscription $subId: ${e.message}")
                                    }
                                }
                                
                                // Cancel reconnect job and close connection
                                try {
                                    connection.reconnectJob?.cancel()
                                    connection.webSocket?.close(1000, "Configuration disabled")
                                    relayConnections.remove(relayUrl)
                                    sendDebugLog("❌ Removed connection: $relayUrl")
                                } catch (e: Exception) {
                                    sendDebugLog("⚠️ Error removing connection $relayUrl: ${e.message}")
                                    // Still remove from map even if close failed
                                    relayConnections.remove(relayUrl)
                                }
                            }
                        } catch (e: Exception) {
                            sendDebugLog("⚠️ Error processing removal of $relayUrl: ${e.message}")
                        }
                    }
                }
                
                // Add connections for newly enabled configurations
                val relaysToAdd = expectedRelayUrls - currentRelayUrls
                if (relaysToAdd.isNotEmpty()) {
                    sendDebugLog("➕ Adding ${relaysToAdd.size} new subscription(s)")
                    enabledConfigurations.forEach { config ->
                        config.relayUrls.forEach { relayUrl ->
                            if (relayUrl in relaysToAdd) {
                                try {
                                    connectToRelay(relayUrl, config)
                                    sendDebugLog("✅ Added connection: $relayUrl for ${config.name}")
                                } catch (e: Exception) {
                                    sendDebugLog("⚠️ Error adding connection $relayUrl: ${e.message}")
                                }
                            }
                        }
                    }
                }
                
                // Update existing connections that might have configuration changes
                val existingRelays = currentRelayUrls.intersect(expectedRelayUrls)
                existingRelays.forEach { relayUrl ->
                    try {
                        val connection = relayConnections[relayUrl]
                        val currentConfig = enabledConfigurations.find { it.relayUrls.contains(relayUrl) }
                        
                        if (connection != null && currentConfig != null) {
                            // Check if this connection's configuration has changed
                            if (connection.configurationId != currentConfig.id) {
                                sendDebugLog("🔄 Configuration changed for $relayUrl, resubscribing...")
                                
                                // Close old subscription
                                connection.subscriptionId?.let { subId ->
                                    try {
                                        connection.webSocket?.send(NostrMessage.createClose(subId))
                                        subscriptionManager.removeSubscription(subId)
                                    } catch (e: Exception) {
                                        sendDebugLog("⚠️ Error closing old subscription: ${e.message}")
                                    }
                                }
                                
                                // Update connection config ID and resubscribe
                                try {
                                    val updatedConnection = connection.copy(configurationId = currentConfig.id)
                                    relayConnections[relayUrl] = updatedConnection
                                    subscribeToEvents(updatedConnection, currentConfig)
                                } catch (e: Exception) {
                                    sendDebugLog("⚠️ Error resubscribing to $relayUrl: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        sendDebugLog("⚠️ Error updating existing connection $relayUrl: ${e.message}")
                    }
                }
                
                sendDebugLog("✅ Configuration sync completed")
            } catch (e: Exception) {
                sendDebugLog("❌ Error during configuration sync: ${e.message}")
                Log.e(TAG, "syncConfigurations failed", e)
            }
        }
    }
    
    /**
     * Refresh all WebSocket connections
     */
    fun refreshConnections() {
        CoroutineScope(Dispatchers.IO).launch {
            sendDebugLog("🔄 Refreshing all WebSocket connections...")
            
            // First, sync configurations to ensure we have the right subscriptions
            syncConfigurations()
            
            // Then check connection health for remaining connections
            val staleConnections = mutableListOf<String>()
            
            relayConnections.forEach { (relayUrl, connection) ->
                val webSocket = connection.webSocket
                if (webSocket == null) {
                    sendDebugLog("❌ $relayUrl: No WebSocket instance")
                    staleConnections.add(relayUrl)
                } else {
                    // Try to send a ping to test the connection
                    try {
                        // Check if WebSocket is in OPEN state
                        val isHealthy = isWebSocketHealthy(webSocket, relayUrl)
                        if (!isHealthy) {
                            sendDebugLog("💔 $relayUrl: Connection appears stale")
                            staleConnections.add(relayUrl)
                        } else {
                            sendDebugLog("✅ $relayUrl: Connection appears healthy")
                        }
                    } catch (e: Exception) {
                        sendDebugLog("❌ $relayUrl: Health check failed - ${e.message}")
                        staleConnections.add(relayUrl)
                    }
                }
            }
            
            // Reconnect stale connections
            if (staleConnections.isNotEmpty()) {
                sendDebugLog("🔧 Reconnecting ${staleConnections.size} stale connections...")
                
                staleConnections.forEach { relayUrl ->
                    val connection = relayConnections[relayUrl]
                    if (connection != null) {
                        // Cancel any existing reconnect job
                        connection.reconnectJob?.cancel()
                        
                        // Close old connection if it exists
                        connection.webSocket?.close(1000, "Refreshing connection")
                        
                        // Reset connection state
                        connection.webSocket = null
                        connection.reconnectAttempts = 0
                        
                        // Find the configuration for this relay
                        val configuration = configurationManager.getEnabledConfigurations()
                            .find { config -> config.relayUrls.contains(relayUrl) }
                        
                        if (configuration != null) {
                            // Reconnect
                            connectToRelay(relayUrl, configuration)
                        } else {
                            sendDebugLog("⚠️ No configuration found for $relayUrl, removing connection")
                            relayConnections.remove(relayUrl)
                        }
                    }
                }
            } else {
                sendDebugLog("✅ All connections appear healthy")
            }
        }
    }
    
    /**
     * Check if a WebSocket connection is healthy with comprehensive diagnostics
     */
    fun isWebSocketHealthy(webSocket: WebSocket, relayUrl: String): Boolean {
        val connection = relayConnections[relayUrl]
        val currentTime = System.currentTimeMillis()
        
        return try {
            // Check basic WebSocket state first
            val basicHealthy = try {
                // Try to send a proper WebSocket ping frame
                val pingSuccess = webSocket.send("ping")
                if (pingSuccess && connection != null) {
                    connection.lastPingTime = currentTime
                }
                pingSuccess
            } catch (e: Exception) {
                sendDebugLog("🏥 Health check ping failed for $relayUrl: ${e.message}")
                false
            }
            
            if (connection != null) {
                val timeSinceLastMessage = currentTime - connection.lastMessageTime
                val timeSinceLastPing = currentTime - connection.lastPingTime
                val maxSilenceMs = (batteryPowerManager.getCurrentPingInterval() * 1000 * 2) // 2x ping interval
                
                // Enhanced diagnostic logging
                sendDebugLog("🏥 Health check for ${relayUrl.substringAfter("://").take(20)}:")
                sendDebugLog("   📡 Basic ping: ${if (basicHealthy) "✅" else "❌"}")
                sendDebugLog("   ⏰ Last message: ${timeSinceLastMessage / 1000}s ago")
                sendDebugLog("   🏓 Last ping: ${if (connection.lastPingTime > 0) "${timeSinceLastPing / 1000}s ago" else "never"}")
                sendDebugLog("   📋 Subscription confirmed: ${connection.subscriptionConfirmed}")
                sendDebugLog("   ⚡ Current ping interval: ${batteryPowerManager.getCurrentPingInterval()}s")
                
                // Consider connection unhealthy if we haven't received ANY message in too long
                val isSilentTooLong = timeSinceLastMessage > maxSilenceMs
                if (isSilentTooLong) {
                    sendDebugLog("💔 Connection silent too long: ${timeSinceLastMessage / 1000}s > ${maxSilenceMs / 1000}s")
                    return false
                }
                
                // Consider connection unhealthy if subscription was sent but never confirmed
                if (connection.subscriptionSentTime > 0 && !connection.subscriptionConfirmed) {
                    val timeSinceSubscription = currentTime - connection.subscriptionSentTime
                    if (timeSinceSubscription > 30000) { // 30 seconds
                        sendDebugLog("💔 Subscription not confirmed after ${timeSinceSubscription / 1000}s")
                        return false
                    }
                }
            }
            
            basicHealthy
        } catch (e: Exception) {
            sendDebugLog("💔 Health check exception for $relayUrl: ${e.message}")
            false
        }
    }
}
