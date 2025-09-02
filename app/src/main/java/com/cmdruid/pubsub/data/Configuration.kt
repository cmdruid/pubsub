package com.cmdruid.pubsub.data

import com.cmdruid.pubsub.nostr.NostrFilter
import com.cmdruid.pubsub.utils.UriBuilder
import java.util.UUID

/**
 * Generate a unique subscription ID for Nostr relay subscriptions
 */
private fun generateSubscriptionId(): String {
    return "sub_${UUID.randomUUID().toString().replace("-", "").take(16)}"
}

/**
 * Represents a complete configuration for monitoring Nostr events
 */
data class Configuration(
    val id: String = UUID.randomUUID().toString(),
    val subscriptionId: String = generateSubscriptionId(),
    val name: String,
    val relayUrls: List<String>,
    val filter: NostrFilter,
    val targetUri: String,
    val isDirectMode: Boolean = false,
    val isEnabled: Boolean = true,
    val keywordFilter: KeywordFilter? = null,
    val excludeMentionsToSelf: Boolean = true,
    val excludeRepliesToEvents: Boolean = false
) {
    /**
     * Check if this configuration is valid and ready to use
     */
    fun isValid(): Boolean {
        return name.isNotBlank() &&
               relayUrls.isNotEmpty() &&
               relayUrls.all { it.isNotBlank() } &&
               targetUri.isNotBlank() &&
               UriBuilder.isValidUri(targetUri) &&
               filter.isValid()
    }
    
    /**
     * Get a summary description of this configuration
     */
    fun getSummary(): String {
        val relayCount = relayUrls.size
        val filterSummary = filter.getSummary()
        val keywordSummary = keywordFilter?.let { 
            if (!it.isEmpty()) " • ${it.getSummary()}" else ""
        } ?: ""
        return "$name • $relayCount relay${if (relayCount != 1) "s" else ""} • $filterSummary$keywordSummary"
    }
    

}
