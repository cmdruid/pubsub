package com.cmdruid.pubsub.utils

import com.cmdruid.pubsub.data.HashtagEntry
import com.cmdruid.pubsub.nostr.NostrFilter
import com.cmdruid.pubsub.nostr.NostrFilterSerializer
import com.google.gson.GsonBuilder
import org.junit.Assert.*
import org.junit.Test
import java.util.*

/**
 * Test to verify that the new hashtag structure works correctly with pubsub:// deep links
 * This tests the compatibility without relying on Android-specific Base64 implementation
 */
class DeepLinkCompatibilityTest {
    
    private val gson = GsonBuilder()
        .registerTypeAdapter(NostrFilter::class.java, NostrFilterSerializer())
        .create()
    
    @Test
    fun testHashtagFilterSerialization() {
        // Create a filter with new hashtag structure
        val filter = NostrFilter(
            kinds = listOf(1),
            hashtagEntries = listOf(
                HashtagEntry("t", "bitcoin"),
                HashtagEntry("p", "nostr"),
                HashtagEntry("n", "test")
            ),
            authors = listOf("abc123def456"),
            limit = 20
        )
        
        // Serialize to JSON (this is what happens in deep links)
        val json = gson.toJson(filter)
        println("Generated JSON for deep link: $json")
        
        // Verify it produces the correct NIP-01 format
        assertTrue("Should contain #t field", json.contains("\"#t\":[\"bitcoin\"]"))
        assertTrue("Should contain #p field", json.contains("\"#p\":[\"nostr\"]"))
        assertTrue("Should contain #n field", json.contains("\"#n\":[\"test\"]"))
        assertTrue("Should contain authors field", json.contains("\"authors\":[\"abc123def456\"]"))
        assertTrue("Should contain kinds field", json.contains("\"kinds\":[1]"))
        assertTrue("Should contain limit field", json.contains("\"limit\":20"))
        
        // Verify internal fields are not leaked
        assertFalse("Should not contain hashtagEntries field", json.contains("hashtagEntries"))
    }
    
    @Test
    fun testHashtagFilterDeserialization() {
        // Simulate JSON that would come from a deep link (avoiding reserved #p)
        val json = """{"kinds":[1],"authors":["abc123def456"],"#t":["bitcoin"],"#n":["nostr"],"#l":["test"],"limit":20}"""
        
        // Parse it back (this is what happens when processing a deep link)
        val filter = gson.fromJson(json, NostrFilter::class.java)
        
        // Verify all data is correctly parsed
        assertEquals("Should have 3 hashtag entries", 3, filter.hashtagEntries?.size)
        assertEquals("Should have correct kinds", listOf(1), filter.kinds)
        assertEquals("Should have correct authors", listOf("abc123def456"), filter.authors)
        assertEquals("Should have correct limit", 20, filter.limit)
        
        // Verify hashtag entries are correctly reconstructed
        val hashtagMap = filter.hashtagEntries?.associateBy { it.tag }
        assertEquals("bitcoin", hashtagMap?.get("t")?.value)
        assertEquals("nostr", hashtagMap?.get("n")?.value)
        assertEquals("test", hashtagMap?.get("l")?.value)
    }
    
    @Test
    fun testRoundTripCompatibility() {
        // Original filter with various hashtag types
        val originalFilter = NostrFilter(
            authors = listOf("deadbeef"),
            kinds = listOf(1, 7),
            hashtagEntries = listOf(
                HashtagEntry("t", "bitcoin"),
                HashtagEntry("n", "somevalue"),
                HashtagEntry("l", "label"),
                HashtagEntry("c", "category")
            ),
            limit = 50
        )
        
        // Serialize (as would happen when creating deep link)
        val json = gson.toJson(originalFilter)
        println("Round-trip JSON: $json")
        
        // Deserialize (as would happen when processing deep link)
        val deserializedFilter = gson.fromJson(json, NostrFilter::class.java)
        
        // Verify all data is preserved
        assertEquals("Authors should be preserved", originalFilter.authors, deserializedFilter.authors)
        assertEquals("Kinds should be preserved", originalFilter.kinds, deserializedFilter.kinds)
        assertEquals("Limit should be preserved", originalFilter.limit, deserializedFilter.limit)
        assertEquals("Hashtag entry count should be preserved", 
                    4, deserializedFilter.hashtagEntries?.size)
        
        // Check all hashtag entries are preserved
        val originalMap = originalFilter.hashtagEntries?.associateBy { it.tag }
        val deserializedMap = deserializedFilter.hashtagEntries?.associateBy { it.tag }
        
        originalMap?.forEach { (tag, entry) ->
            assertEquals("Tag $tag value should be preserved", 
                        entry.value, deserializedMap?.get(tag)?.value)
        }
    }
    
