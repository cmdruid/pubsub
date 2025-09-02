package com.cmdruid.pubsub.integration

import com.cmdruid.pubsub.testing.IntegrationTestBase
import com.cmdruid.pubsub.testing.NotificationMatcher
import com.cmdruid.pubsub.testing.TestServiceContainer
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

/**
 * Integration tests for complete message flow
 * Tests end-to-end event processing from relay to notification
 */
class MessageFlowIntegrationTest : IntegrationTestBase() {
    
    @Test
    fun `incoming bitcoin event should trigger complete workflow`() = runTest {
        // Given: Bitcoin monitoring configuration
        val config = createTestConfiguration(
            name = "Bitcoin Alerts",
            keywords = listOf("bitcoin")
        )
        val bitcoinEvent = createBitcoinEvent("Breaking: Bitcoin reaches new ATH! #bitcoin")
        
        // When: Service starts and bitcoin event is published
        val result = workflowBuilder
            .givenConfiguration(config)
            .givenNetworkState(TestServiceContainer.NetworkState.CONNECTED)
            .whenServiceStarts()
            .whenRelayPublishesEvent(bitcoinEvent)
            .thenExpectEventStored(bitcoinEvent.id)
            .thenExpectEventProcessed(bitcoinEvent.id)
            .thenExpectNotification(NotificationMatcher.withContent("Bitcoin reaches new ATH"))
            .execute()
        
        // Then: Complete workflow should succeed
        assertTrue("Bitcoin event workflow should succeed", result.isSuccess)
        assertNotNull("Should trigger notification", result.triggeredNotification)
        
        // Verify event was processed
        val eventCache = result.serviceComponents!!.eventCache
        assertTrue("Event should be marked as seen", eventCache.hasSeenEvent(bitcoinEvent.id))
    }
    
    @Test
    fun `event filtering should work correctly`() = runTest {
        // Given: Configuration that filters for specific content
        val config = createTestConfiguration(
            name = "Nostr Development",
            keywords = listOf("nostr", "development")
        )
        
        val matchingEvent = createTestEvent("Great nostr development progress! #nostr").copy(
            id = generateUniqueEventId()
        )
        val nonMatchingEvent = createTestEvent("Bitcoin price update").copy(
            id = generateUniqueEventId()
        )
        
        // When: Publishing both matching and non-matching events
        val result = workflowBuilder
            .givenConfiguration(config)
            .whenServiceStarts()
            .whenRelayPublishesEvent(matchingEvent)
            .whenRelayPublishesEvent(nonMatchingEvent)
            .thenExpectEventStored(matchingEvent.id)
            .thenExpectEventStored(nonMatchingEvent.id)
            .thenExpectEventProcessed(matchingEvent.id)
            .execute()
        
        // Then: Both events should be stored, but filtering happens in MessageProcessor
        assertTrue("Event filtering workflow should succeed", result.isSuccess)
        assertEquals("Both events should be stored in relay", 2, testWebSocketServer.getStoredEvents().size)
    }
    
    @Test
    fun `duplicate events should be handled correctly`() = runTest {
        // Given: Configuration and duplicate events
        val config = createTestConfiguration("Duplicate Test")
        val originalEvent = createTestEvent("Original event").copy(id = generateUniqueEventId())
        val duplicateEvent = originalEvent.copy() // Same ID
        
        // When: Publishing same event twice
        val result = workflowBuilder
            .givenConfiguration(config)
            .whenServiceStarts()
            .whenRelayPublishesEvent(originalEvent)
            .whenRelayPublishesEvent(duplicateEvent)
            .thenExpectEventStored(originalEvent.id)
            .execute()
        
        // Then: Should handle duplicates correctly
        assertTrue("Duplicate handling should succeed", result.isSuccess)
        
        // Should only store one copy
        val storedEvents = testWebSocketServer.getStoredEvents()
        val matchingEvents = storedEvents.filter { it.id == originalEvent.id }
        assertEquals("Should only store one copy of duplicate event", 1, matchingEvents.size)
    }
    
