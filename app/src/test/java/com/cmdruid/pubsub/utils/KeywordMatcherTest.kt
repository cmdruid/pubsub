package com.cmdruid.pubsub.utils

import com.cmdruid.pubsub.data.KeywordFilter
import org.junit.Test
import org.junit.Assert.*

/**
 * Test class for KeywordMatcher utility functionality
 */
class KeywordMatcherTest {

    @Test
    fun testMatchesWithKeywordFilter() {
        val filter = KeywordFilter.from(listOf("bitcoin", "nostr"))
        
        // Should match
        assertTrue(KeywordMatcher.matches("I love bitcoin!", filter))
        assertTrue(KeywordMatcher.matches("This is about nostr development", filter))
        assertTrue(KeywordMatcher.matches("BITCOIN is the future", filter))
        
        // Should not match
        assertFalse(KeywordMatcher.matches("ethereum only here", filter))
        assertFalse(KeywordMatcher.matches("", filter))
        assertFalse(KeywordMatcher.matches("bit coin", filter))
    }

    @Test
    fun testMatchesWithKeywordList() {
        val keywords = listOf("lightning", "network")
        
        // Should match
        assertTrue(KeywordMatcher.matches("Lightning Network is fast", keywords))
        assertTrue(KeywordMatcher.matches("network effects are powerful", keywords))
        
        // Should not match
        assertFalse(KeywordMatcher.matches("bitcoin only", keywords))
        assertFalse(KeywordMatcher.matches("", keywords))
    }

    @Test
    fun testMatchesKeyword() {
        // Basic matching
        assertTrue(KeywordMatcher.matchesKeyword("I have bitcoin", "bitcoin"))
        assertTrue(KeywordMatcher.matchesKeyword("Bitcoin is great", "bitcoin"))
        
        // Word boundary testing
        assertTrue(KeywordMatcher.matchesKeyword("The cat is cute", "cat"))
        assertFalse(KeywordMatcher.matchesKeyword("catalog is here", "cat"))
        assertFalse(KeywordMatcher.matchesKeyword("concatenate strings", "cat"))
        
        // Case insensitive
        assertTrue(KeywordMatcher.matchesKeyword("BITCOIN rocks", "bitcoin"))
        assertTrue(KeywordMatcher.matchesKeyword("bitcoin rocks", "BITCOIN"))
    }

    @Test
    fun testShouldProcessContent() {
        // Valid content
        assertTrue(KeywordMatcher.shouldProcessContent("This is valid content"))
        assertTrue(KeywordMatcher.shouldProcessContent("Hi"))
        
        // Invalid content
        assertFalse(KeywordMatcher.shouldProcessContent(""))
        assertFalse(KeywordMatcher.shouldProcessContent("   "))
        assertFalse(KeywordMatcher.shouldProcessContent(null))
        assertFalse(KeywordMatcher.shouldProcessContent("x"))  // Too short
    }

    @Test
    fun testFindMatchingKeywords() {
        val keywords = listOf("bitcoin", "nostr", "lightning", "network")
        
        // Multiple matches
        val content1 = "Bitcoin and Lightning Network are revolutionary"
        val matches1 = KeywordMatcher.findMatchingKeywords(content1, keywords)
        assertEquals(3, matches1.size)
        assertTrue(matches1.contains("bitcoin"))
        assertTrue(matches1.contains("lightning"))
        assertTrue(matches1.contains("network"))
        
        // Single match
        val content2 = "This is about nostr development"
        val matches2 = KeywordMatcher.findMatchingKeywords(content2, keywords)
        assertEquals(1, matches2.size)
        assertTrue(matches2.contains("nostr"))
        
        // No matches
        val content3 = "ethereum and solana discussion"
        val matches3 = KeywordMatcher.findMatchingKeywords(content3, keywords)
        assertEquals(0, matches3.size)
    }

    @Test
    fun testFindMatchingKeywordsWithFilter() {
        val filter = KeywordFilter.from(listOf("bitcoin", "nostr"))
        
        val content = "Bitcoin and nostr are both important"
        val matches = KeywordMatcher.findMatchingKeywords(content, filter)
        
        assertEquals(2, matches.size)
        assertTrue(matches.contains("bitcoin"))
        assertTrue(matches.contains("nostr"))
    }

    @Test
    fun testGetMatchStats() {
        val filter = KeywordFilter.from(listOf("bitcoin", "nostr", "lightning"))
        val content = "Bitcoin and Lightning Network are the future"
        
        val stats = KeywordMatcher.getMatchStats(content, filter)
        
        assertTrue(stats.hasMatches)
        assertEquals(2, stats.matchingKeywords.size)
        assertTrue(stats.matchingKeywords.contains("bitcoin"))
        assertTrue(stats.matchingKeywords.contains("lightning"))
        assertEquals(3, stats.keywordCount)
        assertTrue(stats.contentLength > 0)
        assertTrue(stats.processingTimeMs >= 0)
        
        val summary = stats.getSummary()
        assertTrue(summary.contains("Content:"))
        assertTrue(summary.contains("Keywords:"))
        assertTrue(summary.contains("Matches:"))
        assertTrue(summary.contains("Time:"))
    }

    @Test
    fun testEmptyAndNullInputs() {
        val filter = KeywordFilter.from(listOf("bitcoin"))
        
        // Empty content
        assertFalse(KeywordMatcher.matches("", filter))
        assertFalse(KeywordMatcher.matches("   ", filter))
        
        // Null/empty filter
        assertFalse(KeywordMatcher.matches("bitcoin content", null))
        assertFalse(KeywordMatcher.matches("bitcoin content", KeywordFilter.empty()))
        
        // Empty keyword list
        assertFalse(KeywordMatcher.matches("bitcoin content", emptyList()))
        assertEquals(0, KeywordMatcher.findMatchingKeywords("bitcoin content", emptyList()).size)
    }

    @Test
    fun testLargeContentHandling() {
        val filter = KeywordFilter.from(listOf("bitcoin"))
        
        // Large content (over 10KB)
        val largeContent = "bitcoin ".repeat(2000) // About 14KB
        assertTrue(KeywordMatcher.matches(largeContent, filter))
        
        // Should still find matches in large content
        val matches = KeywordMatcher.findMatchingKeywords(largeContent, filter)
        assertTrue(matches.contains("bitcoin"))
    }

    @Test
    fun testSpecialCharacters() {
        val filter = KeywordFilter.from(listOf("bitcoin", "p2p"))
        
        // Content with special characters
        assertTrue(KeywordMatcher.matches("Bitcoin's price is rising!", filter))
        assertTrue(KeywordMatcher.matches("P2P networks are decentralized", filter))
        assertTrue(KeywordMatcher.matches("Bitcoin. That's the future.", filter))
        
        // Should not match if letters break word boundaries  
        val specialFilter = KeywordFilter.from(listOf("cat"))
        assertFalse(KeywordMatcher.matches("caterpillar", specialFilter))
    }

    @Test
    fun testPerformance() {
        val filter = KeywordFilter.from((1..20).map { "keyword$it" })
        val content = "This is test content with keyword5 and keyword15 in it"
        
        // Test that matching completes in reasonable time
        val startTime = System.nanoTime()
        val stats = KeywordMatcher.getMatchStats(content, filter)
        val endTime = System.nanoTime()
        
        assertTrue(stats.hasMatches)
        assertTrue(stats.processingTimeMs < 100) // Should be very fast
        assertTrue((endTime - startTime) / 1_000_000 < 100) // Less than 100ms
    }
}
