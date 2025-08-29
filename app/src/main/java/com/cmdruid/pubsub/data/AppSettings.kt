package com.cmdruid.pubsub.data

/**
 * Data class representing user-configurable app settings
 */
data class AppSettings(
    val batteryMode: BatteryMode = BatteryMode.BALANCED,
    val notificationFrequency: NotificationFrequency = NotificationFrequency.IMMEDIATE,
    val defaultEventViewer: String = "https://njump.me",
    val defaultRelayServer: String = "wss://relay.damus.io",
    val showDebugConsole: Boolean = true
) {
    /**
     * Get ping intervals based on battery mode
     */
    fun getPingIntervals(): PingIntervals {
        return when (batteryMode) {
            BatteryMode.PERFORMANCE -> PingIntervals(
                foreground = 15L,      // Faster updates
                background = 60L,      // More responsive in background
                doze = 120L,           // Less conservative in doze
                lowBattery = 180L,     // Still responsive on low battery
                criticalBattery = 300L, // Conservative on critical battery
                rare = 180L,           // More responsive for rare usage
                restricted = 180L      // More responsive when restricted
            )
            BatteryMode.BATTERY_SAVER -> PingIntervals(
                foreground = 60L,      // Slower updates to save battery
                background = 300L,     // Much longer intervals in background
                doze = 600L,           // Very conservative in doze
                lowBattery = 600L,     // Maximum conservation on low battery
                criticalBattery = 900L, // Ultra-conservative on critical battery
                rare = 600L,           // Very conservative for rare usage
                restricted = 600L      // Very conservative when restricted
            )
            BatteryMode.BALANCED -> PingIntervals(
                foreground = 30L,      // Current default values
                background = 120L,
                doze = 180L,
                lowBattery = 300L,
                criticalBattery = 600L,
                rare = 300L,
                restricted = 300L
            )
        }
    }
    
    /**
     * Get notification rate limit in milliseconds based on frequency setting
     */
    fun getNotificationRateLimit(): Long {
        return when (notificationFrequency) {
            NotificationFrequency.IMMEDIATE -> 1000L      // 1 second minimum
            NotificationFrequency.EVERY_5_MIN -> 300000L  // 5 minutes
            NotificationFrequency.EVERY_15_MIN -> 900000L // 15 minutes
            NotificationFrequency.HOURLY -> 3600000L      // 1 hour
        }
    }
    
    /**
     * Validate settings and return corrected version if needed
     */
    fun validated(): AppSettings {
        return copy(
            defaultEventViewer = if (defaultEventViewer.isBlank()) "https://njump.me" else defaultEventViewer.trim(),
            defaultRelayServer = if (defaultRelayServer.isBlank()) "wss://relay.damus.io" else defaultRelayServer.trim()
        )
    }
}

/**
 * Battery optimization modes
 */
enum class BatteryMode(val displayName: String, val description: String) {
    PERFORMANCE("Performance", "Faster updates, more responsive"),
    BALANCED("Balanced", "Good balance of battery and performance"),
    BATTERY_SAVER("Battery Saver", "Maximum battery conservation")
}

/**
 * Notification frequency options
 */
enum class NotificationFrequency(val displayName: String, val description: String) {
    IMMEDIATE("Immediate", "Show notifications right away"),
    EVERY_5_MIN("Every 5 minutes", "Batch notifications every 5 minutes"),
    EVERY_15_MIN("Every 15 minutes", "Batch notifications every 15 minutes"),
    HOURLY("Hourly", "Batch notifications every hour")
}

/**
 * Ping intervals for different app states based on battery mode
 */
data class PingIntervals(
    val foreground: Long,
    val background: Long,
    val doze: Long,
    val lowBattery: Long,
    val criticalBattery: Long,
    val rare: Long,
    val restricted: Long
)

/**
 * Preset options for common settings
 */
object SettingsPresets {
    val EVENT_VIEWERS = listOf(
        "https://njump.me",
        "https://snort.social/e/",
        "https://iris.to/note/",
        "https://coracle.social/notes/",
        "https://nostrgraph.net/note/",
        "https://nostr.band/note/"
    )
    
    val RELAY_SERVERS = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.snort.social",
        "wss://nostr.wine",
        "wss://relay.nostr.band",
        "wss://nostr.mom",
        "wss://relay.nostr.info",
        "wss://nostr-pub.wellorder.net"
    )
    
    /**
     * Get display names for event viewers (for better UX)
     */
    fun getEventViewerDisplayName(url: String): String {
        return when {
            url.contains("njump.me") -> "njump.me (Popular)"
            url.contains("snort.social") -> "Snort Social"
            url.contains("iris.to") -> "Iris"
            url.contains("coracle.social") -> "Coracle"
            url.contains("nostrgraph.net") -> "NostrGraph"
            url.contains("nostr.band") -> "Nostr.band"
            else -> url
        }
    }
    
    /**
     * Get display names for relay servers (for better UX)
     */
    fun getRelayServerDisplayName(url: String): String {
        return when {
            url.contains("relay.damus.io") -> "Damus Relay (Popular)"
            url.contains("nos.lol") -> "nos.lol"
            url.contains("relay.snort.social") -> "Snort Relay"
            url.contains("nostr.wine") -> "Nostr Wine"
            url.contains("relay.nostr.band") -> "Nostr.band Relay"
            url.contains("nostr.mom") -> "Nostr Mom"
            url.contains("relay.nostr.info") -> "Nostr Info"
            url.contains("wellorder.net") -> "WellOrder"
            else -> url
        }
    }
}
