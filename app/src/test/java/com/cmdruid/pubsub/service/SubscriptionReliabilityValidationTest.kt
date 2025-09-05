package com.cmdruid.pubsub.service

import android.content.Context
import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.nostr.NostrFilter
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Critical subscription reliability validation
 * These tests validate core subscription functionality
 */
@RunWith(RobolectricTestRunner::class)
class SubscriptionReliabilityValidationTest {

    private lateinit var context: Context
    private lateinit var subscriptionManager: SubscriptionManager

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        subscriptionManager = SubscriptionManager(context)
    }

    @Test
    fun `subscription_registration_must_work_reliably`() {
        // CRITICAL: Basic subscription registration must always work
        
        val subscriptionId = "test-sub-reliability"
        val configurationId = "test-config-reliability"
        val relayUrl = "wss://relay.primal.net"
        val filter = NostrFilter(kinds = listOf(1))
        
        // When: Registering subscription
        subscriptionManager.registerSubscription(subscriptionId, configurationId, filter, relayUrl)
        
        // Then: Must be tracked correctly
        assertTrue("Subscription must be active after registration", 
            subscriptionManager.isActiveSubscription(subscriptionId))
        
        val configId = subscriptionManager.getConfigurationId(subscriptionId)
        assertEquals("Configuration ID must match", configurationId, configId)
        
        val subscriptionInfo = subscriptionManager.getSubscriptionInfo(subscriptionId)
        assertNotNull("Subscription info must be available", subscriptionInfo)
        assertEquals("Subscription ID must match", subscriptionId, subscriptionInfo?.id)
        assertEquals("Relay URL must match", relayUrl, subscriptionInfo?.relayUrl)
    }

    @Test
    fun `timestamp_tracking_must_work_for_duplicate_prevention`() {
        // CRITICAL: Timestamp tracking is core to duplicate prevention
        
        val subscriptionId = "test-sub-timestamp"
        val relayUrl = "wss://relay.damus.io"
        val configurationId = "test-config-timestamp"
        val filter = NostrFilter(kinds = listOf(1))
        
        subscriptionManager.registerSubscription(subscriptionId, configurationId, filter, relayUrl)
        
        // When: Updating timestamp (simulating event processing)
        val eventTimestamp = System.currentTimeMillis() / 1000
        subscriptionManager.updateRelayTimestamp(subscriptionId, relayUrl, eventTimestamp)
        
        // Then: Must track timestamp correctly
        val retrievedTimestamp = subscriptionManager.getRelayTimestamp(subscriptionId, relayUrl)
        assertEquals("Timestamp must be tracked correctly", eventTimestamp, retrievedTimestamp)
        
        // When: Creating relay-specific filter
        val relayFilter = subscriptionManager.createRelaySpecificFilter(
            subscriptionId, relayUrl, filter, null
        )
        
        // Then: Must use precise timestamp
        assertEquals("Filter must use precise timestamp + 1", eventTimestamp + 1, relayFilter.since)
    }

    @Test
    fun `multiple_subscriptions_must_not_interfere`() {
        // CRITICAL: Multiple subscriptions must be completely isolated
        
        val sub1 = "sub-1"
        val sub2 = "sub-2"
        val relay1 = "wss://relay1.com"
        val relay2 = "wss://relay2.com"
        val filter = NostrFilter(kinds = listOf(1))
        
        // When: Registering multiple subscriptions
        subscriptionManager.registerSubscription(sub1, "config-1", filter, relay1)
        subscriptionManager.registerSubscription(sub2, "config-2", filter, relay2)
        
        // When: Updating timestamps independently
        val timestamp1 = 1640995200L
        val timestamp2 = 1640995300L
        
        subscriptionManager.updateRelayTimestamp(sub1, relay1, timestamp1)
        subscriptionManager.updateRelayTimestamp(sub2, relay2, timestamp2)
        
        // Then: Must maintain separate timestamps
        assertEquals("Sub1 timestamp must be isolated", timestamp1, 
            subscriptionManager.getRelayTimestamp(sub1, relay1))
        assertEquals("Sub2 timestamp must be isolated", timestamp2,
            subscriptionManager.getRelayTimestamp(sub2, relay2))
        
        // Cross-subscription timestamps must be null
        assertNull("Sub1 must not have Sub2's timestamp", 
            subscriptionManager.getRelayTimestamp(sub1, relay2))
        assertNull("Sub2 must not have Sub1's timestamp",
            subscriptionManager.getRelayTimestamp(sub2, relay1))
    }

    @Test
    fun `subscription_cleanup_must_work_correctly`() {
        // CRITICAL: Cleanup must not leave orphaned data
        
        val subscriptionId = "cleanup-test-sub"
        val relayUrl = "wss://cleanup-relay.com"
        val filter = NostrFilter(kinds = listOf(1))
        
        // When: Registering and then removing subscription
        subscriptionManager.registerSubscription(subscriptionId, "cleanup-config", filter, relayUrl)
        assertTrue("Must be active after registration", 
            subscriptionManager.isActiveSubscription(subscriptionId))
        
        subscriptionManager.removeSubscription(subscriptionId, relayUrl)
        
        // Then: Must be completely removed
        assertFalse("Must not be active after removal", 
            subscriptionManager.isActiveSubscription(subscriptionId))
        assertNull("Configuration ID must be null after removal",
            subscriptionManager.getConfigurationId(subscriptionId))
        assertNull("Subscription info must be null after removal",
            subscriptionManager.getSubscriptionInfo(subscriptionId))
        assertNull("Timestamp must be null after removal",
            subscriptionManager.getRelayTimestamp(subscriptionId, relayUrl))
    }

    @Test
    fun `subscription_stats_must_be_accurate`() {
        // CRITICAL: Stats must accurately reflect subscription state
        
        val filter = NostrFilter(kinds = listOf(1))
        
        // When: Adding multiple subscriptions
        subscriptionManager.registerSubscription("stats-sub-1", "stats-config-1", filter, "wss://relay1.com")
        subscriptionManager.registerSubscription("stats-sub-2", "stats-config-2", filter, "wss://relay2.com")
        subscriptionManager.registerSubscription("stats-sub-3", "stats-config-3", filter, "wss://relay1.com") // Same relay
        
        // Update some timestamps
        subscriptionManager.updateRelayTimestamp("stats-sub-1", "wss://relay1.com", 1640995200L)
        subscriptionManager.updateRelayTimestamp("stats-sub-2", "wss://relay2.com", 1640995300L)
        
        // Then: Stats must be accurate
        val stats = subscriptionManager.getStats()
        assertEquals("Must track 3 active subscriptions", 3, stats.activeCount)
        assertEquals("Must track 2 unique relays", 2, stats.relayCount)
        assertEquals("Must track 2 events processed", 2, stats.totalEvents)
        
        val activeIds = subscriptionManager.getActiveSubscriptionIds()
        assertEquals("Must return 3 unique subscription IDs", 3, activeIds.size)
    }
}
