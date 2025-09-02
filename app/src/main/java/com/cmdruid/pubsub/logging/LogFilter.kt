package com.cmdruid.pubsub.logging

import com.cmdruid.pubsub.BuildConfig

/**
 * Filter configuration for structured logs
 */
data class LogFilter(
    val enabledTypes: Set<LogType> = getDefaultEnabledTypes(),
    val enabledDomains: Set<LogDomain> = LogDomain.values().toSet(),
    val maxLogs: Int = 100
) {
    /**
     * Check if a log entry passes this filter
     */
    fun passes(entry: StructuredLogEntry): Boolean {
        return entry.type in enabledTypes && entry.domain in enabledDomains
    }
    
    /**
     * Create filter with only specified types enabled
     */
    fun withTypes(vararg types: LogType): LogFilter {
        return copy(enabledTypes = types.toSet())
    }
    
    /**
     * Create filter with only specified domains enabled
     */
    fun withDomains(vararg domains: LogDomain): LogFilter {
        return copy(enabledDomains = domains.toSet())
    }
    

    
    /**
     * Create filter with specific max logs count
     */
    fun withMaxLogs(maxLogs: Int): LogFilter {
        return copy(maxLogs = maxLogs)
    }
    
    companion object {
        /**
         * Get default enabled types based on build configuration
         */
        fun getDefaultEnabledTypes(): Set<LogType> {
            return if (BuildConfig.DEBUG) {
                // Debug builds: Show DEBUG, INFO, WARN, ERROR (no TRACE by default)
                setOf(LogType.DEBUG, LogType.INFO, LogType.WARN, LogType.ERROR)
            } else {
                // Release builds: Show only WARN, ERROR (minimal logging for end users)
                setOf(LogType.WARN, LogType.ERROR)
            }
        }
        
        /**
         * Default filter with build-appropriate log levels
         */
        val DEFAULT = LogFilter()
        
        /**
         * Filter showing only errors and warnings
         */
        val ERRORS_AND_WARNINGS = LogFilter(
            enabledTypes = setOf(LogType.ERROR, LogType.WARN)
        )
        
        /**
         * Filter showing only info and above (no debug/trace)
         */
        val INFO_AND_ABOVE = LogFilter(
            enabledTypes = setOf(LogType.INFO, LogType.WARN, LogType.ERROR)
        )
        
        /**
         * Filter for development with all log types
         */
        val ALL_TYPES = LogFilter(
            enabledTypes = LogType.values().toSet()
        )
    }
}
