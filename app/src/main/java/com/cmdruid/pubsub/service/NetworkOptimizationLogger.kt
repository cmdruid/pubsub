package com.cmdruid.pubsub.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Advanced logging and monitoring system for network optimization features
 * Tracks network state changes, wake lock usage, and reconnection effectiveness
 */
class NetworkOptimizationLogger(private val context: Context) {
    
    companion object {
        private const val TAG = "NetworkOptimization"
        private const val MAX_LOG_ENTRIES = 500
    }
    
    data class NetworkEvent(
        val timestamp: Long,
        val eventType: String,
        val networkType: String,
        val networkQuality: String,
        val connectionCount: Int,
        val batteryLevel: Int,
        val appState: String,
        val details: Map<String, Any> = emptyMap()
    ) {
        fun toFormattedString(): String {
            val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            val timeStr = dateFormat.format(Date(timestamp))
            val detailsStr = if (details.isNotEmpty()) " | ${details.entries.joinToString(", ") { "${it.key}=${it.value}" }}" else ""
            return "[$timeStr] [$batteryLevel%] $eventType: $networkType/$networkQuality (${connectionCount}conn, $appState)$detailsStr"
        }
    }
    
    data class WakeLockEvent(
        val timestamp: Long,
        val action: String, // "acquired" or "released"
        val reason: String,
        val durationMs: Long = 0,
        val batteryLevel: Int,
        val networkType: String,
        val success: Boolean = true,
        val details: Map<String, Any> = emptyMap()
    ) {
        fun toFormattedString(): String {
            val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            val timeStr = dateFormat.format(Date(timestamp))
            val durationStr = if (action == "released") " (${durationMs}ms)" else ""
            val statusStr = if (!success) " [FAILED]" else ""
            val detailsStr = if (details.isNotEmpty()) " | ${details.entries.joinToString(", ") { "${it.key}=${it.value}" }}" else ""
            return "[$timeStr] [$batteryLevel%] WakeLock $action: $reason$durationStr ($networkType)$statusStr$detailsStr"
        }
    }
    
    data class ReconnectionEvent(
        val timestamp: Long,
        val relayUrl: String,
        val decision: String, // "attempted", "skipped", "successful", "failed"
        val reason: String,
        val attemptNumber: Int,
        val delayMs: Long,
        val networkType: String,
        val networkQuality: String,
        val appState: String,
        val batteryLevel: Int,
        val details: Map<String, Any> = emptyMap()
    ) {
        fun toFormattedString(): String {
            val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            val timeStr = dateFormat.format(Date(timestamp))
            val relayShort = relayUrl.substringAfter("://").take(15) + "..."
            val detailsStr = if (details.isNotEmpty()) " | ${details.entries.joinToString(", ") { "${it.key}=${it.value}" }}" else ""
            return "[$timeStr] [$batteryLevel%] Reconnect $decision: $relayShort (#$attemptNumber, ${delayMs}ms delay, $networkType/$networkQuality, $appState) - $reason$detailsStr"
        }
    }
    
    // Event storage
    private val networkEvents = ConcurrentLinkedQueue<NetworkEvent>()
    private val wakeLockEvents = ConcurrentLinkedQueue<WakeLockEvent>()
    private val reconnectionEvents = ConcurrentLinkedQueue<ReconnectionEvent>()
    
    // Metrics tracking
    private val networkStateChanges = AtomicLong(0)
    private val wakeLockAcquisitions = AtomicLong(0)
    private val wakeLockReleases = AtomicLong(0)
    private val reconnectionAttempts = AtomicLong(0)
    private val reconnectionSuccesses = AtomicLong(0)
    private val reconnectionSkips = AtomicLong(0)
    
    // Timing tracking
    private var lastNetworkChange = 0L
    private var lastWakeLockAcquisition = 0L
    private var totalWakeLockTime = 0L
    
    /**
     * Log network state change event
     */
    fun logNetworkStateChange(
        networkType: String,
        networkQuality: String,
        connectionCount: Int,
        batteryLevel: Int,
        appState: String,
        details: Map<String, Any> = emptyMap()
    ) {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastChange = if (lastNetworkChange > 0) currentTime - lastNetworkChange else 0L
        
        val event = NetworkEvent(
            timestamp = currentTime,
            eventType = "network_change",
            networkType = networkType,
            networkQuality = networkQuality,
            connectionCount = connectionCount,
            batteryLevel = batteryLevel,
            appState = appState,
            details = details + mapOf("time_since_last_change_ms" to timeSinceLastChange)
        )
        
        addNetworkEvent(event)
        networkStateChanges.incrementAndGet()
        lastNetworkChange = currentTime
        
        Log.d(TAG, event.toFormattedString())
    }
    
