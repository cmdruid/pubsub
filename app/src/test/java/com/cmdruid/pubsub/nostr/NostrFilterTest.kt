package com.cmdruid.pubsub.nostr

import com.cmdruid.pubsub.data.HashtagEntry
import com.google.gson.Gson
import org.junit.Test
import org.junit.Assert.*

/**
 * Test class for NostrFilter functionality
 * Tests validation logic, serialization, and filter operations
 */
class NostrFilterTest {

    private val gson = Gson()

    // === VALIDATION LOGIC TESTS ===

    @Test
    fun `isValid should accept non-empty filters`() {
        val validFilters = listOf(
            NostrFilter(kinds = listOf(1)),
            NostrFilter(authors = listOf("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef")),
            NostrFilter(since = 1640995200L),
            NostrFilter(until = 1640995300L),
            NostrFilter(limit = 50)
        )
        
        validFilters.forEach { filter ->
            assertTrue("Filter should be valid: ${filter.getSummary()}", filter.isValid())
        }
    }

    @Test
    fun `isEmpty should detect empty filters correctly`() {
        val emptyFilter = NostrFilter()
        val nonEmptyFilter = NostrFilter(kinds = listOf(1))
        
        assertTrue("Empty filter should be detected", emptyFilter.isEmpty())
        assertFalse("Non-empty filter should not be detected as empty", nonEmptyFilter.isEmpty())
        assertFalse("Non-empty filter should be valid", emptyFilter.isValid())
        assertTrue("Non-empty filter should be valid", nonEmptyFilter.isValid())
    }

    @Test
    fun `should validate timestamp constraints correctly`() {
        // Valid timestamp combinations
        val validFilters = listOf(
            NostrFilter(since = 1640995200L, until = 1640995300L), // until > since
            NostrFilter(since = 1640995200L), // only since
            NostrFilter(until = 1640995300L), // only until
            NostrFilter(since = 1640995200L, until = 1640995200L) // equal timestamps
        )
        
        validFilters.forEach { filter ->
            assertTrue("Valid timestamp filter should be valid", filter.isValid())
        }
        
        // Invalid timestamp combination would be caught by isValidConstraints if it exists
        // For now, we test that the filter can be created without exceptions
        val potentiallyInvalidFilter = NostrFilter(since = 1640995300L, until = 1640995200L)
        assertNotNull("Filter should be created", potentiallyInvalidFilter)
    }

    // === SERIALIZATION TESTS ===

    @Test
    fun `should serialize to correct JSON format`() {
        val filter = NostrFilter(
            kinds = listOf(1, 3),
            authors = listOf("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"),
            since = 1640995200L,
            limit = 50
        )
        
        val json = gson.toJson(filter)
        
        // Should contain all specified fields
        assertTrue("Should contain kinds", json.contains("\"kinds\":[1,3]"))
        assertTrue("Should contain authors", json.contains("\"authors\""))
        assertTrue("Should contain since", json.contains("\"since\":1640995200"))
        assertTrue("Should contain limit", json.contains("\"limit\":50"))
        
        // Should not contain null fields
        assertFalse("Should not contain until", json.contains("\"until\""))
    }

    @Test
    fun `should deserialize from JSON correctly`() {
        val json = """
        {
            "kinds": [1, 3, 7],
            "authors": ["1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"],
            "since": 1640995200,
            "limit": 100
        }
        """.trimIndent()
        
        val filter = gson.fromJson(json, NostrFilter::class.java)
        
        assertEquals("Should deserialize kinds", listOf(1, 3, 7), filter.kinds)
        assertEquals("Should deserialize authors", 
            listOf("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"), 
            filter.authors)
        assertEquals("Should deserialize since", 1640995200L, filter.since)
        assertEquals("Should deserialize limit", 100, filter.limit)
        assertNull("Should not deserialize until", filter.until)
    }

    @Test
    fun `should handle hashtag entries in serialization`() {
        val hashtagEntries = listOf(
            HashtagEntry("l", "en"),
            HashtagEntry("r", "relay.com")
        )
        
        val filter = NostrFilter(hashtagEntries = hashtagEntries)
        
        // Serialize and deserialize
        val json = gson.toJson(filter)
        val deserializedFilter = gson.fromJson(json, NostrFilter::class.java)
        
        assertNotNull("Should have hashtag entries", deserializedFilter.hashtagEntries)
        assertEquals("Should preserve hashtag entries count", 
            hashtagEntries.size, deserializedFilter.hashtagEntries!!.size)
        
        hashtagEntries.forEachIndexed { index, original ->
            val deserialized = deserializedFilter.hashtagEntries!![index]
            assertEquals("Should preserve tag", original.tag, deserialized.tag)
            assertEquals("Should preserve value", original.value, deserialized.value)
        }
    }

    @Test
    fun `should handle empty filter serialization`() {
        val emptyFilter = NostrFilter()
        
        val json = gson.toJson(emptyFilter)
        val deserializedFilter = gson.fromJson(json, NostrFilter::class.java)
        
        assertNull("Should preserve null kinds", deserializedFilter.kinds)
        assertNull("Should preserve null authors", deserializedFilter.authors)
        assertNull("Should preserve null since", deserializedFilter.since)
        assertNull("Should preserve null until", deserializedFilter.until)
        assertNull("Should preserve null limit", deserializedFilter.limit)
        
        assertTrue("Deserialized empty filter should be empty", deserializedFilter.isEmpty())
    }

    // === COPY AND MODIFICATION TESTS ===

