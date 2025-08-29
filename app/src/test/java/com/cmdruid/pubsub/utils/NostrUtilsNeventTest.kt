package com.cmdruid.pubsub.utils

import org.junit.Test
import org.junit.Assert.*

/**
 * Comprehensive test suite for NostrUtils nevent functionality
 * Tests NIP-19 nevent encoding/decoding with TLV structure
 */
class NostrUtilsNeventTest {

    // Test data
    private val validEventId = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
    private val validPubkey = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
    private val testRelays = listOf("wss://relay1.example.com", "wss://relay2.example.com")
    private val testKind = 1

    // ========== Basic Nevent Validation ==========

    @Test
    fun testIsValidNevent_ValidFormat() {
        // Create a real nevent to test validation
        val nevent = NostrUtils.hexToNevent(validEventId)
        assertNotNull("Should create valid nevent", nevent)
        assertTrue("Should validate nevent format", NostrUtils.isValidNevent(nevent!!))
    }

    @Test
    fun testIsValidNevent_InvalidFormats() {
        assertFalse("Empty string should be invalid", NostrUtils.isValidNevent(""))
        assertFalse("Wrong prefix should be invalid", NostrUtils.isValidNevent("note1abc123"))
        assertFalse("Too short should be invalid", NostrUtils.isValidNevent("nevent1abc"))
        assertFalse("Random string should be invalid", NostrUtils.isValidNevent("not-a-nevent"))
    }

    @Test
    fun testIsValidEventIdentifier() {
        val nevent = NostrUtils.hexToNevent(validEventId)
        assertNotNull("Should create valid nevent", nevent)
        assertTrue("Should validate event identifier", NostrUtils.isValidEventIdentifier(nevent!!))
        assertFalse("Should reject invalid identifier", NostrUtils.isValidEventIdentifier("invalid"))
    }

    // ========== Basic Nevent Encoding ==========

    @Test
    fun testHexToNevent_BasicEncoding() {
        val nevent = NostrUtils.hexToNevent(validEventId)
        
        assertNotNull("Should encode valid event ID", nevent)
        assertTrue("Should start with nevent1", nevent!!.startsWith("nevent1"))
        assertTrue("Should be reasonable length", nevent.length >= 50)
    }

    @Test
    fun testHexToNevent_InvalidEventId() {
        assertNull("Should reject too short ID", NostrUtils.hexToNevent("123"))
        assertNull("Should reject too long ID", NostrUtils.hexToNevent(validEventId + "extra"))
        assertNull("Should reject non-hex ID", NostrUtils.hexToNevent("ghijklmn" + validEventId.drop(8)))
        assertNull("Should reject empty ID", NostrUtils.hexToNevent(""))
    }

    @Test
    fun testHexToNevent_WithRelayUrls() {
        val nevent = NostrUtils.hexToNevent(validEventId, testRelays)
        
        assertNotNull("Should encode with relay URLs", nevent)
        assertTrue("Should start with nevent1", nevent!!.startsWith("nevent1"))
        
        // Verify relays can be extracted
        val extractedRelays = NostrUtils.extractRelaysFromNevent(nevent)
        assertEquals("Should preserve relay count", testRelays.size, extractedRelays.size)
        assertTrue("Should contain first relay", extractedRelays.contains(testRelays[0]))
        assertTrue("Should contain second relay", extractedRelays.contains(testRelays[1]))
    }

    @Test
    fun testHexToNevent_WithAuthorPubkey() {
        val nevent = NostrUtils.hexToNevent(validEventId, emptyList(), validPubkey)
        
        assertNotNull("Should encode with author pubkey", nevent)
        assertTrue("Should start with nevent1", nevent!!.startsWith("nevent1"))
        
        // Should be longer than basic nevent due to author data
        val basicNevent = NostrUtils.hexToNevent(validEventId)!!
        assertTrue("Should be longer with author", nevent.length > basicNevent.length)
    }