    /**
     * Log network quality assessment
     */
    fun logNetworkQualityAssessment(
        networkType: String,
        quality: String,
        capabilities: NetworkCapabilities?,
        batteryLevel: Int,
        details: Map<String, Any> = emptyMap()
    ) {
        val capabilityDetails = capabilities?.let { caps ->
            mapOf(
                "metered" to !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                "validated" to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                "internet" to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            )
        } ?: emptyMap()
        
        val event = NetworkEvent(
            timestamp = System.currentTimeMillis(),
            eventType = "quality_assessment",
            networkType = networkType,
            networkQuality = quality,
            connectionCount = 0,
            batteryLevel = batteryLevel,
            appState = "unknown",
            details = details + capabilityDetails
        )
        
        addNetworkEvent(event)
        Log.d(TAG, event.toFormattedString())
    }
    
    /**
     * Log wake lock acquisition
     */
    fun logWakeLockAcquisition(
        reason: String,
        durationMs: Long,
        batteryLevel: Int,
        networkType: String,
        details: Map<String, Any> = emptyMap()
    ) {
        val currentTime = System.currentTimeMillis()
        
        val event = WakeLockEvent(
            timestamp = currentTime,
            action = "acquired",
            reason = reason,
            durationMs = durationMs,
            batteryLevel = batteryLevel,
            networkType = networkType,
            success = true,
            details = details
        )
        
        addWakeLockEvent(event)
        wakeLockAcquisitions.incrementAndGet()
        lastWakeLockAcquisition = currentTime
        
        Log.d(TAG, event.toFormattedString())
    }
    
    /**
     * Log wake lock release
     */
    fun logWakeLockRelease(
        reason: String,
        heldDurationMs: Long,
        batteryLevel: Int,
        networkType: String,
        wasTimeout: Boolean = false,
        details: Map<String, Any> = emptyMap()
    ) {
        val event = WakeLockEvent(
            timestamp = System.currentTimeMillis(),
            action = "released",
            reason = reason,
            durationMs = heldDurationMs,
            batteryLevel = batteryLevel,
            networkType = networkType,
            success = !wasTimeout,
            details = details + mapOf("timeout" to wasTimeout)
        )
        
        addWakeLockEvent(event)
        wakeLockReleases.incrementAndGet()
        totalWakeLockTime += heldDurationMs
        
        Log.d(TAG, event.toFormattedString())
    }
    
    /**
     * Log reconnection decision
     */
    fun logReconnectionDecision(
        relayUrl: String,
        decision: String,
        reason: String,
        attemptNumber: Int,
        delayMs: Long,
        networkType: String,
        networkQuality: String,
        appState: String,
        batteryLevel: Int,
        details: Map<String, Any> = emptyMap()
    ) {
        val event = ReconnectionEvent(
            timestamp = System.currentTimeMillis(),
            relayUrl = relayUrl,
            decision = decision,
            reason = reason,
            attemptNumber = attemptNumber,
            delayMs = delayMs,
            networkType = networkType,
            networkQuality = networkQuality,
            appState = appState,
            batteryLevel = batteryLevel,
            details = details
        )
        
        addReconnectionEvent(event)
        
        when (decision) {
            "attempted" -> reconnectionAttempts.incrementAndGet()
            "successful" -> reconnectionSuccesses.incrementAndGet()
            "skipped" -> reconnectionSkips.incrementAndGet()
        }
        
        Log.d(TAG, event.toFormattedString())
    }
    
    /**
     * Add network event to queue with size management
     */
    private fun addNetworkEvent(event: NetworkEvent) {
        networkEvents.offer(event)
        while (networkEvents.size > MAX_LOG_ENTRIES) {
            networkEvents.poll()
        }
    }
    
    /**
     * Add wake lock event to queue with size management
     */
    private fun addWakeLockEvent(event: WakeLockEvent) {
        wakeLockEvents.offer(event)
        while (wakeLockEvents.size > MAX_LOG_ENTRIES) {
            wakeLockEvents.poll()
        }
    }
    
    /**
     * Add reconnection event to queue with size management
     */
    private fun addReconnectionEvent(event: ReconnectionEvent) {
        reconnectionEvents.offer(event)
        while (reconnectionEvents.size > MAX_LOG_ENTRIES) {
            reconnectionEvents.poll()
        }
    }
    
