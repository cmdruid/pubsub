package com.cmdruid.pubsub.service

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log
import com.cmdruid.pubsub.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Structured logging system for battery optimization debugging and monitoring
 */
class BatteryOptimizationLogger(private val context: Context) {
    
    companion object {
        private const val TAG = "BatteryOptimization"
        private const val MAX_LOG_ENTRIES = 1000
    }
    
    enum class LogCategory {
        PING_INTERVAL,
        APP_STATE,
        CONNECTION_HEALTH,
        BATTERY_USAGE,
        NETWORK_STATE,
        WAKE_LOCK,
        OPTIMIZATION_DECISIONS,
        USER_ACTIONS
    }
    
    enum class LogLevel {
        DEBUG, INFO, WARN, ERROR
    }
    
    data class LogEntry(
        val timestamp: Long,
        val batteryLevel: Int,
        val category: LogCategory,
        val level: LogLevel,
        val message: String,
        val data: Map<String, Any> = emptyMap()
    ) {
        fun toFormattedString(): String {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            val timestampStr = dateFormat.format(Date(timestamp))
            val dataStr = if (data.isNotEmpty()) " {${data.entries.joinToString(", ") { "${it.key}: ${it.value}" }}}" else ""
            return "[$timestampStr] [${batteryLevel}%] [${category}] [${level}] $message$dataStr"
        }
    }
    
    private val logEntries = ConcurrentLinkedQueue<LogEntry>()
    private var currentLogLevel = LogLevel.DEBUG
    
    /**
     * Log an optimization event with structured data
     */
    fun logOptimization(
        category: LogCategory,
        level: LogLevel = LogLevel.INFO,
        message: String,
        data: Map<String, Any> = emptyMap()
    ) {
        if (level.ordinal < currentLogLevel.ordinal) {
            return // Skip logs below current level
        }
        
        val batteryLevel = getBatteryLevel()
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            batteryLevel = batteryLevel,
            category = category,
            level = level,
            message = message,
            data = data
        )
        
        // Add to internal log queue
        logEntries.offer(entry)
        
        // Maintain max log entries
        while (logEntries.size > MAX_LOG_ENTRIES) {
            logEntries.poll()
        }
        
        // Log to Android system log
        val formattedMessage = entry.toFormattedString()
        when (level) {
            LogLevel.DEBUG -> Log.d(TAG, formattedMessage)
            LogLevel.INFO -> Log.i(TAG, formattedMessage)
            LogLevel.WARN -> Log.w(TAG, formattedMessage)
            LogLevel.ERROR -> Log.e(TAG, formattedMessage)
        }
        
        // Send to MainActivity for real-time display
        sendToMainActivity(formattedMessage)
    }
    
    /**
     * Log ping interval changes with detailed context
     */
    fun logPingIntervalChange(
        fromInterval: Long,
        toInterval: Long,
        reason: String,
        networkType: String? = null,
        appState: String? = null
    ) {
        val data = mutableMapOf<String, Any>(
            "from" to "${fromInterval}s",
            "to" to "${toInterval}s",
            "reason" to reason
        )
        networkType?.let { data["network"] = it }
        appState?.let { data["app_state"] = it }
        
        logOptimization(
            category = LogCategory.PING_INTERVAL,
            level = LogLevel.INFO,
            message = "Changed ping interval",
            data = data
        )
    }
    
    /**
     * Log app state transitions
     */
    fun logAppStateChange(
        fromState: String,
        toState: String,
        duration: Long? = null
    ) {
        val data = mutableMapOf<String, Any>(
            "from" to fromState,
            "to" to toState
        )
        duration?.let { data["duration_ms"] = it }
        
        logOptimization(
            category = LogCategory.APP_STATE,
            level = LogLevel.INFO,
            message = "App state transition",
            data = data
        )
    }
    
    /**
     * Log connection health events
     */
    fun logConnectionHealth(
        relayUrl: String,
        status: String,
        reconnectAttempts: Int = 0,
        latency: Long? = null
    ) {
        val data = mutableMapOf<String, Any>(
            "relay" to relayUrl.substringAfter("://").take(20) + "...",
            "status" to status,
            "reconnect_attempts" to reconnectAttempts
        )
        latency?.let { data["latency_ms"] = it }
        
        logOptimization(
            category = LogCategory.CONNECTION_HEALTH,
            level = if (status.contains("fail", ignoreCase = true)) LogLevel.WARN else LogLevel.INFO,
            message = "Connection health update",
            data = data
        )
    }
    
    /**
     * Log battery usage patterns
     */
    fun logBatteryUsage(
        event: String,
        batteryDelta: Int? = null,
        chargingState: String? = null
    ) {
        val data = mutableMapOf<String, Any>("event" to event)
        batteryDelta?.let { data["battery_delta"] = "${it}%" }
        chargingState?.let { data["charging"] = it }
        
        logOptimization(
            category = LogCategory.BATTERY_USAGE,
            level = LogLevel.INFO,
            message = "Battery usage event",
            data = data
        )
    }
    
    /**
     * Get current battery level (public method for other components)
     */
    fun getBatteryLevel(): Int {
        return getCurrentBatteryLevel()
    }
    
    /**
     * Get current battery level
     */
    private fun getCurrentBatteryLevel(): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            -1 // Unknown battery level
        }
    }
    
    /**
     * Send log message to MainActivity for real-time display
     */
    private fun sendToMainActivity(message: String) {
        try {
            val intent = Intent(MainActivity.ACTION_DEBUG_LOG).apply {
                putExtra(MainActivity.EXTRA_LOG_MESSAGE, "🔋 $message")
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send log to MainActivity: ${e.message}")
        }
    }
    
    /**
     * Set minimum log level for filtering
     */
    fun setLogLevel(level: LogLevel) {
        currentLogLevel = level
        logOptimization(
            category = LogCategory.OPTIMIZATION_DECISIONS,
            level = LogLevel.INFO,
            message = "Log level changed",
            data = mapOf("level" to level.name)
        )
    }
    
    /**
     * Get all log entries for export/analysis
     */
    fun getAllLogs(): List<LogEntry> {
        return logEntries.toList()
    }
    
    /**
     * Get logs filtered by category
     */
    fun getLogsByCategory(category: LogCategory): List<LogEntry> {
        return logEntries.filter { it.category == category }
    }
    
    /**
     * Clear all log entries
     */
    fun clearLogs() {
        logEntries.clear()
        logOptimization(
            category = LogCategory.OPTIMIZATION_DECISIONS,
            level = LogLevel.INFO,
            message = "Logs cleared"
        )
    }
    
    /**
     * Export logs as formatted string for analysis
     */
    fun exportLogs(category: LogCategory? = null): String {
        val logsToExport = if (category != null) {
            getLogsByCategory(category)
        } else {
            getAllLogs()
        }
        
        return buildString {
            appendLine("=== Battery Optimization Logs ===")
            appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("Total entries: ${logsToExport.size}")
            if (category != null) {
                appendLine("Category filter: $category")
            }
            appendLine()
            
            logsToExport.forEach { entry ->
                appendLine(entry.toFormattedString())
            }
        }
    }
    
    /**
     * Get logging statistics
     */
    fun getStats(): Map<String, Any> {
        val categoryStats = LogCategory.values().associateWith { category ->
            logEntries.count { it.category == category }
        }
        
        return mapOf(
            "total_logs" to logEntries.size,
            "current_battery" to getBatteryLevel(),
            "log_level" to currentLogLevel.name,
            "category_breakdown" to categoryStats
        )
    }
}