    @Test
    fun testHexToNevent_WithKind() {
        val nevent = NostrUtils.hexToNevent(validEventId, emptyList(), null, testKind)
        
        assertNotNull("Should encode with kind", nevent)
        assertTrue("Should start with nevent1", nevent!!.startsWith("nevent1"))
    }

    @Test
    fun testHexToNevent_WithAllMetadata() {
        val nevent = NostrUtils.hexToNevent(validEventId, testRelays, validPubkey, testKind)
        
        assertNotNull("Should encode with all metadata", nevent)
        assertTrue("Should start with nevent1", nevent!!.startsWith("nevent1"))
        
        // Should be longest version with all metadata
        val basicNevent = NostrUtils.hexToNevent(validEventId)!!
        assertTrue("Should be much longer with all metadata", nevent.length > basicNevent.length + 50)
    }

    // ========== Nevent Decoding ==========

    @Test
    fun testNeventToHex_BasicDecoding() {
        val originalEventId = validEventId
        val nevent = NostrUtils.hexToNevent(originalEventId)
        assertNotNull("Should encode successfully", nevent)
        
        val decodedEventId = NostrUtils.neventToHex(nevent!!)
        assertEquals("Should decode to original event ID", originalEventId, decodedEventId)
    }

    @Test
    fun testNeventToHex_WithMetadata() {
        val originalEventId = validEventId
        val nevent = NostrUtils.hexToNevent(originalEventId, testRelays, validPubkey, testKind)
        assertNotNull("Should encode with metadata", nevent)
        
        val decodedEventId = NostrUtils.neventToHex(nevent!!)
        assertEquals("Should decode to original event ID despite metadata", originalEventId, decodedEventId)
    }

    @Test
    fun testNeventToHex_InvalidNevent() {
        assertNull("Should reject invalid nevent", NostrUtils.neventToHex("invalid"))
        assertNull("Should reject empty nevent", NostrUtils.neventToHex(""))
        assertNull("Should reject note format", NostrUtils.neventToHex("note1abc123"))
    }

    // ========== Round Trip Tests ==========

    @Test
    fun testRoundTrip_BasicEventId() {
        val originalEventId = validEventId
        val nevent = NostrUtils.hexToNevent(originalEventId)
        assertNotNull("Should encode", nevent)
        
        val decodedEventId = NostrUtils.neventToHex(nevent!!)
        assertEquals("Should round trip perfectly", originalEventId, decodedEventId)
    }

    @Test
    fun testRoundTrip_WithRelays() {
        val originalEventId = validEventId
        val nevent = NostrUtils.hexToNevent(originalEventId, testRelays)
        assertNotNull("Should encode with relays", nevent)
        
        val decodedEventId = NostrUtils.neventToHex(nevent!!)
        val extractedRelays = NostrUtils.extractRelaysFromNevent(nevent)
        
        assertEquals("Should preserve event ID", originalEventId, decodedEventId)
        assertEquals("Should preserve relay count", testRelays.size, extractedRelays.size)
        testRelays.forEach { relay ->
            assertTrue("Should preserve relay: $relay", extractedRelays.contains(relay))
        }
    }

    @Test
    fun testRoundTrip_WithAllMetadata() {
        val originalEventId = validEventId
        val nevent = NostrUtils.hexToNevent(originalEventId, testRelays, validPubkey, testKind)
        assertNotNull("Should encode with all metadata", nevent)
        
        val decodedEventId = NostrUtils.neventToHex(nevent!!)
        val extractedRelays = NostrUtils.extractRelaysFromNevent(nevent)
        
        assertEquals("Should preserve event ID", originalEventId, decodedEventId)
        assertEquals("Should preserve relay count", testRelays.size, extractedRelays.size)
        testRelays.forEach { relay ->
            assertTrue("Should preserve relay: $relay", extractedRelays.contains(relay))
        }
    }

    // ========== Relay Extraction Tests ==========

