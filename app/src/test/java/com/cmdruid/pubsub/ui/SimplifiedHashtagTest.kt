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
        // Simulate user adding multiple entries with same tag
        val hashtagEntries = listOf(
            HashtagEntry("t", "bitcoin"),
            HashtagEntry("t", "nostr"),
            HashtagEntry("t", "lightning"),
            HashtagEntry("l", "news"),
            HashtagEntry("l", "analysis"),
            HashtagEntry("n", "satoshi")
        )
        
        val filter = NostrFilter(
            kinds = listOf(1),
            hashtagEntries = hashtagEntries
        )
        
        // Serialize to JSON
        val json = gson.toJson(filter)
        println("Consolidated JSON: $json")
        
        // Should contain consolidated arrays
        assertTrue("Should consolidate 't' tags", 
                   json.contains("\"#t\":[\"bitcoin\",\"nostr\",\"lightning\"]") ||
                   json.contains("\"#t\":[\"bitcoin\",\"lightning\",\"nostr\"]") ||
                   json.contains("\"#t\":[\"nostr\",\"bitcoin\",\"lightning\"]") ||
                   json.contains("\"#t\":[\"nostr\",\"lightning\",\"bitcoin\"]") ||
                   json.contains("\"#t\":[\"lightning\",\"bitcoin\",\"nostr\"]") ||
                   json.contains("\"#t\":[\"lightning\",\"nostr\",\"bitcoin\"]"))
        
        assertTrue("Should consolidate 'l' tags",
                   json.contains("\"#l\":[\"news\",\"analysis\"]") ||
                   json.contains("\"#l\":[\"analysis\",\"news\"]"))
        
        assertTrue("Should include single 'n' tag", json.contains("\"#n\":[\"satoshi\"]"))
        
        // Parse back
        val parsedFilter = gson.fromJson(json, NostrFilter::class.java)
        
        // Should have all original entries (or equivalent)
        assertEquals("Should have 6 hashtag entries", 6, parsedFilter.hashtagEntries?.size)
        
        // Verify consolidated correctly
        val tagGroups = parsedFilter.hashtagEntries?.groupBy { it.tag }
        assertEquals("Should have 3 unique tags", 3, tagGroups?.size)
        assertEquals("Should have 3 't' values", 3, tagGroups?.get("t")?.size)
        assertEquals("Should have 2 'l' values", 2, tagGroups?.get("l")?.size)
        assertEquals("Should have 1 'n' value", 1, tagGroups?.get("n")?.size)
        
        // Verify actual values
        val tValues = tagGroups?.get("t")?.map { it.value }?.sorted()
        val lValues = tagGroups?.get("l")?.map { it.value }?.sorted()
        val nValues = tagGroups?.get("n")?.map { it.value }
        
        assertEquals(listOf("bitcoin", "lightning", "nostr"), tValues)
        assertEquals(listOf("analysis", "news"), lValues)
        assertEquals(listOf("satoshi"), nValues)
        
        println("✅ Simplified hashtag consolidation works correctly!")
    }
    
    @Test
    fun testSimplifiedUIWorkflow() {
        // Simulate user adding hashtags in UI (including duplicates)
        val userEntries = mutableListOf<HashtagEntry>()
        
        // User adds entries one by one (some with same tag)
        userEntries.add(HashtagEntry("t", "bitcoin"))
        userEntries.add(HashtagEntry("l", "news"))
        userEntries.add(HashtagEntry("t", "nostr"))  // Same tag as first
        userEntries.add(HashtagEntry("l", "analysis")) // Same tag as second
        userEntries.add(HashtagEntry("t", "lightning")) // Same tag again
        
        // Filter out invalid entries (like the adapter would)
        val validEntries = userEntries.filter { it.isValid() }
        assertEquals("Should have 5 valid entries", 5, validEntries.size)
        
        // Create filter and serialize (like save process would)
        val filter = NostrFilter(hashtagEntries = validEntries)
        val json = gson.toJson(filter)
        
        // Should work correctly with consolidation
        assertTrue("Should contain all 't' values", 
                   json.contains("bitcoin") && json.contains("nostr") && json.contains("lightning"))
        assertTrue("Should contain all 'l' values",
                   json.contains("news") && json.contains("analysis"))
        
        println("✅ Simplified UI workflow works correctly!")
    }
    
    @Test
    fun testEmptyAndInvalidEntries() {
        val mixedEntries = listOf(
            HashtagEntry("t", "bitcoin"),     // Valid
            HashtagEntry("", "nostr"),        // Invalid - empty tag
            HashtagEntry("t", ""),            // Invalid - empty value
            HashtagEntry("invalid", "test"),  // Invalid - multi-char tag
            HashtagEntry("l", "news"),        // Valid
            HashtagEntry("e", "test"),        // Invalid - reserved tag
            HashtagEntry("p", "test")         // Invalid - reserved tag
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
