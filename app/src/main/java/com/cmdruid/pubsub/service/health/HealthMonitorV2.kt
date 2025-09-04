package com.cmdruid.pubsub.service.health

import com.cmdruid.pubsub.service.*
import com.cmdruid.pubsub.logging.UnifiedLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Simplified, testable HealthMonitor
 * Responsibilities: 
 * - Periodic scheduling
 * - Coordinating components
 * - Executing actions
 * 
 * Business logic is extracted to testable components
 */
class HealthMonitorV2(
    private val relayConnectionManager: RelayConnectionManager,
    private val batteryPowerManager: BatteryPowerManager,
    private val metricsCollector: MetricsCollector,
    private val networkManager: NetworkManager,
    private val orchestrator: HealthCheckOrchestrator
) {
    companion object {
        private const val TAG = "HealthMonitorV2"
    }
    
    private var monitorJob: Job? = null
    
    /**
     * Start periodic health monitoring
     */
    fun start() {
        monitorJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    val interval = calculateHealthCheckInterval()
                    delay(interval)
                    performHealthCheck()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Log error but continue
                }
            }
        }
    }
    
    /**
     * Stop health monitoring
     */
    fun stop() {
        monitorJob?.cancel()
    }
    
    /**
     * Perform a single health check synchronously (for testing)
     */
    fun runHealthCheckSync(): HealthCheckResult {
        val connections = relayConnectionManager.getConnectionHealth()
        val batteryLevel = batteryPowerManager.getCurrentBatteryLevel()
        val pingInterval = batteryPowerManager.getCurrentPingInterval()
        val networkQuality = networkManager.getNetworkQuality()
        
        val result = orchestrator.performHealthCheck(
            connections = connections,
            batteryLevel = batteryLevel,
            pingInterval = pingInterval,
            networkQuality = networkQuality
        )
        
        // Execute actions
        executeActions(result.actions)
        
        // Track metrics
        metricsCollector.trackHealthCheck(isBatchCheck = true)
        
        return result
    }
    
    /**
     * Perform health check asynchronously (for production use)
     */
    fun runHealthCheck() {
        CoroutineScope(Dispatchers.IO).launch {
            performHealthCheck()
        }
    }
    
    private fun performHealthCheck() {
        runHealthCheckSync()
    }
    
    private fun executeActions(actions: List<HealthCheckAction>) {
        actions.forEach { action ->
            when (action) {
                HealthCheckAction.REFRESH_CONNECTIONS -> {
                    relayConnectionManager.refreshConnections()
                }
            }
        }
    }
    
    private fun calculateHealthCheckInterval(): Long {
        val batteryLevel = batteryPowerManager.getCurrentBatteryLevel()
        val pingInterval = batteryPowerManager.getCurrentPingInterval()
        val networkQuality = networkManager.getNetworkQuality()
        
        val thresholds = HealthThresholds.forBatteryLevel(
            batteryLevel = batteryLevel,
            pingInterval = pingInterval,
            networkQuality = networkQuality
        )
        
        return thresholds.healthCheckInterval
    }
}
