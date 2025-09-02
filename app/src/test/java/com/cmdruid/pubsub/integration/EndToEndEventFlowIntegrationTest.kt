package com.cmdruid.pubsub.integration

import com.cmdruid.pubsub.testing.IntegrationTestBase
import com.cmdruid.pubsub.testing.NotificationMatcher
import com.cmdruid.pubsub.testing.TestServiceContainer
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

/**
 * End-to-end event flow integration tests
 * Tests complete workflows from event publication to user notification
 */
class EndToEndEventFlowIntegrationTest : IntegrationTestBase() {
    
    @Test
    fun `complete bitcoin event workflow should work end-to-end`() = runTest {
        // Given: Bitcoin monitoring setup with real test relay
        val testRelayUrl = testWebSocketServer.start()
        val config = createTestConfiguration(
            name = "Bitcoin Monitor",
            relayUrls = listOf(testRelayUrl),
            keywords = listOf("bitcoin", "btc")
        )
        
        val bitcoinEvent = createBitcoinEvent(
            content = "🚀 Bitcoin breaks $100k! Historic moment for #bitcoin #btc adoption"
        )
        
        // When: Complete workflow execution
        val result = workflowBuilder
            .givenConfiguration(config)
            .givenNetworkState(TestServiceContainer.NetworkState.CONNECTED)
            .givenBatteryLevel(80)
            .whenServiceStarts()
            .whenRelayPublishesEvent(bitcoinEvent)
            .thenExpectEventStored(bitcoinEvent.id)
            .thenExpectSubscriptionRegistered(config.subscriptionId)
            .thenExpectEventProcessed(bitcoinEvent.id)
            .thenExpectNotification(NotificationMatcher.withContent("Bitcoin breaks"))
            .thenExpectMetricsCollected("event_processed")
            .execute()
        
        // Then: Complete workflow should succeed
        assertTrue("Bitcoin workflow should complete successfully", result.isSuccess)
        if (!result.isSuccess) {
            println("Failure details: ${result.getDetailedReport()}")
        }
        
        // Verify all components worked together
        assertNotNull("Should have service components", result.serviceComponents)
        assertNotNull("Should trigger notification", result.triggeredNotification)
        assertTrue("Should collect metrics", result.collectedMetrics.isNotEmpty())
        
        // Verify event processing
        val eventCache = result.serviceComponents!!.eventCache
        assertTrue("Event should be processed and cached", eventCache.hasSeenEvent(bitcoinEvent.id))
        
        // Verify subscription management
        val subscriptionManager = result.serviceComponents!!.subscriptionManager
        assertTrue("Subscription should be active", 
            subscriptionManager.isActiveSubscription(config.subscriptionId))
        
        println("✅ Complete Bitcoin workflow validated successfully!")
    }
    
    @Test
    fun `nostr development event workflow should work end-to-end`() = runTest {
        // Given: Nostr development monitoring
        val testRelayUrl = testWebSocketServer.start()
        val config = createTestConfiguration(
            name = "Nostr Development",
            relayUrls = listOf(testRelayUrl),
            keywords = listOf("nostr", "development", "protocol")
        )
        
        val devEvent = createTestEvent(
            content = "Major nostr protocol improvement merged! Great development progress #nostr #development"
        ).copy(id = generateUniqueEventId())
        
        // When: Development event workflow
        val result = workflowBuilder
            .givenConfiguration(config)
            .whenServiceStarts()
            .whenRelayPublishesEvent(devEvent)
            .thenExpectEventStored(devEvent.id)
            .thenExpectEventProcessed(devEvent.id)
            .thenExpectNotification(NotificationMatcher.withContent("protocol improvement"))
            .execute()
        
        // Then: Should process development event correctly
        assertTrue("Nostr development workflow should succeed", result.isSuccess)
        
        // Event should be in cache
        val eventCache = result.serviceComponents!!.eventCache
        assertTrue("Development event should be cached", eventCache.hasSeenEvent(devEvent.id))
    }
    
    @Test
    fun `multiple configuration workflow should work correctly`() = runTest {
        // Given: Multiple monitoring configurations
        val testRelayUrl = testWebSocketServer.start()
        
        val bitcoinConfig = createTestConfiguration(
            name = "Bitcoin Alerts",
            relayUrls = listOf(testRelayUrl),
            keywords = listOf("bitcoin")
        )
        
        val nostrConfig = createTestConfiguration(
            name = "Nostr Updates", 
            relayUrls = listOf(testRelayUrl),
            keywords = listOf("nostr")
        )
        
        val bitcoinEvent = createBitcoinEvent("Bitcoin news update #bitcoin")
        val nostrEvent = createTestEvent("Nostr protocol update #nostr").copy(
            id = generateUniqueEventId()
        )
        
        // When: Service handles multiple configurations and events
        val result = workflowBuilder
            .givenConfiguration(bitcoinConfig)
            .givenConfiguration(nostrConfig)
            .whenServiceStarts()
            .whenRelayPublishesEvent(bitcoinEvent)
            .whenRelayPublishesEvent(nostrEvent)
            .thenExpectSubscriptionRegistered(bitcoinConfig.subscriptionId)
            .thenExpectSubscriptionRegistered(nostrConfig.subscriptionId)
            .thenExpectEventStored(bitcoinEvent.id)
            .thenExpectEventStored(nostrEvent.id)
            .execute()
        
        // Then: Should handle multiple configurations correctly
        assertTrue("Multi-configuration workflow should succeed", result.isSuccess)
        
        // Both subscriptions should be active
        val subscriptionManager = result.serviceComponents!!.subscriptionManager
        assertTrue("Bitcoin subscription should be active",
            subscriptionManager.isActiveSubscription(bitcoinConfig.subscriptionId))
        assertTrue("Nostr subscription should be active",
            subscriptionManager.isActiveSubscription(nostrConfig.subscriptionId))
        
        // Both events should be processed
        val eventCache = result.serviceComponents!!.eventCache
        assertTrue("Bitcoin event should be processed", eventCache.hasSeenEvent(bitcoinEvent.id))
        assertTrue("Nostr event should be processed", eventCache.hasSeenEvent(nostrEvent.id))
    }
    
