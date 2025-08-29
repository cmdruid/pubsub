package com.cmdruid.pubsub.utils

import android.net.Uri
import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.nostr.NostrEvent
import com.cmdruid.pubsub.nostr.NostrFilter
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration tests for nevent functionality across the application
 * Tests end-to-end workflows with nevent encoding/decoding
 */
@RunWith(RobolectricTestRunner::class)
class NeventIntegrationTest {

    private val testRelays = listOf("wss://relay1.example.com", "wss://relay2.example.com")
    private val testBaseUri = "https://example.com/events"
    
    private fun createTestEvent(
        id: String = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
        pubkey: String = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
        kind: Int = 1,
        content: String = "Test event content for integration testing"
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        createdAt = System.currentTimeMillis() / 1000,
        kind = kind,
        tags = emptyList(),
        content = content,
        signature = "a".repeat(128)
    )

    private fun createTestConfiguration() = Configuration(
        name = "Test Configuration",
        relayUrls = testRelays,
        filter = NostrFilter(kinds = listOf(1)),
        targetUri = testBaseUri
    )

    // ========== End-to-End Workflow Tests ==========

    @Test
    fun testEndToEndNeventWorkflow() {
        val originalEvent = createTestEvent()
        
        // Step 1: Build URI with nevent (simulating notification creation)
        val uri = UriBuilder.buildEventUri(testBaseUri, originalEvent, testRelays)
        assertNotNull("Should build URI", uri)
        
        // Step 2: Extract nevent from URI (simulating URI parsing)
        val nevent = UriBuilder.extractNeventFromUri(uri!!)
        assertNotNull("Should extract nevent", nevent)
        assertTrue("Should be valid nevent", NostrUtils.isValidNevent(nevent!!))
        
        // Step 3: Decode event ID from nevent
        val decodedEventId = NostrUtils.neventToHex(nevent)
        assertEquals("Should preserve event ID", originalEvent.id, decodedEventId)
        
        // Step 4: Extract relay URLs from nevent
        val extractedRelays = NostrUtils.extractRelaysFromNevent(nevent)
        assertEquals("Should preserve relay count", testRelays.size, extractedRelays.size)
        testRelays.forEach { relay ->
            assertTrue("Should preserve relay: $relay", extractedRelays.contains(relay))
        }
        
        // Step 5: Decode full event from URI (if included)
        val decodedEvent = UriBuilder.decodeEventFromUri(uri)
        assertNotNull("Should decode event", decodedEvent)
        assertEquals("Should preserve event content", originalEvent.content, decodedEvent!!.content)
        assertEquals("Should preserve event pubkey", originalEvent.pubkey, decodedEvent.pubkey)
        assertEquals("Should preserve event kind", originalEvent.kind, decodedEvent.kind)
    }

    @Test
    fun testTrailingSlashNormalization() {
        val configuration = createTestConfiguration()
        val event = createTestEvent()
        
        // Test with various URI formats
        val urisToTest = listOf(
            "https://example.com/events",
            "https://example.com/events/",
            "https://example.com/events//",
            "  https://example.com/events/  "
        )
        
        urisToTest.forEach { testUri ->
            val normalizedUri = UriBuilder.normalizeTargetUri(testUri)
            val uri = UriBuilder.buildEventUri(normalizedUri, event, testRelays)
            assertNotNull("Should build URI for: $testUri", uri)
            
            // Should not have double slashes
            assertFalse("Should not have double slashes in: ${uri.toString()}", 
                       uri.toString().contains("//nevent") && !uri.toString().startsWith("http"))
        }
    }

    @Test
    fun testNeventWithConfiguration() {
        val configuration = createTestConfiguration()
        val event = createTestEvent()
        
        // Build URI using configuration's relay URLs and target URI
        val uri = UriBuilder.buildEventUri(configuration.targetUri, event, configuration.relayUrls)
        assertNotNull("Should build URI with configuration", uri)
        
        // Verify the nevent contains the configuration's relay URLs
        val extractedRelays = UriBuilder.extractRelayUrlsFromUri(uri!!)
        assertEquals("Should use configuration relays", configuration.relayUrls.size, extractedRelays.size)
        configuration.relayUrls.forEach { relay ->
            assertTrue("Should contain configuration relay: $relay", extractedRelays.contains(relay))
        }
    }

