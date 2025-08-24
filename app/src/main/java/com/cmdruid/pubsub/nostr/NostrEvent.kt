package com.cmdruid.pubsub.nostr

import com.google.gson.annotations.SerializedName
import android.util.Log

/**
 * Represents a Nostr event as defined in NIP-01
 * Enhanced with validation, tag utilities, and content parsing
 */
data class NostrEvent(
    @SerializedName("id")
    val id: String,
    
    @SerializedName("pubkey")
    val pubkey: String,
    
    @SerializedName("created_at")
    val createdAt: Long,
    
    @SerializedName("kind")
    val kind: Int,
    
    @SerializedName("tags")
    val tags: List<List<String>>,
    
    @SerializedName("content")
    val content: String,
    
    @SerializedName("sig")
    val signature: String
) {
    /**
     * Get a short preview of the content for notifications
     */
    fun getContentPreview(maxLength: Int = 50): String {
        return if (content.length <= maxLength) {
            content
        } else {
            content.substring(0, maxLength) + "..."
        }
    }
    
    /**
     * Check if this event mentions a specific pubkey
     */
    fun mentionsPubkey(pubkey: String): Boolean {
        return tags.any { tag ->
            tag.size >= 2 && tag[0] == "p" && tag[1] == pubkey
        }
    }
    
    /**
     * Get all mentioned pubkeys from the event tags
     */
    fun getMentionedPubkeys(): List<String> {
        return tags.filter { tag ->
            tag.size >= 2 && tag[0] == "p"
        }.map { it[1] }
    }
    
    /**
     * Validate event structure and content
     */
    fun isValid(): Boolean {
        return try {
            // Check required fields
            if (id.isBlank() || pubkey.isBlank() || signature.isBlank()) {
                return false
            }
            
            // Validate hex fields
            if (!isValidHex(id, 64) || !isValidHex(pubkey, 64) || !isValidHex(signature, 128)) {
                return false
            }
            
            // Validate timestamp (should be positive and reasonable)
            if (createdAt <= 0 || createdAt > System.currentTimeMillis() / 1000 + 3600) {
                return false
            }
            
            // Validate kind (should be non-negative)
            if (kind < 0) {
                return false
            }
            
            true
        } catch (e: Exception) {
            Log.w("NostrEvent", "Validation error for event ${id.take(8)}: ${e.message}")
            false
        }
    }
    
    /**
     * Get tag by type (first occurrence)
     */
    fun getTag(tagType: String): List<String>? {
        return tags.find { it.isNotEmpty() && it[0] == tagType }
    }
    
    /**
     * Get all tags of a specific type
     */
    fun getAllTags(tagType: String): List<List<String>> {
        return tags.filter { it.isNotEmpty() && it[0] == tagType }
    }
    
    /**
     * Extract hashtags from content and tags
     */
    fun getHashtags(): List<String> {
        val tagHashtags = getAllTags("t").mapNotNull { it.getOrNull(1) }
        val contentHashtags = extractHashtagsFromContent()
        return (tagHashtags + contentHashtags).distinct()
    }
    
    /**
     * Extract URLs from content
     */
    fun getUrls(): List<String> {
        val urlRegex = Regex("https?://[^\\s]+")
        return urlRegex.findAll(content).map { it.value }.toList()
    }
    
    /**
     * Check event type helpers
     */
    fun isTextNote(): Boolean = kind == KIND_TEXT_NOTE
    fun isRepost(): Boolean = kind == KIND_REPOST
    fun isDelete(): Boolean = kind == KIND_DELETE
    fun isMetadata(): Boolean = kind == KIND_METADATA
    fun isReaction(): Boolean = kind == KIND_REACTION
    
    /**
     * Get referenced event IDs
     */
    fun getReferencedEvents(): List<String> {
        return getAllTags("e").mapNotNull { it.getOrNull(1) }
    }
    
    /**
     * Get the root event ID (if this is a reply)
     */
    fun getRootEventId(): String? {
        val eTags = getAllTags("e")
        // Look for "root" marker first
        val rootTag = eTags.find { it.size >= 4 && it[3] == "root" }
        if (rootTag != null) return rootTag.getOrNull(1)
        
        // Fall back to first e tag
        return eTags.firstOrNull()?.getOrNull(1)
    }
    
    /**
     * Get the direct reply event ID
     */
    fun getReplyEventId(): String? {
        val eTags = getAllTags("e")
        // Look for "reply" marker
        val replyTag = eTags.find { it.size >= 4 && it[3] == "reply" }
        if (replyTag != null) return replyTag.getOrNull(1)
        
        // Fall back to last e tag if multiple exist
        return if (eTags.size > 1) eTags.lastOrNull()?.getOrNull(1) else null
    }
    
    private fun extractHashtagsFromContent(): List<String> {
        val hashtagRegex = Regex("#([a-zA-Z0-9_]+)")
        return hashtagRegex.findAll(content).map { it.groupValues[1] }.toList()
    }
    
    private fun isValidHex(hex: String, expectedLength: Int): Boolean {
        return hex.length == expectedLength && hex.all { 
            it.isDigit() || it.lowercaseChar() in 'a'..'f' 
        }
    }
    
    companion object {
        // Event kind constants
        const val KIND_METADATA = 0
        const val KIND_TEXT_NOTE = 1
        const val KIND_RECOMMEND_RELAY = 2
        const val KIND_CONTACTS = 3
        const val KIND_ENCRYPTED_DM = 4
        const val KIND_DELETE = 5
        const val KIND_REPOST = 6
        const val KIND_REACTION = 7
        const val KIND_BADGE_AWARD = 8
        const val KIND_CHANNEL_CREATE = 40
        const val KIND_CHANNEL_METADATA = 41
        const val KIND_CHANNEL_MESSAGE = 42
        const val KIND_CHANNEL_HIDE_MESSAGE = 43
        const val KIND_CHANNEL_MUTE_USER = 44
        
        /**
         * Get human-readable kind name
         */
        fun getKindName(kind: Int): String {
            return when (kind) {
                KIND_METADATA -> "Metadata"
                KIND_TEXT_NOTE -> "Text Note"
                KIND_RECOMMEND_RELAY -> "Recommend Relay"
                KIND_CONTACTS -> "Contacts"
                KIND_ENCRYPTED_DM -> "Encrypted DM"
                KIND_DELETE -> "Delete"
                KIND_REPOST -> "Repost"
                KIND_REACTION -> "Reaction"
                KIND_BADGE_AWARD -> "Badge Award"
                KIND_CHANNEL_CREATE -> "Channel Create"
                KIND_CHANNEL_METADATA -> "Channel Metadata"
                KIND_CHANNEL_MESSAGE -> "Channel Message"
                KIND_CHANNEL_HIDE_MESSAGE -> "Channel Hide Message"
                KIND_CHANNEL_MUTE_USER -> "Channel Mute User"
                else -> "Unknown ($kind)"
            }
        }
    }
}
