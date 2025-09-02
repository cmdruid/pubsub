package com.cmdruid.pubsub.service

import android.net.Uri
import android.util.Log
import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.data.ConfigurationManager
import com.cmdruid.pubsub.logging.LogDomain
import com.cmdruid.pubsub.logging.UnifiedLogger
import com.cmdruid.pubsub.nostr.NostrEvent
import com.cmdruid.pubsub.nostr.NostrMessage
import com.cmdruid.pubsub.utils.KeywordMatcher
import com.cmdruid.pubsub.utils.UriBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * CLEAN message processing pipeline
 * BREAKING CHANGE: Complete replacement of MessageHandler with modern architecture
 * 
 * Key improvements:
 * - Clean separation of concerns
 * - Simple validation pipeline
 * - Efficient event processing
 * - No dependency on legacy RelayConnection
 */
class MessageProcessor(
    private val configurationManager: ConfigurationManager,
    private val subscriptionManager: SubscriptionManager,
    private val eventCache: EventCache,
    private val eventNotificationManager: EventNotificationManager,
    private val unifiedLogger: UnifiedLogger,
    private val sendDebugLog: (String) -> Unit
) {
    
    companion object {
        private const val TAG = "MessageProcessor"
    }
    
    /**
     * Clean message processing pipeline
     */
    fun processMessage(messageText: String, subscriptionId: String, relayUrl: String) {
        // Process messages on a background thread to prevent ANRs
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val parsedMessage = NostrMessage.parseMessage(messageText)
                
                when (parsedMessage) {
                    is NostrMessage.ParsedMessage.EventMessage -> {
                        processEvent(parsedMessage.event, subscriptionId, relayUrl)
                    }
                    is NostrMessage.ParsedMessage.EoseMessage -> {
                        confirmSubscription(subscriptionId, relayUrl)
                    }
                    is NostrMessage.ParsedMessage.NoticeMessage -> {
                        processNotice(parsedMessage.notice, subscriptionId)
                    }
                    is NostrMessage.ParsedMessage.OkMessage -> {
                        processOkMessage(parsedMessage.eventId, parsedMessage.success, subscriptionId)
                    }
                    is NostrMessage.ParsedMessage.UnknownMessage -> {
                        unifiedLogger.warn(LogDomain.EVENT, "Unknown message type: ${parsedMessage.type}")
                    }
                    null -> {
                        unifiedLogger.warn(LogDomain.EVENT, "Failed to parse message from $relayUrl")
                    }
                }
            } catch (e: Exception) {
                unifiedLogger.error(LogDomain.EVENT, "Error processing message from $relayUrl: ${e.message}")
            }
        }
    }
    
    /**
     * Process EVENT messages with clean validation pipeline
     */
    private fun processEvent(event: NostrEvent, subscriptionId: String, relayUrl: String) {
        // 1. Validate subscription is active
        if (!subscriptionManager.isActiveSubscription(subscriptionId)) {
            unifiedLogger.trace(LogDomain.EVENT, "Ignoring event from inactive subscription: $subscriptionId")
            return
        }
        
        // 2. Get current configuration
        val configurationId = subscriptionManager.getConfigurationId(subscriptionId)
        val configuration = if (configurationId != null) {
            configurationManager.getConfigurationById(configurationId)
        } else {
            null
        }
        
        if (configuration == null || !configuration.isEnabled) {
            unifiedLogger.trace(LogDomain.EVENT, "Ignoring event: configuration not found or disabled for subscription $subscriptionId")
            return
        }
        
        // 3. Validate event structure
        if (!event.isValid()) {
            unifiedLogger.warn(LogDomain.EVENT, "Invalid event rejected: ${event.id.take(8)}...")
            return
        }
        
        // 4. Check for duplicate events
        if (eventCache.hasSeenEvent(event.id)) {
            unifiedLogger.trace(LogDomain.EVENT, "Ignoring duplicate event: ${event.id.take(8)}...")
            return
        }
        
        // 5. Mark as seen and update PER-RELAY timestamp tracking
        eventCache.markEventSeen(event.id)
        subscriptionManager.updateRelayTimestamp(subscriptionId, relayUrl, event.createdAt)
        
        unifiedLogger.debug(LogDomain.EVENT, "Processing event: ${event.id.take(8)}... from $relayUrl", mapOf(
            "configuration" to configuration.name,
            "event_kind" to NostrEvent.getKindName(event.kind),
            "relay" to relayUrl.substringAfter("://").take(20)
        ))
        
        // 6. Process the validated event
        handleValidatedEvent(event, configuration, subscriptionId)
    }
    
    /**
     * Handle EOSE (End Of Stored Events) messages
     */
    private fun confirmSubscription(subscriptionId: String, relayUrl: String) {
        unifiedLogger.debug(LogDomain.EVENT, "End of stored events for subscription $subscriptionId from $relayUrl")
        // Note: Subscription confirmation is now handled by RelayConnectionManager
    }
    
    /**
     * Handle NOTICE messages
     */
    private fun processNotice(notice: String, subscriptionId: String) {
        unifiedLogger.info(LogDomain.EVENT, "Relay notice for subscription $subscriptionId: $notice")
    }
    
    /**
     * Handle OK messages
     */
    private fun processOkMessage(eventId: String, success: Boolean, subscriptionId: String) {
        unifiedLogger.debug(LogDomain.EVENT, "OK response for subscription $subscriptionId: $eventId - $success")
    }
    
    /**
     * Process a validated Nostr event
     */
    private fun handleValidatedEvent(event: NostrEvent, configuration: Configuration, subscriptionId: String) {
        // Apply keyword filtering if configured
        val keywordFilter = configuration.keywordFilter
        if (keywordFilter != null && !keywordFilter.isEmpty()) {
            if (!KeywordMatcher.shouldProcessContent(event.content)) {
                unifiedLogger.trace(LogDomain.EVENT, "Event ${event.id.take(8)}... skipped: insufficient content for keyword matching")
                return
            }
            
            val matchStats = KeywordMatcher.getMatchStats(event.content, keywordFilter)
            
            if (!matchStats.hasMatches) {
                unifiedLogger.trace(LogDomain.EVENT, "Event ${event.id.take(8)}... filtered: no keyword matches")
                return
            }
            
            val matchingKeywords = matchStats.matchingKeywords.joinToString(", ") { "\"$it\"" }
            unifiedLogger.debug(LogDomain.EVENT, "Event ${event.id.take(8)}... matched keywords: $matchingKeywords")
        }
        
        // Build event URI
        val eventUri = UriBuilder.buildEventUri(configuration.targetUri, event, configuration.relayUrls)
        if (eventUri == null) {
            unifiedLogger.error(LogDomain.EVENT, "Failed to build event URI for ${configuration.name}")
            return
        }
        
        // Check event size
        val eventSizeBytes = UriBuilder.getEventSizeBytes(event)
        val eventSizeKB = eventSizeBytes / 1024
        val isEventTooLarge = UriBuilder.isEventTooLarge(event)
        
        val sizeInfo = if (isEventTooLarge) {
            "ID only (event ${eventSizeKB}KB > 500KB limit)"
        } else {
            "with full event data (${eventSizeKB}KB)"
        }
        
        unifiedLogger.info(LogDomain.EVENT, "Event processed: ${event.id.take(8)}... → ${configuration.name} ($sizeInfo)")
        
        // Show notification
        showEventNotification(event, eventUri, configuration, subscriptionId)
    }
    
    /**
     * Show event notification
     */
    private fun showEventNotification(event: NostrEvent, uri: Uri, configuration: Configuration, subscriptionId: String) {
        eventNotificationManager.showEventNotification(event, uri, configuration, subscriptionId)
    }
}