    @Test
    fun testEmptyHashtagEntries() {
        // Test filter with no hashtag entries
        val filter = NostrFilter(
            kinds = listOf(1),
            authors = listOf("testauthor")
        )
        
        val json = gson.toJson(filter)
        println("Empty hashtags JSON: $json")
        
        // Should not contain any hashtag fields
        assertFalse("Should not contain #t", json.contains("\"#t\""))
        assertFalse("Should not contain #p", json.contains("\"#p\""))
        assertFalse("Should not contain hashtagEntries", json.contains("hashtagEntries"))
        
        val deserializedFilter = gson.fromJson(json, NostrFilter::class.java)
        assertTrue("Should have null or empty hashtag entries", 
                   deserializedFilter.hashtagEntries.isNullOrEmpty())
    }
    
    @Test
    fun testMultipleValuesPerTag() {
        // Test filter with multiple values for the same tag
        val filter = NostrFilter(
            hashtagEntries = listOf(
                HashtagEntry("t", "bitcoin"),
                HashtagEntry("t", "nostr"),
                HashtagEntry("n", "alice"),
                HashtagEntry("n", "bob")
            )
        )
        
        val json = gson.toJson(filter)
        println("Multiple values JSON: $json")
        
        // Should group values by tag
        assertTrue("Should contain bitcoin and nostr in #t", 
                   json.contains("\"#t\":[\"bitcoin\",\"nostr\"]") ||
                   json.contains("\"#t\":[\"nostr\",\"bitcoin\"]"))
        assertTrue("Should contain alice and bob in #n", 
                   json.contains("\"#n\":[\"alice\",\"bob\"]") ||
                   json.contains("\"#n\":[\"bob\",\"alice\"]"))
        
        val deserializedFilter = gson.fromJson(json, NostrFilter::class.java)
        assertEquals("Should have 4 hashtag entries", 4, deserializedFilter.hashtagEntries?.size)
    }
    
    @Test
    fun testTagValidation() {
        // Test that only valid single-letter tags work
        val validEntry = HashtagEntry("t", "bitcoin")
        val validEntry2 = HashtagEntry("l", "label")
        val invalidEntry = HashtagEntry("tag", "bitcoin") // Too long
        val invalidEntry2 = HashtagEntry("1", "bitcoin") // Number
        val reservedEntry1 = HashtagEntry("e", "eventref") // Reserved for event refs
        val reservedEntry2 = HashtagEntry("p", "pubkey") // Reserved for pubkey refs
        val reservedEntry3 = HashtagEntry("E", "eventref") // Reserved (uppercase)
        val reservedEntry4 = HashtagEntry("P", "pubkey") // Reserved (uppercase)
        
        assertTrue("Valid entry 't' should be valid", validEntry.isValid())
        assertTrue("Valid entry 'l' should be valid", validEntry2.isValid())
        assertFalse("Multi-char tag should be invalid", invalidEntry.isValid())
        assertFalse("Numeric tag should be invalid", invalidEntry2.isValid())
        assertFalse("Reserved tag 'e' should be invalid", reservedEntry1.isValid())
        assertFalse("Reserved tag 'p' should be invalid", reservedEntry2.isValid())
        assertFalse("Reserved tag 'E' should be invalid", reservedEntry3.isValid())
        assertFalse("Reserved tag 'P' should be invalid", reservedEntry4.isValid())
    }
    