    /**
     * Get network optimization statistics
     */
    fun getNetworkOptimizationStats(): Map<String, Any> {
        val currentTime = System.currentTimeMillis()
        val collectionPeriod = if (networkEvents.isNotEmpty()) {
            currentTime - networkEvents.first().timestamp
        } else 0L
        
        return mapOf(
            "collection_period_ms" to collectionPeriod,
            "network_state_changes" to networkStateChanges.get(),
            "wake_lock_acquisitions" to wakeLockAcquisitions.get(),
            "wake_lock_releases" to wakeLockReleases.get(),
            "total_wake_lock_time_ms" to totalWakeLockTime,
            "average_wake_lock_duration_ms" to if (wakeLockReleases.get() > 0) {
                totalWakeLockTime / wakeLockReleases.get()
            } else 0L,
            "reconnection_attempts" to reconnectionAttempts.get(),
            "reconnection_successes" to reconnectionSuccesses.get(),
            "reconnection_skips" to reconnectionSkips.get(),
            "reconnection_success_rate" to if (reconnectionAttempts.get() > 0) {
                (reconnectionSuccesses.get().toDouble() / reconnectionAttempts.get()) * 100.0
            } else 0.0,
            "network_events_logged" to networkEvents.size,
            "wake_lock_events_logged" to wakeLockEvents.size,
            "reconnection_events_logged" to reconnectionEvents.size
        )
    }
    
    /**
     * Get recent network events
     */
    fun getRecentNetworkEvents(count: Int = 20): List<NetworkEvent> {
        return networkEvents.toList().takeLast(count)
    }
    
    /**
     * Get recent wake lock events
     */
    fun getRecentWakeLockEvents(count: Int = 20): List<WakeLockEvent> {
        return wakeLockEvents.toList().takeLast(count)
    }
    
    /**
     * Get recent reconnection events
     */
    fun getRecentReconnectionEvents(count: Int = 20): List<ReconnectionEvent> {
        return reconnectionEvents.toList().takeLast(count)
    }
    
    /**
     * Generate comprehensive network optimization effectiveness report
     */
    fun generateNetworkOptimizationReport(): String {
        val stats = getNetworkOptimizationStats()
        val recentNetworkEvents = getRecentNetworkEvents(10)
        val recentWakeLockEvents = getRecentWakeLockEvents(10)
        val recentReconnectionEvents = getRecentReconnectionEvents(10)
        
        return buildString {
            appendLine("=== Network Optimization Report ===")
            appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("Collection Period: ${stats["collection_period_ms"] as Long / 1000}s")
            appendLine()
            
            // Network Statistics
            appendLine("NETWORK OPTIMIZATION STATS:")
            appendLine("- Network State Changes: ${stats["network_state_changes"]}")
            appendLine("- Reconnection Attempts: ${stats["reconnection_attempts"]}")
            appendLine("- Reconnection Successes: ${stats["reconnection_successes"]}")
            appendLine("- Reconnection Skips: ${stats["reconnection_skips"]}")
            appendLine("- Success Rate: ${String.format("%.1f", stats["reconnection_success_rate"])}%")
            appendLine()
            
            // Wake Lock Statistics
            appendLine("WAKE LOCK OPTIMIZATION STATS:")
            appendLine("- Wake Lock Acquisitions: ${stats["wake_lock_acquisitions"]}")
            appendLine("- Wake Lock Releases: ${stats["wake_lock_releases"]}")
            appendLine("- Total Wake Lock Time: ${stats["total_wake_lock_time_ms"]}ms")
            appendLine("- Average Duration: ${stats["average_wake_lock_duration_ms"]}ms")
            appendLine()
            
            // Recent Events
            if (recentNetworkEvents.isNotEmpty()) {
                appendLine("RECENT NETWORK EVENTS:")
                recentNetworkEvents.take(5).forEach { event ->
                    appendLine("  ${event.toFormattedString()}")
                }
                appendLine()
            }
            
            if (recentWakeLockEvents.isNotEmpty()) {
                appendLine("RECENT WAKE LOCK EVENTS:")
                recentWakeLockEvents.take(5).forEach { event ->
                    appendLine("  ${event.toFormattedString()}")
                }
                appendLine()
            }
            
            if (recentReconnectionEvents.isNotEmpty()) {
                appendLine("RECENT RECONNECTION EVENTS:")
                recentReconnectionEvents.take(5).forEach { event ->
                    appendLine("  ${event.toFormattedString()}")
                }
                appendLine()
            }
            
            // Effectiveness Assessment
            val effectiveness = assessNetworkOptimizationEffectiveness(stats)
            appendLine("NETWORK OPTIMIZATION EFFECTIVENESS: $effectiveness")
            appendLine()
            
            // Recommendations
            val recommendations = generateOptimizationRecommendations(stats)
            if (recommendations.isNotEmpty()) {
                appendLine("OPTIMIZATION RECOMMENDATIONS:")
                recommendations.forEach { recommendation ->
                    appendLine("- $recommendation")
                }
            }
        }
    }
    
