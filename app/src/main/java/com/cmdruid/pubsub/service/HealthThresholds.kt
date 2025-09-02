package com.cmdruid.pubsub.service

/**
 * Dynamic health monitoring thresholds based on battery level and app state
 * Part of the battery optimization improvements for health monitoring
 */
data class HealthThresholds(
    val maxSilenceMs: Long,           // Maximum time without messages before unhealthy
    val maxReconnectAttempts: Int,    // Maximum reconnection attempts
    val healthCheckInterval: Long,    // Interval between health checks
    val subscriptionTimeoutMs: Long   // Timeout for subscription confirmation
) {
    companion object {
        /**
         * Calculate optimal health thresholds based on current conditions
         */
        fun forBatteryLevel(
            batteryLevel: Int,
            pingInterval: Long,
            networkQuality: String
        ): HealthThresholds {
            return when {
                batteryLevel <= 15 -> HealthThresholds(
                    maxSilenceMs = pingInterval * 5000,  // Very lenient for critical battery
                    maxReconnectAttempts = 2,
                    healthCheckInterval = pingInterval * 8000,
                    subscriptionTimeoutMs = getSubscriptionTimeout(networkQuality) * 2
                )
                batteryLevel <= 30 -> HealthThresholds(
                    maxSilenceMs = pingInterval * 3500,  // Lenient for low battery
                    maxReconnectAttempts = 3,
                    healthCheckInterval = pingInterval * 3000,
                    subscriptionTimeoutMs = (getSubscriptionTimeout(networkQuality) * 1.5).toLong()
                )
                else -> HealthThresholds(
                    maxSilenceMs = pingInterval * 2500,  // Normal behavior
                    maxReconnectAttempts = 10,
                    healthCheckInterval = pingInterval * 1500,
                    subscriptionTimeoutMs = getSubscriptionTimeout(networkQuality)
                )
            }
        }
        
        /**
         * Get subscription timeout based on network quality
         */
        private fun getSubscriptionTimeout(networkQuality: String): Long {
            return when (networkQuality) {
                "high" -> 15000L    // 15s for high-quality networks
                "medium" -> 30000L  // 30s for medium-quality networks
                "low" -> 60000L     // 60s for low-quality networks
                else -> 45000L      // 45s for unknown quality
            }
        }
        
        /**
         * Get default thresholds for when battery/network info unavailable
         */
        fun getDefault(pingInterval: Long): HealthThresholds {
            return HealthThresholds(
                maxSilenceMs = pingInterval * 2500,
                maxReconnectAttempts = 10,
                healthCheckInterval = pingInterval * 1500,
                subscriptionTimeoutMs = 30000L
            )
        }
    }
    
    /**
     * Check if these thresholds are more conservative than another set
     */
    fun isMoreConservative(other: HealthThresholds): Boolean {
        return maxSilenceMs > other.maxSilenceMs &&
               healthCheckInterval > other.healthCheckInterval &&
               subscriptionTimeoutMs > other.subscriptionTimeoutMs
    }
    
    /**
     * Get human-readable summary of thresholds
     */
    fun getSummary(): String {
        return "HealthThresholds(silence=${maxSilenceMs/1000}s, attempts=$maxReconnectAttempts, interval=${healthCheckInterval/1000}s, timeout=${subscriptionTimeoutMs/1000}s)"
    }
}