    @Test
    fun `invalid events should be rejected properly`() = runTest {
        // Given: Configuration and invalid event
        val config = createTestConfiguration("Invalid Event Test")
        val invalidEvent = createTestEvent("Invalid event").copy(
            id = "invalid-id", // Too short
            signature = "" // Empty signature
        )
        
        // When: Publishing invalid event
        val result = workflowBuilder
            .givenConfiguration(config)
            .whenServiceStarts()
            .whenRelayPublishesEvent(invalidEvent)
            .execute()
        
        // Then: Should reject invalid event
        assertTrue("Invalid event handling should succeed", result.isSuccess)
        
        // Invalid event should not be stored
        val storedEvents = testWebSocketServer.getStoredEvents()
        assertFalse("Invalid event should not be stored", 
            storedEvents.any { it.id == invalidEvent.id })
        
        // Should have generated rejection OK message
        assertTrue("Should generate rejection message",
            testWebSocketServer.hasGeneratedMessage("\"OK\"") && 
            testWebSocketServer.hasGeneratedMessage("false"))
    }
    
    @Test
    fun `subscription should receive matching events correctly`() = runTest {
        // Given: Subscription for text notes only
        val config = createTestConfiguration(
            name = "Text Notes Only",
            kinds = listOf(1) // Text notes
        )
        
        val textEvent = createTestEvent("This is a text note").copy(kind = 1)
        val metadataEvent = createTestEvent("This is metadata").copy(
            id = generateUniqueEventId(),
            kind = 0 // Metadata
        )
        
        // When: Service starts and processes subscription
        val testRelay = testWebSocketServer.start()
        
        // Simulate REQ message for text notes
        val reqMessage = """["REQ","${config.subscriptionId}",{"kinds":[1]}]"""
        testWebSocketServer.processClientMessage(reqMessage)
        
        // Publish events
        testWebSocketServer.publishEvent(textEvent)
        testWebSocketServer.publishEvent(metadataEvent)
        
        val result = workflowBuilder
            .givenConfiguration(config)
            .whenServiceStarts()
            .thenExpectEventStored(textEvent.id)
            .thenExpectEventStored(metadataEvent.id)
            .thenExpectFilterApplied(config.subscriptionId, 1) // Only text note should match
            .execute()
        
        // Then: Should filter correctly
        assertTrue("Subscription filtering should work", result.isSuccess)
        
        // Verify subscription was registered
        assertTrue("Subscription should be active in relay",
            testWebSocketServer.getActiveSubscriptions().containsKey(config.subscriptionId))
    }
    
    @Test
    fun `message processor should handle high volume correctly`() = runTest {
        // Given: Configuration for high volume testing
        val config = createTestConfiguration("High Volume Test")
        val events = (1..10).map { i ->
            createTestEvent("Event number $i").copy(id = generateUniqueEventId())
        }
        
        // When: Publishing multiple events rapidly
        val result = workflowBuilder
            .givenConfiguration(config)
            .whenServiceStarts()
            .apply {
                events.forEach { event ->
                    whenRelayPublishesEvent(event)
                }
            }
            .execute()
        
        // Then: Should handle all events
        assertTrue("High volume processing should succeed", result.isSuccess)
        assertEquals("All events should be stored", 10, testWebSocketServer.getStoredEvents().size)
    }
    
    @Test
    fun `service should handle configuration updates during runtime`() = runTest {
        // Given: Initial configuration
        val initialConfig = createTestConfiguration("Initial Config")
        
        // When: Service starts and configuration is updated
        val result = workflowBuilder
            .givenConfiguration(initialConfig)
            .whenServiceStarts()
            .thenExpectSubscriptionRegistered(initialConfig.subscriptionId)
            .execute()
        
        // Then: Should handle configuration updates
        assertTrue("Configuration update should succeed", result.isSuccess)
        
        // Add new configuration
        val newConfig = createTestConfiguration("Updated Config")
        testContainer.withConfiguration(newConfig)
        
        // Verify both configurations are active
        val configManager = result.serviceComponents!!.configurationManager
        val enabledConfigs = configManager.getEnabledConfigurations()
        assertEquals("Should have both configurations", 2, enabledConfigs.size)
    }
}
