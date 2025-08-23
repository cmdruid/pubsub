package com.cmdruid.pubsub.utils

import android.net.Uri
import android.util.Base64
import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.nostr.NostrFilter
import com.google.gson.Gson
import java.nio.charset.StandardCharsets

/**
 * Handles deep link parsing for registering filter configurations
 * 
 * Deep link format: pubsub://register?label=MyApp&relay=wss://relay1.com&relay=wss://relay2.com&filter=base64url_encoded_filter&uri=https://myapp.com/handle
 */
object DeepLinkHandler {
    
    private val gson = Gson()
    
    data class DeepLinkResult(
        val success: Boolean,
        val configuration: Configuration? = null,
        val errorMessage: String? = null
    )
    
    /**
     * Parse a deep link URI and extract configuration data
     */
    fun parseRegisterDeepLink(uri: Uri): DeepLinkResult {
        try {
            // Debug logging
            android.util.Log.d("DeepLinkHandler", "Parsing URI: $uri")
            android.util.Log.d("DeepLinkHandler", "Scheme: ${uri.scheme}, Host: ${uri.host}")
            android.util.Log.d("DeepLinkHandler", "Query: ${uri.query}")
            
            // Validate scheme and host
            if (uri.scheme != "pubsub" || uri.host != "register") {
                return DeepLinkResult(
                    success = false,
                    errorMessage = "Invalid deep link format. Expected: pubsub://register"
                )
            }
            
            // Extract required parameters and clean up any escape characters
            val label = uri.getQueryParameter("label")?.replace("\\", "")?.trim()
            val targetUri = uri.getQueryParameter("uri")?.replace("\\", "")?.trim()
            val filterBase64 = uri.getQueryParameter("filter")?.replace("\\", "")?.trim()
            
            // Debug parameter extraction
            android.util.Log.d("DeepLinkHandler", "label (cleaned): $label")
            android.util.Log.d("DeepLinkHandler", "uri (cleaned): $targetUri")
            android.util.Log.d("DeepLinkHandler", "filter (cleaned): $filterBase64")
            
            // Validate required parameters
            if (label.isNullOrBlank()) {
                return DeepLinkResult(
                    success = false,
                    errorMessage = "Missing required parameter: label"
                )
            }
            
            if (targetUri.isNullOrBlank()) {
                return DeepLinkResult(
                    success = false,
                    errorMessage = "Missing required parameter: uri"
                )
            }
            
            if (filterBase64.isNullOrBlank()) {
                return DeepLinkResult(
                    success = false,
                    errorMessage = "Missing required parameter: filter"
                )
            }
            
            // Validate target URI format
            if (!UriBuilder.isValidUri(targetUri)) {
                return DeepLinkResult(
                    success = false,
                    errorMessage = "Invalid URI format: $targetUri"
                )
            }
            
            // Extract relay URLs (can have multiple relay parameters) and clean them
            val relayUrls = uri.getQueryParameters("relay").map { it.replace("\\", "").trim() }
            android.util.Log.d("DeepLinkHandler", "relays (cleaned): $relayUrls")
            
            if (relayUrls.isEmpty()) {
                return DeepLinkResult(
                    success = false,
                    errorMessage = "At least one relay parameter is required"
                )
            }
            
            // Validate relay URLs
            val invalidRelays = relayUrls.filter { !UriBuilder.isValidUri(it) }
            if (invalidRelays.isNotEmpty()) {
                return DeepLinkResult(
                    success = false,
                    errorMessage = "Invalid relay URLs: ${invalidRelays.joinToString(", ")}"
                )
            }
            
            // Decode and parse the filter
            val filter = try {
                android.util.Log.d("DeepLinkHandler", "Decoding filter base64: $filterBase64")
                val filterJson = String(
                    Base64.decode(filterBase64, Base64.URL_SAFE),
                    StandardCharsets.UTF_8
                )
                android.util.Log.d("DeepLinkHandler", "Decoded filter JSON: $filterJson")
                val parsedFilter = gson.fromJson(filterJson, NostrFilter::class.java)
                android.util.Log.d("DeepLinkHandler", "Parsed filter: $parsedFilter")
                parsedFilter
            } catch (e: Exception) {
                android.util.Log.e("DeepLinkHandler", "Filter parsing error: ${e.message}", e)
                return DeepLinkResult(
                    success = false,
                    errorMessage = "Invalid filter format: ${e.message}"
                )
            }
            
            // Validate the filter
            if (filter == null || filter.isEmpty()) {
                return DeepLinkResult(
                    success = false,
                    errorMessage = "Filter cannot be empty"
                )
            }
            
            // Create the configuration
            val configuration = Configuration(
                name = label,
                relayUrls = relayUrls,
                filter = filter,
                targetUri = targetUri,
                isEnabled = true
            )
            
            android.util.Log.d("DeepLinkHandler", "Successfully created configuration: ${configuration.name}")
            
            return DeepLinkResult(
                success = true,
                configuration = configuration
            )
            
        } catch (e: Exception) {
            return DeepLinkResult(
                success = false,
                errorMessage = "Error parsing deep link: ${e.message}"
            )
        }
    }
    
    /**
     * Generate a sample deep link for testing/documentation
     */
    fun generateSampleDeepLink(): String {
        val sampleFilter = NostrFilter(
            kinds = listOf(1, 7),
            authors = listOf("sample_pubkey_here"),
            limit = 50
        )
        
        val filterJson = gson.toJson(sampleFilter)
        val filterBase64 = Base64.encodeToString(
            filterJson.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP
        )
        
        return "pubsub://register?" +
                "label=My App" +
                "&relay=wss://relay.damus.io" +
                "&relay=wss://nos.lol" +
                "&filter=$filterBase64" +
                "&uri=https://myapp.com/handle-event"
    }
    
    /**
     * Check if a URI is a valid pubsub deep link
     */
    fun isPubSubDeepLink(uri: Uri): Boolean {
        return uri.scheme == "pubsub" && uri.host == "register"
    }
}
