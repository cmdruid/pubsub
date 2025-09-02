package com.cmdruid.pubsub.integration

import com.cmdruid.pubsub.nostr.NostrFilter
import com.cmdruid.pubsub.testing.IntegrationTestBase
import com.cmdruid.pubsub.testing.TestServiceContainer
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

/**
 * Integration tests for NIP-01 protocol compliance
 * Tests that our implementation correctly follows the Nostr protocol specification
 */
class Nip01ComplianceIntegrationTest : IntegrationTestBase() {
    
    @Test
    fun `should handle EVENT message and respond with OK per NIP-01`() = runTest {
        // Given: Valid event
        val testEvent = createTestEvent("NIP-01 compliance test event")
        val eventMessage = """["EVENT",${gson.toJson(testEvent)}]"""
        
        // When: Processing EVENT message
        testWebSocketServer.start()
        val responses = testWebSocketServer.processClientMessage(eventMessage)
        
        // Then: Should return proper NIP-01 OK message
        assertEquals("Should return exactly one response", 1, responses.size)
        val okMessage = responses[0]
        
        // Verify OK message format: ["OK", <event_id>, <true|false>, <message>]
        assertTrue("Should be OK message", okMessage.startsWith("""["OK""""))
        assertTrue("Should contain event ID", okMessage.contains(testEvent.id))
        assertTrue("Should indicate acceptance", okMessage.contains("true"))
        
        // Event should be stored
        assertTrue("Event should be stored", 
            testWebSocketServer.getStoredEvents().any { it.id == testEvent.id })
    }
    
    @Test
    fun `should handle REQ message with filters per NIP-01`() = runTest {
        // Given: Events in relay and filter for specific kinds
        testWebSocketServer.start()
        
        val textEvent = createTestEvent("Text note").copy(kind = 1)
        val metadataEvent = createTestEvent("Metadata").copy(
            id = generateUniqueEventId(),
            kind = 0
        )
        val reactionEvent = createTestEvent("Reaction").copy(
            id = generateUniqueEventId(),
            kind = 7
        )
        
        testWebSocketServer.publishEvent(textEvent)
        testWebSocketServer.publishEvent(metadataEvent)
        testWebSocketServer.publishEvent(reactionEvent)
        
        // When: Sending REQ for text notes and reactions
        val filter = NostrFilter(kinds = listOf(1, 7))
        val reqMessage = """["REQ","test-sub",${gson.toJson(filter)}]"""
        val responses = testWebSocketServer.processClientMessage(reqMessage)
        
        // Then: Should return matching events + EOSE
        assertTrue("Should have responses", responses.isNotEmpty())
        
        val eventMessages = responses.filter { it.startsWith("""["EVENT","test-sub"""") }
        val eoseMessages = responses.filter { it == """["EOSE","test-sub"]""" }
        
        assertEquals("Should have exactly one EOSE", 1, eoseMessages.size)
        assertEquals("Should return 2 matching events", 2, eventMessages.size)
        
        // Verify only matching events are returned
        val returnedEventIds = eventMessages.map { message ->
            // Extract event ID from EVENT message
            val eventJson = message.substringAfter("""["EVENT","test-sub",""").substringBeforeLast("]")
            gson.fromJson(eventJson, com.cmdruid.pubsub.nostr.NostrEvent::class.java).id
        }
        
        assertTrue("Should include text event", returnedEventIds.contains(textEvent.id))
        assertTrue("Should include reaction event", returnedEventIds.contains(reactionEvent.id))
        assertFalse("Should not include metadata event", returnedEventIds.contains(metadataEvent.id))
    }
    
    @Test
    fun `should handle multiple filters in REQ message per NIP-01`() = runTest {
        // Given: Events and multiple filters
        testWebSocketServer.start()
        
        val authorEvent = createTestEvent("Author event").copy(
            pubkey = "author123456789abcdef123456789abcdef123456789abcdef123456789abcdef"
        )
        val kindEvent = createTestEvent("Kind event").copy(
            id = generateUniqueEventId(),
            kind = 7
        )
        val noMatchEvent = createTestEvent("No match").copy(
            id = generateUniqueEventId(),
            kind = 3,
            pubkey = generateUniquePubkey()
        )
        
        testWebSocketServer.publishEvent(authorEvent)
        testWebSocketServer.publishEvent(kindEvent)
        testWebSocketServer.publishEvent(noMatchEvent)
        
        // When: REQ with multiple filters (OR condition per NIP-01)
        val filter1 = NostrFilter(authors = listOf(authorEvent.pubkey))
        val filter2 = NostrFilter(kinds = listOf(7))
        val reqMessage = """["REQ","multi-filter",${gson.toJson(filter1)},${gson.toJson(filter2)}]"""
        val responses = testWebSocketServer.processClientMessage(reqMessage)
        
        // Then: Should return events matching ANY filter
        val eventMessages = responses.filter { it.startsWith("""["EVENT","multi-filter"""") }
        assertEquals("Should return 2 matching events", 2, eventMessages.size)
    }
    
    @Test
    fun `should handle CLOSE message per NIP-01`() = runTest {
        // Given: Active subscription
        testWebSocketServer.start()
        
        val filter = NostrFilter(kinds = listOf(1))
        val reqMessage = """["REQ","close-test",${gson.toJson(filter)}]"""
        testWebSocketServer.processClientMessage(reqMessage)
        
        assertTrue("Subscription should be active", 
            testWebSocketServer.getActiveSubscriptions().containsKey("close-test"))
        
        // When: Sending CLOSE message
        val closeMessage = """["CLOSE","close-test"]"""
        val responses = testWebSocketServer.processClientMessage(closeMessage)
        
        // Then: Should close subscription
        assertFalse("Subscription should be closed",
            testWebSocketServer.getActiveSubscriptions().containsKey("close-test"))
        
        // CLOSE typically doesn't require response per NIP-01
        assertTrue("CLOSE response should be empty or notice only", 
            responses.isEmpty() || responses.all { it.startsWith("""["NOTICE""") })
    }
    
    @Test
    fun `should handle timestamp filtering per NIP-01`() = runTest {
        // Given: Events with different timestamps
        testWebSocketServer.start()
        
        val currentTime = System.currentTimeMillis() / 1000
        val oldEvent = createTestEvent("Old event").copy(
            id = generateUniqueEventId(),
            createdAt = currentTime - 3600 // 1 hour ago
        )
        val recentEvent = createTestEvent("Recent event").copy(
            id = generateUniqueEventId(), 
            createdAt = currentTime - 1800 // 30 minutes ago
        )
        val futureEvent = createTestEvent("Future event").copy(
            id = generateUniqueEventId(),
            createdAt = currentTime + 1800 // 30 minutes from now
        )
        
        testWebSocketServer.publishEvent(oldEvent)
        testWebSocketServer.publishEvent(recentEvent)
        testWebSocketServer.publishEvent(futureEvent)
        
        // When: REQ with since filter (last 45 minutes)
        val filter = NostrFilter(since = currentTime - 2700) // 45 minutes ago
        val reqMessage = """["REQ","time-filter",${gson.toJson(filter)}]"""
        val responses = testWebSocketServer.processClientMessage(reqMessage)
        
        // Then: Should only return events after 'since'
        val eventMessages = responses.filter { it.startsWith("""["EVENT","time-filter"""") }
        
        // Should return recent and future events, but not old event
        assertTrue("Should return some events", eventMessages.isNotEmpty())
        
        val returnedContent = eventMessages.joinToString()
        assertTrue("Should include recent event", returnedContent.contains(recentEvent.id))
        assertTrue("Should include future event", returnedContent.contains(futureEvent.id))
        assertFalse("Should not include old event", returnedContent.contains(oldEvent.id))
    }
    
    @Test
    fun `should handle malformed messages with proper NOTICE per NIP-01`() = runTest {
        // Given: Various malformed messages
        testWebSocketServer.start()
        
        val malformedMessages = listOf(
            "not json at all",
            "[]", // Empty array
            """["UNKNOWN","data"]""", // Unknown message type
            """["EVENT"]""", // Missing event data
            """["REQ"]""", // Missing subscription ID
            """["CLOSE"]""" // Missing subscription ID
        )
        
        malformedMessages.forEach { message ->
            // When: Processing malformed message
            val responses = testWebSocketServer.processClientMessage(message)
            
            // Then: Should return NOTICE with error per NIP-01
            assertTrue("Should return error notice for: $message", responses.isNotEmpty())
            assertTrue("Should be NOTICE message for: $message", 
                responses.any { it.startsWith("""["NOTICE"""") })
        }
    }
    
    @Test
    fun `should maintain subscription state correctly per NIP-01`() = runTest {
        // Given: Multiple subscriptions
        testWebSocketServer.start()
        
        val filter1 = NostrFilter(kinds = listOf(1))
        val filter2 = NostrFilter(kinds = listOf(0))
        
        // When: Creating multiple subscriptions
        testWebSocketServer.processClientMessage("""["REQ","sub1",${gson.toJson(filter1)}]""")
        testWebSocketServer.processClientMessage("""["REQ","sub2",${gson.toJson(filter2)}]""")
        
        // Then: Both subscriptions should be active
        val activeSubscriptions = testWebSocketServer.getActiveSubscriptions()
        assertEquals("Should have 2 active subscriptions", 2, activeSubscriptions.size)
        assertTrue("Should have sub1", activeSubscriptions.containsKey("sub1"))
        assertTrue("Should have sub2", activeSubscriptions.containsKey("sub2"))
        
        // When: Closing one subscription
        testWebSocketServer.processClientMessage("""["CLOSE","sub1"]""")
        
        // Then: Only sub2 should remain
        val remainingSubscriptions = testWebSocketServer.getActiveSubscriptions()
        assertEquals("Should have 1 remaining subscription", 1, remainingSubscriptions.size)
        assertFalse("Should not have sub1", remainingSubscriptions.containsKey("sub1"))
        assertTrue("Should still have sub2", remainingSubscriptions.containsKey("sub2"))
    }
    
    // === Helper Methods ===
    
    private val gson = com.google.gson.Gson()
}
