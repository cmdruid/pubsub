package com.cmdruid.pubsub.service.health

import com.cmdruid.pubsub.service.*
import com.cmdruid.pubsub.logging.LogDomain
import com.cmdruid.pubsub.logging.UnifiedLogger

/**
 * Orchestrates health checks - testable with clear dependencies
 * Single responsibility: coordinate health checking process
 */
class HealthCheckOrchestrator(
    private val healthEvaluator: HealthEvaluator,
    private val logger: UnifiedLogger
) {
    
    /**
     * Perform health check and return actions to take
     * Pure function - easy to test
     */
    fun performHealthCheck(
        connections: Map<String, RelayHealth>,
        batteryLevel: Int,
        pingInterval: Long,
        networkQuality: String
    ): HealthCheckResult {
        
        // Calculate dynamic thresholds
        val thresholds = HealthThresholds.forBatteryLevel(
            batteryLevel = batteryLevel,
            pingInterval = pingInterval,
            networkQuality = networkQuality
        )
        
        // Evaluate connection health
        val evaluation = healthEvaluator.evaluateConnections(connections, thresholds)
        
        // Log individual connection statuses
        connections.forEach { (relayUrl, health) ->
            val shortUrl = relayUrl.substringAfter("://").take(20)
            val isHealthy = healthEvaluator.isHealthy(health, thresholds)
            
            if (isHealthy) {
                logger.debug(LogDomain.HEALTH, "$shortUrl: ✅ (${health.lastMessageAge/1000}s ago)")
            } else {
                val reason = healthEvaluator.getUnhealthyReason(health, thresholds)
                logger.warn(LogDomain.HEALTH, "$shortUrl: Unhealthy - $reason (threshold: ${thresholds.maxSilenceMs/1000}s)")
            }
        }
        
        // Determine actions to take
        val actions = if (evaluation.hasUnhealthyConnections) {
            // Log summary
            logger.warn(LogDomain.HEALTH, 
                "Enhanced health check found ${evaluation.unhealthyCount} unhealthy connections", 
                mapOf(
                    "battery_level" to batteryLevel,
                    "network_quality" to networkQuality,
                    "thresholds_used" to thresholds.getSummary()
                )
            )
            
            // Log specific reconnection triggers
            evaluation.unhealthyConnections.forEach { (relayUrl, reason) ->
                val shortUrl = relayUrl.substringAfter("://").take(20)
                logger.warn(LogDomain.HEALTH, "Triggering reconnection for $shortUrl: $reason")
            }
            
            listOf(HealthCheckAction.REFRESH_CONNECTIONS)
        } else {
            logger.debug(LogDomain.HEALTH, "All connections healthy with dynamic thresholds")
            emptyList()
        }
        
        // Log effectiveness
        logger.debug(LogDomain.HEALTH, "Enhanced health check effectiveness", mapOf(
            "total_connections" to evaluation.totalConnections,
            "healthy_connections" to evaluation.healthyConnections,
            "unhealthy_connections" to evaluation.unhealthyCount,
            "health_percentage" to evaluation.healthPercentage,
            "thresholds_applied" to thresholds.getSummary(),
            "battery_level" to batteryLevel,
            "network_quality" to networkQuality
        ))
        
        return HealthCheckResult(
            evaluation = evaluation,
            actions = actions,
            batteryLevel = batteryLevel,
            networkQuality = networkQuality
        )
    }
}

/**
 * Result of a health check - immutable and testable
 */
data class HealthCheckResult(
    val evaluation: HealthEvaluationResult,
    val actions: List<HealthCheckAction>,
    val batteryLevel: Int,
    val networkQuality: String
) {
    val shouldRefreshConnections: Boolean = actions.contains(HealthCheckAction.REFRESH_CONNECTIONS)
}

/**
 * Actions that can be taken based on health check results
 */
enum class HealthCheckAction {
    REFRESH_CONNECTIONS,
    // Future actions could be added here
}
