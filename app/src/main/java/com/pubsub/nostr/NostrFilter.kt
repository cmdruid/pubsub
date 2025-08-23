package com.pubsub.nostr

import com.google.gson.annotations.SerializedName

/**
 * Represents a Nostr filter as defined in NIP-01
 * Enhanced with additional tag support and validation
 */
data class NostrFilter(
    @SerializedName("ids")
    val ids: List<String>? = null,
    
    @SerializedName("authors")
    val authors: List<String>? = null,
    
    @SerializedName("kinds")
    val kinds: List<Int>? = null,
    
    @SerializedName("#e")
    val eventRefs: List<String>? = null,
    
    @SerializedName("#p")
    val pubkeyRefs: List<String>? = null,
    
    @SerializedName("#t")
    val hashtags: List<String>? = null,
    
    @SerializedName("#d")
    val dTags: List<String>? = null,
    
    @SerializedName("#r")
    val rTags: List<String>? = null,
    
    @SerializedName("#a")
    val aTags: List<String>? = null,
    
    @SerializedName("since")
    val since: Long? = null,
    
    @SerializedName("until")
    val until: Long? = null,
    
    @SerializedName("limit")
    val limit: Int? = null,
    
    @SerializedName("search")
    val search: String? = null
) {
    /**
     * Check if this filter is valid and has at least one filtering criteria
     */
    fun isValid(): Boolean {
        return !isEmpty()
    }
    
    /**
     * Check if this filter is empty (no filtering criteria)
     */
    fun isEmpty(): Boolean {
        return ids.isNullOrEmpty() &&
               authors.isNullOrEmpty() &&
               kinds.isNullOrEmpty() &&
               eventRefs.isNullOrEmpty() &&
               pubkeyRefs.isNullOrEmpty() &&
               hashtags.isNullOrEmpty() &&
               dTags.isNullOrEmpty() &&
               rTags.isNullOrEmpty() &&
               aTags.isNullOrEmpty() &&
               since == null &&
               until == null &&
               search.isNullOrBlank()
    }
    
    /**
     * Get a human-readable summary of this filter
     */
    fun getSummary(): String {
        val parts = mutableListOf<String>()
        
        authors?.takeIf { it.isNotEmpty() }?.let { 
            parts.add("${it.size} author${if (it.size != 1) "s" else ""}")
        }
        
        kinds?.takeIf { it.isNotEmpty() }?.let { 
            parts.add("kinds: ${it.joinToString(",")}")
        }
        
        pubkeyRefs?.takeIf { it.isNotEmpty() }?.let { 
            parts.add("${it.size} mention${if (it.size != 1) "s" else ""}")
        }
        
        eventRefs?.takeIf { it.isNotEmpty() }?.let { 
            parts.add("${it.size} event ref${if (it.size != 1) "s" else ""}")
        }
        
        hashtags?.takeIf { it.isNotEmpty() }?.let { 
            parts.add("hashtags: ${it.joinToString(",")}")
        }
        
        search?.takeIf { it.isNotBlank() }?.let {
            parts.add("search: \"$it\"")
        }
        
        limit?.let {
            parts.add("limit: $it")
        }
        
        return if (parts.isEmpty()) "No filters" else parts.joinToString(" • ")
    }

    companion object {
        /**
         * Create a filter for mentions of a specific pubkey
         */
        fun forMentions(pubkey: String, kinds: List<Int> = listOf(1)): NostrFilter {
            return NostrFilter(
                pubkeyRefs = listOf(pubkey),
                kinds = kinds
            )
        }
        
        /**
         * Create a filter for events by specific authors
         */
        fun forAuthors(authors: List<String>, kinds: List<Int> = listOf(1)): NostrFilter {
            return NostrFilter(
                authors = authors,
                kinds = kinds
            )
        }
        
        /**
         * Create a filter for recent events (since timestamp)
         */
        fun forRecent(since: Long, kinds: List<Int> = listOf(1), limit: Int = 100): NostrFilter {
            return NostrFilter(
                since = since,
                kinds = kinds,
                limit = limit
            )
        }
        
        /**
         * Create an empty filter
         */
        fun empty(): NostrFilter {
            return NostrFilter()
        }
        
        /**
         * Common event kinds as defined in NIPs
         */
        object EventKinds {
            const val TEXT_NOTE = 1
            const val SET_METADATA = 0
            const val RECOMMEND_SERVER = 2
            const val CONTACT_LIST = 3
            const val ENCRYPTED_DIRECT_MESSAGE = 4
            const val DELETE = 5
            const val REPOST = 6
            const val REACTION = 7
            const val CHANNEL_CREATE = 40
            const val CHANNEL_METADATA = 41
            const val CHANNEL_MESSAGE = 42
            const val CHANNEL_HIDE_MESSAGE = 43
            const val CHANNEL_MUTE_USER = 44
        }
    }
}
