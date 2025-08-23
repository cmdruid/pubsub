package com.cmdruid.pubsub.nostr

import com.google.gson.annotations.SerializedName

/**
 * Represents a Nostr event as defined in NIP-01
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
}
