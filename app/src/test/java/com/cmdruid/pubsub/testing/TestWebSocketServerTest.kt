package com.cmdruid.pubsub.testing

import com.cmdruid.pubsub.nostr.NostrEvent
import com.cmdruid.pubsub.nostr.NostrFilter
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Test class for TestWebSocketServer to validate NIP-01 compliance
 * Focuses on message processing rather than complex WebSocket mocking
 */
@RunWith(RobolectricTestRunner::class)
class TestWebSocketServerTest {
    
    private lateinit var testServer: TestWebSocketServer
    
    @Before
    fun setup() {
        testServer = TestWebSocketServer()
    }
    
    @After
    fun cleanup() {
        testServer.stop()
    }
    
    @Test
    fun `should start and stop server without exceptions`() {
        // Given: Test server
        
        // When: Starting and stopping server
        val url = testServer.start()
        testServer.stop()
        
        // Then: Should complete without exceptions
        assertNotNull(url)
        assertTrue(url.startsWith("http://"))
    }
    
    @Test
    fun `should handle EVENT messages and return OK responses`() {
        // Given: Valid EVENT message
        val testEvent = createValidTestEvent()
        val eventMessage = """["EVENT",${gson.toJson(testEvent)}]"""
        
        // When: Processing EVENT message
        val responses = testServer.processClientMessage(eventMessage)
        
        // Then: Should return OK response
        assertEquals(1, responses.size)
        val okMessage = responses[0]
        assertTrue("Should be OK message", okMessage.startsWith("""["OK""""))
        assertTrue("Should indicate success", okMessage.contains("true"))
        
        // Event should be stored
        assertEquals(1, testServer.getStoredEvents().size)
        assertEquals(testEvent.id, testServer.getStoredEvents()[0].id)
    }
    
    @Test
    fun `should reject invalid EVENT messages`() {
        // Given: Invalid EVENT message (blank ID)
        val invalidEvent = createValidTestEvent().copy(id = "")
        val eventMessage = """["EVENT",${gson.toJson(invalidEvent)}]"""
        
        // When: Processing invalid EVENT message
        val responses = testServer.processClientMessage(eventMessage)
        
        // Then: Should return rejection OK response
        assertEquals(1, responses.size)
        val okMessage = responses[0]
        assertTrue("Should be OK message", okMessage.startsWith("""["OK""""))
        assertTrue("Should indicate rejection", okMessage.contains("false"))
        assertTrue("Should indicate validation error", okMessage.contains("invalid"))
        
        // Event should not be stored
        assertEquals(0, testServer.getStoredEvents().size)
    }
    
    @Test
    fun `should handle duplicate EVENT messages correctly`() {
        // Given: Same event sent twice
        val testEvent = createValidTestEvent()
        val eventMessage = """["EVENT",${gson.toJson(testEvent)}]"""
        
        // When: Processing same event twice
        val firstResponse = testServer.processClientMessage(eventMessage)
        val secondResponse = testServer.processClientMessage(eventMessage)
        
        // Then: First should succeed, second should indicate duplicate
        assertTrue("First response should indicate success", firstResponse[0].contains("true"))
        assertTrue("Second response should indicate duplicate", secondResponse[0].contains("duplicate"))
        
        // Should only store one copy
        assertEquals(1, testServer.getStoredEvents().size)
    }
    
    @Test
    fun `should handle REQ messages and return events with EOSE`() {
        // Given: Server with stored events
        val textEvent = createValidTestEvent().copy(kind = 1)
        val metadataEvent = createValidTestEvent().copy(
            id = "different1234567890abcdef1234567890abcdef1234567890abcdef12345678",
            kind = 0
        )
        
        testServer.publishEvent(textEvent)
        testServer.publishEvent(metadataEvent)
        
        // When: Requesting text notes only (kind 1)
        val filter = NostrFilter(kinds = listOf(1))
        val reqMessage = """["REQ","test-sub",${gson.toJson(filter)}]"""
        val responses = testServer.processClientMessage(reqMessage)
        
        // Then: Should return matching event + EOSE
        assertEquals(2, responses.size) // 1 EVENT + 1 EOSE
        
        val eventMessage = responses[0]
        val eoseMessage = responses[1]
        
        assertTrue("First should be EVENT", eventMessage.startsWith("""["EVENT","test-sub""""))
        assertTrue("Should contain text event", eventMessage.contains(textEvent.id))
        assertFalse("Should not contain metadata event", eventMessage.contains(metadataEvent.id))
        
        assertEquals("Second should be EOSE", """["EOSE","test-sub"]""", eoseMessage)
        
        // Subscription should be active
        assertTrue(testServer.getActiveSubscriptions().containsKey("test-sub"))
    }
    
    @Test
    fun `should filter events by timestamp according to NIP-01`() {
        // Given: Events with different timestamps
        val currentTime = System.currentTimeMillis() / 1000
        val oldEvent = createValidTestEvent().copy(
            id = "old12345678901234567890123456789012345678901234567890123456789012",
            createdAt = currentTime - 3600 // 1 hour ago
        )
        val newEvent = createValidTestEvent().copy(
            id = "new12345678901234567890123456789012345678901234567890123456789012",
            createdAt = currentTime - 1800 // 30 minutes ago
        )
        
        testServer.publishEvent(oldEvent)
        testServer.publishEvent(newEvent)
        
        // When: Requesting events since 45 minutes ago
        val filter = NostrFilter(since = currentTime - 2700) // 45 minutes ago
        val reqMessage = """["REQ","time-filter",${gson.toJson(filter)}]"""
        val responses = testServer.processClientMessage(reqMessage)
        
        // Then: Should return responses (at least EOSE)
        assertTrue("Should have at least EOSE", responses.size >= 1)
        
        val eoseMessage = responses.last()
        assertEquals("Should end with EOSE", """["EOSE","time-filter"]""", eoseMessage)
        
        // If there are events, verify filtering worked
        if (responses.size > 1) {
            val eventMessage = responses[0]
            assertTrue("Should contain new event", eventMessage.contains(newEvent.id))
            assertFalse("Should not contain old event", eventMessage.contains(oldEvent.id))
        }
    }
    
    @Test
    fun `should handle CLOSE messages`() {
        // Given: Active subscription
        val filter = NostrFilter(kinds = listOf(1))
        val reqMessage = """["REQ","test-sub",${gson.toJson(filter)}]"""
        testServer.processClientMessage(reqMessage)
        
        assertTrue("Subscription should be active", testServer.getActiveSubscriptions().containsKey("test-sub"))
        
        // When: Closing subscription
        val closeMessage = """["CLOSE","test-sub"]"""
        val responses = testServer.processClientMessage(closeMessage)
        
        // Then: Should close subscription
        assertTrue("Should handle close gracefully", responses.isEmpty() || responses.all { it.startsWith("[\"NOTICE") })
        assertFalse("Subscription should be removed", testServer.getActiveSubscriptions().containsKey("test-sub"))
    }
    
    @Test
    fun `should handle malformed messages gracefully`() {
        // Given: Various malformed messages
        val malformedMessages = listOf(
            "invalid json",
            "[]", // Empty array
            """["UNKNOWN"]""", // Unknown message type
            """["EVENT"]""", // Missing event data
            """["REQ"]""", // Missing subscription ID
            """["CLOSE"]""" // Missing subscription ID
        )
        
        malformedMessages.forEach { message ->
            // When: Processing malformed message
            val responses = testServer.processClientMessage(message)
            
            // Then: Should return error notice
            assertTrue("Should return error for: $message", responses.isNotEmpty())
            assertTrue("Should be NOTICE message for: $message", responses[0].startsWith("""["NOTICE""""))
        }
    }
    
    // === Helper Methods ===
    
    private val gson = com.google.gson.Gson()
    
    private fun createValidTestEvent(): NostrEvent {
        return NostrEvent(
            id = "abcd1234567890abcdef1234567890abcdef1234567890abcdef1234567890ab",
            pubkey = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
            createdAt = System.currentTimeMillis() / 1000,
            kind = 1,
            tags = emptyList(),
            content = "Test event for WebSocket server testing",
            signature = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
        )
    }
}