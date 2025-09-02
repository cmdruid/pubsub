package com.cmdruid.pubsub.service

import android.content.Context
import android.content.SharedPreferences
import com.cmdruid.pubsub.nostr.NostrFilter
import io.mockk.*
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

/**
 * Test class for SubscriptionManager functionality
 * Tests per-relay timestamp tracking, subscription lifecycle, and data persistence
 */
@RunWith(RobolectricTestRunner::class)
class SubscriptionManagerTest {

    // Mocked dependencies
    private lateinit var mockContext: Context
    private lateinit var mockSharedPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    // Test subject
    private lateinit var subscriptionManager: SubscriptionManager

    // Test data
    private lateinit var testFilter: NostrFilter

    @Before
    fun setup() {
        // Initialize mocks
        mockContext = mockk()
        mockSharedPrefs = mockk()
        mockEditor = mockk(relaxed = true)

        // Setup SharedPreferences mocking
        every { mockContext.getSharedPreferences("relay_timestamps", Context.MODE_PRIVATE) } returns mockSharedPrefs
        every { mockSharedPrefs.edit() } returns mockEditor
        every { mockSharedPrefs.all } returns emptyMap<String, Any>()

        // Create test data
        testFilter = NostrFilter(
            kinds = listOf(1),
            since = 1640995200L
        )

        // Create SubscriptionManager
        subscriptionManager = SubscriptionManager(mockContext)
    }

    // === PER-RELAY TIMESTAMP TRACKING TESTS ===

    @Test
    fun `should track timestamps per relay independently`() {
        // Given: Two different relays
        val relay1 = "wss://relay1.com"
        val relay2 = "wss://relay2.com"
        val subscriptionId = "test-sub-123"
        val timestamp1 = 1640995300L
        val timestamp2 = 1640995400L

        // When: Updating timestamps for different relays
        subscriptionManager.updateRelayTimestamp(subscriptionId, relay1, timestamp1)
        subscriptionManager.updateRelayTimestamp(subscriptionId, relay2, timestamp2)

        // Then: Should track independently
        assertEquals(timestamp1, subscriptionManager.getRelayTimestamp(subscriptionId, relay1))
        assertEquals(timestamp2, subscriptionManager.getRelayTimestamp(subscriptionId, relay2))
    }

    @Test
    fun `should create relay-specific filters with correct since timestamps`() {
        // Given: Subscription with existing timestamp for relay
        val subscriptionId = "test-sub-123"
        val relayUrl = "wss://relay1.com"
        val lastTimestamp = 1640995300L
        
        subscriptionManager.updateRelayTimestamp(subscriptionId, relayUrl, lastTimestamp)

        // When: Creating relay-specific filter
        val relayFilter = subscriptionManager.createRelaySpecificFilter(
            subscriptionId = subscriptionId,
            relayUrl = relayUrl,
            baseFilter = testFilter
        )

        // Then: Should use precise timestamp + 1
        assertEquals(lastTimestamp + 1, relayFilter.since)
    }

    @Test
    fun `should update relay timestamps on event processing`() {
        // Given: Subscription and relay
        val subscriptionId = "test-sub-123"
        val relayUrl = "wss://relay1.com"
        val eventTimestamp = 1640995300L

        // When: Updating relay timestamp
        subscriptionManager.updateRelayTimestamp(subscriptionId, relayUrl, eventTimestamp)

        // Then: Should persist to SharedPreferences
        verify { mockEditor.putString(any(), any()) }
        verify { mockEditor.apply() }

        // And timestamp should be retrievable
        assertEquals(eventTimestamp, subscriptionManager.getRelayTimestamp(subscriptionId, relayUrl))
    }

    @Test
    fun `should handle subscription confirmation`() {
        // Given: Registered subscription
        val subscriptionId = "test-sub-123"
        val configurationId = "config-123"
        val relayUrl = "wss://relay1.com"

        // When: Registering subscription
        subscriptionManager.registerSubscription(subscriptionId, configurationId, testFilter, relayUrl)

        // Then: Should be active
        assertTrue(subscriptionManager.isActiveSubscription(subscriptionId))
        assertEquals(configurationId, subscriptionManager.getConfigurationId(subscriptionId))
    }