    /**
     * Assess network optimization effectiveness
     */
    private fun assessNetworkOptimizationEffectiveness(stats: Map<String, Any>): String {
        val successRate = stats["reconnection_success_rate"] as Double
        val avgWakeLockDuration = stats["average_wake_lock_duration_ms"] as Long
        val networkChanges = stats["network_state_changes"] as Long
        val collectionPeriod = stats["collection_period_ms"] as Long
        
        val scores = mutableListOf<Double>()
        
        // Reconnection success rate score
        scores.add(successRate / 100.0)
        
        // Wake lock efficiency score (shorter is better, max 30 seconds)
        val wakeLockScore = if (avgWakeLockDuration > 0) {
            maxOf(0.0, 1.0 - (avgWakeLockDuration / 30000.0))
        } else 1.0
        scores.add(wakeLockScore)
        
        // Network responsiveness score (more changes indicate better monitoring)
        val networkScore = if (collectionPeriod > 0) {
            minOf(1.0, (networkChanges * 60000.0) / collectionPeriod) // Changes per minute
        } else 0.5
        scores.add(networkScore)
        
        val averageScore = scores.average()
        
        return when {
            averageScore >= 0.9 -> "EXCELLENT"
            averageScore >= 0.75 -> "VERY_GOOD"
            averageScore >= 0.6 -> "GOOD"
            averageScore >= 0.4 -> "MODERATE"
            averageScore >= 0.2 -> "POOR"
            else -> "INEFFECTIVE"
        }
    }
    
    /**
     * Generate optimization recommendations based on collected data
     */
    private fun generateOptimizationRecommendations(stats: Map<String, Any>): List<String> {
        val recommendations = mutableListOf<String>()
        
        val successRate = stats["reconnection_success_rate"] as Double
        val avgWakeLockDuration = stats["average_wake_lock_duration_ms"] as Long
        val wakeLockAcquisitions = stats["wake_lock_acquisitions"] as Long
        val reconnectionSkips = stats["reconnection_skips"] as Long
        val reconnectionAttempts = stats["reconnection_attempts"] as Long
        
        if (successRate < 80.0) {
            recommendations.add("Reconnection success rate is low (${String.format("%.1f", successRate)}%). Consider adjusting network quality thresholds.")
        }
        
        if (avgWakeLockDuration > 20000) {
            recommendations.add("Average wake lock duration is high (${avgWakeLockDuration}ms). Review critical operation timeouts.")
        }
        
        if (wakeLockAcquisitions > 0 && reconnectionAttempts > 0) {
            val wakeLockPerReconnect = wakeLockAcquisitions.toDouble() / reconnectionAttempts
            if (wakeLockPerReconnect > 1.5) {
                recommendations.add("High wake lock usage per reconnection (${String.format("%.1f", wakeLockPerReconnect)}). Optimize wake lock acquisition logic.")
            }
        }
        
        if (reconnectionSkips > reconnectionAttempts) {
            recommendations.add("Many reconnections are being skipped (${reconnectionSkips} skips vs ${reconnectionAttempts} attempts). Consider relaxing skip conditions for better connectivity.")
        }
        
        return recommendations
    }
    
    /**
     * Clear all logged events and reset counters
     */
    fun clearLogs() {
        networkEvents.clear()
        wakeLockEvents.clear()
        reconnectionEvents.clear()
        
        networkStateChanges.set(0)
        wakeLockAcquisitions.set(0)
        wakeLockReleases.set(0)
        reconnectionAttempts.set(0)
        reconnectionSuccesses.set(0)
        reconnectionSkips.set(0)
        
        lastNetworkChange = 0L
        lastWakeLockAcquisition = 0L
        totalWakeLockTime = 0L
        
        Log.i(TAG, "Network optimization logs cleared")
    }
}