    @Test
    fun testExtractRelaysFromNevent_NoRelays() {
        val nevent = NostrUtils.hexToNevent(validEventId)
        assertNotNull("Should encode without relays", nevent)
        
        val extractedRelays = NostrUtils.extractRelaysFromNevent(nevent!!)
        assertTrue("Should return empty list for no relays", extractedRelays.isEmpty())
    }

    @Test
    fun testExtractRelaysFromNevent_SingleRelay() {
        val singleRelay = listOf("wss://single.relay.com")
        val nevent = NostrUtils.hexToNevent(validEventId, singleRelay)
        assertNotNull("Should encode with single relay", nevent)
        
        val extractedRelays = NostrUtils.extractRelaysFromNevent(nevent!!)
        assertEquals("Should extract single relay", 1, extractedRelays.size)
        assertEquals("Should match original relay", singleRelay[0], extractedRelays[0])
    }

    @Test
    fun testExtractRelaysFromNevent_MultipleRelays() {
        val nevent = NostrUtils.hexToNevent(validEventId, testRelays)
        assertNotNull("Should encode with multiple relays", nevent)
        
        val extractedRelays = NostrUtils.extractRelaysFromNevent(nevent!!)
        assertEquals("Should extract all relays", testRelays.size, extractedRelays.size)
        testRelays.forEach { relay ->
            assertTrue("Should contain relay: $relay", extractedRelays.contains(relay))
        }
    }

    @Test
    fun testExtractRelaysFromNevent_InvalidNevent() {
        val emptyResult = NostrUtils.extractRelaysFromNevent("invalid")
        assertTrue("Should return empty list for invalid nevent", emptyResult.isEmpty())
        
        val emptyResult2 = NostrUtils.extractRelaysFromNevent("")
        assertTrue("Should return empty list for empty nevent", emptyResult2.isEmpty())
    }

    // ========== Edge Cases and Error Handling ==========

    @Test
    fun testEdgeCase_VeryLongRelayUrl() {
        val longRelay = "wss://" + "a".repeat(200) + ".example.com"
        val nevent = NostrUtils.hexToNevent(validEventId, listOf(longRelay))
        
        // Should handle long URLs gracefully (either encode or skip)
        assertNotNull("Should handle long relay URL", nevent)
    }

    @Test
    fun testEdgeCase_ManyRelays() {
        val manyRelays = (1..10).map { "wss://relay$it.example.com" }
        val nevent = NostrUtils.hexToNevent(validEventId, manyRelays)
        assertNotNull("Should handle many relays", nevent)
        
        val extractedRelays = NostrUtils.extractRelaysFromNevent(nevent!!)
        assertTrue("Should extract some relays", extractedRelays.isNotEmpty())
        assertTrue("Should not exceed reasonable limit", extractedRelays.size <= manyRelays.size)
    }

    @Test
    fun testEdgeCase_EmptyRelayInList() {
        val relaysWithEmpty = listOf("wss://good.relay.com", "", "wss://another.relay.com")
        val nevent = NostrUtils.hexToNevent(validEventId, relaysWithEmpty)
        assertNotNull("Should handle empty relay in list", nevent)
        
        val extractedRelays = NostrUtils.extractRelaysFromNevent(nevent!!)
        // Should skip empty relays
        assertFalse("Should not contain empty relay", extractedRelays.contains(""))
        assertTrue("Should contain good relays", extractedRelays.contains("wss://good.relay.com"))
    }

    @Test
    fun testEdgeCase_InvalidPubkey() {
        // Test with invalid pubkey - should still work but skip the pubkey
        val nevent = NostrUtils.hexToNevent(validEventId, testRelays, "invalid-pubkey")
        assertNotNull("Should handle invalid pubkey gracefully", nevent)
        
        val decodedEventId = NostrUtils.neventToHex(nevent!!)
        assertEquals("Should still decode event ID correctly", validEventId, decodedEventId)
    }