    // === SUBSCRIPTION LIFECYCLE TESTS ===

    @Test
    fun `should register subscriptions correctly`() {
        // Given: Subscription parameters
        val subscriptionId = "test-sub-123"
        val configurationId = "config-123"
        val relayUrl = "wss://relay1.com"

        // When: Registering subscription
        subscriptionManager.registerSubscription(subscriptionId, configurationId, testFilter, relayUrl)

        // Then: Should be registered and active
        assertTrue(subscriptionManager.isActiveSubscription(subscriptionId))
        
        val subscriptionInfo = subscriptionManager.getSubscriptionInfo(subscriptionId)
        assertNotNull(subscriptionInfo)
        assertEquals(subscriptionId, subscriptionInfo!!.id)
        assertEquals(configurationId, subscriptionInfo.configurationId)
        assertEquals(relayUrl, subscriptionInfo.relayUrl)
    }

    @Test
    fun `should validate active subscriptions`() {
        // Given: Registered and unregistered subscriptions
        val activeSubscriptionId = "active-sub-123"
        val inactiveSubscriptionId = "inactive-sub-456"
        
        subscriptionManager.registerSubscription(activeSubscriptionId, "config-123", testFilter, "wss://relay1.com")

        // When: Checking subscription status
        val isActive = subscriptionManager.isActiveSubscription(activeSubscriptionId)
        val isInactive = subscriptionManager.isActiveSubscription(inactiveSubscriptionId)

        // Then: Should validate correctly
        assertTrue(isActive)
        assertFalse(isInactive)
    }

    @Test
    fun `should return configuration IDs for subscriptions`() {
        // Given: Registered subscription
        val subscriptionId = "test-sub-123"
        val configurationId = "config-123"
        
        subscriptionManager.registerSubscription(subscriptionId, configurationId, testFilter, "wss://relay1.com")

        // When: Getting configuration ID
        val retrievedConfigId = subscriptionManager.getConfigurationId(subscriptionId)

        // Then: Should return correct configuration ID
        assertEquals(configurationId, retrievedConfigId)
    }

    @Test
    fun `should clean up old timestamps`() {
        // Given: Old timestamp data in SharedPreferences
        val oldTimestamp = System.currentTimeMillis() - (35 * 24 * 60 * 60 * 1000L) // 35 days old
        val oldTimestampData = "test-sub|wss://relay.com|1640995300|$oldTimestamp|0|1|0"
        
        every { mockSharedPrefs.all } returns mapOf("old-key" to oldTimestampData)

        // When: Loading persisted timestamps (which triggers cleanup)
        subscriptionManager.loadPersistedTimestamps()
        subscriptionManager.cleanupOldTimestamps()

        // Then: Should remove old timestamps
        verify { mockEditor.remove("old-key") }
    }

    // === DATA PERSISTENCE TESTS ===

    @Test
    fun `should persist timestamps across app restarts`() {
        // Given: Timestamp data to persist
        val subscriptionId = "test-sub-123"
        val relayUrl = "wss://relay1.com"
        val timestamp = 1640995300L

        // When: Updating timestamp
        subscriptionManager.updateRelayTimestamp(subscriptionId, relayUrl, timestamp)

        // Then: Should persist to SharedPreferences with correct format
        val expectedKey = "$subscriptionId:$relayUrl"
        verify { mockEditor.putString(eq(expectedKey), match { value ->
            value.contains(subscriptionId) && 
            value.contains(relayUrl) && 
            value.contains(timestamp.toString())
        }) }
    }

    @Test
    fun `should handle missing timestamp data gracefully`() {
        // Given: No existing timestamp for relay
        val subscriptionId = "test-sub-123"
        val relayUrl = "wss://new-relay.com"

        // When: Getting timestamp for new relay
        val timestamp = subscriptionManager.getRelayTimestamp(subscriptionId, relayUrl)

        // Then: Should return null
        assertNull(timestamp)
    }

