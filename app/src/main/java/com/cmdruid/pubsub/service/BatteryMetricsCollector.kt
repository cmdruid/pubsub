package com.cmdruid.pubsub.service

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Collects and tracks battery optimization effectiveness metrics
 * Monitors Phase 1 implementation impact on network activity and battery usage
 */
class BatteryMetricsCollector(private val context: Context) {
    
    companion object {
        private const val TAG = "BatteryMetrics"
        private const val METRICS_COLLECTION_INTERVAL_MS = 60000L // 1 minute
    }
    
    data class PingMetrics(
        var totalPings: Long = 0,
        var pingsByInterval: MutableMap<Long, Long> = mutableMapOf(),
        var startTime: Long = System.currentTimeMillis(),
        var lastPingTime: Long = 0
    )
    
    data class ConnectionMetrics(
        var totalConnections: Int = 0,
        var successfulConnections: Int = 0,
        var reconnectAttempts: Int = 0,
        var connectionUptime: Long = 0,
        var connectionDowntime: Long = 0,
        var lastConnectionTime: Long = 0
    )
    
    data class AppStateMetrics(
        var foregroundTime: Long = 0,
        var backgroundTime: Long = 0,
        var dozeTime: Long = 0,
        var stateTransitions: Int = 0,
        var lastStateChange: Long = System.currentTimeMillis(),
        var currentState: PubSubService.AppState = PubSubService.AppState.FOREGROUND
    )
    
    data class BatteryMetrics(
        var initialBatteryLevel: Int = -1,
        var currentBatteryLevel: Int = -1,
        var batteryDrainRate: Double = 0.0, // Percentage per hour
        var chargingState: String = "unknown",
        var lastBatteryCheck: Long = 0
    )
    
    data class OptimizationReport(
        val reportTime: Long,
        val collectionDuration: Long,
        val pingFrequencyReduction: Double, // Percentage reduction
        val connectionStability: Double, // Percentage uptime
        val appStateTransitions: Int,
        val batteryDrainImprovement: Double, // Percentage improvement
        val networkActivityReduction: Double, // Estimated reduction
        val phase1Effectiveness: String // Overall assessment
    )
    
    // Metrics storage
    private val pingMetrics = PingMetrics()
    private val connectionMetrics = ConnectionMetrics()
    private val appStateMetrics = AppStateMetrics()
    private val batteryMetrics = BatteryMetrics()
    
    // Atomic counters for thread safety
    private val totalNetworkEvents = AtomicLong(0)
    private val optimizationEvents = AtomicLong(0)
    
    // Historical data for comparison
    private val metricsHistory = mutableListOf<OptimizationReport>()
    
    init {
        initializeBatteryMetrics()
    }
    
    /**
     * Initialize battery metrics collection
     */
    private fun initializeBatteryMetrics() {
        batteryMetrics.initialBatteryLevel = getCurrentBatteryLevel()
        batteryMetrics.currentBatteryLevel = batteryMetrics.initialBatteryLevel
        batteryMetrics.lastBatteryCheck = System.currentTimeMillis()
        batteryMetrics.chargingState = getChargingState()
    }
    
    /**
     * Track ping frequency changes for optimization effectiveness
     */
    fun trackPingFrequency(intervalSeconds: Long, appState: PubSubService.AppState) {
        synchronized(pingMetrics) {
            pingMetrics.totalPings++
            pingMetrics.pingsByInterval[intervalSeconds] = 
                (pingMetrics.pingsByInterval[intervalSeconds] ?: 0) + 1
            pingMetrics.lastPingTime = System.currentTimeMillis()
            
            Log.d(TAG, "Tracked ping: interval=${intervalSeconds}s, state=$appState, total=${pingMetrics.totalPings}")
        }
    }
    
    /**
     * Track connection stability metrics
     */
    fun trackConnectionEvent(
        eventType: String, // "connect", "disconnect", "reconnect"
        relayUrl: String,
        success: Boolean = true,
        duration: Long = 0
    ) {
        synchronized(connectionMetrics) {
            when (eventType) {
                "connect" -> {
                    connectionMetrics.totalConnections++
                    if (success) {
                        connectionMetrics.successfulConnections++
                        connectionMetrics.lastConnectionTime = System.currentTimeMillis()
                    }
                }
                "reconnect" -> {
                    connectionMetrics.reconnectAttempts++
                }
                "uptime" -> {
                    connectionMetrics.connectionUptime += duration
                }
                "downtime" -> {
                    connectionMetrics.connectionDowntime += duration
                }
            }
            
            Log.d(TAG, "Tracked connection: type=$eventType, success=$success, relay=${relayUrl.take(20)}...")
        }
    }
    
