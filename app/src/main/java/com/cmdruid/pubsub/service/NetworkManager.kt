package com.cmdruid.pubsub.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manages network connectivity monitoring and network-aware optimization
 * Extracted from PubSubService to improve maintainability
 */
class NetworkManager(
    private val context: Context,
    private val metricsCollector: MetricsCollector,
    private val onNetworkStateChange: (Boolean, String, String) -> Unit,
    private val onRefreshConnections: () -> Unit,
    private val sendDebugLog: (String) -> Unit
) {
    
    companion object {
        private const val TAG = "NetworkManager"
    }
    
    // Network state monitoring
    private lateinit var connectivityManager: ConnectivityManager
    private var isNetworkAvailable: Boolean = true
    private var currentNetworkType: String = "unknown"
    private var networkQuality: String = "unknown"
    private var lastNetworkChange: Long = System.currentTimeMillis()
    
    // Network connectivity callback
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkStateChange(available = true, network = network)
        }
        
        override fun onLost(network: Network) {
            handleNetworkStateChange(available = false, network = network)
        }
        
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            handleNetworkCapabilitiesChanged(network, networkCapabilities)
        }
    }
    
    // Getters for current state
    fun isNetworkAvailable(): Boolean = isNetworkAvailable
    fun getCurrentNetworkType(): String = currentNetworkType
    fun getNetworkQuality(): String = networkQuality
    
    /**
     * Initialize network monitoring
     */
    fun initialize() {
        setupNetworkMonitoring()
    }
    
    /**
     * Clean up network monitoring
     */
    fun cleanup() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering network callback: ${e.message}")
        }
    }
    
    /**
     * Setup network monitoring for battery optimization
     */
    private fun setupNetworkMonitoring() {
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        // Get initial network state
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        
        isNetworkAvailable = activeNetwork != null
        currentNetworkType = getNetworkTypeName(networkCapabilities)
        networkQuality = getNetworkQuality(networkCapabilities)
        
        // Register network callback for monitoring changes
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
            
            metricsCollector.trackConnectionEvent("network_monitoring_initialized", "network_manager", true)
            
            sendDebugLog("Network monitoring setup: $currentNetworkType, available: $isNetworkAvailable")
        } catch (e: Exception) {
            metricsCollector.trackConnectionEvent("network_monitoring_failed", "network_manager", false)
            sendDebugLog("Network monitoring setup failed: ${e.message}")
        }
    }
    
    /**
     * Handle network state changes
     */
    private fun handleNetworkStateChange(available: Boolean, network: Network) {
        val previousState = isNetworkAvailable
        val currentTime = System.currentTimeMillis()
        val networkDownTime = if (!previousState && available) currentTime - lastNetworkChange else 0L
        
        isNetworkAvailable = available
        lastNetworkChange = currentTime
        
        // Get network capabilities for detailed info
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
        val newNetworkType = getNetworkTypeName(networkCapabilities)
        val newNetworkQuality = getNetworkQuality(networkCapabilities)
        
        if (previousState != available || currentNetworkType != newNetworkType) {
            currentNetworkType = newNetworkType
            networkQuality = newNetworkQuality
            
            metricsCollector.trackConnectionEvent("network_optimization", "network_manager", true) // Was: batteryOptimizationLogger.logOptimization(

            
            val statusEmoji = if (available) "✅" else "❌"
            sendDebugLog("$statusEmoji Network: $currentNetworkType, quality: $networkQuality")
            
            // Log network state change for monitoring
            metricsCollector.trackConnectionEvent("network_state_change", "network_manager", true)
            
            // Handle network-aware reconnection logic
            handleNetworkAwareReconnection(available, networkDownTime)
            
            // Track network activity for metrics
            metricsCollector.trackConnectionEvent("network_state_change", "network_manager", true)
            
            // Notify service of the change
            onNetworkStateChange(available, currentNetworkType, networkQuality)
        }
    }
    
    /**
     * Handle network capabilities changes
     */
    private fun handleNetworkCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        val newNetworkType = getNetworkTypeName(networkCapabilities)
        val newNetworkQuality = getNetworkQuality(networkCapabilities)
        
        if (currentNetworkType != newNetworkType || networkQuality != newNetworkQuality) {
            val oldType = currentNetworkType
            val oldQuality = networkQuality
            
            currentNetworkType = newNetworkType
            networkQuality = newNetworkQuality
            
            metricsCollector.trackConnectionEvent("network_optimization", "network_manager", true) // Was: batteryOptimizationLogger.logOptimization(

            
            sendDebugLog("Network capabilities: $oldType → $currentNetworkType, quality: $oldQuality → $networkQuality")
            
            // Adjust optimization based on network quality
            adjustOptimizationForNetworkQuality(newNetworkQuality)
            
            // Notify service of the change
            onNetworkStateChange(isNetworkAvailable, currentNetworkType, networkQuality)
        }
    }
    
    /**
     * Get human-readable network type name
     */
    private fun getNetworkTypeName(networkCapabilities: NetworkCapabilities?): String {
        return when {
            networkCapabilities == null -> "none"
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }
    }
    
    /**
     * Determine network quality based on capabilities
     */
    private fun getNetworkQuality(networkCapabilities: NetworkCapabilities?): String {
        if (networkCapabilities == null) return "none"
        
        return when {
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "high" else "medium"
            }
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "high" else "low"
            }
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "high"
            else -> "medium"
        }
    }
    
    /**
     * Handle network-aware reconnection logic
     */
    private fun handleNetworkAwareReconnection(networkAvailable: Boolean, downTime: Long) {
        if (networkAvailable && downTime > 0) {
            // Network came back online - consider reconnecting
            val shouldReconnect = downTime > 5000L // Only if offline for more than 5 seconds
            
            metricsCollector.trackConnectionEvent("network_optimization", "network_manager", true) // Was: batteryOptimizationLogger.logOptimization(

            
            if (shouldReconnect) {
                sendDebugLog("Network restored, refreshing connections (offline for ${downTime}ms)")
                CoroutineScope(Dispatchers.IO).launch {
                    onRefreshConnections()
                }
            }
        } else if (!networkAvailable) {
            // Network lost - avoid unnecessary reconnection attempts
            sendDebugLog("Network lost, avoiding reconnection attempts")
        }
    }
    
    /**
     * Adjust optimization based on network quality
     */
    private fun adjustOptimizationForNetworkQuality(quality: String) {
        // For optimization, we can adjust ping intervals based on network quality
        // High quality networks can handle more frequent pings
        // Low quality networks should use longer intervals to save battery
        
        val qualityMultiplier = when (quality) {
            "high" -> 1.0    // No adjustment for high quality
            "medium" -> 1.2  // 20% longer intervals for medium quality
            "low" -> 1.5     // 50% longer intervals for low quality
            else -> 1.0
        }
        
        metricsCollector.trackConnectionEvent("network_quality_adjustment", "network_manager", qualityMultiplier != 1.0)
        
        if (qualityMultiplier != 1.0) {
            sendDebugLog("Adjusting optimization for $quality network quality (${qualityMultiplier}x)")
        }
    }
    
    /**
     * Make network-aware decision about whether to attempt reconnection
     */
    fun makeNetworkAwareReconnectionDecision(
        reconnectAttempts: Int,
        currentAppState: PubSubService.AppState
    ): ReconnectionDecision {
        // Don't reconnect if no network
        if (!isNetworkAvailable) {
            return ReconnectionDecision(false, "network_unavailable")
        }
        
        // Don't reconnect if too many attempts
        if (reconnectAttempts >= 10) {
            return ReconnectionDecision(false, "max_attempts_reached")
        }
        
        // Be more conservative on cellular networks
        if (currentNetworkType == "cellular" && networkQuality == "low") {
            if (reconnectAttempts >= 5) {
                return ReconnectionDecision(false, "cellular_low_quality_limit")
            }
        }
        
        // Be more conservative in background/doze/standby modes
        if (currentAppState == PubSubService.AppState.DOZE && reconnectAttempts >= 3) {
            return ReconnectionDecision(false, "doze_mode_limit")
        }
        
        if (currentAppState == PubSubService.AppState.RESTRICTED && reconnectAttempts >= 2) {
            return ReconnectionDecision(false, "restricted_app_limit")
        }
        
        if (currentAppState == PubSubService.AppState.RARE && reconnectAttempts >= 3) {
            return ReconnectionDecision(false, "rare_app_limit")
        }
        
        if (currentAppState == PubSubService.AppState.BACKGROUND && reconnectAttempts >= 7) {
            return ReconnectionDecision(false, "background_mode_limit")
        }
        
        return ReconnectionDecision(true, "network_conditions_favorable")
    }
    
    /**
     * Calculate optimal reconnect delay based on network conditions and app state
     */
    fun calculateOptimalReconnectDelay(
        baseDelay: Long,
        currentAppState: PubSubService.AppState
    ): Long {
        var adjustedDelay = baseDelay
        
        // Adjust based on network type and quality
        when (currentNetworkType) {
            "cellular" -> {
                adjustedDelay = when (networkQuality) {
                    "low" -> (baseDelay * 2.0).toLong()  // Double delay for low quality cellular
                    "medium" -> (baseDelay * 1.5).toLong() // 50% longer for medium cellular
                    else -> baseDelay
                }
            }
            "wifi" -> {
                adjustedDelay = when (networkQuality) {
                    "high" -> (baseDelay * 0.8).toLong() // Faster reconnects on high quality WiFi
                    else -> baseDelay
                }
            }
        }
        
        // Adjust based on app state
        adjustedDelay = when (currentAppState) {
            PubSubService.AppState.FOREGROUND -> adjustedDelay // No adjustment for foreground
            PubSubService.AppState.BACKGROUND -> (adjustedDelay * 1.5).toLong() // 50% longer in background
            PubSubService.AppState.DOZE -> (adjustedDelay * 3.0).toLong() // 3x longer in doze mode
            PubSubService.AppState.RARE -> (adjustedDelay * 2.5).toLong() // 2.5x longer for rare apps
            PubSubService.AppState.RESTRICTED -> (adjustedDelay * 4.0).toLong() // 4x longer for restricted apps
        }
        
        return adjustedDelay
    }
    
    /**
     * Data class for reconnection decisions
     */
    data class ReconnectionDecision(
        val shouldReconnect: Boolean,
        val reason: String
    )
}