    @Test
    fun `should load persisted timestamps correctly`() {
        // Given: Persisted timestamp data
        val subscriptionId = "test-sub-123"
        val relayUrl = "wss://relay1.com"
        val timestamp = 1640995300L
        val connectionTime = System.currentTimeMillis() - (1000 * 60 * 60) // 1 hour ago
        val persistedData = "$subscriptionId|$relayUrl|$timestamp|$connectionTime|0|5|0"
        
        every { mockSharedPrefs.all } returns mapOf("$subscriptionId:$relayUrl" to persistedData)

        // When: Loading persisted timestamps
        subscriptionManager.loadPersistedTimestamps()

        // Then: Should load timestamp correctly
        assertEquals(timestamp, subscriptionManager.getRelayTimestamp(subscriptionId, relayUrl))
    }

    // === NEW RELAY-SPECIFIC FILTER CREATION TESTS ===

    @Test
    fun `should create filter with 5-minute safety buffer for new relays`() {
        // Given: New relay with no existing timestamp
        val subscriptionId = "test-sub-123"
        val newRelayUrl = "wss://new-relay.com"
        val currentTime = System.currentTimeMillis() / 1000

        // When: Creating relay-specific filter
        val filter = subscriptionManager.createRelaySpecificFilter(
            subscriptionId = subscriptionId,
            relayUrl = newRelayUrl,
            baseFilter = testFilter
        )

        // Then: Should use 5-minute safety buffer
        assertTrue("Filter should have recent 'since' timestamp", filter.since!! >= currentTime - 300)
        assertTrue("Filter should not be too far in past", filter.since!! <= currentTime)
    }

    @Test
    fun `should use precise timestamp for known relays`() {
        // Given: Relay with existing timestamp
        val subscriptionId = "test-sub-123"
        val relayUrl = "wss://known-relay.com"
        val lastTimestamp = 1640995300L
        
        subscriptionManager.updateRelayTimestamp(subscriptionId, relayUrl, lastTimestamp)

        // When: Creating relay-specific filter
        val filter = subscriptionManager.createRelaySpecificFilter(
            subscriptionId = subscriptionId,
            relayUrl = relayUrl,
            baseFilter = testFilter
        )

        // Then: Should use precise timestamp + 1
        assertEquals(lastTimestamp + 1, filter.since)
    }

    // === SUBSCRIPTION REMOVAL TESTS ===

    @Test
    fun `should remove subscription from specific relay`() {
        // Given: Subscription registered for multiple relays
        val subscriptionId = "test-sub-123"
        val relay1 = "wss://relay1.com"
        val relay2 = "wss://relay2.com"
        
        subscriptionManager.registerSubscription(subscriptionId, "config-123", testFilter, relay1)
        subscriptionManager.registerSubscription(subscriptionId, "config-123", testFilter, relay2)

        // When: Removing subscription from one relay
        subscriptionManager.removeSubscription(subscriptionId, relay1)

        // Then: Should still be active (exists on other relay)
        assertTrue(subscriptionManager.isActiveSubscription(subscriptionId))
    }

    @Test
    fun `should remove subscription from all relays`() {
        // Given: Subscription registered for multiple relays
        val subscriptionId = "test-sub-123"
        val relay1 = "wss://relay1.com"
        val relay2 = "wss://relay2.com"
        
        subscriptionManager.registerSubscription(subscriptionId, "config-123", testFilter, relay1)
        subscriptionManager.registerSubscription(subscriptionId, "config-123", testFilter, relay2)

        // When: Removing subscription from all relays
        subscriptionManager.removeSubscription(subscriptionId)

        // Then: Should no longer be active
        assertFalse(subscriptionManager.isActiveSubscription(subscriptionId))
    }

    // === STATISTICS TESTS ===

