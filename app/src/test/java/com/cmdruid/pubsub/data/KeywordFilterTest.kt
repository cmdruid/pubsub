package com.cmdruid.pubsub.data

import org.junit.Test
import org.junit.Assert.*

/**
 * Test class for KeywordFilter functionality
 */
class KeywordFilterTest {

    @Test
    fun testEmptyKeywordFilter() {
        val filter = KeywordFilter.empty()
        assertTrue(filter.isEmpty())
        assertFalse(filter.isValid())
        assertFalse(filter.matches("test content"))
    }

    @Test
    fun testKeywordFilterFromList() {
        val keywords = listOf("bitcoin", "nostr", "lightning")
        val filter = KeywordFilter.from(keywords)
        
        assertFalse(filter.isEmpty())
        assertTrue(filter.isValid())
        assertEquals(3, filter.keywords.size)
        assertTrue(filter.keywords.contains("bitcoin"))
        assertTrue(filter.keywords.contains("nostr"))
        assertTrue(filter.keywords.contains("lightning"))
    }

    @Test
    fun testKeywordFilterFromString() {
        val keywordsString = "bitcoin, nostr, lightning"
        val filter = KeywordFilter.fromString(keywordsString)
        
        assertFalse(filter.isEmpty())
        assertTrue(filter.isValid())
        assertEquals(3, filter.keywords.size)
        assertTrue(filter.keywords.contains("bitcoin"))
        assertTrue(filter.keywords.contains("nostr"))
        assertTrue(filter.keywords.contains("lightning"))
    }

    @Test
    fun testKeywordMatching() {
        val filter = KeywordFilter.from(listOf("bitcoin", "nostr"))
        
        // Should match
        assertTrue(filter.matches("I love bitcoin"))
        assertTrue(filter.matches("This is about nostr"))
        assertTrue(filter.matches("Bitcoin is great"))
        assertTrue(filter.matches("NOSTR rocks"))
        
        // Should not match
        assertFalse(filter.matches("ethereum only"))
        assertFalse(filter.matches("bit coin"))
        assertFalse(filter.matches("nostril"))
        assertFalse(filter.matches(""))
    }

    @Test
    fun testWordBoundaryMatching() {
        val filter = KeywordFilter.from(listOf("cat"))
        
        // Should match
        assertTrue(filter.matches("I have a cat"))
        assertTrue(filter.matches("cat is cute"))
        assertTrue(filter.matches("The cat."))
        
        // Should not match partial words
        assertFalse(filter.matches("catalog"))
        assertFalse(filter.matches("concatenate"))
        assertFalse(filter.matches("scatter"))
    }

    @Test
    fun testKeywordSanitization() {
        val dirtyKeywords = listOf("  bitcoin  ", "", "   ", "a", "nostr", "x".repeat(150))
        val filter = KeywordFilter.from(dirtyKeywords)
        
        // Should only have valid keywords (long keyword gets truncated to 100 chars)
        assertEquals(3, filter.keywords.size)
        assertTrue(filter.keywords.contains("bitcoin"))
        assertTrue(filter.keywords.contains("nostr"))
        assertFalse(filter.keywords.contains("a"))  // Too short
        assertFalse(filter.keywords.contains(""))   // Empty
        // Long keyword should be truncated to 100 characters
        assertTrue(filter.keywords.any { it.length == 100 && it.all { c -> c == 'x' } })
    }

    @Test
    fun testAddKeyword() {
        val filter = KeywordFilter.empty()
            .addKeyword("bitcoin")
            .addKeyword("nostr")
        
        assertEquals(2, filter.keywords.size)
        assertTrue(filter.keywords.contains("bitcoin"))
        assertTrue(filter.keywords.contains("nostr"))
    }

    @Test
    fun testRemoveKeyword() {
        val filter = KeywordFilter.from(listOf("bitcoin", "nostr", "lightning"))
            .removeKeyword("nostr")
        
        assertEquals(2, filter.keywords.size)
        assertTrue(filter.keywords.contains("bitcoin"))
        assertTrue(filter.keywords.contains("lightning"))
        assertFalse(filter.keywords.contains("nostr"))
    }

    @Test
    fun testToKeywordsString() {
        val filter = KeywordFilter.from(listOf("bitcoin", "nostr", "lightning"))
        val keywordsString = filter.toKeywordsString()
        
        assertEquals("bitcoin,nostr,lightning", keywordsString)
    }

    @Test
    fun testGetSummary() {
        val emptyFilter = KeywordFilter.empty()
        assertEquals("No keywords", emptyFilter.getSummary())
        
        val singleFilter = KeywordFilter.from(listOf("bitcoin"))
        assertEquals("keyword: \"bitcoin\"", singleFilter.getSummary())
        
        val multiFilter = KeywordFilter.from(listOf("bitcoin", "nostr"))
        assertEquals("2 keywords: \"bitcoin\", \"nostr\"", multiFilter.getSummary())
    }

    @Test
    fun testKeywordLimits() {
        // Test max keyword count
        val tooManyKeywords = (1..25).map { "keyword$it" }
        val filter = KeywordFilter.from(tooManyKeywords).sanitizeKeywords()
        assertEquals(20, filter.keywords.size) // Should be limited to max

        // Test keyword length limit
        val longKeyword = "x".repeat(150)
        val longFilter = KeywordFilter.from(listOf(longKeyword)).sanitizeKeywords()
        assertTrue(longFilter.keywords.first().length <= 100)
    }

    @Test
    fun testDuplicateKeywords() {
        val duplicateKeywords = listOf("bitcoin", "nostr", "bitcoin", "lightning", "nostr")
        val filter = KeywordFilter.from(duplicateKeywords).sanitizeKeywords()
        
        assertEquals(3, filter.keywords.size)
        assertTrue(filter.keywords.contains("bitcoin"))
        assertTrue(filter.keywords.contains("nostr"))
        assertTrue(filter.keywords.contains("lightning"))
    }
}
