package com.cmdruid.pubsub.service

import android.util.Log
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Rolling cache for detecting duplicate events
 * Thread-safe implementation using LinkedHashSet for O(1) operations
 */
class EventCache(
    private val maxSize: Int = 500
) {
    
    companion object {
        private const val TAG = "EventCache"
    }
    
    private val seenEventIds = LinkedHashSet<String>()
    private val lock = ReentrantReadWriteLock()
    
    /**
     * Check if event has been seen before
     */
    fun hasSeenEvent(eventId: String): Boolean {
        return lock.read {
            seenEventIds.contains(eventId)
        }
    }
    
    /**
     * Mark event as seen, maintaining rolling window
     */
    fun markEventSeen(eventId: String): Boolean {
        return lock.write {
            // Check if already exists
            if (seenEventIds.contains(eventId)) {
                return@write false // Already seen
            }
            
            // Remove oldest if at capacity
            if (seenEventIds.size >= maxSize) {
                val oldest = seenEventIds.iterator().next()
                seenEventIds.remove(oldest)
                Log.v(TAG, "Evicted oldest event from cache: ${oldest.take(8)}...")
            }
            
            // Add new event
            seenEventIds.add(eventId)
            Log.v(TAG, "Cached event: ${eventId.take(8)}... (${seenEventIds.size}/$maxSize)")
            
            true // Successfully added
        }
    }
    
    /**
     * Clear all cached events
     */
    fun clear() {
        lock.write {
            seenEventIds.clear()
            Log.d(TAG, "Cleared event cache")
        }
    }
    
    /**
     * Get current cache size
     */
    fun size(): Int {
        return lock.read {
            seenEventIds.size
        }
    }
    
    /**
     * Get cache statistics
     */
    fun getStats(): CacheStats {
        return lock.read {
            CacheStats(
                currentSize = seenEventIds.size,
                maxSize = maxSize,
                utilizationPercent = if (maxSize > 0) {
                    (seenEventIds.size.toFloat() / maxSize * 100).toInt()
                } else 0,
                oldestEventId = seenEventIds.firstOrNull(),
                newestEventId = seenEventIds.lastOrNull()
            )
        }
    }
    
    /**
     * Check if cache is near capacity
     */
    fun isNearCapacity(threshold: Float = 0.9f): Boolean {
        return lock.read {
            seenEventIds.size >= (maxSize * threshold)
        }
    }
    
    /**
     * Get recent event IDs (last N events)
     */
    fun getRecentEventIds(count: Int = 10): List<String> {
        return lock.read {
            seenEventIds.toList().takeLast(count)
        }
    }
    
    /**
     * Compact cache by removing oldest events if needed
     */
    fun compact(targetSize: Int = maxSize / 2) {
        lock.write {
            if (seenEventIds.size > targetSize) {
                val toRemove = seenEventIds.size - targetSize
                repeat(toRemove) {
                    if (seenEventIds.isNotEmpty()) {
                        val oldest = seenEventIds.iterator().next()
                        seenEventIds.remove(oldest)
                    }
                }
                Log.d(TAG, "Compacted cache: removed $toRemove events, now ${seenEventIds.size}")
            }
        }
    }
    
    data class CacheStats(
        val currentSize: Int,
        val maxSize: Int,
        val utilizationPercent: Int,
        val oldestEventId: String?,
        val newestEventId: String?
    ) {
        override fun toString(): String {
            return "EventCache: $currentSize/$maxSize ($utilizationPercent%)"
        }
    }
}
