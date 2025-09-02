package com.cmdruid.pubsub.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * REWRITTEN EventCache with persistence for cross-session duplicate detection
 * BREAKING CHANGE: Complete replacement with 4x capacity + persistent storage
 * Eliminates duplicate events across service restarts and provides better performance
 */
class EventCache(
    private val context: Context,
    private val memorySize: Int = 2000,  // INCREASED: 4x larger than legacy (was 500)
    private val persistentSize: Int = 10000
) {
    companion object {
        private const val TAG = "EventCache"
        private const val PREFS_NAME = "event_cache"
        private const val CLEANUP_INTERVAL_HOURS = 24
    }
    
    // Fast in-memory cache for recent events
    private val memoryCache = LinkedHashSet<String>()
    private val lock = ReentrantReadWriteLock()
    
    // Persistent storage for cross-session continuity
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private var lastCleanup = 0L
    
    /**
     * Check if event has been seen before (checks both memory and persistent cache)
     * ENHANCED: Now checks persistent storage for cross-session duplicates
     */
    fun hasSeenEvent(eventId: String): Boolean {
        return lock.read {
            // Fast check in memory first
            if (memoryCache.contains(eventId)) {
                return@read true
            }
            
            // Check persistent storage for cross-session duplicates
            prefs.contains(eventId)
        }
    }
    
    /**
     * Mark event as seen in both memory and persistent cache
     * ENHANCED: Now persists events for cross-session duplicate detection
     */
    fun markEventSeen(eventId: String): Boolean {
        return lock.write {
            // Check if already exists
            if (memoryCache.contains(eventId) || prefs.contains(eventId)) {
                return@write false // Already seen
            }
            
            // Add to memory cache with rolling window
            if (memoryCache.size >= memorySize) {
                val oldest = memoryCache.iterator().next()
                memoryCache.remove(oldest)
            }
            memoryCache.add(eventId)
            
            // Add to persistent cache with timestamp
            val currentTime = System.currentTimeMillis()
            prefs.edit().putLong(eventId, currentTime).apply()
            
            // Periodic cleanup of persistent storage
            if (currentTime - lastCleanup > CLEANUP_INTERVAL_HOURS * 60 * 60 * 1000) {
                cleanupPersistentCache()
                lastCleanup = currentTime
            }
            
            Log.v(TAG, "Cached event: ${eventId.take(8)}... (mem: ${memoryCache.size}/$memorySize)")
            true
        }
    }
    
    /**
     * Load recent events from persistent storage into memory on startup
     * NEW: Ensures cross-session duplicate detection
     */
    fun loadPersistentCache() {
        lock.write {
            val currentTime = System.currentTimeMillis()
            val maxAge = 24 * 60 * 60 * 1000L // 24 hours
            val allPrefs = prefs.all
            var loadedCount = 0
            
            // Load recent events into memory cache
            allPrefs.entries.sortedByDescending { (it.value as? Long) ?: 0L }
                .take(memorySize)
                .forEach { (eventId, timestamp) ->
                    if (timestamp is Long && (currentTime - timestamp) < maxAge) {
                        memoryCache.add(eventId)
                        loadedCount++
                    }
                }
            
            Log.i(TAG, "Loaded $loadedCount recent events into memory cache")
        }
    }
    
    /**
     * Clean up old entries from persistent storage
     * Prevents unlimited storage growth
     */
    private fun cleanupPersistentCache() {
        val currentTime = System.currentTimeMillis()
        val maxAge = 7 * 24 * 60 * 60 * 1000L // 7 days
        val allPrefs = prefs.all
        val toRemove = mutableListOf<String>()
        
        allPrefs.forEach { (eventId, timestamp) ->
            if (timestamp is Long && (currentTime - timestamp) > maxAge) {
                toRemove.add(eventId)
            }
        }
        
        if (toRemove.isNotEmpty()) {
            val editor = prefs.edit()
            toRemove.forEach { editor.remove(it) }
            editor.apply()
            
            Log.i(TAG, "Cleaned up ${toRemove.size} old events from persistent cache")
        }
    }
    
    /**
     * Clear all cached events (both memory and persistent)
     */
    fun clear() {
        lock.write {
            memoryCache.clear()
            prefs.edit().clear().apply()
            Log.d(TAG, "Cleared event cache (memory + persistent)")
        }
    }
    
    /**
     * Get current cache size
     */
    fun size(): Int {
        return lock.read {
            memoryCache.size
        }
    }
    
    /**
     * Get comprehensive cache statistics
     * ENHANCED: Now includes persistent cache information
     */
    fun getStats(): CacheStats {
        return lock.read {
            val persistentCount = prefs.all.size
            
            CacheStats(
                currentSize = memoryCache.size,
                maxSize = memorySize,
                persistentSize = persistentCount,
                persistentCapacity = persistentSize,
                utilizationPercent = if (memorySize > 0) {
                    (memoryCache.size.toFloat() / memorySize * 100).toInt()
                } else 0,
                oldestEventId = memoryCache.firstOrNull(),
                newestEventId = memoryCache.lastOrNull()
            )
        }
    }
    
    /**
     * Check if cache is near capacity
     */
    fun isNearCapacity(threshold: Float = 0.9f): Boolean {
        return lock.read {
            memoryCache.size >= (memorySize * threshold)
        }
    }
    
    /**
     * Get recent event IDs (last N events)
     */
    fun getRecentEventIds(count: Int = 10): List<String> {
        return lock.read {
            memoryCache.toList().takeLast(count)
        }
    }
    
    /**
     * Compact cache by removing oldest events if needed
     */
    fun compact(targetSize: Int = memorySize / 2) {
        lock.write {
            if (memoryCache.size > targetSize) {
                val toRemove = memoryCache.size - targetSize
                repeat(toRemove) {
                    if (memoryCache.isNotEmpty()) {
                        val oldest = memoryCache.iterator().next()
                        memoryCache.remove(oldest)
                    }
                }
                Log.d(TAG, "Compacted cache: removed $toRemove events, now ${memoryCache.size}")
            }
        }
    }
    
    /**
     * Enhanced cache statistics with persistent storage information
     */
    data class CacheStats(
        val currentSize: Int,
        val maxSize: Int,
        val persistentSize: Int,
        val persistentCapacity: Int,
        val utilizationPercent: Int,
        val oldestEventId: String?,
        val newestEventId: String?
    ) {
        override fun toString(): String {
            return "EventCache: mem=$currentSize/$maxSize ($utilizationPercent%), persistent=$persistentSize"
        }
    }
}