package com.cmdruid.pubsub.logging

/**
 * Log domain categories for structured logging
 */
enum class LogDomain(val displayName: String, val icon: String) {
    SERVICE("Service", "⚡"),
    NETWORK("Network", "🌐"),
    RELAY("Relay", "📡"),
    EVENT("Event", "📨"),
    NOTIFICATION("Notification", "🔔"),
    BATTERY("Battery", "🔋"),
    HEALTH("Health", "🩺"),
    SUBSCRIPTION("Subscription", "📋"),
    UI("UI", "🖥️"),
    SYSTEM("System", "⚙️");
    
    companion object {
        fun fromString(value: String): LogDomain? {
            return values().find { it.name.equals(value, ignoreCase = true) }
        }
    }
}
