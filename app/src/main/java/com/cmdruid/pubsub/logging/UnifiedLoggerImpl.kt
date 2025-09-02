package com.cmdruid.pubsub.logging

import android.content.Context
import android.content.Intent
import android.util.Log
import com.cmdruid.pubsub.ui.MainActivity
import com.cmdruid.pubsub.data.ConfigurationManager
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Implementation of UnifiedLogger that integrates with existing debug console system
 */
class UnifiedLoggerImpl(
    private val context: Context,
    private val configurationManager: ConfigurationManager,
    private val maxLogEntries: Int = 1000
) : UnifiedLogger {
    
    companion object {
        private const val TAG = "UnifiedLogger"
    }
    
    private val logEntries = ConcurrentLinkedQueue<StructuredLogEntry>()
    private var minLogLevel = LogType.TRACE
    private var currentFilter = LogFilter.DEFAULT
    
    /**
     * Update the filter to enable early filtering for performance
     */
    fun setFilter(filter: LogFilter) {
        currentFilter = filter
    }
    
    override fun log(type: LogType, domain: LogDomain, message: String, data: Map<String, Any>) {
        // Early filtering for performance - don't create logs that won't be displayed
        if (type.priority < minLogLevel.priority || 
            type !in currentFilter.enabledTypes || 
            domain !in currentFilter.enabledDomains) {
            return
        }
        
        // Create entry with minimal overhead
        val entry = StructuredLogEntry(
            timestamp = System.currentTimeMillis(),
            type = type,
            domain = domain,
            message = message,
            data = data,
            threadName = Thread.currentThread().name,
            className = null // Skip expensive stack trace lookup
        )
        
        // Send to MainActivity immediately for real-time display
        sendToMainActivity(entry)
        
        // Do all expensive operations on background thread
        CoroutineScope(Dispatchers.IO).launch {
            // Add to internal log queue
            logEntries.offer(entry)
            
            // Maintain max log entries
            while (logEntries.size > maxLogEntries) {
                logEntries.poll()
            }
            
            // Store in ConfigurationManager for persistence
            configurationManager.addStructuredLog(entry)
            
            // Log to Android system log (only if needed for debugging)
            val formattedMessage = entry.toDetailedString()
            when (type) {
                LogType.TRACE -> Log.v(TAG, formattedMessage)
                LogType.DEBUG -> Log.d(TAG, formattedMessage)
                LogType.INFO -> Log.i(TAG, formattedMessage)
                LogType.WARN -> Log.w(TAG, formattedMessage)
                LogType.ERROR -> Log.e(TAG, formattedMessage)
            }
        }
    }
    
    override fun getAllLogs(): List<StructuredLogEntry> {
        return logEntries.toList()
    }
    
    override fun getFilteredLogs(filter: LogFilter): List<StructuredLogEntry> {
        return logEntries.filter { filter.passes(it) }
    }
    
    override fun clearLogs() {
        logEntries.clear()
        configurationManager.clearStructuredLogs()
        info(LogDomain.SYSTEM, "Logs cleared")
    }
    
    override fun setMinLogLevel(level: LogType) {
        val oldLevel = minLogLevel
        minLogLevel = level
        info(
            LogDomain.SYSTEM, 
            "Log level changed",
            mapOf("from" to oldLevel.displayName, "to" to level.displayName)
        )
    }
    
    /**
     * Send log message to MainActivity for real-time display
     */
    private fun sendToMainActivity(entry: StructuredLogEntry) {
        try {
            val intent = Intent(MainActivity.ACTION_DEBUG_LOG).apply {
                putExtra(MainActivity.EXTRA_LOG_MESSAGE, entry.toJson())
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send log to MainActivity: ${e.message}")
        }
    }
    

    
    /**
     * Export logs as formatted string for analysis
     */
    fun exportLogs(filter: LogFilter? = null): String {
        val logs = if (filter != null) getFilteredLogs(filter) else getAllLogs()
        
        if (logs.isEmpty()) {
            return "No logs available."
        }
        
        val sb = StringBuilder()
        sb.appendLine("PubSub Structured Logs Export")
        sb.appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        sb.appendLine("Total logs: ${logs.size}")
        if (filter != null) {
            sb.appendLine("Filter: Types=${filter.enabledTypes.joinToString(",") { it.name }}, Domains=${filter.enabledDomains.joinToString(",") { it.name }}")
        }
        sb.appendLine("=".repeat(80))
        sb.appendLine()
        
        logs.reversed().forEach { entry ->
            sb.appendLine(entry.toDetailedString())
        }
        
        return sb.toString()
    }
}
