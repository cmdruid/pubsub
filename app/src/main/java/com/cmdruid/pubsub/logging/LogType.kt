package com.cmdruid.pubsub.logging

/**
 * Log level types for structured logging
 */
enum class LogType(val displayName: String, val priority: Int, val icon: String) {
    TRACE("Trace", 1, "🔍"),
    DEBUG("Debug", 2, "🐛"),
    INFO("Info", 3, "ℹ️"),
    WARN("Warn", 4, "⚠️"),
    ERROR("Error", 5, "❌");
    
    companion object {
        fun fromString(value: String): LogType? {
            return values().find { it.name.equals(value, ignoreCase = true) }
        }
    }
}
