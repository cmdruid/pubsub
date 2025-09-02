package com.cmdruid.pubsub.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.cmdruid.pubsub.nostr.NostrFilter
import java.util.concurrent.ConcurrentHashMap

/**
 * Advanced subscription manager with per-relay timestamp tracking
 * Eliminates duplicate events by maintaining precise timestamps per relay connection
 * Provides efficient subscription lifecycle management and health monitoring
 */
class SubscriptionManager(private val context: Context) {
    
    companion object {
        private const val TAG = "SubscriptionManager"
        private const val PREFS_NAME = "relay_timestamps"
        private const val MAX_TIMESTAMP_AGE_DAYS = 30
    }
    
    // Per-relay timestamp tracking - the core innovation
    private val relayTimestamps = ConcurrentHashMap<String, RelaySubscriptionTimestamp>()
    
    // Active subscription tracking (now includes relay info)
    private val activeSubscriptions = ConcurrentHashMap<String, SubscriptionInfo>()
    
    // Persistent storage for cross-session continuity
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    data class RelaySubscriptionTimestamp(
        val subscriptionId: String,
        val relayUrl: String,
        val lastEventTimestamp: Long,
        val lastConnectionTime: Long,
        val subscriptionConfirmedTime: Long,
        val eventCount: Long = 0,
        val connectionDowntime: Long = 0
    ) {
        fun getKey(): String = "$subscriptionId:$relayUrl"
    }
    
    data class SubscriptionInfo(
        val id: String,
        val configurationId: String,
        val filter: NostrFilter,
        val relayUrl: String,
        val createdAt: Long
    )
    
    /**
     * Register a subscription for a specific relay
     */
    fun registerSubscription(subscriptionId: String, configurationId: String, filter: NostrFilter, relayUrl: String) {
        val subscriptionInfo = SubscriptionInfo(
            id = subscriptionId,
            configurationId = configurationId,
            filter = filter,
            relayUrl = relayUrl,
            createdAt = System.currentTimeMillis()
        )
        
        // Store subscription info with relay-specific key
        val key = "$subscriptionId:$relayUrl"
        activeSubscriptions[key] = subscriptionInfo
        
        Log.d(TAG, "Registered subscription: $subscriptionId for relay: $relayUrl")
    }
    
    /**
     * Update timestamp for specific relay + subscription combination
     * This is the core method that eliminates duplicate events
     */
    fun updateRelayTimestamp(
        subscriptionId: String,
        relayUrl: String,
        eventTimestamp: Long
    ) {
        val key = "$subscriptionId:$relayUrl"
        val currentTime = System.currentTimeMillis()
        val existing = relayTimestamps[key]
        
        if (existing == null || eventTimestamp > existing.lastEventTimestamp) {
            val updated = RelaySubscriptionTimestamp(
                subscriptionId = subscriptionId,
                relayUrl = relayUrl,
                lastEventTimestamp = eventTimestamp,
                lastConnectionTime = existing?.lastConnectionTime ?: currentTime,
                subscriptionConfirmedTime = existing?.subscriptionConfirmedTime ?: 0L,
                eventCount = (existing?.eventCount ?: 0) + 1,
                connectionDowntime = existing?.connectionDowntime ?: 0L
            )
            
            relayTimestamps[key] = updated
            
            // Persist to SharedPreferences for cross-session continuity
            persistTimestamp(key, updated)
            
            Log.d(TAG, "Updated relay timestamp: $relayUrl -> $eventTimestamp (${updated.eventCount} events)")
        }
    }
    
    /**
     * Get last event timestamp for specific relay + subscription
     */
    fun getRelayTimestamp(subscriptionId: String, relayUrl: String): Long? {
        val key = "$subscriptionId:$relayUrl"
        return relayTimestamps[key]?.lastEventTimestamp
    }
    
    /**
     * Create relay-specific resubscription filter - eliminates duplicate events
     */
    fun createRelaySpecificFilter(
        subscriptionId: String,
        relayUrl: String,
        baseFilter: NostrFilter
    ): NostrFilter {
        val lastTimestamp = getRelayTimestamp(subscriptionId, relayUrl)
        
        return if (lastTimestamp != null) {
            // Use precise timestamp + 1 second - NO MORE SAFETY BUFFER!
            val filter = baseFilter.copy(since = lastTimestamp + 1)
            Log.d(TAG, "Using precise timestamp for $relayUrl: since=${lastTimestamp + 1}")
            filter
        } else {
            // First time for this relay - use minimal 5-minute safety buffer
            val currentTimestamp = System.currentTimeMillis() / 1000
            val filter = baseFilter.copy(since = currentTimestamp - 300) // 5 minutes only
            Log.d(TAG, "New relay $relayUrl: using 5-minute safety buffer")
            filter
        }
    }
    