    /**
     * Track app state transitions and timing
     */
    fun trackAppStateChange(
        fromState: PubSubService.AppState,
        toState: PubSubService.AppState,
        duration: Long
    ) {
        synchronized(appStateMetrics) {
            val currentTime = System.currentTimeMillis()
            
            // Add duration to the previous state
            when (fromState) {
                PubSubService.AppState.FOREGROUND -> appStateMetrics.foregroundTime += duration
                PubSubService.AppState.BACKGROUND -> appStateMetrics.backgroundTime += duration
                PubSubService.AppState.DOZE -> appStateMetrics.dozeTime += duration
                PubSubService.AppState.RARE -> appStateMetrics.backgroundTime += duration // Count as background time
                PubSubService.AppState.RESTRICTED -> appStateMetrics.backgroundTime += duration // Count as background time
            }
            
            appStateMetrics.stateTransitions++
            appStateMetrics.lastStateChange = currentTime
            appStateMetrics.currentState = toState
            
            Log.d(TAG, "Tracked state change: $fromState -> $toState (${duration}ms)")
        }
    }
    
    /**
     * Update battery usage metrics
     */
    fun updateBatteryMetrics() {
        val currentTime = System.currentTimeMillis()
        val currentLevel = getCurrentBatteryLevel()
        val chargingState = getChargingState()
        
        synchronized(batteryMetrics) {
            if (batteryMetrics.currentBatteryLevel != -1 && currentLevel != -1) {
                val timeDiff = currentTime - batteryMetrics.lastBatteryCheck
                val levelDiff = batteryMetrics.currentBatteryLevel - currentLevel
                
                // Calculate drain rate (percentage per hour) only if not charging
                if (timeDiff > 0 && chargingState == "not_charging" && levelDiff > 0) {
                    val hoursElapsed = timeDiff / (1000.0 * 60.0 * 60.0)
                    batteryMetrics.batteryDrainRate = levelDiff / hoursElapsed
                }
            }
            
            batteryMetrics.currentBatteryLevel = currentLevel
            batteryMetrics.chargingState = chargingState
            batteryMetrics.lastBatteryCheck = currentTime
            
            Log.d(TAG, "Updated battery: level=$currentLevel%, charging=$chargingState, drain=${batteryMetrics.batteryDrainRate}%/h")
        }
    }
    
    /**
     * Track network activity for optimization impact measurement
     */
    fun trackNetworkActivity(eventType: String, optimized: Boolean = false) {
        totalNetworkEvents.incrementAndGet()
        if (optimized) {
            optimizationEvents.incrementAndGet()
        }
        
        Log.d(TAG, "Tracked network activity: $eventType, optimized=$optimized")
    }
    
    /**
     * Generate Phase 1 effectiveness report
     */
    fun generatePhase1Report(): OptimizationReport {
        val currentTime = System.currentTimeMillis()
        val collectionDuration = currentTime - pingMetrics.startTime
        
        // Calculate ping frequency reduction
        val foregroundPings = pingMetrics.pingsByInterval[30L] ?: 0L
        val backgroundPings = pingMetrics.pingsByInterval[120L] ?: 0L
        val dozePings = pingMetrics.pingsByInterval[300L] ?: 0L
        
        val totalOptimizedPings = backgroundPings + dozePings
        val totalPings = pingMetrics.totalPings
        val pingFrequencyReduction = if (totalPings > 0) {
            (totalOptimizedPings.toDouble() / totalPings) * 100.0
        } else 0.0
        
        // Calculate connection stability
        val totalConnectionTime = connectionMetrics.connectionUptime + connectionMetrics.connectionDowntime
        val connectionStability = if (totalConnectionTime > 0) {
            (connectionMetrics.connectionUptime.toDouble() / totalConnectionTime) * 100.0
        } else 100.0
        
        // Estimate battery drain improvement
        val batteryDrainImprovement = calculateBatteryImprovement()
        
        // Estimate network activity reduction
        val networkActivityReduction = estimateNetworkReduction()
        
        // Overall effectiveness assessment
        val effectiveness = assessPhase1Effectiveness(
            pingFrequencyReduction,
            connectionStability,
            batteryDrainImprovement,
            networkActivityReduction
        )
        
        val report = OptimizationReport(
            reportTime = currentTime,
            collectionDuration = collectionDuration,
            pingFrequencyReduction = pingFrequencyReduction,
            connectionStability = connectionStability,
            appStateTransitions = appStateMetrics.stateTransitions,
            batteryDrainImprovement = batteryDrainImprovement,
            networkActivityReduction = networkActivityReduction,
            phase1Effectiveness = effectiveness
        )
        
        // Store in history
        metricsHistory.add(report)
        
        Log.i(TAG, "Generated Phase 1 report: $effectiveness")
        return report
    }
    
