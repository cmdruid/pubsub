package com.cmdruid.pubsub.utils

import android.net.Uri
import android.util.Base64
import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.data.KeywordFilter
import com.cmdruid.pubsub.nostr.NostrEvent
import com.cmdruid.pubsub.nostr.NostrFilter
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets

/**
 * Utility class for building URIs with event data
 */
object UriBuilder {
    
    private val gson = GsonBuilder()
        .registerTypeAdapter(com.cmdruid.pubsub.nostr.NostrFilter::class.java, com.cmdruid.pubsub.nostr.NostrFilterSerializer())
        .create()
    
    /**
     * Build a URI with nevent identifier and optional event data
     * Format: {baseUri}/{nevent}?event=base64url_encoded_json
     * If event size exceeds 500KB, only the nevent identifier is included
     */
    fun buildEventUri(baseUri: String, event: NostrEvent, relayUrls: List<String> = emptyList()): Uri? {
        return try {
            // Encode event ID as nevent (NIP-19) with TLV structure including relay URLs
            val nevent = NostrUtils.hexToNevent(
                eventId = event.id, 
                relayUrls = relayUrls,
                authorPubkey = event.pubkey,
                kind = event.kind
            ) ?: return null
            
            // Serialize the event to JSON
            val eventJson = gson.toJson(event)
            val eventSizeBytes = eventJson.toByteArray(StandardCharsets.UTF_8).size
            val maxSizeBytes = 500 * 1024 // 500KB
            
            // Build URI: {baseUri}/{nevent} - handle trailing slashes properly
            val baseUriWithSlash = if (baseUri.endsWith("/")) baseUri else "$baseUri/"
            val uriWithNevent = "$baseUriWithSlash$nevent"
            
            // Only include the event data if it's under 500KB
            if (eventSizeBytes <= maxSizeBytes) {
                // Encode as base64url (URL-safe base64)
                val eventBase64 = Base64.encodeToString(
                    eventJson.toByteArray(StandardCharsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP
                )
                Uri.parse(uriWithNevent).buildUpon().apply {
                    appendQueryParameter("event", eventBase64)
                }.build()
            } else {
                // If event is too large, only include the nevent identifier
                Uri.parse(uriWithNevent)
            }
        } catch (e: Exception) {
            null
        }
    }
    

    /**
     * Check if an event would exceed the size limit for URI inclusion
     */
    fun isEventTooLarge(event: NostrEvent): Boolean {
        return try {
            val eventJson = gson.toJson(event)
            val eventSizeBytes = eventJson.toByteArray(StandardCharsets.UTF_8).size
            val maxSizeBytes = 500 * 1024 // 500KB
            eventSizeBytes > maxSizeBytes
        } catch (e: Exception) {
            true // Assume too large if we can't determine size
        }
    }
    
    /**
     * Get the size in bytes of an event when serialized to JSON
     */
    fun getEventSizeBytes(event: NostrEvent): Int {
        return try {
            val eventJson = gson.toJson(event)
            eventJson.toByteArray(StandardCharsets.UTF_8).size
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Decode a base64url encoded event from URI query parameter
     */
    fun decodeEventFromUri(uri: Uri): NostrEvent? {
        return try {
            val eventBase64 = uri.getQueryParameter("event") ?: return null
            val eventJson = String(
                Base64.decode(eventBase64, Base64.URL_SAFE),
                StandardCharsets.UTF_8
            )
            gson.fromJson(eventJson, NostrEvent::class.java)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Extract nevent identifier from a URI path
     */
    fun extractNeventFromUri(uri: Uri): String? {
        return try {
            val path = uri.path ?: return null
            
            // Extract the last path segment which should be the nevent
            val lastSegment = path.substringAfterLast("/")
            if (NostrUtils.isValidNevent(lastSegment)) lastSegment else null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Get event ID from nevent in a URI
     */
    fun getEventIdFromUri(uri: Uri): String? {
        return try {
            val nevent = extractNeventFromUri(uri) ?: return null
            NostrUtils.neventToHex(nevent)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Extract relay URLs from nevent in a URI
     */
    fun extractRelayUrlsFromUri(uri: Uri): List<String> {
        return try {
            val nevent = extractNeventFromUri(uri) ?: return emptyList()
            NostrUtils.extractRelaysFromNevent(nevent)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Build a pubsub://register deep link from a Configuration
     */
    fun buildRegisterDeepLink(configuration: Configuration): String? {
        return try {
            val filterJson = gson.toJson(configuration.filter)
            val filterBase64 = Base64.encodeToString(
                filterJson.toByteArray(StandardCharsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP
            )
            
            val uriBuilder = Uri.Builder()
                .scheme("pubsub")
                .authority("register")
                .appendQueryParameter("label", configuration.name)
                .appendQueryParameter("uri", configuration.targetUri)
                .appendQueryParameter("filter", filterBase64)
            
            // Add relay URLs
            configuration.relayUrls.forEach { relayUrl ->
                uriBuilder.appendQueryParameter("relay", relayUrl)
            }
            
            // Add keywords if present
            configuration.keywordFilter?.let { keywordFilter ->
                if (!keywordFilter.isEmpty()) {
                    keywordFilter.keywords.forEach { keyword ->
                        uriBuilder.appendQueryParameter("keyword", keyword)
                    }
                }
            }
            
            // Add local filter parameters (only include non-default values)
            if (!configuration.excludeMentionsToSelf) {
                uriBuilder.appendQueryParameter("excludeMentionsToSelf", "false")
            }
            if (configuration.excludeRepliesToEvents) {
                uriBuilder.appendQueryParameter("excludeRepliesToEvents", "true")
            }
            
            uriBuilder.build().toString()
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Build a pubsub://register deep link with custom parameters
     */
    fun buildRegisterDeepLink(
        label: String,
        relayUrls: List<String>,
        filter: NostrFilter,
        targetUri: String,
        keywords: List<String> = emptyList(),
        excludeMentionsToSelf: Boolean = true,
        excludeRepliesToEvents: Boolean = false
    ): String? {
        return try {
            val filterJson = gson.toJson(filter)
            val filterBase64 = Base64.encodeToString(
                filterJson.toByteArray(StandardCharsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP
            )
            
            val uriBuilder = Uri.Builder()
                .scheme("pubsub")
                .authority("register")
                .appendQueryParameter("label", label)
                .appendQueryParameter("uri", targetUri)
                .appendQueryParameter("filter", filterBase64)
            
            // Add relay URLs
            relayUrls.forEach { relayUrl ->
                uriBuilder.appendQueryParameter("relay", relayUrl)
            }
            
            // Add keywords
            keywords.forEach { keyword ->
                uriBuilder.appendQueryParameter("keyword", keyword)
            }
            
            // Add local filter parameters (only include non-default values)
            if (!excludeMentionsToSelf) {
                uriBuilder.appendQueryParameter("excludeMentionsToSelf", "false")
            }
            if (excludeRepliesToEvents) {
                uriBuilder.appendQueryParameter("excludeRepliesToEvents", "true")
            }
            
            uriBuilder.build().toString()
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Normalize a target URI by handling trailing slashes consistently
     */
    fun normalizeTargetUri(uriString: String): String {
        val trimmed = uriString.trim()
        return if (trimmed.endsWith("/")) {
            trimmed.dropLast(1)
        } else {
            trimmed
        }
    }
    
    /**
     * Validate if a URI string is properly formatted
     * Also handles trailing slashes by normalizing before validation
     */
    fun isValidUri(uriString: String): Boolean {
        return try {
            val normalizedUri = normalizeTargetUri(uriString)
            val uri = Uri.parse(normalizedUri)
            uri.scheme != null && uri.host != null
        } catch (e: Exception) {
            false
        }
    }
}