    @Test
    fun testNIP01Compliance() {
        // Test full NIP-01 compliance with various field types
        val filter = NostrFilter(
            ids = listOf("eventid1", "eventid2"),
            authors = listOf("author1", "author2"), 
            kinds = listOf(1, 7),
            hashtagEntries = listOf(
                HashtagEntry("t", "bitcoin"),
                HashtagEntry("l", "somelabel") // Use non-conflicting tags
            ),
            since = 1234567890L,
            until = 1234567999L,
            limit = 100
        )
        
        val json = gson.toJson(filter)
        println("Full NIP-01 JSON: $json")
        
        // Verify all NIP-01 fields are present and properly formatted
        assertTrue("Should contain ids", json.contains("\"ids\":"))
        assertTrue("Should contain authors", json.contains("\"authors\":"))
        assertTrue("Should contain kinds", json.contains("\"kinds\":"))
        // Note: We removed eventRefs and pubkeyRefs to avoid conflicts with hashtag entries
        assertTrue("Should contain #t for hashtags", json.contains("\"#t\":[\"bitcoin\"]"))
        assertTrue("Should contain #l for hashtag entries", json.contains("\"#l\":[\"somelabel\"]"))
        assertTrue("Should contain since", json.contains("\"since\":1234567890"))
        assertTrue("Should contain until", json.contains("\"until\":1234567999"))
        assertTrue("Should contain limit", json.contains("\"limit\":100"))
        
        // Parse back and verify
        val parsedFilter = gson.fromJson(json, NostrFilter::class.java)
        assertNotNull("Parsed filter should not be null", parsedFilter)
        println("Parsed hashtag entries: ${parsedFilter.hashtagEntries}")
        println("Hashtag entries size: ${parsedFilter.hashtagEntries?.size}")
        assertEquals("Should have correct number of hashtag entries", 2, parsedFilter.hashtagEntries?.size)
    }
    
    @Test
    fun testReservedTagsHandling() {
        // Test that #e and #p tags are properly handled as standard NIP-01 fields
        val jsonWithReservedTags = """{"kinds":[1],"#e":["eventref"],"#p":["pubkey"],"#t":["bitcoin"],"#l":["label"]}"""
        
        val filter = gson.fromJson(jsonWithReservedTags, NostrFilter::class.java)
        
        // Standard NIP-01 fields should be populated correctly
        assertEquals("Should have eventRefs from #e", listOf("eventref"), filter.eventRefs)
        assertEquals("Should have pubkeyRefs from #p", listOf("pubkey"), filter.pubkeyRefs)
        
        // Only non-standard hashtag entries should be included in hashtagEntries
        assertEquals("Should have 2 hashtag entries", 2, filter.hashtagEntries?.size)
        
        val hashtagMap = filter.hashtagEntries?.associateBy { it.tag }
        assertEquals("Should have 't' tag", "bitcoin", hashtagMap?.get("t")?.value)
        assertEquals("Should have 'l' tag", "label", hashtagMap?.get("l")?.value)
        assertNull("Should not have 'e' in hashtag entries (goes to eventRefs)", hashtagMap?.get("e"))
        assertNull("Should not have 'p' in hashtag entries (goes to pubkeyRefs)", hashtagMap?.get("p"))
    }
    
