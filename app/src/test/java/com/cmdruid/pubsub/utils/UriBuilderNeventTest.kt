package com.cmdruid.pubsub.utils

import android.net.Uri
import com.cmdruid.pubsub.nostr.NostrEvent
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Comprehensive test suite for UriBuilder nevent functionality
 * Tests URI building, parsing, and relay extraction with nevent format
 */
@RunWith(RobolectricTestRunner::class)
class UriBuilderNeventTest {

    // Test data
    private val testBaseUri = "https://example.com/events"
    private val testBaseUriWithSlash = "https://example.com/events/"
    private val testRelays = listOf("wss://relay1.example.com", "wss://relay2.example.com")
    
    private fun createTestEvent(
        id: String = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
        pubkey: String = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
        kind: Int = 1,
        content: String = "Test event content"
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        createdAt = System.currentTimeMillis() / 1000,
        kind = kind,
        tags = emptyList(),
        content = content,
        signature = "a".repeat(128) // Valid length signature
    )

    // ========== Basic URI Building ==========

    @Test
    fun testBuildEventUri_BasicFunctionality() {
        val event = createTestEvent()
        val uri = UriBuilder.buildEventUri(testBaseUri, event)
        
        assertNotNull("Should build URI successfully", uri)
        assertTrue("Should contain base URI", uri.toString().startsWith(testBaseUri))
        
        // Should contain nevent in path
        val path = uri!!.path
        assertNotNull("Should have path", path)
        val pathSegments = path!!.split("/")
        val lastSegment = pathSegments.last()
        assertTrue("Should contain nevent in path", lastSegment.startsWith("nevent1"))
    }

    @Test
    fun testBuildEventUri_WithRelays() {
        val event = createTestEvent()
        val uri = UriBuilder.buildEventUri(testBaseUri, event, testRelays)
        
        assertNotNull("Should build URI with relays", uri)
        
        // Extract nevent from URI and verify it contains relay information
        val nevent = UriBuilder.extractNeventFromUri(uri!!)
        assertNotNull("Should extract nevent from URI", nevent)
        
        val extractedRelays = NostrUtils.extractRelaysFromNevent(nevent!!)
        assertEquals("Should preserve relay count", testRelays.size, extractedRelays.size)
        testRelays.forEach { relay ->
            assertTrue("Should contain relay: $relay", extractedRelays.contains(relay))
        }
    }

    @Test
    fun testBuildEventUri_WithEventData() {
        val event = createTestEvent()
        val uri = UriBuilder.buildEventUri(testBaseUri, event, testRelays)
        
        assertNotNull("Should build URI with event data", uri)
        
        // Should include event data as query parameter for small events
        val eventParam = uri!!.getQueryParameter("event")
        assertNotNull("Should include event data parameter", eventParam)
        assertTrue("Event data should not be empty", eventParam!!.isNotEmpty())
        
        // Should be able to decode the event back
        val decodedEvent = UriBuilder.decodeEventFromUri(uri)
        assertNotNull("Should decode event from URI", decodedEvent)
        assertEquals("Should preserve event ID", event.id, decodedEvent!!.id)
        assertEquals("Should preserve event content", event.content, decodedEvent.content)
    }

    @Test
    fun testBuildEventUri_LargeEventHandling() {
        // Create an event with large content
        val largeContent = "x".repeat(600 * 1024) // 600KB content
        val largeEvent = createTestEvent(content = largeContent)
        
        assertTrue("Event should be too large", UriBuilder.isEventTooLarge(largeEvent))
        
        val uri = UriBuilder.buildEventUri(testBaseUri, largeEvent, testRelays)
        assertNotNull("Should build URI even for large event", uri)
        
        // Should NOT include event data for large events
        val eventParam = uri!!.getQueryParameter("event")
        assertNull("Should not include event data for large events", eventParam)
        
        // But should still include nevent with relay info
        val nevent = UriBuilder.extractNeventFromUri(uri)
        assertNotNull("Should still include nevent", nevent)
        
        val extractedRelays = NostrUtils.extractRelaysFromNevent(nevent!!)
        assertEquals("Should still preserve relay info", testRelays.size, extractedRelays.size)
    }

    // ========== Trailing Slash Handling ==========

    @Test
    fun testTrailingSlashHandling_BaseUriWithoutSlash() {
        val event = createTestEvent()
        val uri = UriBuilder.buildEventUri(testBaseUri, event)
        
        assertNotNull("Should build URI", uri)
        assertTrue("Should add slash before nevent", uri.toString().contains("$testBaseUri/nevent1"))
    }

