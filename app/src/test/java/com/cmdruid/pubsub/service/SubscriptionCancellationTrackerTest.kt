package com.cmdruid.pubsub.service

import com.cmdruid.pubsub.logging.LogDomain
import com.cmdruid.pubsub.logging.UnifiedLogger
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for SubscriptionCancellationTracker
 */
class SubscriptionCancellationTrackerTest {
    
    private lateinit var mockLogger: UnifiedLogger
    private lateinit var tracker: SubscriptionCancellationTracker
    
    @Before
    fun setUp() {
        mockLogger = mockk(relaxed = true)
        tracker = SubscriptionCancellationTracker(mockLogger)
    }
    
    @Test
    fun `should track new subscription cancellations`() {
        // Given
        val subscriptionId = "test-subscription-123"
        
        // When
        val wasNewCancellation = tracker.markSubscriptionCancelled(subscriptionId)
        
        // Then
        assertTrue(wasNewCancellation)
        assertTrue(tracker.isSubscriptionCancelled(subscriptionId))
        
        verify { mockLogger.info(LogDomain.SUBSCRIPTION, any()) }
    }
    
    @Test
    fun `should not track duplicate cancellations`() {
        // Given
        val subscriptionId = "test-subscription-123"
        tracker.markSubscriptionCancelled(subscriptionId)
        clearMocks(mockLogger)
        
        // When
        val wasNewCancellation = tracker.markSubscriptionCancelled(subscriptionId)
        
        // Then
        assertFalse(wasNewCancellation)
        assertTrue(tracker.isSubscriptionCancelled(subscriptionId))
        
        // Should not log info for duplicate cancellation
        verify(exactly = 0) { mockLogger.info(LogDomain.SUBSCRIPTION, any()) }
    }
    
    @Test
    fun `should return false for non-cancelled subscriptions`() {
        // Given
        val subscriptionId = "test-subscription-123"
        
        // When
        val isCancelled = tracker.isSubscriptionCancelled(subscriptionId)
        
        // Then
        assertFalse(isCancelled)
    }
    
    @Test
    fun `should provide accurate cancellation statistics`() {
        // Given
        val subscriptionIds = listOf("sub1", "sub2", "sub3")
        
        // When
        subscriptionIds.forEach { tracker.markSubscriptionCancelled(it) }
        val stats = tracker.getCancellationStats()
        
        // Then
        assertEquals(3L, stats.totalCancellations)
        assertEquals(3, stats.activeCancelledSubscriptions)
        assertEquals(3, stats.recentCancellations)
    }
    
    @Test
    fun `should handle memory limits gracefully`() {
        // Given - Create more subscriptions than the limit
        val maxSubscriptions = 1000
        val excessSubscriptions = 100
        
        // When - Add subscriptions beyond the limit
        repeat(maxSubscriptions + excessSubscriptions) { index ->
            tracker.markSubscriptionCancelled("subscription-$index")
        }
        
        // Then - Should still be within memory limits
        val stats = tracker.getCancellationStats()
        assertTrue(stats.activeCancelledSubscriptions <= maxSubscriptions)
        
        // Should have logged cleanup
        verify { mockLogger.debug(LogDomain.SUBSCRIPTION, any()) }
    }
    
    @Test
    fun `should clear all cancellation records`() {
        // Given
        val subscriptionIds = listOf("sub1", "sub2", "sub3")
        subscriptionIds.forEach { tracker.markSubscriptionCancelled(it) }
        
        // When
        tracker.clearAll()
        
        // Then
        subscriptionIds.forEach { subscriptionId ->
            assertFalse(tracker.isSubscriptionCancelled(subscriptionId))
        }
        
        val stats = tracker.getCancellationStats()
        assertEquals(0L, stats.totalCancellations)
        assertEquals(0, stats.activeCancelledSubscriptions)
        
        verify { mockLogger.info(LogDomain.SUBSCRIPTION, any()) }
    }
    
    @Test
    fun `should handle multiple subscriptions correctly`() {
        // Given
        val subscriptionIds = (1..10).map { "subscription-$it" }
        
        // When - Add subscriptions sequentially
        subscriptionIds.forEach { subscriptionId ->
            tracker.markSubscriptionCancelled(subscriptionId)
        }
        
        // Then - All subscriptions should be tracked
        subscriptionIds.forEach { subscriptionId ->
            assertTrue(tracker.isSubscriptionCancelled(subscriptionId))
        }
        
        val stats = tracker.getCancellationStats()
        assertEquals(10L, stats.totalCancellations)
        assertEquals(10, stats.activeCancelledSubscriptions)
    }
    
    @Test
    fun `should track recent cancellations correctly`() {
        // Given
        val oldSubscriptionId = "old-subscription"
        val recentSubscriptionId = "recent-subscription"
        
        // When - Mark old subscription as cancelled (simulate old timestamp)
        tracker.markSubscriptionCancelled(oldSubscriptionId)
        
        // Add a small delay to ensure different timestamps
        Thread.sleep(10)
        tracker.markSubscriptionCancelled(recentSubscriptionId)
        
        val stats = tracker.getCancellationStats()
        
        // Then - Both should be tracked
        assertEquals(2L, stats.totalCancellations)
        assertEquals(2, stats.activeCancelledSubscriptions)
        assertEquals(2, stats.recentCancellations) // Both are recent within cleanup interval
    }
}