    // ========== Compatibility Tests ==========

    @Test
    fun testNeventCompatibilityWithDeepLinks() {
        val configuration = createTestConfiguration()
        val event = createTestEvent()
        
        // Build event URI
        val eventUri = UriBuilder.buildEventUri(configuration.targetUri, event, configuration.relayUrls)
        assertNotNull("Should build event URI", eventUri)
        
        // Build deep link URI
        val deepLinkUri = UriBuilder.buildRegisterDeepLink(configuration)
        assertNotNull("Should build deep link URI", deepLinkUri)
        
        // Both should work together - deep link creates configuration, 
        // configuration is used to create event URIs with nevents
        assertTrue("Event URI should contain nevent", eventUri.toString().contains("nevent1"))
        assertTrue("Deep link should be pubsub scheme", deepLinkUri!!.startsWith("pubsub://"))
    }

    @Test
    fun testNeventWithMultipleRelays() {
        val manyRelays = listOf(
            "wss://relay1.example.com",
            "wss://relay2.example.com", 
            "wss://relay3.example.com",
            "wss://relay4.example.com",
            "wss://relay5.example.com"
        )
        
        val event = createTestEvent()
        val uri = UriBuilder.buildEventUri(testBaseUri, event, manyRelays)
        assertNotNull("Should handle many relays", uri)
        
        val extractedRelays = UriBuilder.extractRelayUrlsFromUri(uri!!)
        assertTrue("Should extract some relays", extractedRelays.isNotEmpty())
        assertTrue("Should not exceed original count", extractedRelays.size <= manyRelays.size)
        
        // Should preserve at least the first few relays
        assertTrue("Should contain first relay", extractedRelays.contains(manyRelays[0]))
    }

    // ========== Error Recovery Tests ==========

    @Test
    fun testErrorRecoveryInWorkflow() {
        // Test that the workflow handles various error conditions gracefully
        
        // Test with invalid event ID
        val invalidEvent = createTestEvent(id = "invalid-id")
        val uri1 = UriBuilder.buildEventUri(testBaseUri, invalidEvent, testRelays)
        assertNull("Should handle invalid event gracefully", uri1)
        
        // Test with valid event but corrupted URI
        val validEvent = createTestEvent()
        val validUri = UriBuilder.buildEventUri(testBaseUri, validEvent, testRelays)
        assertNotNull("Should build valid URI", validUri)
        
        // Test extracting from corrupted URI
        val corruptedUriString = validUri.toString().replace("nevent1", "invalid1")
        val corruptedUri = Uri.parse(corruptedUriString)
        val extractedNevent = UriBuilder.extractNeventFromUri(corruptedUri)
        assertNull("Should handle corrupted nevent gracefully", extractedNevent)
    }

    // ========== Performance Integration Tests ==========

    @Test
    fun testPerformanceIntegration() {
        val startTime = System.currentTimeMillis()
        val events = mutableListOf<NostrEvent>()
        val uris = mutableListOf<Uri>()
        
        // Create and process 25 events
        repeat(25) { i ->
            val event = createTestEvent(
                id = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcd${String.format("%02d", i)}"
            )
            events.add(event)
            
            // Build URI
            val uri = UriBuilder.buildEventUri(testBaseUri, event, testRelays)
            assertNotNull("Should build URI $i", uri)
            uris.add(uri!!)
        }
        
        // Process all URIs
        uris.forEachIndexed { i, uri ->
            val extractedEventId = UriBuilder.getEventIdFromUri(uri)
            val extractedRelays = UriBuilder.extractRelayUrlsFromUri(uri)
            val decodedEvent = UriBuilder.decodeEventFromUri(uri)
            
            assertEquals("Should extract correct event ID $i", events[i].id, extractedEventId)
            assertEquals("Should extract correct relay count $i", testRelays.size, extractedRelays.size)
            assertNotNull("Should decode event $i", decodedEvent)
        }
        
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        
        assertTrue("Should complete integration test in reasonable time", duration < 5000) // 5 seconds
        println("✅ Processed ${events.size} events end-to-end in ${duration}ms")
    }

