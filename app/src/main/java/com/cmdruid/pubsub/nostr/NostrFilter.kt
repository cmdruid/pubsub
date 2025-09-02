package com.cmdruid.pubsub.nostr

import com.google.gson.annotations.SerializedName
import com.cmdruid.pubsub.data.HashtagEntry

/**
 * Represents a Nostr filter as defined in NIP-01
 * Enhanced with builder pattern, validation, and advanced filtering
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
    
    // Dynamic hashtag filters using single-letter tags (a-z, A-Z)
    // This will be serialized as "#a", "#b", "#t", etc. based on the HashtagEntry list
    @SerializedName("hashtag_entries")
    val hashtagEntries: List<HashtagEntry>? = null,
    
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
    val search: String? = null // NIP-50: Full-text search (not widely supported by relays)
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
               hashtagEntries.isNullOrEmpty() &&
               dTags.isNullOrEmpty() &&
               rTags.isNullOrEmpty() &&
               aTags.isNullOrEmpty() &&
               since == null &&
               until == null &&
               limit == null &&
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
        
        hashtagEntries?.takeIf { it.isNotEmpty() }?.let { entries ->
            val hashtagCount = entries.count { it.tag.lowercase() == "t" }
            val customCount = entries.count { it.tag.lowercase() != "t" }
            
            if (hashtagCount > 0) {
                parts.add("${hashtagCount} hashtag${if (hashtagCount != 1) "s" else ""}")
            }
            if (customCount > 0) {
                val customSummary = entries.filter { it.tag.lowercase() != "t" }
                    .map { "${it.tag}:${it.value}" }.joinToString(",")
                parts.add("custom tags: $customSummary")
            }
        }
        
        search?.takeIf { it.isNotBlank() }?.let {
            parts.add("search: \"$it\"")
        }
        
        limit?.let {
            parts.add("limit: $it")
        }
        
        return if (parts.isEmpty()) "No filters" else parts.joinToString(" • ")
    }
    
    /**
     * Validate filter constraints (beyond just non-empty check)
     */
    fun isValidConstraints(): Boolean {
        return try {
            // Validate timestamp ranges
            if (since != null && until != null && since!! >= until!!) {
                return false // Invalid time range
            }
            
            // Validate limit
            if (limit != null && limit!! <= 0) {
                return false // Invalid limit
            }
            
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Estimate the filter complexity/size for relay optimization
     */
    fun estimateComplexity(): Int {
        var complexity = 0
        ids?.let { complexity += it.size }
        authors?.let { complexity += it.size }
        kinds?.let { complexity += it.size }
        eventRefs?.let { complexity += it.size }
        pubkeyRefs?.let { complexity += it.size }
        hashtagEntries?.let { complexity += it.size }
        dTags?.let { complexity += it.size }
        
        return complexity
    }

    companion object {
        /**
         * Create filter builder
         */
        fun builder(): FilterBuilder = FilterBuilder()
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

/**
 * Builder class for creating complex filters with type safety
 */
class FilterBuilder {
    private var ids: MutableList<String>? = null
    private var authors: MutableList<String>? = null
    private var kinds: MutableList<Int>? = null
    private var eventRefs: MutableList<String>? = null
    private var pubkeyRefs: MutableList<String>? = null
    private var hashtagEntries: MutableList<HashtagEntry>? = null
    private var dTags: MutableList<String>? = null
    private var since: Long? = null
    private var until: Long? = null
    private var limit: Int? = null
    private var search: String? = null
    
    fun ids(vararg eventIds: String) = apply {
        if (ids == null) ids = mutableListOf()
        ids!!.addAll(eventIds)
    }
    
    fun authors(vararg pubkeys: String) = apply {
        if (authors == null) authors = mutableListOf()
        authors!!.addAll(pubkeys)
    }
    
    fun kinds(vararg eventKinds: Int) = apply {
        if (kinds == null) kinds = mutableListOf()
        kinds!!.addAll(eventKinds.toList())
    }
    
    fun eventRefs(vararg eventIds: String) = apply {
        if (eventRefs == null) eventRefs = mutableListOf()
        eventRefs!!.addAll(eventIds)
    }
    
    fun pubkeyRefs(vararg pubkeys: String) = apply {
        if (pubkeyRefs == null) pubkeyRefs = mutableListOf()
        pubkeyRefs!!.addAll(pubkeys)
    }
    
    fun hashtagEntries(vararg entries: HashtagEntry) = apply {
        if (hashtagEntries == null) hashtagEntries = mutableListOf()
        hashtagEntries!!.addAll(entries)
    }
    
    fun hashtag(tag: String, value: String) = apply {
        if (hashtagEntries == null) hashtagEntries = mutableListOf()
        hashtagEntries!!.add(HashtagEntry(tag, value))
    }
    
    fun since(timestamp: Long) = apply { this.since = timestamp }
    fun until(timestamp: Long) = apply { this.until = timestamp }
    fun limit(count: Int) = apply { this.limit = count }
    fun search(query: String) = apply { this.search = query }
    
    fun textNotesOnly() = apply { kinds(NostrEvent.KIND_TEXT_NOTE) }
    fun metadataOnly() = apply { kinds(NostrEvent.KIND_METADATA) }
    fun reactionsOnly() = apply { kinds(NostrEvent.KIND_REACTION) }
    
    fun build(): NostrFilter {
        return NostrFilter(
            ids = ids?.toList(),
            authors = authors?.toList(),
            kinds = kinds?.toList(),
            eventRefs = eventRefs?.toList(),
            pubkeyRefs = pubkeyRefs?.toList(),
            hashtagEntries = hashtagEntries?.toList(),
            dTags = dTags?.toList(),
            since = since,
            until = until,
            limit = limit,
            search = search
        )
    }
}
