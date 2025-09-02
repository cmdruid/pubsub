package com.cmdruid.pubsub.service

import android.util.Log
import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.data.ConfigurationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles connection health testing and diagnostics for PubSubService
 * Extracted to keep PubSubService focused on core functionality
 */
class ConnectionHealthTester(
    private val relayConnections: ConcurrentHashMap<String, PubSubService.RelayConnection>,
    private val subscriptionManager: SubscriptionManager,
    private val configurationManager: ConfigurationManager,
    private val isWebSocketHealthy: (WebSocket, String) -> Boolean,
    private val connectToRelay: suspend (String, Configuration) -> Unit,
    private val logServiceStats: () -> Unit,
    private val sendDebugLog: (String) -> Unit
) {
    
    companion object {
        private const val TAG = "ConnectionHealthTester"
    }
    
    /**
     * Test connection health for all relays - for manual testing
     */
    fun testConnectionHealth() {
        CoroutineScope(Dispatchers.IO).launch {
            sendDebugLog("=== CONNECTION HEALTH TEST STARTED ===")
            val currentTime = System.currentTimeMillis()
            
            relayConnections.forEach { (relayUrl, connection) ->
                val shortUrl = relayUrl.substringAfter("://").take(20)
                sendDebugLog("Testing $shortUrl...")
                
                val webSocket = connection.webSocket
                if (webSocket == null) {
                    sendDebugLog("No WebSocket instance")
                } else {
                    val isHealthy = isWebSocketHealthy(webSocket, relayUrl)
                    sendDebugLog("Health result: ${if (isHealthy) "✅ HEALTHY" else "❌ UNHEALTHY"}")
                }
                
                // Additional diagnostics
                val timeSinceLastMessage = currentTime - connection.lastMessageTime
                val timeSinceSubscription = if (connection.subscriptionSentTime > 0) currentTime - connection.subscriptionSentTime else -1
                
                sendDebugLog("Last message: ${timeSinceLastMessage / 1000}s ago")
                sendDebugLog("Subscription: ${if (connection.subscriptionConfirmed) "confirmed" else "pending"}")
                sendDebugLog("Reconnect attempts: ${connection.reconnectAttempts}")
            }
            
            sendDebugLog("=== CONNECTION HEALTH TEST COMPLETED ===")
            logServiceStats()
        }
    }
    
    /**
     * Force reconnection of all connections - for testing reconnection logic
     */
    fun forceReconnectAll() {
        CoroutineScope(Dispatchers.IO).launch {
            sendDebugLog("=== FORCE RECONNECT ALL STARTED ===")
            
            val connectionUrls = relayConnections.keys.toList()
            sendDebugLog("Forcing reconnection of ${connectionUrls.size} connections...")
            
            connectionUrls.forEach { relayUrl ->
                val connection = relayConnections[relayUrl]
                if (connection != null) {
                    sendDebugLog("Forcing reconnect: ${relayUrl.substringAfter("://").take(20)}")
                    
                    // Cancel any existing reconnect job
                    connection.reconnectJob?.cancel()
                    
                    // Close existing connection
                    connection.webSocket?.close(1000, "Force reconnect test")
                    
                    // Reset connection state
                    connection.webSocket = null
                    connection.reconnectAttempts = 0
                    connection.subscriptionConfirmed = false
                    
                    // Find configuration and reconnect
                    val configuration = configurationManager.getEnabledConfigurations()
                        .find { config -> config.relayUrls.contains(relayUrl) }
                    
                    if (configuration != null) {
                        connectToRelay(relayUrl, configuration)
                    } else {
                        sendDebugLog("No configuration found for $relayUrl")
                    }
                }
            }
            
            sendDebugLog("=== FORCE RECONNECT ALL COMPLETED ===")
        }
    }
    
    /**
     * Log detailed connection statistics - for debugging
     */
    fun logDetailedConnectionStats(
        currentAppState: PubSubService.AppState,
        currentPingInterval: Long,
        currentNetworkType: String,
        isNetworkAvailable: Boolean,
        currentBatteryLevel: Int,
        isCharging: Boolean,
        isDozeMode: Boolean
    ) {
        sendDebugLog("=== DETAILED CONNECTION STATS ===")
        val currentTime = System.currentTimeMillis()
        
        sendDebugLog("Service State:")
        sendDebugLog("App state: ${currentAppState.name}")
        sendDebugLog("Ping interval: ${currentPingInterval}s")
        sendDebugLog("Network: $currentNetworkType (available: $isNetworkAvailable)")
        sendDebugLog("Battery: $currentBatteryLevel% (charging: $isCharging)")
        sendDebugLog("Doze mode: $isDozeMode")
        
        val subscriptionStats = subscriptionManager.getStats()
        sendDebugLog("Subscription Stats:")
        sendDebugLog("Active subscriptions: ${subscriptionStats.activeCount}")
        sendDebugLog("Timestamp tracking: ${subscriptionStats.timestampCount}")
        
        sendDebugLog("Connection Details:")
        relayConnections.forEach { (relayUrl, connection) ->
            val shortUrl = relayUrl.substringAfter("://").take(20)
            val timeSinceLastMessage = currentTime - connection.lastMessageTime
            val timeSinceLastPing = if (connection.lastPingTime > 0) currentTime - connection.lastPingTime else -1
            val timeSinceSubscription = if (connection.subscriptionSentTime > 0) currentTime - connection.subscriptionSentTime else -1
            
            sendDebugLog("$shortUrl:")
            sendDebugLog("WebSocket: ${if (connection.webSocket != null) "present" else "null"}")
            sendDebugLog("Config ID: ${connection.configurationId}")
            sendDebugLog("Subscription ID: ${connection.subscriptionId}")
            sendDebugLog("Subscription confirmed: ${connection.subscriptionConfirmed}")
            sendDebugLog("Last message: ${if (timeSinceLastMessage < 60000) "${timeSinceLastMessage / 1000}s" else "${timeSinceLastMessage / 60000}min"} ago")
            sendDebugLog("Last ping: ${if (timeSinceLastPing >= 0) "${timeSinceLastPing / 1000}s ago" else "never"}")
            sendDebugLog("Subscription sent: ${if (timeSinceSubscription >= 0) "${timeSinceSubscription / 1000}s ago" else "never"}")
            sendDebugLog("Reconnect attempts: ${connection.reconnectAttempts}")
        }
        
        sendDebugLog("=== END DETAILED STATS ===")
    }
}