    @Test
    fun testTrailingSlashHandling_BaseUriWithSlash() {
        val event = createTestEvent()
        val uri = UriBuilder.buildEventUri(testBaseUriWithSlash, event)
        
        assertNotNull("Should build URI", uri)
        assertTrue("Should not double slash", uri.toString().contains("events/nevent1"))
        assertFalse("Should not have double slash", uri.toString().contains("events//nevent1"))
    }

    @Test
    fun testNormalizeTargetUri_RemovesTrailingSlash() {
        val uriWithSlash = "https://example.com/path/"
        val uriWithoutSlash = "https://example.com/path"
        
        assertEquals("Should remove trailing slash", 
                    uriWithoutSlash, UriBuilder.normalizeTargetUri(uriWithSlash))
        assertEquals("Should leave URI without slash unchanged", 
                    uriWithoutSlash, UriBuilder.normalizeTargetUri(uriWithoutSlash))
    }

    @Test
    fun testNormalizeTargetUri_HandlesEdgeCases() {
        assertEquals("Should handle root path", "https://example.com", 
                    UriBuilder.normalizeTargetUri("https://example.com/"))
        assertEquals("Should handle multiple slashes", "https://example.com/path", 
                    UriBuilder.normalizeTargetUri("https://example.com/path/"))
        assertEquals("Should handle empty string", "", 
                    UriBuilder.normalizeTargetUri(""))
        assertEquals("Should trim whitespace", "https://example.com/path", 
                    UriBuilder.normalizeTargetUri("  https://example.com/path/  "))
    }

    // ========== URI Parsing and Extraction ==========

    @Test
    fun testExtractNeventFromUri_ValidUri() {
        val event = createTestEvent()
        val uri = UriBuilder.buildEventUri(testBaseUri, event, testRelays)
        assertNotNull("Should build URI", uri)
        
        val extractedNevent = UriBuilder.extractNeventFromUri(uri!!)
        assertNotNull("Should extract nevent", extractedNevent)
        assertTrue("Should be valid nevent", NostrUtils.isValidNevent(extractedNevent!!))
        
        // Should be able to decode back to original event ID
        val decodedEventId = NostrUtils.neventToHex(extractedNevent)
        assertEquals("Should decode to original event ID", event.id, decodedEventId)
    }

    @Test
    fun testExtractNeventFromUri_InvalidUri() {
        val invalidUri = Uri.parse("https://example.com/not-a-nevent")
        val extractedNevent = UriBuilder.extractNeventFromUri(invalidUri)
        assertNull("Should not extract nevent from invalid URI", extractedNevent)
        
        val emptyUri = Uri.parse("https://example.com/")
        val extractedNevent2 = UriBuilder.extractNeventFromUri(emptyUri)
        assertNull("Should not extract nevent from empty path", extractedNevent2)
    }

    @Test
    fun testGetEventIdFromUri_ValidUri() {
        val event = createTestEvent()
        val uri = UriBuilder.buildEventUri(testBaseUri, event, testRelays)
        assertNotNull("Should build URI", uri)
        
        val extractedEventId = UriBuilder.getEventIdFromUri(uri!!)
        assertEquals("Should extract correct event ID", event.id, extractedEventId)
    }

    @Test
    fun testGetEventIdFromUri_InvalidUri() {
        val invalidUri = Uri.parse("https://example.com/invalid")
        val extractedEventId = UriBuilder.getEventIdFromUri(invalidUri)
        assertNull("Should return null for invalid URI", extractedEventId)
    }

    @Test
    fun testExtractRelayUrlsFromUri_WithRelays() {
        val event = createTestEvent()
        val uri = UriBuilder.buildEventUri(testBaseUri, event, testRelays)
        assertNotNull("Should build URI", uri)
        
        val extractedRelays = UriBuilder.extractRelayUrlsFromUri(uri!!)
        assertEquals("Should extract correct number of relays", testRelays.size, extractedRelays.size)
        testRelays.forEach { relay ->
            assertTrue("Should contain relay: $relay", extractedRelays.contains(relay))
        }
    }

    @Test
    fun testExtractRelayUrlsFromUri_NoRelays() {
        val event = createTestEvent()
        val uri = UriBuilder.buildEventUri(testBaseUri, event, emptyList())
        assertNotNull("Should build URI", uri)
        
        val extractedRelays = UriBuilder.extractRelayUrlsFromUri(uri!!)
        assertTrue("Should return empty list for no relays", extractedRelays.isEmpty())
    }

