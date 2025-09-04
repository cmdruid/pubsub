package com.cmdruid.pubsub.service

import com.cmdruid.pubsub.logging.LogDomain
import com.cmdruid.pubsub.logging.UnifiedLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Dedicated health monitoring component
 * EXTRACTED from PubSubService for clean separation of concerns
 */
class HealthMonitor(
    private val relayConnectionManager: RelayConnectionManager,
    private val batteryPowerManager: BatteryPowerManager,
    private val metricsCollector: MetricsCollector,
    private val networkManager: NetworkManager,
    private val unifiedLogger: UnifiedLogger
) {
    companion object {
        private const val TAG = "HealthMonitor"
    }
    
    private var monitorJob: Job? = null
    
    /**
     * Start adaptive health monitoring
     */
    fun start() {
        monitorJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    val interval = calculateHealthCheckInterval()
                    delay(interval)
                    
                    unifiedLogger.debug(LogDomain.HEALTH, "Adaptive health check starting (interval: ${interval/1000}s)")
                    
                    performHealthCheck()
                    
                } catch (e: CancellationException) {
                    unifiedLogger.info(LogDomain.HEALTH, "Health monitor stopped (service shutting down)")
                    throw e
                } catch (e: Exception) {
                    unifiedLogger.error(LogDomain.HEALTH, "Health monitor error: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Stop health monitoring
     */
    fun stop() {
        monitorJob?.cancel()
        unifiedLogger.debug(LogDomain.HEALTH, "Health monitor stopped")
    }
    
    /**
     * Perform a single health check
     */
    fun runHealthCheck() {
        CoroutineScope(Dispatchers.IO).launch {
            performHealthCheck()
        }
    }
    
    /**
     * ENHANCED health check using dynamic thresholds and smart criteria
     */
    private fun performHealthCheck() {
        val healthResults = relayConnectionManager.getConnectionHealth()
        val batteryLevel = batteryPowerManager.getCurrentBatteryLevel()
        val networkQuality = networkManager.getNetworkQuality()
        
        // Get DYNAMIC health thresholds based on current conditions
        val thresholds = HealthThresholds.forBatteryLevel(
            batteryLevel = batteryLevel,
            pingInterval = batteryPowerManager.getCurrentPingInterval(),
            networkQuality = networkQuality
        )
        
        // Evaluate health with DYNAMIC thresholds (not fixed criteria)
        val unhealthyRelays = healthResults.filterValues { health ->
            !isHealthyWithDynamicThresholds(health, thresholds)
        }
        
        // Log health status with threshold context
        healthResults.forEach { (relayUrl, health) ->
            val shortUrl = relayUrl.substringAfter("://").take(20)
            val isHealthy = isHealthyWithDynamicThresholds(health, thresholds)
            
            if (isHealthy) {
                unifiedLogger.debug(LogDomain.HEALTH, "$shortUrl: ${health.getShortStatus()} (${health.lastMessageAge/1000}s ago)")
            } else {
                val reason = getUnhealthyReason(health, thresholds)
                unifiedLogger.warn(LogDomain.HEALTH, "$shortUrl: Unhealthy - $reason (threshold: ${thresholds.maxSilenceMs/1000}s)")
            }
        }
        
        // Trigger reconnection with smart logic
        if (unhealthyRelays.isNotEmpty()) {
            unifiedLogger.warn(LogDomain.HEALTH, "Enhanced health check found ${unhealthyRelays.size} unhealthy connections", mapOf(
                "battery_level" to batteryLevel,
                "network_quality" to networkQuality,
                "thresholds_used" to thresholds.getSummary()
            ))
            
            // Log which relays are being triggered for reconnection
            unhealthyRelays.forEach { (relayUrl, health) ->
                val shortUrl = relayUrl.substringAfter("://").take(20)
                val reason = getUnhealthyReason(health, thresholds)
                unifiedLogger.warn(LogDomain.HEALTH, "Triggering reconnection for $shortUrl: $reason")
            }
            
            relayConnectionManager.refreshConnections()
        } else {
            unifiedLogger.debug(LogDomain.HEALTH, "All connections healthy with dynamic thresholds")
        }
        
        // Update battery metrics
        metricsCollector.trackHealthCheck(isBatchCheck = true)
        
        // Log enhanced health check effectiveness
        logEnhancedHealthCheckEffectiveness(healthResults, thresholds)
    }
    
    /**
     * Check if connection is healthy using DYNAMIC thresholds
     */
    private fun isHealthyWithDynamicThresholds(health: RelayHealth, thresholds: HealthThresholds): Boolean {
        return health.state == ConnectionState.CONNECTED &&
               health.subscriptionConfirmed &&
               health.lastMessageAge < thresholds.maxSilenceMs &&
               health.reconnectAttempts < thresholds.maxReconnectAttempts
    }
    
    /**
     * Get specific reason why connection is unhealthy
     */
    private fun getUnhealthyReason(health: RelayHealth, thresholds: HealthThresholds): String {
        return when {
            health.state != ConnectionState.CONNECTED -> "disconnected (${health.state.name})"
            !health.subscriptionConfirmed -> "subscription not confirmed"
            health.lastMessageAge > thresholds.maxSilenceMs -> "silent too long (${health.lastMessageAge/1000}s > ${thresholds.maxSilenceMs/1000}s)"
            health.reconnectAttempts >= thresholds.maxReconnectAttempts -> "too many reconnect attempts (${health.reconnectAttempts})"
            else -> "unknown"
        }
    }
    
    /**
     * ENHANCED adaptive health check interval using HealthThresholds system
     */
    private fun calculateHealthCheckInterval(): Long {
        val batteryLevel = batteryPowerManager.getCurrentBatteryLevel()
        val pingInterval = batteryPowerManager.getCurrentPingInterval()
        val networkQuality = networkManager.getNetworkQuality()
        
        // Get DYNAMIC thresholds based on current conditions
        val thresholds = HealthThresholds.forBatteryLevel(
            batteryLevel = batteryLevel,
            pingInterval = pingInterval,
            networkQuality = networkQuality
        )
        
        // Use the calculated health check interval from thresholds
        val adaptiveInterval = thresholds.healthCheckInterval
        
        unifiedLogger.debug(LogDomain.HEALTH, "Enhanced adaptive health interval", mapOf(
            "ping_interval" to "${pingInterval}s",
            "battery_level" to batteryLevel,
            "network_quality" to networkQuality,
            "adaptive_interval_ms" to adaptiveInterval,
            "adaptive_interval_s" to adaptiveInterval / 1000,
            "thresholds_summary" to thresholds.getSummary()
        ))
        
        return adaptiveInterval
    }
    
    /**
     * ENHANCED health check effectiveness logging with dynamic threshold context
     */
    private fun logEnhancedHealthCheckEffectiveness(
        healthResults: Map<String, RelayHealth>, 
        thresholds: HealthThresholds
    ) {
        val healthyCount = healthResults.count { isHealthyWithDynamicThresholds(it.value, thresholds) }
        val unhealthyCount = healthResults.size - healthyCount
        
        unifiedLogger.debug(LogDomain.HEALTH, "Enhanced health check effectiveness", mapOf(
            "total_connections" to healthResults.size,
            "healthy_connections" to healthyCount,
            "unhealthy_connections" to unhealthyCount,
            "health_percentage" to if (healthResults.isNotEmpty()) {
                (healthyCount.toDouble() / healthResults.size * 100.0)
            } else 100.0,
            "thresholds_applied" to thresholds.getSummary(),
            "battery_level" to batteryPowerManager.getCurrentBatteryLevel(),
            "network_quality" to networkManager.getNetworkQuality()
        ))
    }
}