    /**
     * Get current battery level
     */
    private fun getCurrentBatteryLevel(): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get battery level: ${e.message}")
            -1
        }
    }
    
    /**
     * Get charging state
     */
    private fun getChargingState(): String {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val isCharging = batteryManager.isCharging
            if (isCharging) "charging" else "not_charging"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get charging state: ${e.message}")
            "unknown"
        }
    }
    
    /**
     * Calculate estimated battery drain improvement
     */
    private fun calculateBatteryImprovement(): Double {
        // Estimate based on ping frequency reduction and app state distribution
        val backgroundTime = appStateMetrics.backgroundTime
        val foregroundTime = appStateMetrics.foregroundTime
        val totalTime = backgroundTime + foregroundTime
        
        if (totalTime == 0L) return 0.0
        
        val backgroundRatio = backgroundTime.toDouble() / totalTime
        val foregroundRatio = foregroundTime.toDouble() / totalTime
        
        // Estimate battery savings: background mode uses 75% less power for network activity
        val estimatedSavings = backgroundRatio * 0.75 * 100.0 // Convert to percentage
        
        return minOf(estimatedSavings, 50.0) // Cap at 50% improvement
    }
    
    /**
     * Estimate network activity reduction
     */
    private fun estimateNetworkReduction(): Double {
        val totalEvents = totalNetworkEvents.get()
        val optimizedEvents = optimizationEvents.get()
        
        return if (totalEvents > 0) {
            (optimizedEvents.toDouble() / totalEvents) * 100.0
        } else 0.0
    }
    
    /**
     * Assess overall Phase 1 effectiveness
     */
    private fun assessPhase1Effectiveness(
        pingReduction: Double,
        connectionStability: Double,
        batteryImprovement: Double,
        networkReduction: Double
    ): String {
        val scores = listOf(
            pingReduction / 100.0,
            connectionStability / 100.0,
            batteryImprovement / 100.0,
            networkReduction / 100.0
        )
        
        val averageScore = scores.average()
        
        return when {
            averageScore >= 0.8 -> "EXCELLENT"
            averageScore >= 0.6 -> "GOOD"
            averageScore >= 0.4 -> "MODERATE"
            averageScore >= 0.2 -> "POOR"
            else -> "INEFFECTIVE"
        }
    }
    
    /**
     * Get comprehensive metrics summary
     */
    fun getMetricsSummary(): Map<String, Any> {
        updateBatteryMetrics()
        
        return mapOf(
            "ping_metrics" to mapOf(
                "total_pings" to pingMetrics.totalPings,
                "pings_by_interval" to pingMetrics.pingsByInterval.toMap(),
                "collection_duration_ms" to (System.currentTimeMillis() - pingMetrics.startTime)
            ),
            "connection_metrics" to mapOf(
                "total_connections" to connectionMetrics.totalConnections,
                "successful_connections" to connectionMetrics.successfulConnections,
                "reconnect_attempts" to connectionMetrics.reconnectAttempts,
                "uptime_ms" to connectionMetrics.connectionUptime,
                "downtime_ms" to connectionMetrics.connectionDowntime,
                "stability_percentage" to if (connectionMetrics.connectionUptime + connectionMetrics.connectionDowntime > 0) {
                    (connectionMetrics.connectionUptime.toDouble() / 
                     (connectionMetrics.connectionUptime + connectionMetrics.connectionDowntime)) * 100.0
                } else 100.0
            ),
            "app_state_metrics" to mapOf(
                "foreground_time_ms" to appStateMetrics.foregroundTime,
                "background_time_ms" to appStateMetrics.backgroundTime,
                "doze_time_ms" to appStateMetrics.dozeTime,
                "state_transitions" to appStateMetrics.stateTransitions,
                "current_state" to appStateMetrics.currentState.name
            ),
            "battery_metrics" to mapOf(
                "initial_level" to batteryMetrics.initialBatteryLevel,
                "current_level" to batteryMetrics.currentBatteryLevel,
                "drain_rate_percent_per_hour" to batteryMetrics.batteryDrainRate,
                "charging_state" to batteryMetrics.chargingState,
                "battery_delta" to (batteryMetrics.initialBatteryLevel - batteryMetrics.currentBatteryLevel)
            ),
            "network_metrics" to mapOf(
                "total_network_events" to totalNetworkEvents.get(),
                "optimized_events" to optimizationEvents.get(),
                "optimization_rate_percentage" to if (totalNetworkEvents.get() > 0) {
                    (optimizationEvents.get().toDouble() / totalNetworkEvents.get()) * 100.0
                } else 0.0
            )
        )
    }
    
    /**
     * Export metrics as formatted report
     */
    fun exportMetricsReport(): String {
        val report = generatePhase1Report()
        val summary = getMetricsSummary()
        
        return buildString {
            appendLine("=== Battery Optimization Phase 1 Metrics Report ===")
            appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(report.reportTime))}")
            appendLine("Collection Duration: ${report.collectionDuration / 1000}s")
            appendLine()
            
            appendLine("OPTIMIZATION EFFECTIVENESS: ${report.phase1Effectiveness}")
            appendLine("- Ping Frequency Reduction: ${String.format("%.1f", report.pingFrequencyReduction)}%")
            appendLine("- Connection Stability: ${String.format("%.1f", report.connectionStability)}%")
            appendLine("- Battery Drain Improvement: ${String.format("%.1f", report.batteryDrainImprovement)}%")
            appendLine("- Network Activity Reduction: ${String.format("%.1f", report.networkActivityReduction)}%")
            appendLine()
            
            appendLine("DETAILED METRICS:")
            summary.forEach { (category, data) ->
                appendLine("$category:")
                if (data is Map<*, *>) {
                    data.forEach { (key, value) ->
                        appendLine("  $key: $value")
                    }
                }
                appendLine()
            }
            
            appendLine("HISTORICAL DATA:")
            appendLine("Total reports generated: ${metricsHistory.size}")
            if (metricsHistory.size > 1) {
                val previousReport = metricsHistory[metricsHistory.size - 2]
                appendLine("Improvement since last report:")
                appendLine("  Ping reduction: ${String.format("%.1f", report.pingFrequencyReduction - previousReport.pingFrequencyReduction)}%")
                appendLine("  Battery improvement: ${String.format("%.1f", report.batteryDrainImprovement - previousReport.batteryDrainImprovement)}%")
            }
        }
    }
    
    /**
     * Reset all metrics (useful for testing)
     */
    fun resetMetrics() {
        synchronized(pingMetrics) {
            pingMetrics.totalPings = 0
            pingMetrics.pingsByInterval.clear()
            pingMetrics.startTime = System.currentTimeMillis()
        }
        
        synchronized(connectionMetrics) {
            connectionMetrics.totalConnections = 0
            connectionMetrics.successfulConnections = 0
            connectionMetrics.reconnectAttempts = 0
            connectionMetrics.connectionUptime = 0
            connectionMetrics.connectionDowntime = 0
        }
        
        synchronized(appStateMetrics) {
            appStateMetrics.foregroundTime = 0
            appStateMetrics.backgroundTime = 0
            appStateMetrics.dozeTime = 0
            appStateMetrics.stateTransitions = 0
            appStateMetrics.lastStateChange = System.currentTimeMillis()
        }
        
        totalNetworkEvents.set(0)
        optimizationEvents.set(0)
        metricsHistory.clear()
        
        initializeBatteryMetrics()
        
        Log.i(TAG, "Metrics reset completed")
    }
}

