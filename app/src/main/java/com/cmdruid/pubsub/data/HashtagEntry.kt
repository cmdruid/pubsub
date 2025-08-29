package com.cmdruid.pubsub.data

/**
 * Represents a hashtag filter entry with a single-letter tag and its value
 * According to NIP-01, tags should be single letters (a-z, A-Z) that are indexed by relays
 */
data class HashtagEntry(
    val tag: String,
    val value: String
) {
    /**
     * Validate that the tag is a single character within a-z and A-Z
     * For UI purposes, excludes reserved tags 'e', 'p', and 't' which have dedicated UI fields
     * Note: This validation is for manual user input - external filters can still use reserved tags
     */
    fun isValid(): Boolean {
        return tag.length == 1 && 
               tag.matches(Regex("[a-zA-Z]")) && 
               tag !in listOf("e", "p", "t", "E", "P", "T") && // Prevent UI confusion with dedicated fields
               value.isNotBlank()
    }
    
    /**
     * Get the filter key for this tag (e.g., "#t" for tag "t")
     */
    fun getFilterKey(): String = "#$tag"
    
    /**
     * Get a display string for this hashtag entry
     */
    fun getDisplayString(): String = "$tag: $value"
    
    companion object {
        /**
         * Create a HashtagEntry from a display string format "tag: value"
         */
        fun fromDisplayString(displayString: String): HashtagEntry? {
            val parts = displayString.split(":", limit = 2)
            return if (parts.size == 2) {
                val tag = parts[0].trim()
                val value = parts[1].trim()
                if (tag.length == 1 && tag.matches(Regex("[a-zA-Z]")) && value.isNotBlank()) {
                    HashtagEntry(tag, value)
                } else null
            } else null
        }
        
        /**
         * Validate a tag character for UI input
         * Excludes reserved tags 'e' and 'p' which have dedicated UI fields
         * Note: This is for UI validation only - filters can contain e/p tags from external sources
         */
        fun isValidTag(tag: String): Boolean {
            return tag.length == 1 && 
                   tag.matches(Regex("[a-zA-Z]")) && 
                   tag !in listOf("e", "p", "E", "P") // Prevent UI confusion with dedicated fields
        }
    }
}
