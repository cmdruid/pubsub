package com.cmdruid.pubsub.service

import android.util.Log
import com.cmdruid.pubsub.nostr.NostrFilter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages active subscriptions and tracks their state
 */
class SubscriptionManager {
    
    companion object {
        private const val TAG = "SubscriptionManager"
    }
    
    private val activeSubscriptions = ConcurrentHashMap<String, SubscriptionInfo>()
    private val subscriptionTimestamps = ConcurrentHashMap<String, Long>()
    
    data class SubscriptionInfo(
        val id: String,
        val configurationId: String,
        val filter: NostrFilter,
        val relayUrl: String,
        val createdAt: Long
    )
    
    /**
     * Register a subscription with a specific ID
     */
    fun registerSubscription(subscriptionId: String, configurationId: String, filter: NostrFilter, relayUrl: String) {
        val subscriptionInfo = SubscriptionInfo(
            id = subscriptionId,
            configurationId = configurationId,
            filter = filter,
            relayUrl = relayUrl,
            createdAt = System.currentTimeMillis()
        )
        
        activeSubscriptions[subscriptionId] = subscriptionInfo
        Log.d(TAG, "Registered subscription: $subscriptionId for config: $configurationId")
    }
    
    /**
     * Remove a subscription
     */
    fun removeSubscription(subscriptionId: String) {
        activeSubscriptions.remove(subscriptionId)
        subscriptionTimestamps.remove(subscriptionId)
        Log.d(TAG, "Removed subscription: $subscriptionId")
    }
    
    /**
     * Check if subscription is active
     */
    fun isActiveSubscription(subscriptionId: String): Boolean {
        return activeSubscriptions.containsKey(subscriptionId)
    }
    
    /**
     * Get subscription info
     */
    fun getSubscriptionInfo(subscriptionId: String): SubscriptionInfo? {
        return activeSubscriptions[subscriptionId]
    }
    
    /**
     * Get configuration ID for a subscription
     */
    fun getConfigurationId(subscriptionId: String): String? {
        return activeSubscriptions[subscriptionId]?.configurationId
    }
    
    /**
     * Get all active subscription IDs
     */
    fun getActiveSubscriptionIds(): Set<String> {
        return activeSubscriptions.keys.toSet()
    }
    
    /**
     * Update the last event timestamp for a subscription
     */
    fun updateLastEventTimestamp(subscriptionId: String, eventTimestamp: Long) {
        val current = subscriptionTimestamps[subscriptionId] ?: 0L
        if (eventTimestamp > current) {
            subscriptionTimestamps[subscriptionId] = eventTimestamp
            Log.d(TAG, "Updated timestamp for $subscriptionId: $eventTimestamp")
        }
    }
    
    /**
     * Get the last event timestamp for a subscription
     */
    fun getLastEventTimestamp(subscriptionId: String): Long? {
        return subscriptionTimestamps[subscriptionId]
    }
    
    /**
     * Create resubscription filter with updated "since" timestamp
     */
    fun createResubscriptionFilter(subscriptionId: String): NostrFilter? {
        val subscriptionInfo = activeSubscriptions[subscriptionId] ?: return null
        val lastTimestamp = subscriptionTimestamps[subscriptionId]
        
        return if (lastTimestamp != null) {
            // Add 1 second to avoid getting the same event again
            subscriptionInfo.filter.copy(since = lastTimestamp + 1)
        } else {
            subscriptionInfo.filter
        }
    }
    
    /**
     * Clean up orphaned subscriptions for configurations that no longer exist
     */
    fun cleanupOrphanedSubscriptions(validConfigurationIds: Set<String>) {
        val toRemove = activeSubscriptions.values.filter { subscription ->
            subscription.configurationId !in validConfigurationIds
        }
        
        toRemove.forEach { subscription ->
            removeSubscription(subscription.id)
            Log.d(TAG, "Cleaned up orphaned subscription: ${subscription.id}")
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
     * Clear all subscriptions
     */
    fun clearAll() {
        activeSubscriptions.clear()
        subscriptionTimestamps.clear()
        Log.d(TAG, "Cleared all subscriptions")
    }
    
    /**
     * Get statistics about subscriptions
     */
    fun getStats(): SubscriptionStats {
        return SubscriptionStats(
            activeCount = activeSubscriptions.size,
            timestampCount = subscriptionTimestamps.size,
            oldestSubscription = activeSubscriptions.values.minByOrNull { it.createdAt }?.createdAt,
            newestSubscription = activeSubscriptions.values.maxByOrNull { it.createdAt }?.createdAt
        )
    }
    
    data class SubscriptionStats(
        val activeCount: Int,
        val timestampCount: Int,
        val oldestSubscription: Long?,
        val newestSubscription: Long?
    )
    

}
