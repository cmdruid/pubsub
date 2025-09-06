package com.cmdruid.pubsub.service

import android.content.Context
import android.content.SharedPreferences
import com.cmdruid.pubsub.data.SettingsManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Error types for diagnostic categorization
 */
enum class ErrorType {
    WEBSOCKET,      // WebSocket connection errors
    PARSING,        // Message parsing failures
    FILTER,         // Filter application errors
    NOTIFICATION,   // Notification delivery failures
    SUBSCRIPTION,   // Subscription management errors
    HEALTH,         // Health check failures
    NETWORK,        // Network connectivity issues
    BATTERY,        // Battery optimization errors
    UNKNOWN         // Unclassified errors
}

/**
 * WRITE-ONLY metrics collector for service components
 * - Collects performance data in background threads
 * - Zero overhead when metrics disabled
 * - Never blocks main thread
 * - Persists data to SharedPreferences for cross-instance sharing
 */
class MetricsCollector(
    private val context: Context,
    private val settingsManager: SettingsManager
) {
    
    companion object {
        private const val TAG = "MetricsCollector"
        private const val PREFS_NAME = "metrics_data"
    }
    
    // Background scope for all operations
    private val collectorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Shared storage for cross-instance data sharing
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Fast check if metrics collection is enabled
     */
    private fun isEnabled(): Boolean {
        return settingsManager.isMetricsCollectionActive()
    }
    
    /**
     * Track battery optimization (non-blocking)
     */
    fun trackBatteryOptimization(
        optimizationType: String,
        batteryLevel: Int,
        optimizationApplied: Boolean = true
    ) {
        if (!isEnabled()) return
        
        // All operations in background thread
        collectorScope.launch {
            try {
                val currentChecks = prefs.getLong("battery_level_checks", 0) + 1
                val currentOptimizations = if (optimizationApplied) {
                    prefs.getLong("battery_optimizations", 0) + 1
                } else {
                    prefs.getLong("battery_optimizations", 0)
                }
                
                prefs.edit().apply {
                    putLong("battery_level_checks", currentChecks)
                    putLong("battery_optimizations", currentOptimizations)
                    putInt("last_battery_level", batteryLevel)
                    putLong("last_update", System.currentTimeMillis())
                }.apply()
                
                println("[$TAG] Battery optimization tracked: $optimizationType (level=$batteryLevel%, applied=$optimizationApplied)")
            } catch (e: Exception) {
                // Silent failure to avoid disrupting service
            }
        }
    }
    
    /**
     * Track wake lock usage (non-blocking)
     */
    fun trackWakeLockUsage(
        acquired: Boolean,
        optimized: Boolean = false,
        durationMs: Long = 0
    ) {
        if (!isEnabled()) return
        
        collectorScope.launch {
            try {
                if (acquired) {
                    val acquisitions = prefs.getLong("wake_lock_acquisitions", 0) + 1
                    val optimizations = if (optimized) {
                        prefs.getLong("wake_lock_optimizations", 0) + 1
                    } else {
                        prefs.getLong("wake_lock_optimizations", 0)
                    }
                    
                    prefs.edit().apply {
                        putLong("wake_lock_acquisitions", acquisitions)
                        putLong("wake_lock_optimizations", optimizations)
                        putLong("last_update", System.currentTimeMillis())
                    }.apply()
                }
            } catch (e: Exception) {
                // Silent failure
            }
        }
    }
    
    /**
     * Track connection events (non-blocking)
     */
    fun trackConnectionEvent(
        eventType: String,
        relayUrl: String,
        success: Boolean = true
    ) {
        if (!isEnabled()) return
        
        collectorScope.launch {
            try {
                val editor = prefs.edit()
                
                when (eventType) {
                    "attempt" -> {
                        val attempts = prefs.getLong("connection_attempts", 0) + 1
                        editor.putLong("connection_attempts", attempts)
                    }
                    "success" -> if (success) {
                        val successes = prefs.getLong("successful_connections", 0) + 1
                        editor.putLong("successful_connections", successes)
                    }
                    "failure" -> {
                        val failures = prefs.getLong("connection_failures", 0) + 1
                        editor.putLong("connection_failures", failures)
                    }
                    "reconnect" -> {
                        val reconnects = prefs.getLong("reconnection_attempts", 0) + 1
                        editor.putLong("reconnection_attempts", reconnects)
                    }
                    else -> {
                        // Generic connection events
                        val attempts = prefs.getLong("connection_attempts", 0) + 1
                        editor.putLong("connection_attempts", attempts)
                        if (success) {
                            val successes = prefs.getLong("successful_connections", 0) + 1
                            editor.putLong("successful_connections", successes)
                        }
                    }
                }
                
                editor.putLong("last_update", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                // Silent failure
            }
        }
    }
    
    /**
     * Track health check events (non-blocking)
     */
    fun trackHealthCheck(isBatchCheck: Boolean = false) {
        if (!isEnabled()) return
        
        collectorScope.launch {
            try {
                if (isBatchCheck) {
                    val batchChecks = prefs.getLong("batch_health_checks", 0) + 1
                    prefs.edit().putLong("batch_health_checks", batchChecks)
                } else {
                    val healthChecks = prefs.getLong("health_checks", 0) + 1
                    prefs.edit().putLong("health_checks", healthChecks)
                }
                    .putLong("last_update", System.currentTimeMillis())
                    .apply()
            } catch (e: Exception) {
                // Silent failure
            }
        }
    }
    
    /**
     * Track duplicate event detection (non-blocking)
     */
    fun trackDuplicateEvent(
        eventProcessed: Boolean,
        duplicateDetected: Boolean,
        duplicatePrevented: Boolean,
        usedPreciseTimestamp: Boolean = false,
        networkDataSaved: Long = 0
    ) {
        if (!isEnabled()) return
        
        collectorScope.launch {
            try {
                val editor = prefs.edit()
                
                if (eventProcessed) {
                    val processed = prefs.getLong("events_processed", 0) + 1
                    editor.putLong("events_processed", processed)
                }
                
                if (duplicateDetected) {
                    val detected = prefs.getLong("duplicates_detected", 0) + 1
                    editor.putLong("duplicates_detected", detected)
                }
                
                if (duplicatePrevented) {
                    val prevented = prefs.getLong("duplicates_prevented", 0) + 1
                    val dataSaved = prefs.getLong("network_data_saved", 0) + networkDataSaved
                    editor.putLong("duplicates_prevented", prevented)
                    editor.putLong("network_data_saved", dataSaved)
                }
                
                if (usedPreciseTimestamp) {
                    val precise = prefs.getLong("precise_timestamp_usage", 0) + 1
                    editor.putLong("precise_timestamp_usage", precise)
                } else {
                    val safety = prefs.getLong("safety_buffer_usage", 0) + 1
                    editor.putLong("safety_buffer_usage", safety)
                }
                
                editor.putLong("last_update", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                // Silent failure
            }
        }
    }
    
    /**
     * Track subscription renewal events (non-blocking)
     */
    fun trackSubscriptionRenewal(
        subscriptionId: String,
        relayUrl: String,
        success: Boolean,
        delay: Long,
        reason: String? = null
    ) {
        if (!isEnabled()) return
        
        collectorScope.launch {
            try {
                val editor = prefs.edit()
                
                if (success) {
                    val renewals = prefs.getLong("subscription_renewals", 0) + 1
                    editor.putLong("subscription_renewals", renewals)
                } else {
                    val failures = prefs.getLong("subscription_renewal_failures", 0) + 1
                    editor.putLong("subscription_renewal_failures", failures)
                }
                
                // Track renewal delays (cumulative)
                val totalDelay = prefs.getLong("subscription_renewal_delays", 0) + delay
                editor.putLong("subscription_renewal_delays", totalDelay)
                
                // Track per-subscription renewal count
                val key = "renewals_${subscriptionId}_${relayUrl.hashCode()}"
                val subRenewals = prefs.getLong(key, 0) + 1
                editor.putLong(key, subRenewals)
                
                editor.putLong("last_update", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                // Silent failure
            }
        }
    }
    
    /**
     * Track event flow for subscription health monitoring (non-blocking)
     */
    fun trackEventFlow(
        subscriptionId: String,
        relayUrl: String,
        eventsReceived: Int,
        timeSpan: Long
    ) {
        if (!isEnabled()) return
        
        collectorScope.launch {
            try {
                val editor = prefs.edit()
                
                // Track total events per subscription
                val key = "events_${subscriptionId}_${relayUrl.hashCode()}"
                val totalEvents = prefs.getLong(key, 0) + eventsReceived
                editor.putLong(key, totalEvents)
                
                // Track event flow rate (events per minute)
                val flowRate = if (timeSpan > 0) (eventsReceived * 60000.0 / timeSpan) else 0.0
                val rateKey = "flow_rate_${subscriptionId}_${relayUrl.hashCode()}"
                val avgRate = prefs.getFloat(rateKey, 0f)
                val newAvgRate = (avgRate + flowRate.toFloat()) / 2f // Simple moving average
                editor.putFloat(rateKey, newAvgRate)
                
                // Track silence periods (when eventsReceived = 0)
                if (eventsReceived == 0) {
                    val silencePeriods = prefs.getLong("subscription_silence_periods", 0) + 1
                    editor.putLong("subscription_silence_periods", silencePeriods)
                }
                
                editor.putLong("last_update", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                // Silent failure
            }
        }
    }
    
    /**
     * Track categorized errors for diagnostic purposes (non-blocking)
     */
    fun trackError(
        errorType: ErrorType,
        relayUrl: String? = null,
        subscriptionId: String? = null,
        errorMessage: String
    ) {
        if (!isEnabled()) return

        collectorScope.launch {
            try {
                val editor = prefs.edit()

                // Track error counts by type
                val errorKey = "errors_${errorType.name.lowercase()}"
                val errorCount = prefs.getLong(errorKey, 0) + 1
                editor.putLong(errorKey, errorCount)

                // Track total errors
                val totalErrors = prefs.getLong("total_errors", 0) + 1
                editor.putLong("total_errors", totalErrors)

                // Track per-relay errors
                relayUrl?.let { url ->
                    val relayKey = "relay_errors_${url.hashCode()}"
                    val relayErrors = prefs.getLong(relayKey, 0) + 1
                    editor.putLong(relayKey, relayErrors)
                }

                // Track per-subscription errors
                subscriptionId?.let { subId ->
                    val subKey = "sub_errors_${subId}"
                    val subErrors = prefs.getLong(subKey, 0) + 1
                    editor.putLong(subKey, subErrors)
                }

                // Store last error for diagnostic purposes
                editor.putString("last_error_type", errorType.name)
                editor.putString("last_error_message", errorMessage.take(100)) // Limit length
                editor.putLong("last_error_time", System.currentTimeMillis())

                editor.putLong("last_update", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                // Silent failure
            }
        }
    }

    /**
     * Track notification rate limiting events
     */
    fun trackNotificationRateLimit(
        subscriptionId: String,
        relayUrl: String,
        reason: String
    ) {
        if (!isEnabled()) return

        collectorScope.launch {
            try {
                val editor = prefs.edit()

                // Track total rate limits
                val totalRateLimits = prefs.getLong("notification_rate_limits", 0) + 1
                editor.putLong("notification_rate_limits", totalRateLimits)

                // Track per-subscription rate limits
                val subKey = "rate_limits_${subscriptionId}"
                val subRateLimits = prefs.getLong(subKey, 0) + 1
                editor.putLong(subKey, subRateLimits)

                // Track per-relay rate limits
                val relayKey = "rate_limits_relay_${relayUrl.hashCode()}"
                val relayRateLimits = prefs.getLong(relayKey, 0) + 1
                editor.putLong(relayKey, relayRateLimits)

                // Store last rate limit for diagnostic purposes
                editor.putString("last_rate_limit_reason", reason.take(100))
                editor.putLong("last_rate_limit_time", System.currentTimeMillis())

                editor.putLong("last_update", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                // Silent failure
            }
        }
    }
    
    /**
     * Clear all collected data (non-blocking)
     */
    fun clearAllData() {
        collectorScope.launch {
            try {
                prefs.edit().clear().apply()
                println("[$TAG] All metrics data cleared")
            } catch (e: Exception) {
                println("[$TAG] Error clearing metrics data: ${e.message}")
            }
        }
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        collectorScope.cancel()
    }
}
