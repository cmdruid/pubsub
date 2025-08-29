package com.cmdruid.pubsub.ui

import com.cmdruid.pubsub.data.HashtagEntry
import com.cmdruid.pubsub.nostr.NostrFilter
import com.cmdruid.pubsub.nostr.NostrFilterSerializer
import com.google.gson.GsonBuilder
import org.junit.Assert.*
import org.junit.Test

/**
 * Test the simplified hashtag approach where users can add duplicate tags
 * and they get consolidated during serialization
 */
class SimplifiedHashtagTest {
    
    private val gson = GsonBuilder()
        .registerTypeAdapter(NostrFilter::class.java, NostrFilterSerializer())
        .create()
    
    @Test
    fun testDuplicateTagConsolidation() {
        // Simulate user adding multiple entries with same tag - using non-reserved tags
        val hashtagEntries = listOf(
            HashtagEntry("n", "bitcoin"),
            HashtagEntry("n", "nostr"),
            HashtagEntry("n", "lightning"),
            HashtagEntry("l", "news"),
            HashtagEntry("l", "analysis"),
            HashtagEntry("c", "satoshi")
        )
        
        val filter = NostrFilter(
            kinds = listOf(1),
            hashtagEntries = hashtagEntries
        )
        
        // Serialize to JSON
        val json = gson.toJson(filter)
        println("Consolidated JSON: $json")
        
        // Should contain consolidated arrays
        assertTrue("Should consolidate 'n' tags", 
                   json.contains("\"#n\":[\"bitcoin\",\"nostr\",\"lightning\"]") ||
                   json.contains("\"#n\":[\"bitcoin\",\"lightning\",\"nostr\"]") ||
                   json.contains("\"#n\":[\"nostr\",\"bitcoin\",\"lightning\"]") ||
                   json.contains("\"#n\":[\"nostr\",\"lightning\",\"bitcoin\"]") ||
                   json.contains("\"#n\":[\"lightning\",\"bitcoin\",\"nostr\"]") ||
                   json.contains("\"#n\":[\"lightning\",\"nostr\",\"bitcoin\"]"))
        
        assertTrue("Should consolidate 'l' tags",
                   json.contains("\"#l\":[\"news\",\"analysis\"]") ||
                   json.contains("\"#l\":[\"analysis\",\"news\"]"))
        
        assertTrue("Should include single 'c' tag", json.contains("\"#c\":[\"satoshi\"]"))
        
        // Parse back
        val parsedFilter = gson.fromJson(json, NostrFilter::class.java)
        
        // Should have all original entries (or equivalent)
        assertEquals("Should have 6 hashtag entries", 6, parsedFilter.hashtagEntries?.size)
        
        // Verify consolidated correctly
        val tagGroups = parsedFilter.hashtagEntries?.groupBy { it.tag }
        assertEquals("Should have 3 unique tags", 3, tagGroups?.size)
        assertEquals("Should have 3 'n' values", 3, tagGroups?.get("n")?.size)
        assertEquals("Should have 2 'l' values", 2, tagGroups?.get("l")?.size)
        assertEquals("Should have 1 'c' value", 1, tagGroups?.get("c")?.size)
        
        // Verify actual values
        val nValues = tagGroups?.get("n")?.map { it.value }?.sorted()
        val lValues = tagGroups?.get("l")?.map { it.value }?.sorted()
        val cValues = tagGroups?.get("c")?.map { it.value }
        
        assertEquals(listOf("bitcoin", "lightning", "nostr"), nValues)
        assertEquals(listOf("analysis", "news"), lValues)
        assertEquals(listOf("satoshi"), cValues)
        
        println("✅ Simplified hashtag consolidation works correctly!")
    }
    
    @Test
    fun testSimplifiedUIWorkflow() {
        // Simulate user adding hashtags in UI (including duplicates)
        val userEntries = mutableListOf<HashtagEntry>()
        
        // User adds entries one by one (some with same tag) - using non-reserved tags
        userEntries.add(HashtagEntry("n", "bitcoin"))
        userEntries.add(HashtagEntry("l", "news"))
        userEntries.add(HashtagEntry("n", "nostr"))  // Same tag as first
        userEntries.add(HashtagEntry("l", "analysis")) // Same tag as second
        userEntries.add(HashtagEntry("n", "lightning")) // Same tag again
        
        // Filter out invalid entries (like the adapter would)
        val validEntries = userEntries.filter { it.isValid() }
        assertEquals("Should have 5 valid entries", 5, validEntries.size)
        
        // Create filter and serialize (like save process would)
        val filter = NostrFilter(hashtagEntries = validEntries)
        val json = gson.toJson(filter)
        
        // Should work correctly with consolidation
        assertTrue("Should contain all 'n' values", 
                   json.contains("bitcoin") && json.contains("nostr") && json.contains("lightning"))
        assertTrue("Should contain all 'l' values",
                   json.contains("news") && json.contains("analysis"))
        
        println("✅ Simplified UI workflow works correctly!")
    }
    
    @Test
    fun testEmptyAndInvalidEntries() {
        val mixedEntries = listOf(
            HashtagEntry("n", "bitcoin"),     // Valid - using non-reserved tag
            HashtagEntry("", "nostr"),        // Invalid - empty tag
            HashtagEntry("n", ""),            // Invalid - empty value
            HashtagEntry("invalid", "test"),  // Invalid - multi-char tag
            HashtagEntry("l", "news"),        // Valid
            HashtagEntry("e", "test"),        // Invalid - reserved tag
            HashtagEntry("p", "test"),        // Invalid - reserved tag
            HashtagEntry("t", "test")         // Invalid - reserved tag
        )
        
        val validEntries = mixedEntries.filter { it.isValid() }
        assertEquals("Should have 2 valid entries", 2, validEntries.size)
        
        val filter = NostrFilter(hashtagEntries = validEntries)
        val json = gson.toJson(filter)
        
        // Should only include valid entries
        assertTrue("Should include bitcoin", json.contains("bitcoin"))
        assertTrue("Should include news", json.contains("news"))
        assertFalse("Should not include invalid entries", json.contains("invalid"))
        
        println("✅ Invalid entry filtering works correctly!")
    }
}
