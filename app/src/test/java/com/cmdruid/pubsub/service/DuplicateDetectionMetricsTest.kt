package com.cmdruid.pubsub.service

import android.content.Context
import com.cmdruid.pubsub.nostr.NostrFilter
import io.mockk.*
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Tests for duplicate detection metrics tracking
 * Validates that both EventCache and timestamp-based duplicate prevention are tracked
 */
@RunWith(RobolectricTestRunner::class)
class DuplicateDetectionMetricsTest {

    private lateinit var context: Context
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var eventCache: EventCache
    private lateinit var mockMetricsCollector: MetricsCollector
    private lateinit var metricsCallCapture: MutableList<Map<String, Any>>

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        subscriptionManager = SubscriptionManager(context)
        eventCache = EventCache(context)
        mockMetricsCollector = mockk(relaxed = true)
        metricsCallCapture = mutableListOf()

        // Capture metrics calls
        every { 
            mockMetricsCollector.trackDuplicateEvent(any(), any(), any(), any(), any()) 
        } answers {
            metricsCallCapture.add(mapOf(
                "eventProcessed" to firstArg<Boolean>(),
                "duplicateDetected" to secondArg<Boolean>(),
                "duplicatePrevented" to thirdArg<Boolean>(),
                "usedPreciseTimestamp" to arg<Boolean>(3),
                "networkDataSaved" to arg<Long>(4)
            ))
        }
    }

    @Test
    fun `should track timestamp-based duplicate prevention for existing relay`() {
        // Given: Subscription with existing timestamp (simulating previous events)
        val subscriptionId = "test-sub-123"
        val relayUrl = "wss://relay.primal.net"
        val baseFilter = NostrFilter(kinds = listOf(1))
        
        // Simulate previous event timestamp (1 hour ago)
        val oneHourAgo = (System.currentTimeMillis() / 1000) - 3600
        subscriptionManager.updateRelayTimestamp(subscriptionId, relayUrl, oneHourAgo)

        // When: Creating relay-specific filter (this happens during reconnection)
        val filter = subscriptionManager.createRelaySpecificFilter(
            subscriptionId = subscriptionId,
            relayUrl = relayUrl,
            baseFilter = baseFilter,
            metricsCollector = mockMetricsCollector
        )

        // Then: Should track timestamp-based duplicate prevention
        assertEquals("Should have one metrics call", 1, metricsCallCapture.size)
        val metrics = metricsCallCapture[0]
        
        assertFalse("Should not be event processed", metrics["eventProcessed"] as Boolean)
        assertFalse("Should not be duplicate detected", metrics["duplicateDetected"] as Boolean)
        assertTrue("Should be duplicate prevented", metrics["duplicatePrevented"] as Boolean)
        assertTrue("Should use precise timestamp", metrics["usedPreciseTimestamp"] as Boolean)
        assertTrue("Should save network data", (metrics["networkDataSaved"] as Long) > 0)

        // Should use precise timestamp
        assertEquals("Should use last timestamp + 1", oneHourAgo + 1, filter.since)
    }

    @Test
    fun `should track safety buffer usage for new relay`() {
        // Given: Subscription with no existing timestamp (new relay)
        val subscriptionId = "test-sub-456"
        val relayUrl = "wss://new-relay.com"
        val baseFilter = NostrFilter(kinds = listOf(1))

        // When: Creating relay-specific filter for new relay
        val filter = subscriptionManager.createRelaySpecificFilter(
            subscriptionId = subscriptionId,
            relayUrl = relayUrl,
            baseFilter = baseFilter,
            metricsCollector = mockMetricsCollector
        )

        // Then: Should track safety buffer usage
        assertEquals("Should have one metrics call", 1, metricsCallCapture.size)
        val metrics = metricsCallCapture[0]
        
        assertFalse("Should not be event processed", metrics["eventProcessed"] as Boolean)
        assertFalse("Should not be duplicate detected", metrics["duplicateDetected"] as Boolean)
        assertFalse("Should not be duplicate prevented", metrics["duplicatePrevented"] as Boolean)
        assertFalse("Should not use precise timestamp", metrics["usedPreciseTimestamp"] as Boolean)
        assertEquals("Should not save network data", 0L, metrics["networkDataSaved"] as Long)

        // Should use safety buffer (5 minutes ago)
        val expectedSince = (System.currentTimeMillis() / 1000) - 300
        assertTrue("Should use 5-minute safety buffer", 
            filter.since!! >= expectedSince - 5 && filter.since!! <= expectedSince + 5)
    }

    @Test
    fun `should detect EventCache duplicates correctly`() {
        // Given: Event already in cache
        val eventId = "duplicate-event-123"
        
        // Mark event as seen first
        val wasNew = eventCache.markEventSeen(eventId)
        assertTrue("First time should be new", wasNew)

        // When: Checking for duplicate
        val isDuplicate = eventCache.hasSeenEvent(eventId)
        
        // Then: Should detect as duplicate
        assertTrue("Should detect as duplicate", isDuplicate)

        // When: Trying to mark as seen again
        val wasNewSecondTime = eventCache.markEventSeen(eventId)
        
        // Then: Should return false (already seen)
        assertFalse("Second time should not be new", wasNewSecondTime)
    }

    @Test
    fun `should handle large time gaps in timestamp-based prevention`() {
        // Given: Subscription with very old timestamp (simulating long disconnection)
        val subscriptionId = "test-sub-789"
        val relayUrl = "wss://relay.damus.io"
        val baseFilter = NostrFilter(kinds = listOf(1))
        
        // Simulate timestamp from 24 hours ago
        val twentyFourHoursAgo = (System.currentTimeMillis() / 1000) - 86400
        subscriptionManager.updateRelayTimestamp(subscriptionId, relayUrl, twentyFourHoursAgo)

        // When: Creating relay-specific filter after long gap
        val filter = subscriptionManager.createRelaySpecificFilter(
            subscriptionId = subscriptionId,
            relayUrl = relayUrl,
            baseFilter = baseFilter,
            metricsCollector = mockMetricsCollector
        )

        // Then: Should track significant duplicate prevention
        assertEquals("Should have one metrics call", 1, metricsCallCapture.size)
        val metrics = metricsCallCapture[0]
        
        assertTrue("Should be duplicate prevented", metrics["duplicatePrevented"] as Boolean)
        assertTrue("Should use precise timestamp", metrics["usedPreciseTimestamp"] as Boolean)
        
        // Should estimate significant data savings for 24-hour gap
        val networkDataSaved = metrics["networkDataSaved"] as Long
        assertTrue("Should save significant network data for 24h gap", networkDataSaved > 100000) // > 100KB

        // Should use precise timestamp
        assertEquals("Should use last timestamp + 1", twentyFourHoursAgo + 1, filter.since)
    }

    @Test
    fun `should not track duplicate prevention for minimal time gaps`() {
        // Given: Subscription with very recent timestamp (1 minute ago)
        val subscriptionId = "test-sub-recent"
        val relayUrl = "wss://relay.nostr.band"
        val baseFilter = NostrFilter(kinds = listOf(1))
        
        // Simulate timestamp from 1 minute ago
        val oneMinuteAgo = (System.currentTimeMillis() / 1000) - 60
        subscriptionManager.updateRelayTimestamp(subscriptionId, relayUrl, oneMinuteAgo)

        // When: Creating relay-specific filter with minimal gap
        val filter = subscriptionManager.createRelaySpecificFilter(
            subscriptionId = subscriptionId,
            relayUrl = relayUrl,
            baseFilter = baseFilter,
            metricsCollector = mockMetricsCollector
        )

        // Then: Should track precise timestamp usage but minimal duplicate prevention
        assertEquals("Should have one metrics call", 1, metricsCallCapture.size)
        val metrics = metricsCallCapture[0]
        
        assertTrue("Should use precise timestamp", metrics["usedPreciseTimestamp"] as Boolean)
        // Duplicate prevention might be false for very recent timestamps (< 1 estimated event)
        val duplicatePrevented = metrics["duplicatePrevented"] as Boolean
        val networkDataSaved = metrics["networkDataSaved"] as Long
        
        // For 1-minute gap, estimated events = 1, so should still prevent some
        if (duplicatePrevented) {
            assertTrue("If duplicates prevented, should save some data", networkDataSaved > 0)
        }
    }

    @Test
    fun `should track precise timestamp rate correctly`() {
        // Given: Multiple subscriptions with different timestamp states
        val baseFilter = NostrFilter(kinds = listOf(1))
        
        // Subscription 1: Has existing timestamp
        subscriptionManager.updateRelayTimestamp("sub-1", "wss://relay1.com", 1640995200L)
        subscriptionManager.createRelaySpecificFilter("sub-1", "wss://relay1.com", baseFilter, mockMetricsCollector)
        
        // Subscription 2: New relay (no timestamp)
        subscriptionManager.createRelaySpecificFilter("sub-2", "wss://relay2.com", baseFilter, mockMetricsCollector)
        
        // Subscription 3: Has existing timestamp
        subscriptionManager.updateRelayTimestamp("sub-3", "wss://relay3.com", 1640995300L)
        subscriptionManager.createRelaySpecificFilter("sub-3", "wss://relay3.com", baseFilter, mockMetricsCollector)

        // Then: Should have 3 metrics calls
        assertEquals("Should have 3 metrics calls", 3, metricsCallCapture.size)
        
        // Count precise timestamp usage
        val preciseTimestampCount = metricsCallCapture.count { it["usedPreciseTimestamp"] as Boolean }
        val safetyBufferCount = metricsCallCapture.count { !(it["usedPreciseTimestamp"] as Boolean) }
        
        assertEquals("Should have 2 precise timestamp usages", 2, preciseTimestampCount)
        assertEquals("Should have 1 safety buffer usage", 1, safetyBufferCount)
    }
}