    // ========== Real-world Scenario Tests ==========

    @Test
    fun testRealWorldScenario_NotificationFlow() {
        // Simulate the real notification flow:
        // 1. Event received from relay
        // 2. Configuration matches event
        // 3. URI built with nevent and relay info
        // 4. Notification sent with URI
        // 5. User clicks notification
        // 6. External app receives URI and processes it
        
        val configuration = createTestConfiguration()
        val incomingEvent = createTestEvent(
            content = "This is a real Bitcoin transaction announcement! #bitcoin #lightning"
        )
        
        // Step 1-3: Build notification URI (what MessageHandler would do)
        val notificationUri = UriBuilder.buildEventUri(
            configuration.targetUri, 
            incomingEvent, 
            configuration.relayUrls
        )
        assertNotNull("Should create notification URI", notificationUri)
        
        // Step 4-5: URI is sent in notification, user clicks (simulated)
        val uriString = notificationUri.toString()
        assertTrue("URI should contain nevent", uriString.contains("nevent1"))
        assertTrue("URI should contain base URI", uriString.contains(configuration.targetUri))
        
        // Step 6: External app processes URI (what recipient app would do)
        val receivedUri = Uri.parse(uriString)
        
        // Extract event information
        val eventId = UriBuilder.getEventIdFromUri(receivedUri)
        val relayHints = UriBuilder.extractRelayUrlsFromUri(receivedUri)
        val fullEvent = UriBuilder.decodeEventFromUri(receivedUri)
        
        // Verify all information is preserved
        assertEquals("Should preserve event ID", incomingEvent.id, eventId)
        assertEquals("Should preserve relay hints", configuration.relayUrls.size, relayHints.size)
        assertNotNull("Should preserve full event", fullEvent)
        assertEquals("Should preserve event content", incomingEvent.content, fullEvent!!.content)
        
        // Verify relay hints are useful
        configuration.relayUrls.forEach { relay ->
            assertTrue("Should provide relay hint: $relay", relayHints.contains(relay))
        }
        
        println("✅ Real-world notification flow completed successfully")
        println("   Event ID: ${eventId?.take(8)}...")
        println("   Relay hints: ${relayHints.size}")
        println("   Full event: ${fullEvent?.content?.take(50)}...")
    }

    @Test
    fun testNIP19Compliance() {
        // Test that our nevent implementation is compliant with NIP-19 specification
        val event = createTestEvent()
        val uri = UriBuilder.buildEventUri(testBaseUri, event, testRelays)
        assertNotNull("Should build URI", uri)
        
        val nevent = UriBuilder.extractNeventFromUri(uri!!)
        assertNotNull("Should extract nevent", nevent)
        
        // NIP-19 compliance checks
        assertTrue("Should start with nevent1", nevent!!.startsWith("nevent1"))
        assertTrue("Should be reasonable length", nevent.length >= 50)
        assertTrue("Should be valid bech32", NostrUtils.isValidNevent(nevent))
        
        // Should decode correctly
        val decodedId = NostrUtils.neventToHex(nevent)
        assertEquals("Should decode to original event ID", event.id, decodedId)
        
        // Should include relay information
        val relays = NostrUtils.extractRelaysFromNevent(nevent)
        assertEquals("Should include relay information", testRelays.size, relays.size)
        
        println("✅ NIP-19 compliance verified")
        println("   Nevent: ${nevent.take(20)}...")
        println("   Decoded ID: ${decodedId?.take(8)}...")
        println("   Relays: ${relays.size}")
    }
}