    @Test
    fun testExtractRelayUrlsFromUri_InvalidUri() {
        val invalidUri = Uri.parse("https://example.com/invalid")
        val extractedRelays = UriBuilder.extractRelayUrlsFromUri(invalidUri)
        assertTrue("Should return empty list for invalid URI", extractedRelays.isEmpty())
    }

    // ========== Round Trip Tests ==========

    @Test
    fun testRoundTrip_BasicEvent() {
        val originalEvent = createTestEvent()
        
        // Build URI
        val uri = UriBuilder.buildEventUri(testBaseUri, originalEvent, testRelays)
        assertNotNull("Should build URI", uri)
        
        // Extract components
        val extractedEventId = UriBuilder.getEventIdFromUri(uri!!)
        val extractedRelays = UriBuilder.extractRelayUrlsFromUri(uri)
        val decodedEvent = UriBuilder.decodeEventFromUri(uri)
        
        // Verify round trip
        assertEquals("Should preserve event ID", originalEvent.id, extractedEventId)
        assertEquals("Should preserve relay count", testRelays.size, extractedRelays.size)
        assertNotNull("Should decode event", decodedEvent)
        assertEquals("Should preserve event content", originalEvent.content, decodedEvent!!.content)
        assertEquals("Should preserve event pubkey", originalEvent.pubkey, decodedEvent.pubkey)
        assertEquals("Should preserve event kind", originalEvent.kind, decodedEvent.kind)
    }

    @Test
    fun testRoundTrip_LargeEvent() {
        val largeEvent = createTestEvent(content = "x".repeat(600 * 1024))
        
        // Build URI (should exclude event data)
        val uri = UriBuilder.buildEventUri(testBaseUri, largeEvent, testRelays)
        assertNotNull("Should build URI", uri)
        
        // Should extract event ID and relays but not full event
        val extractedEventId = UriBuilder.getEventIdFromUri(uri!!)
        val extractedRelays = UriBuilder.extractRelayUrlsFromUri(uri)
        val decodedEvent = UriBuilder.decodeEventFromUri(uri)
        
        assertEquals("Should preserve event ID", largeEvent.id, extractedEventId)
        assertEquals("Should preserve relay count", testRelays.size, extractedRelays.size)
        assertNull("Should not decode large event", decodedEvent)
    }

    // ========== URI Validation ==========

    @Test
    fun testIsValidUri_ValidUris() {
        assertTrue("Should validate HTTP URI", UriBuilder.isValidUri("http://example.com"))
        assertTrue("Should validate HTTPS URI", UriBuilder.isValidUri("https://example.com/path"))
        assertTrue("Should validate URI with trailing slash", UriBuilder.isValidUri("https://example.com/"))
        assertTrue("Should validate URI with query", UriBuilder.isValidUri("https://example.com?param=value"))
    }

    @Test
    fun testIsValidUri_InvalidUris() {
        assertFalse("Should reject empty URI", UriBuilder.isValidUri(""))
        assertFalse("Should reject URI without scheme", UriBuilder.isValidUri("example.com"))
        assertFalse("Should reject URI without host", UriBuilder.isValidUri("https://"))
        assertFalse("Should reject malformed URI", UriBuilder.isValidUri("not-a-uri"))
    }

    @Test
    fun testIsValidUri_NormalizedValidation() {
        // Should normalize before validation
        assertTrue("Should validate URI with trailing slash", 
                  UriBuilder.isValidUri("https://example.com/path/"))
        assertTrue("Should validate URI without trailing slash", 
                  UriBuilder.isValidUri("https://example.com/path"))
    }

    // ========== Event Size Utilities ==========

    @Test
    fun testIsEventTooLarge_SmallEvent() {
        val smallEvent = createTestEvent(content = "Small content")
        assertFalse("Small event should not be too large", UriBuilder.isEventTooLarge(smallEvent))
    }

    @Test
    fun testIsEventTooLarge_LargeEvent() {
        val largeEvent = createTestEvent(content = "x".repeat(600 * 1024))
        assertTrue("Large event should be too large", UriBuilder.isEventTooLarge(largeEvent))
    }

    @Test
    fun testGetEventSizeBytes_AccurateSize() {
        val event = createTestEvent(content = "Test content")
        val size = UriBuilder.getEventSizeBytes(event)
        
        assertTrue("Should return positive size", size > 0)
        assertTrue("Should be reasonable size for small event", size < 1024) // Less than 1KB
        
        val largeEvent = createTestEvent(content = "x".repeat(1000))
        val largeSize = UriBuilder.getEventSizeBytes(largeEvent)
        assertTrue("Large event should have larger size", largeSize > size)
    }

