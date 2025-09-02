package com.cmdruid.pubsub.service

import android.content.Context
import android.content.SharedPreferences
import com.cmdruid.pubsub.data.SettingsManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