    /**
     * Track connection downtime for intelligent reconnection strategies
     */
    fun updateConnectionDowntime(subscriptionId: String, relayUrl: String, downtime: Long) {
        val key = "$subscriptionId:$relayUrl"
        relayTimestamps[key]?.let { existing ->
            relayTimestamps[key] = existing.copy(
                connectionDowntime = downtime,
                lastConnectionTime = System.currentTimeMillis()
            )
            persistTimestamp(key, relayTimestamps[key]!!)
        }
    }
    
    /**
     * Get connection downtime for relay-specific reconnection strategy
     */
    fun getConnectionDowntime(subscriptionId: String, relayUrl: String): Long {
        val key = "$subscriptionId:$relayUrl"
        return relayTimestamps[key]?.connectionDowntime ?: 0L
    }
    
    /**
     * Load persisted timestamps on startup - ensures cross-session continuity
     */
    fun loadPersistedTimestamps() {
        val allPrefs = prefs.all
        var loadedCount = 0
        
        allPrefs.forEach { (key, value) ->
            if (value is String && key.contains(":")) {
                try {
                    val parts = value.split("|")
                    if (parts.size >= 6) {
                        val timestamp = RelaySubscriptionTimestamp(
                            subscriptionId = parts[0],
                            relayUrl = parts[1], 
                            lastEventTimestamp = parts[2].toLong(),
                            lastConnectionTime = parts[3].toLong(),
                            subscriptionConfirmedTime = parts[4].toLong(),
                            eventCount = parts[5].toLong(),
                            connectionDowntime = if (parts.size > 6) parts[6].toLong() else 0L
                        )
                        
                        // Only load recent timestamps (within 30 days)
                        val age = System.currentTimeMillis() - timestamp.lastConnectionTime
                        if (age < MAX_TIMESTAMP_AGE_DAYS * 24 * 60 * 60 * 1000L) {
                            relayTimestamps[key] = timestamp
                            loadedCount++
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse persisted timestamp: $key", e)
                }
            }
        }
        
        Log.i(TAG, "Loaded $loadedCount persisted relay timestamps")
    }
    
    /**
     * Persist timestamp to SharedPreferences
     */
    private fun persistTimestamp(key: String, timestamp: RelaySubscriptionTimestamp) {
        val value = "${timestamp.subscriptionId}|${timestamp.relayUrl}|${timestamp.lastEventTimestamp}|${timestamp.lastConnectionTime}|${timestamp.subscriptionConfirmedTime}|${timestamp.eventCount}|${timestamp.connectionDowntime}"
        prefs.edit().putString(key, value).apply()
    }
    
    /**
     * Clean up old timestamps to prevent storage bloat
     */
    fun cleanupOldTimestamps() {
        val cutoffTime = System.currentTimeMillis() - (MAX_TIMESTAMP_AGE_DAYS * 24 * 60 * 60 * 1000L)
        val toRemove = mutableListOf<String>()
        
        relayTimestamps.forEach { (key, timestamp) ->
            if (timestamp.lastConnectionTime < cutoffTime) {
                toRemove.add(key)
            }
        }
        
        toRemove.forEach { key ->
            relayTimestamps.remove(key)
            prefs.edit().remove(key).apply()
        }
        
        if (toRemove.isNotEmpty()) {
            Log.i(TAG, "Cleaned up ${toRemove.size} old relay timestamps")
        }
    }
    
    // === MODERN API (Per-relay focused) ===
    
    /**
     * Check if subscription is active (checks any relay for this subscription)
     */
    fun isActiveSubscription(subscriptionId: String): Boolean {
        return activeSubscriptions.keys.any { it.startsWith("$subscriptionId:") }
    }
    
    /**
     * Get configuration ID for a subscription (from any relay)
     */
    fun getConfigurationId(subscriptionId: String): String? {
        return activeSubscriptions.values.find { it.id == subscriptionId }?.configurationId
    }
    
    /**
     * Get subscription info (from first matching relay)
     */
    fun getSubscriptionInfo(subscriptionId: String): SubscriptionInfo? {
        return activeSubscriptions.values.find { it.id == subscriptionId }
    }
    
    /**
     * Get all active subscription IDs (unique across all relays)
     */
    fun getActiveSubscriptionIds(): Set<String> {
        return activeSubscriptions.values.map { it.id }.toSet()
    }
    
    /**
     * Remove subscription from specific relay
     */
    fun removeSubscription(subscriptionId: String, relayUrl: String) {
        val key = "$subscriptionId:$relayUrl"
        activeSubscriptions.remove(key)
        relayTimestamps.remove(key)
        prefs.edit().remove(key).apply()
        Log.d(TAG, "Removed subscription: $subscriptionId from relay: $relayUrl")
    }
    
    /**
     * Remove subscription from all relays
     */
    fun removeSubscription(subscriptionId: String) {
        val keysToRemove = activeSubscriptions.keys.filter { it.startsWith("$subscriptionId:") }
        keysToRemove.forEach { key ->
            activeSubscriptions.remove(key)
            relayTimestamps.remove(key)
            prefs.edit().remove(key).apply()
        }
        Log.d(TAG, "Removed subscription: $subscriptionId from ${keysToRemove.size} relays")
    }
    
    /**
     * Clear all subscriptions and timestamps
     */
    fun clearAll() {
        activeSubscriptions.clear()
        relayTimestamps.clear()
        prefs.edit().clear().apply()
        Log.d(TAG, "Cleared all subscriptions and timestamps")
    }
    
    /**
     * Clean up orphaned subscriptions for configurations that no longer exist
     */
    fun cleanupOrphanedSubscriptions(validConfigurationIds: Set<String>) {
        val toRemove = activeSubscriptions.values.filter { subscription ->
            subscription.configurationId !in validConfigurationIds
        }
        
        toRemove.forEach { subscription ->
            removeSubscription(subscription.id, subscription.relayUrl)
            Log.d(TAG, "Cleaned up orphaned subscription: ${subscription.id} from ${subscription.relayUrl}")
        }
        
        if (toRemove.isNotEmpty()) {
            Log.i(TAG, "Cleaned up ${toRemove.size} orphaned subscriptions")
        }
    }
    
    /**
     * Get subscriptions for a specific configuration
     */
    fun getSubscriptionsForConfiguration(configurationId: String): List<SubscriptionInfo> {
        return activeSubscriptions.values.filter { it.configurationId == configurationId }
    }
    
    /**
     * Get relay-specific statistics
     */
    fun getRelayStats(relayUrl: String): RelayStats {
        val relayTimestamps = this.relayTimestamps.values.filter { it.relayUrl == relayUrl }
        
        return RelayStats(
            relayUrl = relayUrl,
            subscriptionCount = relayTimestamps.size,
            totalEvents = relayTimestamps.sumOf { it.eventCount },
            avgConnectionTime = if (relayTimestamps.isNotEmpty()) {
                relayTimestamps.map { System.currentTimeMillis() - it.lastConnectionTime }.average().toLong()
            } else 0L,
            oldestTimestamp = relayTimestamps.minByOrNull { it.lastEventTimestamp }?.lastEventTimestamp,
            newestTimestamp = relayTimestamps.maxByOrNull { it.lastEventTimestamp }?.lastEventTimestamp
        )
    }
    
    /**
     * Get comprehensive statistics about subscriptions
     */
    fun getStats(): SubscriptionStats {
        val uniqueSubscriptions = activeSubscriptions.values.map { it.id }.toSet()
        val relayCount = activeSubscriptions.values.map { it.relayUrl }.toSet().size
        
        return SubscriptionStats(
            activeCount = uniqueSubscriptions.size,
            timestampCount = relayTimestamps.size,
            relayCount = relayCount,
            oldestSubscription = activeSubscriptions.values.minByOrNull { it.createdAt }?.createdAt,
            newestSubscription = activeSubscriptions.values.maxByOrNull { it.createdAt }?.createdAt,
            totalEvents = relayTimestamps.values.sumOf { it.eventCount }
        )
    }
    
    // === LEGACY METHODS DELETED - BETA APPROACH ===
    // All legacy compatibility methods removed - use per-relay methods directly
    
    // === NEW DATA CLASSES ===
    
    data class RelayStats(
        val relayUrl: String,
        val subscriptionCount: Int,
        val totalEvents: Long,
        val avgConnectionTime: Long,
        val oldestTimestamp: Long?,
        val newestTimestamp: Long?
    )
    
    data class SubscriptionStats(
        val activeCount: Int,
        val timestampCount: Int,
        val relayCount: Int,
        val oldestSubscription: Long?,
        val newestSubscription: Long?,
        val totalEvents: Long
    )
}