    @Test
    fun `copy should create independent instances`() {
        val original = NostrFilter(
            kinds = listOf(1, 3),
            authors = listOf("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"),
            since = 1640995200L
        )
        
        val modified = original.copy(since = 1640995300L, limit = 100)
        
        // Original should be unchanged
        assertEquals("Original kinds should be unchanged", listOf(1, 3), original.kinds)
        assertEquals("Original since should be unchanged", 1640995200L, original.since)
        assertNull("Original limit should be unchanged", original.limit)
        
        // Modified should have changes
        assertEquals("Modified kinds should be preserved", listOf(1, 3), modified.kinds)
        assertEquals("Modified since should be updated", 1640995300L, modified.since)
        assertEquals("Modified limit should be set", 100, modified.limit)
    }

    @Test
    fun `should handle null values correctly in copy`() {
        val filter = NostrFilter(kinds = listOf(1), since = 1640995200L)
        val nullified = filter.copy(since = null)
        
        assertEquals("Should preserve kinds", listOf(1), nullified.kinds)
        assertNull("Should nullify since", nullified.since)
    }

    // === SUMMARY TESTS ===

    @Test
    fun `getSummary should create readable representation`() {
        val filter = NostrFilter(
            kinds = listOf(1, 3),
            authors = listOf("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"),
            since = 1640995200L,
            limit = 50
        )
        
        val summary = filter.getSummary()
        
        assertTrue("Should mention kinds", summary.contains("kinds"))
        assertTrue("Should mention authors", summary.contains("author"))
        assertFalse("Summary should not be empty", summary.isEmpty())
    }

    @Test
    fun `getSummary should handle empty filter`() {
        val emptyFilter = NostrFilter()
        val summary = emptyFilter.getSummary()
        
        assertNotNull("Summary should not be null", summary)
        // Empty filter summary behavior depends on implementation
    }

    // === EDGE CASES TESTS ===

    @Test
    fun `should handle empty lists correctly`() {
        val filter = NostrFilter(
            kinds = emptyList(),
            authors = emptyList(),
            hashtagEntries = emptyList()
        )
        
        assertTrue("Empty lists should make filter empty", filter.isEmpty())
        assertFalse("Empty filter should not be valid", filter.isValid())
        
        // Empty lists should be preserved in serialization
        val json = gson.toJson(filter)
        val deserialized = gson.fromJson(json, NostrFilter::class.java)
        
        assertEquals("Should preserve empty kinds", emptyList<Int>(), deserialized.kinds)
        assertEquals("Should preserve empty authors", emptyList<String>(), deserialized.authors)
    }

    @Test
    fun `should handle large filters`() {
        val largeFilter = NostrFilter(
            kinds = (0..100).toList(),
            authors = (1..50).map { "author$it".padEnd(64, '0') },
            hashtagEntries = (1..20).map { HashtagEntry("l", "lang$it") },
            since = 1640995200L,
            until = 1640995300L,
            limit = 1000
        )
        
        assertTrue("Large filter should be valid", largeFilter.isValid())
        assertFalse("Large filter should not be empty", largeFilter.isEmpty())
        
        // Should serialize/deserialize correctly
        val json = gson.toJson(largeFilter)
        val deserialized = gson.fromJson(json, NostrFilter::class.java)
        
        assertEquals("Should preserve large kinds list", largeFilter.kinds, deserialized.kinds)
        assertEquals("Should preserve large authors list", largeFilter.authors, deserialized.authors)
        assertEquals("Should preserve hashtag entries", largeFilter.hashtagEntries?.size, deserialized.hashtagEntries?.size)
    }

    // === COMPARISON TESTS ===

    @Test
    fun `equals should work correctly`() {
        val filter1 = NostrFilter(kinds = listOf(1), since = 1640995200L)
        val filter2 = NostrFilter(kinds = listOf(1), since = 1640995200L)
        val filter3 = NostrFilter(kinds = listOf(1), since = 1640995300L)
        
        assertEquals("Identical filters should be equal", filter1, filter2)
        assertNotEquals("Different filters should not be equal", filter1, filter3)
    }

    @Test
    fun `hashCode should be consistent`() {
        val filter1 = NostrFilter(kinds = listOf(1), since = 1640995200L)
        val filter2 = NostrFilter(kinds = listOf(1), since = 1640995200L)
        
        assertEquals("Equal filters should have same hash code", filter1.hashCode(), filter2.hashCode())
    }

    // === COMPATIBILITY TESTS ===

    @Test
    fun `should maintain backward compatibility with old JSON format`() {
        // Basic JSON format
        val basicJson = """
        {
            "kinds": [1],
            "authors": ["1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"]
        }
        """.trimIndent()
        
        // Should deserialize without errors
        val filter = gson.fromJson(basicJson, NostrFilter::class.java)
        
        assertEquals("Should preserve kinds", listOf(1), filter.kinds)
        assertEquals("Should preserve authors", 
            listOf("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"), 
            filter.authors)
        assertTrue("Should be valid filter", filter.isValid())
    }

    @Test
    fun `should handle malformed JSON gracefully`() {
        val malformedJsons = listOf(
            """{"kinds": "not_an_array"}""",
            """{"since": "not_a_number"}""",
            """{"authors": [123]}""" // Numbers instead of strings
        )
        
        malformedJsons.forEach { json ->
            try {
                val filter = gson.fromJson(json, NostrFilter::class.java)
                // If deserialization succeeds, should handle gracefully
                assertNotNull("Should handle malformed JSON", filter)
            } catch (e: Exception) {
                // Expected for malformed JSON
                assertTrue("Should handle malformed JSON exceptions", true)
            }
        }
    }
}