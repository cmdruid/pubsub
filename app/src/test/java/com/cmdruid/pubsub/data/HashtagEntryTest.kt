package com.cmdruid.pubsub.data

import org.junit.Test
import org.junit.Assert.*

/**
 * Test class for HashtagEntry data model
 * Tests validation logic, filter key generation, and display strings
 */
class HashtagEntryTest {

    @Test
    fun `isValid should accept single letter tags`() {
        val validEntries = listOf(
            HashtagEntry("l", "en"),           // Language tag
            HashtagEntry("r", "relay.com"),    // Relay tag
            HashtagEntry("a", "address"),      // Address tag
            HashtagEntry("d", "identifier")    // Identifier tag
        )
        
        validEntries.forEach { entry ->
            assertTrue("Entry with tag '${entry.tag}' should be valid", entry.isValid())
        }
    }

    @Test
    fun `isValid should reject reserved tags`() {
        val reservedEntries = listOf(
            HashtagEntry("e", "event123"),      // Event tag (reserved)
            HashtagEntry("p", "pubkey123"),     // Pubkey tag (reserved)
            HashtagEntry("t", "hashtag"),       // Hashtag tag (reserved)
            HashtagEntry("", "value"),          // Empty tag
            HashtagEntry("ab", "value"),        // Multi-character tag
            HashtagEntry("1", "value")          // Numeric tag
        )
        
        reservedEntries.forEach { entry ->
            assertFalse("Entry with tag '${entry.tag}' should be invalid", entry.isValid())
        }
    }

    @Test
    fun `isValid should reject empty values`() {
        val invalidEntries = listOf(
            HashtagEntry("l", ""),              // Empty value
            HashtagEntry("l", "   "),           // Whitespace-only value
            HashtagEntry("r", "\n"),            // Newline value
            HashtagEntry("d", "\t")             // Tab value
        )
        
        invalidEntries.forEach { entry ->
            assertFalse("Entry with empty/whitespace value should be invalid: '${entry.value}'", entry.isValid())
        }
    }

    @Test
    fun `should generate correct filter keys`() {
        val entries = listOf(
            HashtagEntry("l", "en"),
            HashtagEntry("r", "relay.com"),
            HashtagEntry("d", "identifier")
        )
        
        entries.forEach { entry ->
            val filterKey = entry.getFilterKey()
            assertEquals("Filter key should be '#${entry.tag}'", "#${entry.tag}", filterKey)
        }
    }

    @Test
    fun `should create proper display strings`() {
        val testCases = listOf(
            Pair(HashtagEntry("l", "en"), "l: en"),
            Pair(HashtagEntry("r", "relay.example.com"), "r: relay.example.com"),
            Pair(HashtagEntry("d", "identifier123"), "d: identifier123")
        )
        
        testCases.forEach { (entry, expectedDisplay) ->
            val displayString = entry.getDisplayString()
            assertEquals("Display string for ${entry.tag}:${entry.value}", expectedDisplay, displayString)
        }
    }

    @Test
    fun `should handle special characters in values`() {
        val specialCharEntries = listOf(
            HashtagEntry("l", "en-US"),
            HashtagEntry("r", "relay.example.com:8080"),
            HashtagEntry("d", "test_identifier")
        )
        
        specialCharEntries.forEach { entry ->
            assertTrue("Entry with special chars should be valid: '${entry.value}'", entry.isValid())
            
            val filterKey = entry.getFilterKey()
            assertEquals("Should generate correct filter key", "#${entry.tag}", filterKey)
            
            val displayString = entry.getDisplayString()
            assertFalse("Display string should not be empty", displayString.isEmpty())
        }
    }

    @Test
    fun `should support equality and hashing`() {
        val entry1 = HashtagEntry("l", "en")
        val entry2 = HashtagEntry("l", "en")
        val entry3 = HashtagEntry("l", "es")
        val entry4 = HashtagEntry("r", "en") // Same value, different tag
        
        // Equality
        assertEquals("Identical entries should be equal", entry1, entry2)
        assertNotEquals("Different value entries should not be equal", entry1, entry3)
        assertNotEquals("Different tag entries should not be equal", entry1, entry4)
        
        // Hash codes
        assertEquals("Equal entries should have same hash code", entry1.hashCode(), entry2.hashCode())
    }

    @Test
    fun `should handle case sensitivity correctly`() {
        val lowerCaseEntry = HashtagEntry("l", "en")
        val upperCaseEntry = HashtagEntry("l", "EN")
        val mixedCaseEntry = HashtagEntry("l", "En")
        
        // All should be valid
        assertTrue("Lowercase should be valid", lowerCaseEntry.isValid())
        assertTrue("Uppercase should be valid", upperCaseEntry.isValid())
        assertTrue("Mixed case should be valid", mixedCaseEntry.isValid())
        
        // Should be treated as different values
        assertNotEquals("Case sensitive comparison", lowerCaseEntry, upperCaseEntry)
        assertNotEquals("Case sensitive comparison", lowerCaseEntry, mixedCaseEntry)
        
        // Display strings should preserve case
        assertEquals("Should preserve lowercase", "l: en", lowerCaseEntry.getDisplayString())
        assertEquals("Should preserve uppercase", "l: EN", upperCaseEntry.getDisplayString())
    }

    @Test
    fun `fromDisplayString should parse correctly`() {
        val testCases = listOf(
            Pair("l: en", HashtagEntry("l", "en")),
            Pair("r: relay.com", HashtagEntry("r", "relay.com")),
            Pair("d: test123", HashtagEntry("d", "test123"))
        )
        
        testCases.forEach { (displayString, expectedEntry) ->
            val parsedEntry = HashtagEntry.fromDisplayString(displayString)
            assertNotNull("Should parse: $displayString", parsedEntry)
            assertEquals("Should parse correctly", expectedEntry, parsedEntry)
        }
        
        // Invalid display strings
        val invalidDisplayStrings = listOf(
            "invalid",           // No colon
            "ab: value",         // Multi-character tag
            ": value",           // Empty tag
            "l:",                // Empty value
            "e: event123"        // Reserved tag
        )
        
        invalidDisplayStrings.forEach { displayString ->
            val parsedEntry = HashtagEntry.fromDisplayString(displayString)
            assertNull("Should not parse invalid: $displayString", parsedEntry)
        }
    }

    @Test
    fun `isValidTag should validate tag characters correctly`() {
        val validTags = listOf("l", "r", "d", "a", "c", "z", "A", "Z")
        val invalidTags = listOf("e", "p", "t", "E", "P", "T", "", "ab", "1", "@")
        
        validTags.forEach { tag ->
            assertTrue("Tag '$tag' should be valid", HashtagEntry.isValidTag(tag))
        }
        
        invalidTags.forEach { tag ->
            assertFalse("Tag '$tag' should be invalid", HashtagEntry.isValidTag(tag))
        }
    }

    @Test
    fun `should handle edge cases gracefully`() {
        // Test with various edge cases
        val edgeCases = listOf(
            HashtagEntry("l", "a"),              // Single character value
            HashtagEntry("r", "a".repeat(100)),  // Very long value
            HashtagEntry("d", "123"),            // Numeric value
            HashtagEntry("l", "test-value_123")  // Complex value
        )
        
        edgeCases.forEach { entry ->
            // Should not throw exceptions
            val isValid = entry.isValid()
            val filterKey = entry.getFilterKey()
            val displayString = entry.getDisplayString()
            
            assertNotNull("Filter key should not be null", filterKey)
            assertNotNull("Display string should not be null", displayString)
        }
    }
}