package com.cmdruid.pubsub.data

import java.util.regex.Pattern

/**
 * Represents a keyword-based content filter for Nostr events
 * Provides case-insensitive word boundary matching with OR logic (match any keyword)
 */
data class KeywordFilter(
    val keywords: List<String> = emptyList()
) {
    companion object {
        private const val MAX_KEYWORD_LENGTH = 100
        private const val MAX_KEYWORD_COUNT = 20
        private const val MIN_KEYWORD_LENGTH = 2
        
        /**
         * Create an empty keyword filter
         */
        fun empty(): KeywordFilter = KeywordFilter()
        
        /**
         * Create a keyword filter from a list of raw keywords
         */
        fun from(keywords: List<String>): KeywordFilter {
            return KeywordFilter(keywords).sanitizeKeywords()
        }
        
        /**
         * Create a keyword filter from a comma-separated string
         */
        fun fromString(keywordsString: String): KeywordFilter {
            val keywords = keywordsString.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            return from(keywords)
        }
    }
    
    /**
     * Check if this keyword filter is valid
     */
    fun isValid(): Boolean {
        return keywords.isNotEmpty() && 
               keywords.all { isValidKeyword(it) }
    }
    
    /**
     * Check if this keyword filter is empty
     */
    fun isEmpty(): Boolean {
        return keywords.isEmpty()
    }
    
    /**
     * Sanitize keywords by trimming, removing empty/invalid entries, and enforcing limits
     */
    fun sanitizeKeywords(): KeywordFilter {
        val sanitized = keywords
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length >= MIN_KEYWORD_LENGTH }
            .map { if (it.length > MAX_KEYWORD_LENGTH) it.take(MAX_KEYWORD_LENGTH) else it }
            .distinct()
            .take(MAX_KEYWORD_COUNT)
        
        return KeywordFilter(sanitized)
    }
    
    /**
     * Check if the given content matches any of the keywords
     * Uses case-insensitive word boundary matching
     */
    fun matches(content: String): Boolean {
        if (keywords.isEmpty() || content.isBlank()) {
            return false
        }
        
        return keywords.any { keyword ->
            matchesKeyword(content, keyword)
        }
    }
    
    /**
     * Get a summary description of this keyword filter
     */
    fun getSummary(): String {
        return when {
            keywords.isEmpty() -> "No keywords"
            keywords.size == 1 -> "keyword: \"${keywords.first()}\""
            else -> "${keywords.size} keywords: ${keywords.joinToString(", ") { "\"$it\"" }}"
        }
    }
    
    /**
     * Add a new keyword to this filter
     */
    fun addKeyword(keyword: String): KeywordFilter {
        val sanitizedKeyword = keyword.trim()
        if (sanitizedKeyword.isBlank() || !isValidKeyword(sanitizedKeyword)) {
            return this
        }
        
        if (keywords.contains(sanitizedKeyword) || keywords.size >= MAX_KEYWORD_COUNT) {
            return this
        }
        
        return KeywordFilter(keywords + sanitizedKeyword)
    }
    
    /**
     * Remove a keyword from this filter
     */
    fun removeKeyword(keyword: String): KeywordFilter {
        return KeywordFilter(keywords.filter { it != keyword })
    }
    
    /**
     * Convert keywords to a comma-separated string for serialization
     */
    fun toKeywordsString(): String {
        return keywords.joinToString(",")
    }
    
    private fun isValidKeyword(keyword: String): Boolean {
        return keyword.isNotBlank() && 
               keyword.length >= MIN_KEYWORD_LENGTH && 
               keyword.length <= MAX_KEYWORD_LENGTH &&
               keyword.trim() == keyword // No leading/trailing whitespace
    }
    
    private fun matchesKeyword(content: String, keyword: String): Boolean {
        return try {
            // Create word boundary pattern for case-insensitive matching
            val pattern = Pattern.compile("\\b${Pattern.quote(keyword)}\\b", Pattern.CASE_INSENSITIVE)
            pattern.matcher(content).find()
        } catch (e: Exception) {
            // Fallback to simple case-insensitive contains if regex fails
            content.lowercase().contains(keyword.lowercase())
        }
    }
}
