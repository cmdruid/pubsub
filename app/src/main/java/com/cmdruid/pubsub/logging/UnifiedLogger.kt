package com.cmdruid.pubsub.logging

/**
 * Unified logging interface for structured logging across the application
 */
interface UnifiedLogger {
    
    /**
     * Log a message with specified type and domain
     */
    fun log(type: LogType, domain: LogDomain, message: String, data: Map<String, Any> = emptyMap())
    
    /**
     * Log a trace message
     */
    fun trace(domain: LogDomain, message: String, data: Map<String, Any> = emptyMap()) {
        log(LogType.TRACE, domain, message, data)
    }
    
    /**
     * Log a debug message
     */
    fun debug(domain: LogDomain, message: String, data: Map<String, Any> = emptyMap()) {
        log(LogType.DEBUG, domain, message, data)
    }
    
    /**
     * Log an info message
     */
    fun info(domain: LogDomain, message: String, data: Map<String, Any> = emptyMap()) {
        log(LogType.INFO, domain, message, data)
    }
    
    /**
     * Log a warning message
     */
    fun warn(domain: LogDomain, message: String, data: Map<String, Any> = emptyMap()) {
        log(LogType.WARN, domain, message, data)
    }
    
    /**
     * Log an error message
     */
    fun error(domain: LogDomain, message: String, data: Map<String, Any> = emptyMap()) {
        log(LogType.ERROR, domain, message, data)
    }
    
    /**
     * Get all log entries
     */
    fun getAllLogs(): List<StructuredLogEntry>
    
    /**
     * Get filtered log entries
     */
    fun getFilteredLogs(filter: LogFilter): List<StructuredLogEntry>
    
    /**
     * Clear all log entries
     */
    fun clearLogs()
    
    /**
     * Set minimum log level for filtering
     */
    fun setMinLogLevel(level: LogType)
}
