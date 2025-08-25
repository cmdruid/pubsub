package com.cmdruid.pubsub.utils

import android.net.Uri
import android.util.Base64
import com.cmdruid.pubsub.nostr.NostrEvent
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
     * Build a URI with note identifier and optional event data
     * Format: {baseUri}/{note}?event=base64url_encoded_json
     * If event size exceeds 500KB, only the note identifier is included
     */
    fun buildEventUri(baseUri: String, event: NostrEvent): Uri? {
        return try {
            // Encode event ID as note (NIP-19) - simpler than nevent
            val note = NostrUtils.hexToNote(event.id) ?: return null
            
            // Serialize the event to JSON
            val eventJson = gson.toJson(event)
            val eventSizeBytes = eventJson.toByteArray(StandardCharsets.UTF_8).size
            val maxSizeBytes = 500 * 1024 // 500KB
            
            // Build URI: {baseUri}/{note}
            val baseUriWithSlash = if (baseUri.endsWith("/")) baseUri else "$baseUri/"
            val uriWithNote = "$baseUriWithSlash$note"
            
            // Only include the event data if it's under 500KB
            if (eventSizeBytes <= maxSizeBytes) {
                // Encode as base64url (URL-safe base64)
                val eventBase64 = Base64.encodeToString(
                    eventJson.toByteArray(StandardCharsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP
                )
                Uri.parse(uriWithNote).buildUpon().apply {
                    appendQueryParameter("event", eventBase64)
                }.build()
            } else {
                // If event is too large, only include the note identifier
                Uri.parse(uriWithNote)
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Build a simple URI with minimal event data (deprecated - use buildEventUri instead)
     */
    @Deprecated("Use buildEventUri with full NostrEvent instead")
    fun buildSimpleEventUri(baseUri: String, eventId: String, content: String): Uri? {
        return try {
            Uri.parse(baseUri).buildUpon().apply {
                appendQueryParameter("id", eventId)
                appendQueryParameter("content", content.take(100)) // Limit content length
            }.build()
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
     * Extract note identifier from a URI path
     */
    fun extractNoteFromUri(uri: Uri): String? {
        return try {
            val path = uri.path ?: return null
            
            // Extract the last path segment which should be the note
            val lastSegment = path.substringAfterLast("/")
            if (NostrUtils.isValidNote(lastSegment)) lastSegment else null
        } catch (e: Exception) {
            null
        }
    }
    

    
    /**
     * Get event ID from note in a URI
     */
    fun getEventIdFromUri(uri: Uri): String? {
        return try {
            val note = extractNoteFromUri(uri) ?: return null
            NostrUtils.noteToHex(note)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Validate if a URI string is properly formatted
     */
    fun isValidUri(uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            uri.scheme != null && uri.host != null
        } catch (e: Exception) {
            false
        }
    }
}
