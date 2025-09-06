package com.cmdruid.pubsub.service

import android.util.Log
import com.cmdruid.pubsub.logging.UnifiedLogger
import com.cmdruid.pubsub.logging.LogDomain
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight tracker for subscription cancellations to prevent duplicate cancellation messages
 * 
 * Key features:
 * - Memory-efficient with size limits and automatic cleanup
 * - Tracks cancellation attempts per subscription ID
 * - Prevents duplicate cancellation messages to relays
 * - Thread-safe for concurrent access
 */
class SubscriptionCancellationTracker(
    private val unifiedLogger: UnifiedLogger
) {
    companion object {
        private const val TAG = "SubscriptionCancellationTracker"
        private const val MAX_CANCELLED_SUBSCRIPTIONS = 1000 // Limit memory usage
        private const val CLEANUP_INTERVAL_MS = 300000L // 5 minutes
    }
    
    // Track cancelled subscription IDs with timestamps
    private val cancelledSubscriptions = ConcurrentHashMap<String, Long>()
    private val totalCancellations = AtomicLong(0)
    private var lastCleanupTime = System.currentTimeMillis()
    
    /**
     * Check if a subscription has already been cancelled
     */
    fun isSubscriptionCancelled(subscriptionId: String): Boolean {
        return cancelledSubscriptions.containsKey(subscriptionId)
    }
    
    /**
     * Mark a subscription as cancelled and track the cancellation
     */
    fun markSubscriptionCancelled(subscriptionId: String): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // Perform periodic cleanup to prevent memory bloat
        if (currentTime - lastCleanupTime > CLEANUP_INTERVAL_MS) {
            performCleanup()
            lastCleanupTime = currentTime
        }
        
        // Check if we're at capacity and need to make room
        if (cancelledSubscriptions.size >= MAX_CANCELLED_SUBSCRIPTIONS) {
            // Remove oldest entries to make room (simple LRU-like behavior)
            val oldestEntries = cancelledSubscriptions.entries
                .sortedBy { it.value }
                .take(MAX_CANCELLED_SUBSCRIPTIONS / 4) // Remove 25% of entries
            
            oldestEntries.forEach { (subscriptionId, _) ->
                cancelledSubscriptions.remove(subscriptionId)
            }
            
            unifiedLogger.debug(LogDomain.SUBSCRIPTION, 
                "Cleaned up ${oldestEntries.size} old cancellation records to prevent memory bloat")
        }
        
        // Add the new cancellation
        val wasAlreadyCancelled = cancelledSubscriptions.containsKey(subscriptionId)
        cancelledSubscriptions[subscriptionId] = currentTime
        totalCancellations.incrementAndGet()
        
        if (!wasAlreadyCancelled) {
            unifiedLogger.info(LogDomain.SUBSCRIPTION, 
                "Marked subscription $subscriptionId as cancelled (total cancellations: ${totalCancellations.get()})")
        }
        
        return !wasAlreadyCancelled // Return true if this was a new cancellation
    }
    
    /**
     * Get statistics about cancellations
     */
    fun getCancellationStats(): CancellationStats {
        val currentTime = System.currentTimeMillis()
        val recentCancellations = cancelledSubscriptions.values.count { 
            currentTime - it < CLEANUP_INTERVAL_MS 
        }
        
        return CancellationStats(
            totalCancellations = totalCancellations.get(),
            activeCancelledSubscriptions = cancelledSubscriptions.size,
            recentCancellations = recentCancellations
        )
    }
    
    /**
     * Perform cleanup of old cancellation records
     */
    private fun performCleanup() {
        val currentTime = System.currentTimeMillis()
        val cutoffTime = currentTime - (CLEANUP_INTERVAL_MS * 2) // Keep records for 10 minutes
        
        val beforeSize = cancelledSubscriptions.size
        val removed = cancelledSubscriptions.entries.removeAll { (_, timestamp) ->
            timestamp < cutoffTime
        }
        
        if (removed) {
            val removedCount = beforeSize - cancelledSubscriptions.size
            unifiedLogger.debug(LogDomain.SUBSCRIPTION, 
                "Cleaned up $removedCount old cancellation records")
        }
    }
    
    /**
     * Clear all cancellation records (for testing or reset)
     */
    fun clearAll() {
        val clearedCount = cancelledSubscriptions.size
        cancelledSubscriptions.clear()
        totalCancellations.set(0)
        
        unifiedLogger.info(LogDomain.SUBSCRIPTION, 
            "Cleared all cancellation records ($clearedCount subscriptions)")
    }
    
    /**
     * Data class for cancellation statistics
     */
    data class CancellationStats(
        val totalCancellations: Long,
        val activeCancelledSubscriptions: Int,
        val recentCancellations: Int
    )
}
