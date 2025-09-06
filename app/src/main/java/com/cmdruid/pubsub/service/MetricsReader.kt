package com.cmdruid.pubsub.service

import android.content.Context
import android.content.SharedPreferences
import com.cmdruid.pubsub.data.SettingsManager
import kotlinx.coroutines.*
import java.io.File

/**
 * READ-ONLY metrics reader for UI components
 * - Reads performance data in background threads
 * - Zero overhead when metrics disabled
 * - Never blocks main thread
 * - Reads from SharedPreferences written by MetricsCollector
 */
class MetricsReader(
    private val context: Context,
    private val settingsManager: SettingsManager
) {
    
    companion object {
        private const val TAG = "MetricsReader"
        private const val PREFS_NAME = "metrics_data"
    }
    
    // Background scope for all operations
    private val readerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Shared storage for reading data written by MetricsCollector
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Fast check if metrics are enabled
     */
    private fun isEnabled(): Boolean {
        return settingsManager.isMetricsCollectionActive()
    }
    
    /**
     * Generate metrics report asynchronously (never blocks caller)
     */
    suspend fun generateMetricsReport(): MetricsReport? = withContext(Dispatchers.IO) {
        if (!isEnabled()) return@withContext null
        
        try {
            val currentTime = System.currentTimeMillis()
            val startTime = prefs.getLong("start_time", currentTime)
            
            MetricsReport(
                generatedAt = currentTime,
                generationTimeMs = 0, // No complex generation needed
                batteryReport = generateBatteryReport(currentTime, startTime),
                connectionReport = generateConnectionReport(currentTime, startTime),
                subscriptionReport = generateSubscriptionReport(currentTime, startTime),
                errorReport = generateErrorReport(currentTime, startTime),
                notificationReport = generateNotificationReport(currentTime, startTime),
                duplicateEventReport = generateDuplicateEventReport(currentTime, startTime)
            )
        } catch (e: Exception) {
            println("[$TAG] Error generating report: ${e.message}")
            null
        }
    }
    
    /**
     * Generate battery metrics report (background thread)
     */
    private fun generateBatteryReport(currentTime: Long, startTime: Long): BatteryReport {
        val durationHours = (currentTime - startTime) / (1000.0 * 60.0 * 60.0)
        val batteryChecks = prefs.getLong("battery_level_checks", 0)
        val optimizationsApplied = prefs.getLong("battery_optimizations", 0)
        val wakeLockAcquisitions = prefs.getLong("wake_lock_acquisitions", 0)
        val wakeLockOptimizations = prefs.getLong("wake_lock_optimizations", 0)
        val currentBatteryLevel = prefs.getInt("last_battery_level", -1)
        
        val optimizationRate = if (batteryChecks > 0) {
            val rate = (optimizationsApplied.toDouble() / batteryChecks * 100.0)
            // Cap at 100% to prevent display issues
            minOf(rate, 100.0)
        } else 0.0
        
        val wakeLockOptimizationRate = if (wakeLockAcquisitions > 0) {
            val rate = (wakeLockOptimizations.toDouble() / wakeLockAcquisitions * 100.0)
            // Cap at 100% to prevent display issues
            minOf(rate, 100.0)
        } else 0.0
        
        return BatteryReport(
            collectionDurationHours = durationHours,
            batteryChecks = batteryChecks,
            optimizationsApplied = optimizationsApplied,
            optimizationRate = optimizationRate,
            wakeLockAcquisitions = wakeLockAcquisitions,
            wakeLockOptimizations = wakeLockOptimizations,
            wakeLockOptimizationRate = wakeLockOptimizationRate,
            currentBatteryLevel = currentBatteryLevel
        )
    }
    
    /**
     * Generate connection metrics report (background thread)
     */
    private fun generateConnectionReport(currentTime: Long, startTime: Long): ConnectionReport {
        val durationHours = (currentTime - startTime) / (1000.0 * 60.0 * 60.0)
        val connectionAttempts = prefs.getLong("connection_attempts", 0)
        val successfulConnections = prefs.getLong("successful_connections", 0)
        val reconnectionAttempts = prefs.getLong("reconnection_attempts", 0)
        val healthChecks = prefs.getLong("health_checks", 0)
        val batchHealthChecks = prefs.getLong("batch_health_checks", 0)
        
        val successRate = if (connectionAttempts > 0) {
            (successfulConnections.toDouble() / connectionAttempts * 100.0)
        } else 0.0
        
        val totalHealthChecks = healthChecks + batchHealthChecks
        val batchCheckRate = if (totalHealthChecks > 0) {
            (batchHealthChecks.toDouble() / totalHealthChecks * 100.0)
        } else 0.0
        
        return ConnectionReport(
            collectionDurationHours = durationHours,
            connectionAttempts = connectionAttempts,
            successfulConnections = successfulConnections,
            connectionSuccessRate = successRate,
            reconnectionAttempts = reconnectionAttempts,
            totalHealthChecks = totalHealthChecks,
            batchHealthChecks = batchHealthChecks,
            batchCheckOptimizationRate = batchCheckRate
        )
    }
    
    /**
     * Generate duplicate event metrics report (background thread)
     */
    private fun generateDuplicateEventReport(currentTime: Long, startTime: Long): DuplicateEventReport {
        val durationHours = (currentTime - startTime) / (1000.0 * 60.0 * 60.0)
        val eventsProcessed = prefs.getLong("events_processed", 0)
        val duplicatesDetected = prefs.getLong("duplicates_detected", 0)
        val duplicatesPrevented = prefs.getLong("duplicates_prevented", 0)
        val networkDataSaved = prefs.getLong("network_data_saved", 0)
        val preciseTimestampUsage = prefs.getLong("precise_timestamp_usage", 0)
        val safetyBufferUsage = prefs.getLong("safety_buffer_usage", 0)
        
        val duplicateRate = if (eventsProcessed > 0) {
            (duplicatesDetected.toDouble() / eventsProcessed * 100.0)
        } else 0.0
        
        // FIXED: Prevention rate should be based on total duplicates (detected + prevented)
        // This represents how many duplicates we successfully prevented vs total duplicates encountered
        val totalDuplicates = duplicatesDetected + duplicatesPrevented
        val preventionRate = if (totalDuplicates > 0) {
            (duplicatesPrevented.toDouble() / totalDuplicates * 100.0)
        } else 0.0
        
        val totalTimestampUsage = preciseTimestampUsage + safetyBufferUsage
        val preciseTimestampRate = if (totalTimestampUsage > 0) {
            (preciseTimestampUsage.toDouble() / totalTimestampUsage * 100.0)
        } else 0.0
        
        return DuplicateEventReport(
            collectionDurationHours = durationHours,
            eventsProcessed = eventsProcessed,
            duplicatesDetected = duplicatesDetected,
            duplicateRate = duplicateRate,
            duplicatesPrevented = duplicatesPrevented,
            preventionRate = preventionRate,
            networkDataSavedBytes = networkDataSaved,
            preciseTimestampUsage = preciseTimestampUsage,
            preciseTimestampRate = preciseTimestampRate
        )
    }
    
    /**
     * Generate subscription report (background thread)
     */
    private fun generateSubscriptionReport(currentTime: Long, startTime: Long): SubscriptionReport {
        val durationHours = (currentTime - startTime) / (1000.0 * 60.0 * 60.0)
        val renewals = prefs.getLong("subscription_renewals", 0)
        val failures = prefs.getLong("subscription_renewal_failures", 0)
        val totalDelays = prefs.getLong("subscription_renewal_delays", 0)
        val silencePeriods = prefs.getLong("subscription_silence_periods", 0)
        
        val totalRenewals = renewals + failures
        val renewalSuccessRate = if (totalRenewals > 0) {
            (renewals.toDouble() / totalRenewals * 100.0)
        } else 0.0
        
        val averageRenewalDelay = if (totalRenewals > 0) {
            totalDelays.toDouble() / totalRenewals
        } else 0.0
        
        // Calculate total event flow from all subscription keys
        var totalEventFlow = 0L
        var totalFlowRates = 0.0
        var flowRateCount = 0
        
        val allKeys = prefs.all.keys
        allKeys.forEach { key ->
            when {
                key.startsWith("events_") -> {
                    totalEventFlow += prefs.getLong(key, 0)
                }
                key.startsWith("flow_rate_") -> {
                    totalFlowRates += prefs.getFloat(key, 0f).toDouble()
                    flowRateCount++
                }
            }
        }
        
        val averageEventFlowRate = if (flowRateCount > 0) {
            totalFlowRates / flowRateCount
        } else 0.0
        
        return SubscriptionReport(
            collectionDurationHours = durationHours,
            subscriptionRenewals = renewals,
            subscriptionRenewalFailures = failures,
            renewalSuccessRate = renewalSuccessRate,
            averageRenewalDelay = averageRenewalDelay,
            subscriptionSilencePeriods = silencePeriods,
            totalEventFlow = totalEventFlow,
            averageEventFlowRate = averageEventFlowRate
        )
    }
    
    /**
     * Generate error report (background thread)
     */
    private fun generateErrorReport(currentTime: Long, startTime: Long): ErrorReport {
        val durationHours = (currentTime - startTime) / (1000.0 * 60.0 * 60.0)
        val totalErrors = prefs.getLong("total_errors", 0)
        val websocketErrors = prefs.getLong("errors_websocket", 0)
        val parsingErrors = prefs.getLong("errors_parsing", 0)
        val filterErrors = prefs.getLong("errors_filter", 0)
        val notificationErrors = prefs.getLong("errors_notification", 0)
        val subscriptionErrors = prefs.getLong("errors_subscription", 0)
        val healthErrors = prefs.getLong("errors_health", 0)
        val networkErrors = prefs.getLong("errors_network", 0)
        val batteryErrors = prefs.getLong("errors_battery", 0)
        val unknownErrors = prefs.getLong("errors_unknown", 0)
        
        return ErrorReport(
            collectionDurationHours = durationHours,
            totalErrors = totalErrors,
            websocketErrors = websocketErrors,
            parsingErrors = parsingErrors,
            filterErrors = filterErrors,
            notificationErrors = notificationErrors,
            subscriptionErrors = subscriptionErrors,
            healthErrors = healthErrors,
            networkErrors = networkErrors,
            batteryErrors = batteryErrors,
            unknownErrors = unknownErrors,
            lastErrorType = prefs.getString("last_error_type", null),
            lastErrorMessage = prefs.getString("last_error_message", null),
            lastErrorTime = prefs.getLong("last_error_time", 0)
        )
    }

    private fun generateNotificationReport(currentTime: Long, startTime: Long): NotificationReport {
        val durationHours = (currentTime - startTime) / (1000.0 * 60.0 * 60.0)
        val totalRateLimits = prefs.getLong("notification_rate_limits", 0)
        
        return NotificationReport(
            collectionDurationHours = durationHours,
            totalRateLimits = totalRateLimits,
            lastRateLimitReason = prefs.getString("last_rate_limit_reason", null),
            lastRateLimitTime = prefs.getLong("last_rate_limit_time", 0)
        )
    }
    
    /**
     * Generate enhanced metrics report with comprehensive diagnostics
     * This is called by the existing metrics export functionality
     */
    suspend fun generateEnhancedMetricsReport(): EnhancedMetricsReport? = withContext(Dispatchers.IO) {
        if (!isEnabled()) return@withContext null
        
        try {
            val currentTime = System.currentTimeMillis()
            val startTime = prefs.getLong("start_time", currentTime)
            
            // Generate standard metrics report
            val standardReport = generateMetricsReport()
            
            // Add comprehensive system diagnostics
            val systemDiagnostics = generateSystemDiagnostics()
            val loggingDiagnostics = generateLoggingDiagnostics()
            
            EnhancedMetricsReport(
                standardMetrics = standardReport,
                systemDiagnostics = systemDiagnostics,
                loggingDiagnostics = loggingDiagnostics,
                exportTimestamp = currentTime
            )
        } catch (e: Exception) {
            println("[$TAG] Error generating enhanced report: ${e.message}")
            null
        }
    }
    
    private fun generateSystemDiagnostics(): SystemDiagnostics {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val runtime = Runtime.getRuntime()
            
            SystemDiagnostics(
                deviceManufacturer = android.os.Build.MANUFACTURER,
                deviceModel = android.os.Build.MODEL,
                androidVersion = android.os.Build.VERSION.RELEASE,
                sdkVersion = android.os.Build.VERSION.SDK_INT,
                appVersion = "0.9.7",
                batteryLevel = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY),
                isCharging = batteryManager.isCharging,
                batteryStatus = getBatteryStatusString(batteryManager),
                networkAvailable = connectivityManager.activeNetwork != null,
                networkType = connectivityManager.activeNetworkInfo?.typeName ?: "unknown",
                networkConnected = connectivityManager.activeNetworkInfo?.isConnected ?: false,
                usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024,
                totalMemoryMB = runtime.totalMemory() / 1024 / 1024,
                maxMemoryMB = runtime.maxMemory() / 1024 / 1024,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            // Return minimal diagnostics if there's an error
            SystemDiagnostics(
                deviceManufacturer = "unknown",
                deviceModel = "unknown", 
                androidVersion = "unknown",
                sdkVersion = 0,
                appVersion = "0.9.7",
                batteryLevel = -1,
                isCharging = false,
                batteryStatus = "unknown",
                networkAvailable = false,
                networkType = "unknown",
                networkConnected = false,
                usedMemoryMB = 0,
                totalMemoryMB = 0,
                maxMemoryMB = 0,
                timestamp = System.currentTimeMillis()
            )
        }
    }
    
    private fun generateLoggingDiagnostics(): LoggingDiagnostics {
        return try {
            val logFilter = settingsManager.getLogFilter()
            
            LoggingDiagnostics(
                filterValid = logFilter.enabledTypes.isNotEmpty() && logFilter.enabledDomains.isNotEmpty(),
                enabledTypes = logFilter.enabledTypes.map { it.name },
                enabledDomains = logFilter.enabledDomains.map { it.name },
                maxLogs = logFilter.maxLogs,
                debugConsoleVisible = settingsManager.shouldShowDebugConsole(),
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            LoggingDiagnostics(
                filterValid = false,
                enabledTypes = emptyList(),
                enabledDomains = emptyList(),
                maxLogs = 0,
                debugConsoleVisible = false,
                timestamp = System.currentTimeMillis()
            )
        }
    }
    
    private fun getBatteryStatusString(batteryManager: android.os.BatteryManager): String {
        return try {
            val status = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)
            when (status) {
                android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                android.os.BatteryManager.BATTERY_STATUS_FULL -> "Full"
                android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
                android.os.BatteryManager.BATTERY_STATUS_UNKNOWN -> "Unknown"
                else -> "Unknown ($status)"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /**
     * Clear all metrics data (non-blocking)
     */
    fun clearAllData() {
        readerScope.launch {
            try {
                // Clear SharedPreferences
                prefs.edit().clear().apply()
                
                // Clear any cached metrics files
                val metricsDir = File(context.cacheDir, "metrics")
                if (metricsDir.exists()) {
                    metricsDir.deleteRecursively()
                }
                
                val performanceMetricsDir = File(context.cacheDir, "performance_metrics")
                if (performanceMetricsDir.exists()) {
                    performanceMetricsDir.deleteRecursively()
                }
                
                // Clear any metrics log files
                val filesDir = context.filesDir
                filesDir.listFiles { file ->
                    file.name.contains("metrics") || 
                    file.name.contains("performance") ||
                    file.name.contains("battery_optimization") ||
                    file.name.contains("network_optimization")
                }?.forEach { it.delete() }
                
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
        readerScope.cancel()
    }
    
    // Data classes for reports (same as before)
    data class MetricsReport(
        val generatedAt: Long,
        val generationTimeMs: Long,
        val batteryReport: BatteryReport?,
        val connectionReport: ConnectionReport?,
        val subscriptionReport: SubscriptionReport?,
        val errorReport: ErrorReport?,
        val notificationReport: NotificationReport?,
        val duplicateEventReport: DuplicateEventReport?
    )
    
    data class BatteryReport(
        val collectionDurationHours: Double,
        val batteryChecks: Long,
        val optimizationsApplied: Long,
        val optimizationRate: Double,
        val wakeLockAcquisitions: Long,
        val wakeLockOptimizations: Long,
        val wakeLockOptimizationRate: Double,
        val currentBatteryLevel: Int
    )
    
    data class ConnectionReport(
        val collectionDurationHours: Double,
        val connectionAttempts: Long,
        val successfulConnections: Long,
        val connectionSuccessRate: Double,
        val reconnectionAttempts: Long,
        val totalHealthChecks: Long,
        val batchHealthChecks: Long,
        val batchCheckOptimizationRate: Double
    )
    
    data class SubscriptionReport(
        val collectionDurationHours: Double,
        val subscriptionRenewals: Long,
        val subscriptionRenewalFailures: Long,
        val renewalSuccessRate: Double,
        val averageRenewalDelay: Double,
        val subscriptionSilencePeriods: Long,
        val totalEventFlow: Long,
        val averageEventFlowRate: Double
    )
    
    data class ErrorReport(
        val collectionDurationHours: Double,
        val totalErrors: Long,
        val websocketErrors: Long,
        val parsingErrors: Long,
        val filterErrors: Long,
        val notificationErrors: Long,
        val subscriptionErrors: Long,
        val healthErrors: Long,
        val networkErrors: Long,
        val batteryErrors: Long,
        val unknownErrors: Long,
        val lastErrorType: String?,
        val lastErrorMessage: String?,
        val lastErrorTime: Long
    )

    data class NotificationReport(
        val collectionDurationHours: Double,
        val totalRateLimits: Long,
        val lastRateLimitReason: String?,
        val lastRateLimitTime: Long
    )
    
    data class DuplicateEventReport(
        val collectionDurationHours: Double,
        val eventsProcessed: Long,
        val duplicatesDetected: Long,
        val duplicateRate: Double,
        val duplicatesPrevented: Long,
        val preventionRate: Double,
        val networkDataSavedBytes: Long,
        val preciseTimestampUsage: Long,
        val preciseTimestampRate: Double
    )
    
    // Enhanced metrics report with comprehensive diagnostics
    data class EnhancedMetricsReport(
        val standardMetrics: MetricsReport?,
        val systemDiagnostics: SystemDiagnostics,
        val loggingDiagnostics: LoggingDiagnostics,
        val exportTimestamp: Long
    )
    
    data class SystemDiagnostics(
        val deviceManufacturer: String,
        val deviceModel: String,
        val androidVersion: String,
        val sdkVersion: Int,
        val appVersion: String,
        val batteryLevel: Int,
        val isCharging: Boolean,
        val batteryStatus: String,
        val networkAvailable: Boolean,
        val networkType: String,
        val networkConnected: Boolean,
        val usedMemoryMB: Long,
        val totalMemoryMB: Long,
        val maxMemoryMB: Long,
        val timestamp: Long
    )
    
    data class LoggingDiagnostics(
        val filterValid: Boolean,
        val enabledTypes: List<String>,
        val enabledDomains: List<String>,
        val maxLogs: Int,
        val debugConsoleVisible: Boolean,
        val timestamp: Long
    )
}