    // ========== Error Handling ==========

    @Test
    fun testErrorHandling_NullEvent() {
        // This would cause compilation error, so we test with invalid event data instead
        val invalidEvent = createTestEvent(id = "invalid-id")
        val uri = UriBuilder.buildEventUri(testBaseUri, invalidEvent, testRelays)
        assertNull("Should return null for invalid event ID", uri)
    }

    @Test
    fun testErrorHandling_EmptyBaseUri() {
        val event = createTestEvent()
        val uri = UriBuilder.buildEventUri("", event, testRelays)
        // Empty base URI will create a URI like "/nevent1..." which is technically valid
        assertNotNull("Should handle empty base URI", uri)
        assertTrue("Should start with nevent", uri.toString().contains("nevent1"))
    }

    @Test
    fun testErrorHandling_InvalidBaseUri() {
        val event = createTestEvent()
        val uri = UriBuilder.buildEventUri("not-a-uri", event, testRelays)
        // Invalid base URI will still create a URI string, just not a valid HTTP(S) URI
        assertNotNull("Should handle invalid base URI", uri)
        assertTrue("Should contain the base and nevent", uri.toString().contains("not-a-uri") && uri.toString().contains("nevent1"))
    }

    // ========== Integration with Event Decoding ==========

    @Test
    fun testDecodeEventFromUri_ValidEvent() {
        val originalEvent = createTestEvent()
        val uri = UriBuilder.buildEventUri(testBaseUri, originalEvent, testRelays)
        assertNotNull("Should build URI", uri)
        
        val decodedEvent = UriBuilder.decodeEventFromUri(uri!!)
        assertNotNull("Should decode event", decodedEvent)
        
        // Verify all fields are preserved
        assertEquals("Should preserve ID", originalEvent.id, decodedEvent!!.id)
        assertEquals("Should preserve pubkey", originalEvent.pubkey, decodedEvent.pubkey)
        assertEquals("Should preserve content", originalEvent.content, decodedEvent.content)
        assertEquals("Should preserve kind", originalEvent.kind, decodedEvent.kind)
        assertEquals("Should preserve signature", originalEvent.signature, decodedEvent.signature)
        assertEquals("Should preserve created_at", originalEvent.createdAt, decodedEvent.createdAt)
    }

    @Test
    fun testDecodeEventFromUri_NoEventData() {
        val event = createTestEvent()
        val uri = UriBuilder.buildEventUri(testBaseUri, event, testRelays)
        assertNotNull("Should build URI", uri)
        
        // Create URI without event query parameter
        val uriWithoutEvent = Uri.parse(uri.toString().split("?")[0])
        val decodedEvent = UriBuilder.decodeEventFromUri(uriWithoutEvent)
        assertNull("Should return null when no event data", decodedEvent)
    }

    @Test
    fun testDecodeEventFromUri_CorruptedEventData() {
        val event = createTestEvent()
        val uri = UriBuilder.buildEventUri(testBaseUri, event, testRelays)
        assertNotNull("Should build URI", uri)
        
        // Create URI with corrupted event data
        val corruptedUri = Uri.parse(uri.toString().replace("event=", "event=corrupted"))
        val decodedEvent = UriBuilder.decodeEventFromUri(corruptedUri)
        assertNull("Should return null for corrupted event data", decodedEvent)
    }

    // ========== Performance Tests ==========

    @Test
    fun testPerformance_MultipleBuildAndParse() {
        val startTime = System.currentTimeMillis()
        
        repeat(50) { i ->
            val event = createTestEvent(
                id = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcd${String.format("%02d", i)}"
            )
            
            // Build URI
            val uri = UriBuilder.buildEventUri(testBaseUri, event, testRelays)
            assertNotNull("Should build URI $i", uri)
            
            // Parse components
            val extractedEventId = UriBuilder.getEventIdFromUri(uri!!)
            val extractedRelays = UriBuilder.extractRelayUrlsFromUri(uri)
            val decodedEvent = UriBuilder.decodeEventFromUri(uri)
            
            assertEquals("Should extract correct event ID $i", event.id, extractedEventId)
            assertEquals("Should extract correct relay count $i", testRelays.size, extractedRelays.size)
            assertNotNull("Should decode event $i", decodedEvent)
        }
        
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        
        assertTrue("Should complete 50 build/parse cycles in reasonable time", duration < 10000) // 10 seconds
        println("✅ Built and parsed 50 URIs in ${duration}ms")
    }
}