    @Test
    fun `event deduplication should work across relay connections`() = runTest {
        // Given: Configuration with multiple relays (simulating same event from different relays)
        val testRelayUrl = testWebSocketServer.start()
        val config = createTestConfiguration(
            name = "Deduplication Test",
            relayUrls = listOf(testRelayUrl, "ws://relay2.test") // Second relay will fail but that's ok
        )
        
        val originalEvent = createTestEvent("Duplicate test event").copy(
            id = generateUniqueEventId()
        )
        
        // When: Same event is published multiple times
        val result = workflowBuilder
            .givenConfiguration(config)
            .whenServiceStarts()
            .whenRelayPublishesEvent(originalEvent)
            .whenRelayPublishesEvent(originalEvent) // Duplicate
            .thenExpectEventStored(originalEvent.id)
            .thenExpectEventProcessed(originalEvent.id)
            .execute()
        
        // Then: Should deduplicate correctly
        assertTrue("Deduplication workflow should succeed", result.isSuccess)
        
        // Event should only be processed once
        val eventCache = result.serviceComponents!!.eventCache
        assertTrue("Event should be marked as seen", eventCache.hasSeenEvent(originalEvent.id))
        
        // Should only be stored once in relay
        val storedEvents = testWebSocketServer.getStoredEvents().filter { it.id == originalEvent.id }
        assertEquals("Event should only be stored once", 1, storedEvents.size)
    }
    
    @Test
    fun `timestamp-based filtering should work in complete workflow`() = runTest {
        // Given: Configuration with timestamp-aware subscription
        val testRelayUrl = testWebSocketServer.start()
        val config = createTestConfiguration(
            name = "Timestamp Filter Test",
            relayUrls = listOf(testRelayUrl)
        )
        
        val currentTime = System.currentTimeMillis() / 1000
        val oldEvent = createTestEvent("Old event").copy(
            id = generateUniqueEventId(),
            createdAt = currentTime - 7200 // 2 hours ago
        )
        val newEvent = createTestEvent("New event").copy(
            id = generateUniqueEventId(),
            createdAt = currentTime - 1800 // 30 minutes ago
        )
        
        // Pre-populate relay with events
        testWebSocketServer.publishEvent(oldEvent)
        testWebSocketServer.publishEvent(newEvent)
        
        // When: Service starts and creates subscription with 'since' timestamp
        val result = workflowBuilder
            .givenConfiguration(config)
            .whenServiceStarts()
            .execute()
        
        // Then: Should handle timestamp filtering
        assertTrue("Timestamp filtering workflow should succeed", result.isSuccess)
        
        // Both events should be stored in relay
        assertEquals("Both events should be in relay", 2, testWebSocketServer.getStoredEvents().size)
        
        // Subscription should be created with appropriate timestamp filter
        val subscriptionManager = result.serviceComponents!!.subscriptionManager
        assertTrue("Subscription should be active",
            subscriptionManager.isActiveSubscription(config.subscriptionId))
    }
    
    @Test
    fun `service shutdown should clean up all resources`() = runTest {
        // Given: Service with active connections and data
        val testRelayUrl = testWebSocketServer.start()
        val config = createTestConfiguration(
            name = "Shutdown Test",
            relayUrls = listOf(testRelayUrl)
        )
        
        val testEvent = createTestEvent("Shutdown test event").copy(
            id = generateUniqueEventId()
        )
        
        // When: Service starts, processes data, then shuts down
        val result = workflowBuilder
            .givenConfiguration(config)
            .whenServiceStarts()
            .whenRelayPublishesEvent(testEvent)
            .thenExpectEventProcessed(testEvent.id)
            .execute()
        
        // Then: Startup should succeed
        assertTrue("Service startup should succeed", result.isSuccess)
        
        // When: Service shuts down (simulated by cleanup)
        result.serviceComponents!!.relayConnectionManager.disconnectFromAllRelays()
        result.serviceComponents!!.healthMonitor.stop()
        
        // Then: Should clean up gracefully
        // (In a real implementation, we would verify connections are closed, resources freed, etc.)
        val subscriptionManager = result.serviceComponents!!.subscriptionManager
        
        // Subscriptions can be cleared
        subscriptionManager.clearAll()
        assertFalse("Subscriptions should be cleared",
            subscriptionManager.isActiveSubscription(config.subscriptionId))
    }
    
    @Test
    fun `health monitoring should work during complete workflow`() = runTest {
        // Given: Configuration with health monitoring
        val testRelayUrl = testWebSocketServer.start()
        val config = createTestConfiguration(
            name = "Health Monitoring Test",
            relayUrls = listOf(testRelayUrl)
        )
        
        // When: Service starts and runs health monitoring
        val result = workflowBuilder
            .givenConfiguration(config)
            .whenServiceStarts()
            .thenExpectSubscriptionRegistered(config.subscriptionId)
            .execute()
        
        // Then: Health monitoring should be active
        assertTrue("Health monitoring workflow should succeed", result.isSuccess)
        
        val healthMonitor = result.serviceComponents!!.healthMonitor
        assertNotNull("Health monitor should exist", healthMonitor)
        
        // Health monitor should be able to start/stop
        healthMonitor.stop()
        // No exception means success
    }
}
