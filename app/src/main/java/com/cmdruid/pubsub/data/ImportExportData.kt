package com.cmdruid.pubsub.data

import com.cmdruid.pubsub.nostr.NostrFilter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Data classes for Import/Export functionality
 * Defines the JSON schema for backing up and restoring subscription configurations
 */

/**
 * Root export data structure - simplified format matching user preference
 */
data class ExportData(
    val version: String,
    val date: String,
    val subscriptions: List<ExportableSubscription>
) {
    companion object {
        fun create(subscriptions: List<Configuration>, appVersion: String): ExportData {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
            
            return ExportData(
                version = appVersion,
                date = dateFormat.format(Date()),
                subscriptions = subscriptions.map { ExportableSubscription.fromConfiguration(it) }
            )
        }
    }
}

/**
 * Exportable subscription configuration - simplified format
 */
data class ExportableSubscription(
    val name: String,
    val enabled: Boolean,
    val target: String,
    val relays: List<String>,
    val filters: Map<String, Any>,
    val keywords: List<String>?
) {
    companion object {
        fun fromConfiguration(config: Configuration): ExportableSubscription {
            // Convert NostrFilter to NIP-1 compatible map
            val filtersMap = mutableMapOf<String, Any>()
            
            config.filter.authors?.takeIf { it.isNotEmpty() }?.let { 
                filtersMap["authors"] = it 
            }
            config.filter.kinds?.takeIf { it.isNotEmpty() }?.let { 
                filtersMap["kinds"] = it 
            }
            config.filter.eventRefs?.takeIf { it.isNotEmpty() }?.let { 
                filtersMap["#e"] = it 
            }
            config.filter.pubkeyRefs?.takeIf { it.isNotEmpty() }?.let { 
                filtersMap["#p"] = it 
            }
            
            // Handle hashtag entries - convert to NIP-1 format
            config.filter.hashtagEntries?.let { entries ->
                entries.groupBy { it.tag }.forEach { (tag, tagEntries) ->
                    val values = tagEntries.map { it.value }
                    if (values.isNotEmpty()) {
                        filtersMap["#$tag"] = values
                    }
                }
            }
            
            return ExportableSubscription(
                name = config.name,
                enabled = config.isEnabled,
                target = config.targetUri,
                relays = config.relayUrls,
                filters = filtersMap,
                keywords = config.keywordFilter?.keywords?.takeIf { it.isNotEmpty() }
            )
        }
    }
    
    /**
     * Convert back to Configuration object
     */
    fun toConfiguration(): Configuration {
        // Convert NIP-1 compatible map back to NostrFilter
        val hashtagEntries = mutableListOf<HashtagEntry>()
        
        // Extract hashtag entries from filters map
        filters.forEach { (key, value) ->
            if (key.startsWith("#") && value is List<*>) {
                val tag = key.substring(1) // Remove the # prefix
                val values = value.filterIsInstance<String>()
                values.forEach { tagValue ->
                    hashtagEntries.add(HashtagEntry(tag, tagValue))
                }
            }
        }
        
        val nostrFilter = NostrFilter(
            authors = (filters["authors"] as? List<*>)?.filterIsInstance<String>()?.takeIf { it.isNotEmpty() },
            kinds = (filters["kinds"] as? List<*>)?.filterIsInstance<Number>()?.map { it.toInt() }?.takeIf { it.isNotEmpty() },
            eventRefs = (filters["#e"] as? List<*>)?.filterIsInstance<String>()?.takeIf { it.isNotEmpty() },
            pubkeyRefs = (filters["#p"] as? List<*>)?.filterIsInstance<String>()?.takeIf { it.isNotEmpty() },
            hashtagEntries = hashtagEntries.takeIf { it.isNotEmpty() }
            // Note: since, until, limit are intentionally excluded from import/export
            // as they are app-managed runtime fields, not user configuration
        )
        
        val keywordFilter = keywords?.takeIf { it.isNotEmpty() }?.let { KeywordFilter(it) }
        
        return Configuration(
            name = name,
            relayUrls = relays,
            filter = nostrFilter,
            targetUri = target,
            isDirectMode = false, // Default to false for imports
            isEnabled = enabled,
            keywordFilter = keywordFilter
        )
    }
    
    /**
     * Validate this subscription configuration
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        
        if (name.isBlank()) {
            errors.add("Subscription name cannot be empty")
        }
        
        if (relays.isEmpty()) {
            errors.add("Must have at least one relay URL")
        }
        
        relays.forEachIndexed { index, url ->
            if (!isValidRelayUrl(url)) {
                errors.add("Invalid relay URL #${index + 1}: $url")
            }
        }
        
        if (!isValidEventViewerUrl(target)) {
            errors.add("Invalid target URI: $target")
        }
        
        return errors
    }
    
    private fun isValidRelayUrl(url: String): Boolean {
        return url.startsWith("wss://") || url.startsWith("ws://")
    }
    
    private fun isValidEventViewerUrl(url: String): Boolean {
        return url.startsWith("https://") || url.startsWith("http://")
    }
}



/**
 * Result types for import/export operations
 */
sealed class ExportResult {
    data class Success(val filename: String, val subscriptionCount: Int) : ExportResult()
    data class Error(val message: String, val cause: Throwable? = null) : ExportResult()
}

sealed class ImportResult {
    data class Success(
        val importedCount: Int, 
        val duplicateCount: Int,
        val skippedCount: Int
    ) : ImportResult()
    data class Error(val message: String, val cause: Throwable? = null) : ImportResult()
}

sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val errors: List<String>) : ValidationResult()
}

/**
 * Import options for handling duplicates
 */
enum class ImportMode {
    ADD_NEW_ONLY,           // Skip duplicates, add only new subscriptions
    REPLACE_ALL,            // Replace all existing subscriptions
    REPLACE_DUPLICATES      // Replace duplicates, add new ones
}

/**
 * Preview of what will be imported
 */
data class ImportPreview(
    val totalSubscriptions: Int,
    val newSubscriptions: List<String>,
    val duplicateSubscriptions: List<String>,
    val version: String,
    val date: String
)