    @Test 
    fun testDeepLinkIntegration() {
        // This simulates the complete deep link workflow
        
        // 1. App creates a filter with hashtag entries (avoiding reserved tags)
        val originalFilter = NostrFilter(
            kinds = listOf(1),
            authors = listOf("abcd1234"),
            hashtagEntries = listOf(
                HashtagEntry("t", "bitcoin"),
                HashtagEntry("n", "nostr"),
                HashtagEntry("l", "test")
            ),
            limit = 25
        )
        
        // 2. Serialize for deep link (this is what apps would send in pubsub:// URIs)
        val json = gson.toJson(originalFilter)
        println("Deep link filter JSON: $json")
        
        // 3. Verify proper NIP-01 format
        assertTrue("Should contain #t", json.contains("\"#t\":[\"bitcoin\"]"))
        assertTrue("Should contain #n", json.contains("\"#n\":[\"nostr\"]"))
        assertTrue("Should contain #l", json.contains("\"#l\":[\"test\"]"))
        assertFalse("Should not leak internal fields", json.contains("hashtagEntries"))
        
        // 4. Parse back (this is what would happen when processing the deep link)
        val parsedFilter = gson.fromJson(json, NostrFilter::class.java)
        
        // 5. Verify complete round-trip compatibility
        assertEquals("Kinds should match", originalFilter.kinds, parsedFilter.kinds)
        assertEquals("Authors should match", originalFilter.authors, parsedFilter.authors)
        assertEquals("Limit should match", originalFilter.limit, parsedFilter.limit)
        assertEquals("Hashtag entry count should match", 
                    originalFilter.hashtagEntries?.size, parsedFilter.hashtagEntries?.size)
        
        // 6. Verify hashtag entries are correctly reconstructed
        val originalMap = originalFilter.hashtagEntries?.associateBy { it.tag }
        val parsedMap = parsedFilter.hashtagEntries?.associateBy { it.tag }
        
        originalMap?.forEach { (tag, entry) ->
            assertEquals("Tag $tag should have correct value", 
                        entry.value, parsedMap?.get(tag)?.value)
        }
        
        println("✅ Deep link integration test passed - hashtag structure is fully compatible!")
    }
    
    @Test
    fun testMultipleValuesPerTagInDeepLinks() {
        // Test that multiple values per tag work correctly in deep links
        val filterWithMultipleValues = NostrFilter(
            kinds = listOf(1),
            hashtagEntries = listOf(
                HashtagEntry("t", "bitcoin"),
                HashtagEntry("t", "nostr"), 
                HashtagEntry("t", "lightning"),
                HashtagEntry("l", "news"),
                HashtagEntry("l", "analysis")
            )
        )
        
        // Serialize for deep link
        val json = gson.toJson(filterWithMultipleValues)
        println("Multiple values JSON: $json")
        
        // Should group values by tag correctly
        assertTrue("Should contain bitcoin, nostr, lightning in #t", 
                   json.contains("\"#t\":[\"bitcoin\",\"nostr\",\"lightning\"]") ||
                   json.contains("\"#t\":[\"bitcoin\",\"lightning\",\"nostr\"]") ||
                   json.contains("\"#t\":[\"nostr\",\"bitcoin\",\"lightning\"]") ||
                   json.contains("\"#t\":[\"nostr\",\"lightning\",\"bitcoin\"]") ||
                   json.contains("\"#t\":[\"lightning\",\"bitcoin\",\"nostr\"]") ||
                   json.contains("\"#t\":[\"lightning\",\"nostr\",\"bitcoin\"]"))
        
        assertTrue("Should contain news, analysis in #l",
                   json.contains("\"#l\":[\"news\",\"analysis\"]") ||
                   json.contains("\"#l\":[\"analysis\",\"news\"]"))
        
        // Parse back
        val parsedFilter = gson.fromJson(json, NostrFilter::class.java)
        
        // Should have all 5 hashtag entries
        assertEquals("Should have 5 hashtag entries", 5, parsedFilter.hashtagEntries?.size)
        
        // Verify by grouping
        val parsedByTag = parsedFilter.hashtagEntries?.groupBy { it.tag }
        
        val tValues = parsedByTag?.get("t")?.map { it.value }?.sorted()
        val lValues = parsedByTag?.get("l")?.map { it.value }?.sorted()
        
        assertEquals("Should have 3 't' values", listOf("bitcoin", "lightning", "nostr"), tValues)
        assertEquals("Should have 2 'l' values", listOf("analysis", "news"), lValues)
        
        println("✅ Multiple values per tag work correctly in deep links!")
    }
}
