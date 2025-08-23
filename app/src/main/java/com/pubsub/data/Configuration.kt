package com.pubsub.data

import com.pubsub.nostr.NostrFilter
import java.util.UUID

/**
 * Represents a complete configuration for monitoring Nostr events
 */
data class Configuration(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val relayUrls: List<String>,
    val filter: NostrFilter,
    val targetUri: String,
    val isDirectMode: Boolean = false,
    val isEnabled: Boolean = true
) {
    /**
     * Check if this configuration is valid and ready to use
     */
    fun isValid(): Boolean {
        return name.isNotBlank() &&
               relayUrls.isNotEmpty() &&
               relayUrls.all { it.isNotBlank() } &&
               targetUri.isNotBlank() &&
               filter.isValid()
    }
    
    /**
     * Get a summary description of this configuration
     */
    fun getSummary(): String {
        val relayCount = relayUrls.size
        val filterSummary = filter.getSummary()
        return "$name • $relayCount relay${if (relayCount != 1) "s" else ""} • $filterSummary"
    }
}