    @Test
    fun testEdgeCase_NegativeKind() {
        // Test with negative kind - should skip it
        val nevent = NostrUtils.hexToNevent(validEventId, testRelays, null, -1)
        assertNotNull("Should handle negative kind gracefully", nevent)
        
        val decodedEventId = NostrUtils.neventToHex(nevent!!)
        assertEquals("Should still decode event ID correctly", validEventId, decodedEventId)
    }

    // ========== TLV Structure Tests ==========

    @Test
    fun testTLVStructure_EventIdAlwaysFirst() {
        // Event ID should always be TLV type 0 and be decodable
        val nevent = NostrUtils.hexToNevent(validEventId, testRelays, validPubkey, testKind)
        assertNotNull("Should encode with all metadata", nevent)
        
        val decodedEventId = NostrUtils.neventToHex(nevent!!)
        assertEquals("Should always decode event ID first", validEventId, decodedEventId)
    }

    @Test
    fun testTLVStructure_PreserveOrder() {
        // Test that multiple relays are preserved (order may vary but all should be present)
        val orderedRelays = listOf("wss://first.com", "wss://second.com", "wss://third.com")
        val nevent = NostrUtils.hexToNevent(validEventId, orderedRelays)
        assertNotNull("Should encode ordered relays", nevent)
        
        val extractedRelays = NostrUtils.extractRelaysFromNevent(nevent!!)
        assertEquals("Should preserve all relays", orderedRelays.size, extractedRelays.size)
        orderedRelays.forEach { relay ->
            assertTrue("Should contain relay: $relay", extractedRelays.contains(relay))
        }
    }

    // ========== Performance Tests ==========

    @Test
    fun testPerformance_MultipleEncodings() {
        val startTime = System.currentTimeMillis()
        
        // Encode/decode 100 nevents to test performance
        repeat(100) { i ->
            val eventId = validEventId.dropLast(2) + String.format("%02d", i % 100)
            val nevent = NostrUtils.hexToNevent(eventId, testRelays)
            assertNotNull("Should encode event $i", nevent)
            
            val decoded = NostrUtils.neventToHex(nevent!!)
            assertEquals("Should decode event $i", eventId, decoded)
        }
        
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        
        assertTrue("Should complete 100 encode/decode cycles in reasonable time", duration < 5000) // 5 seconds
        println("✅ Encoded/decoded 100 nevents in ${duration}ms")
    }

    // ========== Compatibility Tests ==========

    @Test
    fun testNIP19Compliance_NeventPrefix() {
        val nevent = NostrUtils.hexToNevent(validEventId)
        assertNotNull("Should create nevent", nevent)
        assertTrue("Should use correct NIP-19 prefix", nevent!!.startsWith("nevent1"))
    }

    @Test
    fun testNIP19Compliance_Bech32Format() {
        val nevent = NostrUtils.hexToNevent(validEventId)
        assertNotNull("Should create nevent", nevent)
        
        // Should only contain valid bech32 characters after prefix
        val datapart = nevent!!.substring(7) // Remove "nevent1"
        val validBech32Chars = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        
        datapart.forEach { char ->
            assertTrue("Should only contain valid bech32 chars: $char", 
                      char.lowercaseChar() in validBech32Chars)
        }
    }

    @Test
    fun testNIP19Compliance_TLVStructure() {
        // Test that our TLV structure follows NIP-19 spec:
        // Type 0: Event ID (32 bytes)
        // Type 1: Relay URL (variable length)  
        // Type 2: Author (32 bytes)
        // Type 3: Kind (4 bytes)
        
        val nevent = NostrUtils.hexToNevent(validEventId, testRelays, validPubkey, testKind)
        assertNotNull("Should create nevent with all TLV types", nevent)
        
        // Should be able to extract all components
        val decodedEventId = NostrUtils.neventToHex(nevent!!)
        val extractedRelays = NostrUtils.extractRelaysFromNevent(nevent)
        
        assertEquals("Should preserve event ID (Type 0)", validEventId, decodedEventId)
        assertEquals("Should preserve relays (Type 1)", testRelays.size, extractedRelays.size)
        // Note: We don't currently expose author/kind extraction, but they should be encoded
    }
}