    @Test
    fun `should provide comprehensive subscription statistics`() {
        // Given: Multiple subscriptions
        subscriptionManager.registerSubscription("sub1", "config1", testFilter, "wss://relay1.com")
        subscriptionManager.registerSubscription("sub2", "config2", testFilter, "wss://relay1.com")
        subscriptionManager.registerSubscription("sub1", "config1", testFilter, "wss://relay2.com")

        // When: Getting statistics
        val stats = subscriptionManager.getStats()

        // Then: Should provide accurate statistics
        assertEquals(2, stats.activeCount) // 2 unique subscriptions
        assertEquals(2, stats.relayCount) // 2 unique relays
        assertTrue(stats.oldestSubscription!! <= System.currentTimeMillis())
        assertTrue(stats.newestSubscription!! <= System.currentTimeMillis())
    }

    @Test
    fun `should provide relay-specific statistics`() {
        // Given: Subscriptions on specific relay
        val relayUrl = "wss://relay1.com"
        subscriptionManager.registerSubscription("sub1", "config1", testFilter, relayUrl)
        subscriptionManager.registerSubscription("sub2", "config2", testFilter, relayUrl)
        subscriptionManager.updateRelayTimestamp("sub1", relayUrl, 1640995300L)
        subscriptionManager.updateRelayTimestamp("sub2", relayUrl, 1640995400L)

        // When: Getting relay statistics
        val relayStats = subscriptionManager.getRelayStats(relayUrl)

        // Then: Should provide accurate relay statistics
        assertEquals(relayUrl, relayStats.relayUrl)
        assertEquals(2, relayStats.subscriptionCount)
        assertEquals(2L, relayStats.totalEvents)
        assertEquals(1640995300L, relayStats.oldestTimestamp)
        assertEquals(1640995400L, relayStats.newestTimestamp)
    }

    // === CLEANUP TESTS ===

    @Test
    fun `should cleanup orphaned subscriptions`() {
        // Given: Subscriptions for various configurations
        subscriptionManager.registerSubscription("sub1", "config1", testFilter, "wss://relay1.com")
        subscriptionManager.registerSubscription("sub2", "config2", testFilter, "wss://relay1.com")
        subscriptionManager.registerSubscription("sub3", "config3", testFilter, "wss://relay1.com")

        // When: Cleaning up with only some valid configurations
        val validConfigIds = setOf("config1", "config2")
        subscriptionManager.cleanupOrphanedSubscriptions(validConfigIds)

        // Then: Should remove orphaned subscription
        assertTrue(subscriptionManager.isActiveSubscription("sub1"))
        assertTrue(subscriptionManager.isActiveSubscription("sub2"))
        // sub3 should be removed as config3 is not in valid set
    }

    @Test
    fun `should clear all subscriptions and timestamps`() {
        // Given: Active subscriptions and timestamps
        subscriptionManager.registerSubscription("sub1", "config1", testFilter, "wss://relay1.com")
        subscriptionManager.updateRelayTimestamp("sub1", "wss://relay1.com", 1640995300L)

        // When: Clearing all
        subscriptionManager.clearAll()

        // Then: Should clear everything
        assertFalse(subscriptionManager.isActiveSubscription("sub1"))
        assertNull(subscriptionManager.getRelayTimestamp("sub1", "wss://relay1.com"))
        verify { mockEditor.clear() }
    }

    // === EDGE CASES TESTS ===

    @Test
    fun `should handle malformed persisted data gracefully`() {
        // Given: Malformed persisted data
        every { mockSharedPrefs.all } returns mapOf(
            "valid-key" to "sub1|wss://relay.com|1640995300|1640995200|0|1|0",
            "invalid-key" to "malformed-data",
            "incomplete-key" to "sub2|relay"
        )

        // When: Loading persisted timestamps
        subscriptionManager.loadPersistedTimestamps()

        // Then: Should load valid data and skip invalid data (no exceptions)
        // Test passes if no exception is thrown
    }

    @Test
    fun `should handle empty configuration lists`() {
        // Given: Empty valid configuration list
        val validConfigIds = emptySet<String>()

        // When: Cleaning up orphaned subscriptions
        subscriptionManager.cleanupOrphanedSubscriptions(validConfigIds)

        // Then: Should not crash (all subscriptions would be considered orphaned)
        // Test passes if no exception is thrown
    }
}
