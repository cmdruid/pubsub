package com.cmdruid.pubsub.utils

import com.cmdruid.pubsub.data.KeywordFilter
import java.util.regex.Pattern

/**
 * Utility class for efficiently matching keywords against event content
 * Optimized for real-time event filtering with minimal performance impact
 */
class KeywordMatcher {
    
    companion object {
        private const val MAX_CONTENT_LENGTH = 10000 // Limit content processing for performance
        
        /**
         * Matches content against a keyword filter
         * Returns true if any keyword matches the content
         */
        fun matches(content: String, keywordFilter: KeywordFilter?): Boolean {
            // Early return for null or empty filter
            if (keywordFilter == null || keywordFilter.isEmpty()) {
                return false
            }
            
            // Early return for empty content
            if (content.isBlank()) {
                return false
            }
            
            // Limit content length for performance
            val processableContent = if (content.length > MAX_CONTENT_LENGTH) {
                content.take(MAX_CONTENT_LENGTH)
            } else {
                content
            }
            
            return keywordFilter.matches(processableContent)
        }
        
        /**
         * Matches content against a list of keywords
         * Returns true if any keyword matches the content
         */
        fun matches(content: String, keywords: List<String>): Boolean {
            if (keywords.isEmpty() || content.isBlank()) {
                return false
            }
            
            val processableContent = if (content.length > MAX_CONTENT_LENGTH) {
                content.take(MAX_CONTENT_LENGTH)
            } else {
                content
            }
            
            return keywords.any { keyword ->
                matchesKeyword(processableContent, keyword)
            }
        }
        
        /**
         * Matches content against a single keyword using word boundary matching
         */
        fun matchesKeyword(content: String, keyword: String): Boolean {
            if (keyword.isBlank() || content.isBlank()) {
                return false
            }
            
            return try {
                // Create word boundary pattern for case-insensitive matching
                val pattern = Pattern.compile("\\b${Pattern.quote(keyword.trim())}\\b", Pattern.CASE_INSENSITIVE)
                pattern.matcher(content).find()
            } catch (e: Exception) {
                // Fallback to simple case-insensitive contains if regex fails
                content.lowercase().contains(keyword.trim().lowercase())
            }
        }
        
        /**
         * Find all matching keywords in the given content
         * Returns a list of keywords that match the content
         */
        fun findMatchingKeywords(content: String, keywords: List<String>): List<String> {
            if (keywords.isEmpty() || content.isBlank()) {
                return emptyList()
            }
            
            val processableContent = if (content.length > MAX_CONTENT_LENGTH) {
                content.take(MAX_CONTENT_LENGTH)
            } else {
                content
            }
            
            return keywords.filter { keyword ->
                matchesKeyword(processableContent, keyword)
            }
        }
        
        /**
         * Find all matching keywords from a keyword filter
         * Returns a list of keywords that match the content
         */
        fun findMatchingKeywords(content: String, keywordFilter: KeywordFilter?): List<String> {
            return if (keywordFilter != null && !keywordFilter.isEmpty()) {
                findMatchingKeywords(content, keywordFilter.keywords)
            } else {
                emptyList()
            }
        }
        
        /**
         * Check if content should be processed (basic validation)
         */
        fun shouldProcessContent(content: String?): Boolean {
            return !content.isNullOrBlank() && content.length >= 2
        }
        
        /**
         * Get matching statistics for debugging
         */
        fun getMatchStats(content: String, keywordFilter: KeywordFilter?): MatchStats {
            if (keywordFilter == null || keywordFilter.isEmpty()) {
                return MatchStats(
                    contentLength = content.length,
                    keywordCount = 0,
                    matchingKeywords = emptyList(),
                    processingTimeMs = 0
                )
            }
            
            val startTime = System.nanoTime()
            val matchingKeywords = findMatchingKeywords(content, keywordFilter)
            val endTime = System.nanoTime()
            
            return MatchStats(
                contentLength = content.length,
                keywordCount = keywordFilter.keywords.size,
                matchingKeywords = matchingKeywords,
                processingTimeMs = (endTime - startTime) / 1_000_000 // Convert to milliseconds
            )
        }
    }
    
    /**
     * Statistics for keyword matching performance and results
     */
    data class MatchStats(
        val contentLength: Int,
        val keywordCount: Int,
        val matchingKeywords: List<String>,
        val processingTimeMs: Long
    ) {
        val hasMatches: Boolean get() = matchingKeywords.isNotEmpty()
        
        fun getSummary(): String {
            return "Content: ${contentLength}chars, Keywords: $keywordCount, Matches: ${matchingKeywords.size}, Time: ${processingTimeMs}ms"
        }
    }
}
