package com.cmdruid.pubsub.service.health

import com.cmdruid.pubsub.service.ConnectionState
import com.cmdruid.pubsub.service.RelayHealth
import com.cmdruid.pubsub.service.HealthThresholds

/**
 * Pure health evaluation logic - easy to test
 * Single responsibility: determine if a connection is healthy
 */
class HealthEvaluator {
    
    /**
     * Evaluate if a single connection is healthy using given thresholds
     */
    fun isHealthy(health: RelayHealth, thresholds: HealthThresholds): Boolean {
        return health.state == ConnectionState.CONNECTED &&
               health.subscriptionConfirmed &&
               health.lastMessageAge < thresholds.maxSilenceMs &&
               health.reconnectAttempts < thresholds.maxReconnectAttempts
    }
    
    /**
     * Get specific reason why a connection is unhealthy
     */
    fun getUnhealthyReason(health: RelayHealth, thresholds: HealthThresholds): String {
        return when {
            health.state != ConnectionState.CONNECTED -> "disconnected (${health.state.name})"
            !health.subscriptionConfirmed -> "subscription not confirmed"
            health.lastMessageAge > thresholds.maxSilenceMs -> 
                "silent too long (${health.lastMessageAge/1000}s > ${thresholds.maxSilenceMs/1000}s)"
            health.reconnectAttempts >= thresholds.maxReconnectAttempts -> 
                "too many reconnect attempts (${health.reconnectAttempts})"
            else -> "unknown"
        }
    }
    
    /**
     * Evaluate all connections and return unhealthy ones with reasons
     */
    fun evaluateConnections(
        connections: Map<String, RelayHealth>, 
        thresholds: HealthThresholds
    ): HealthEvaluationResult {
        val unhealthyConnections = mutableMapOf<String, String>()
        
        connections.forEach { (relayUrl, health) ->
            if (!isHealthy(health, thresholds)) {
                val reason = getUnhealthyReason(health, thresholds)
                unhealthyConnections[relayUrl] = reason
            }
        }
        
        return HealthEvaluationResult(
            totalConnections = connections.size,
            healthyConnections = connections.size - unhealthyConnections.size,
            unhealthyConnections = unhealthyConnections,
            thresholds = thresholds
        )
    }
}

/**
 * Result of health evaluation - immutable data class
 */
data class HealthEvaluationResult(
    val totalConnections: Int,
    val healthyConnections: Int,
    val unhealthyConnections: Map<String, String>, // relayUrl -> reason
    val thresholds: HealthThresholds
) {
    val hasUnhealthyConnections: Boolean = unhealthyConnections.isNotEmpty()
    val unhealthyCount: Int = unhealthyConnections.size
    val healthPercentage: Double = if (totalConnections > 0) {
        (healthyConnections.toDouble() / totalConnections * 100.0)
    } else 100.0
}
