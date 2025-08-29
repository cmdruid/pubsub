package com.cmdruid.pubsub.service

import android.net.Uri
import android.util.Log
import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.data.ConfigurationManager
import com.cmdruid.pubsub.nostr.NostrEvent
import com.cmdruid.pubsub.nostr.NostrMessage
import com.cmdruid.pubsub.utils.KeywordMatcher
import com.cmdruid.pubsub.utils.UriBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles WebSocket message parsing, event processing, and notification triggering
 * Extracted from PubSubService to improve maintainability
 */
class MessageHandler(
    private val configurationManager: ConfigurationManager,
    private val subscriptionManager: SubscriptionManager,
    private val eventCache: EventCache,
    private val eventNotificationManager: EventNotificationManager,
    private val relayConnections: ConcurrentHashMap<String, PubSubService.RelayConnection>,
    private val sendDebugLog: (String) -> Unit
) {
    
    companion object {
        private const val TAG = "MessageHandler"
    }
    
    /**
     * Handle incoming WebSocket message
     */
    fun handleWebSocketMessage(messageText: String, originalConfiguration: Configuration, relayUrl: String) {
        // Process messages on a background thread to prevent ANRs
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val parsedMessage = NostrMessage.parseMessage(messageText)
                
                when (parsedMessage) {
                    is NostrMessage.ParsedMessage.EventMessage -> {
                        handleEventMessage(parsedMessage, originalConfiguration, relayUrl)
                    }
                    is NostrMessage.ParsedMessage.EoseMessage -> {
                        handleEoseMessage(parsedMessage, originalConfiguration, relayUrl)
                    }
                    is NostrMessage.ParsedMessage.NoticeMessage -> {
                        handleNoticeMessage(parsedMessage, originalConfiguration)
                    }
                    is NostrMessage.ParsedMessage.OkMessage -> {
                        handleOkMessage(parsedMessage, originalConfiguration)
                    }
                    is NostrMessage.ParsedMessage.UnknownMessage -> {
                        handleUnknownMessage(parsedMessage, originalConfiguration)
                    }
                    null -> {
                        sendDebugLog("❌ Failed to parse message for ${originalConfiguration.name}: $messageText")
                    }
                }
            } catch (e: Exception) {
                sendDebugLog("❌ Error processing message for ${originalConfiguration.name}: ${e.message}")
            }
        }
    }
    
    /**
     * Handle EVENT messages
     */
    private fun handleEventMessage(
        parsedMessage: NostrMessage.ParsedMessage.EventMessage,
        originalConfiguration: Configuration,
        relayUrl: String
    ) {
        val subscriptionId = parsedMessage.subscriptionId
        val event = parsedMessage.event
        
        // Mark subscription as confirmed when we receive events
        val connection = relayConnections[relayUrl]
        if (connection != null && connection.subscriptionId == subscriptionId) {
            if (!connection.subscriptionConfirmed) {
                connection.subscriptionConfirmed = true
                val timeSinceSubscription = System.currentTimeMillis() - connection.subscriptionSentTime
                sendDebugLog("✅ Subscription confirmed for $relayUrl after ${timeSinceSubscription}ms")
            }
        }
        
        // Enhanced event processing with subscription tracking and duplicate detection
        
        // 1. Check if subscription is still active
        if (!subscriptionManager.isActiveSubscription(subscriptionId)) {
            sendDebugLog("🚫 Ignoring event from inactive subscription: $subscriptionId")
            return
        }
        
        // 2. Get the current configuration for this subscription to avoid stale data
        val configurationId = subscriptionManager.getConfigurationId(subscriptionId)
        val currentConfiguration = if (configurationId != null) {
            configurationManager.getConfigurationById(configurationId)
        } else {
            null
        }
        
        if (currentConfiguration == null || !currentConfiguration.isEnabled) {
            sendDebugLog("🚫 Ignoring event: configuration not found or disabled for subscription $subscriptionId")
            return
        }
        
        // 3. Validate event structure
        if (!event.isValid()) {
            sendDebugLog("❌ Invalid event rejected: ${event.id.take(8)}...")
            return
        }
        
        // 4. Check for duplicate events
        if (eventCache.hasSeenEvent(event.id)) {
            sendDebugLog("🔄 Ignoring duplicate event: ${event.id.take(8)}...")
            return
        }
        
        // 5. Mark as seen and update timestamp tracking
        eventCache.markEventSeen(event.id)
        subscriptionManager.updateLastEventTimestamp(subscriptionId, event.createdAt)
        
        sendDebugLog("📨 Event: ${event.id.take(8)}... (${currentConfiguration.name}) [${NostrEvent.getKindName(event.kind)}]")
        handleNostrEvent(event, currentConfiguration, subscriptionId)
    }
    
    /**
     * Handle EOSE (End Of Stored Events) messages
     */
    private fun handleEoseMessage(
        parsedMessage: NostrMessage.ParsedMessage.EoseMessage,
        originalConfiguration: Configuration,
        relayUrl: String
    ) {
        val subscriptionId = parsedMessage.subscriptionId
        
        // Mark subscription as confirmed when we receive EOSE
        val connection = relayConnections[relayUrl]
        if (connection != null && connection.subscriptionId == subscriptionId) {
            if (!connection.subscriptionConfirmed) {
                connection.subscriptionConfirmed = true
                val timeSinceSubscription = System.currentTimeMillis() - connection.subscriptionSentTime
                sendDebugLog("✅ Subscription confirmed (EOSE) for $relayUrl after ${timeSinceSubscription}ms")
            }
        }
        
        val configurationId = subscriptionManager.getConfigurationId(subscriptionId)
        val currentConfiguration = if (configurationId != null) {
            configurationManager.getConfigurationById(configurationId)
        } else {
            originalConfiguration
        }
        sendDebugLog("✅ End of stored events for ${currentConfiguration?.name ?: "unknown"}")
    }
    
    /**
     * Handle NOTICE messages
     */
    private fun handleNoticeMessage(
        parsedMessage: NostrMessage.ParsedMessage.NoticeMessage,
        originalConfiguration: Configuration
    ) {
        sendDebugLog("📢 Relay notice for ${originalConfiguration.name}: ${parsedMessage.notice}")
    }
    
    /**
     * Handle OK messages
     */
    private fun handleOkMessage(
        parsedMessage: NostrMessage.ParsedMessage.OkMessage,
        originalConfiguration: Configuration
    ) {
        Log.d(TAG, "OK response for ${originalConfiguration.name}: ${parsedMessage.eventId} - ${parsedMessage.success}")
    }
    
    /**
     * Handle unknown message types
     */
    private fun handleUnknownMessage(
        parsedMessage: NostrMessage.ParsedMessage.UnknownMessage,
        originalConfiguration: Configuration
    ) {
        sendDebugLog("⚠️ Unknown message type for ${originalConfiguration.name}: ${parsedMessage.type}")
    }
    
    /**
     * Process a validated Nostr event
     */
    private fun handleNostrEvent(event: NostrEvent, configuration: Configuration, subscriptionId: String) {
        // Check event size before processing
        val eventSizeBytes = UriBuilder.getEventSizeBytes(event)
        val eventSizeKB = eventSizeBytes / 1024
        val isEventTooLarge = UriBuilder.isEventTooLarge(event)
        
        // Apply keyword filtering if configured
        val keywordFilter = configuration.keywordFilter
        if (keywordFilter != null && !keywordFilter.isEmpty()) {
            if (!KeywordMatcher.shouldProcessContent(event.content)) {
                sendDebugLog("⏭️ Event ${event.id.take(8)}... skipped: insufficient content for keyword matching")
                return
            }
            
            val matchStats = KeywordMatcher.getMatchStats(event.content, keywordFilter)
            
            if (!matchStats.hasMatches) {
                sendDebugLog("🚫 Event ${event.id.take(8)}... filtered: no keyword matches (${matchStats.keywordCount} keywords, ${matchStats.processingTimeMs}ms)")
                return
            }
            
            val matchingKeywords = matchStats.matchingKeywords.joinToString(", ") { "\"$it\"" }
            sendDebugLog("✅ Event ${event.id.take(8)}... matched keywords: $matchingKeywords (${matchStats.processingTimeMs}ms)")
        } else {
            sendDebugLog("📋 Event ${event.id.take(8)}... (no keyword filter)")
        }
        
        val eventUri = UriBuilder.buildEventUri(configuration.targetUri, event, configuration.relayUrls)
        if (eventUri == null) {
            sendDebugLog("❌ Failed to build event URI for ${configuration.name}")
            return
        }
        
        // Log event processing with size information
        val sizeInfo = if (isEventTooLarge) {
            "ID only (event ${eventSizeKB}KB > 500KB limit)"
        } else {
            "with full event data (${eventSizeKB}KB)"
        }
        
        sendDebugLog("📤 Event ${event.id.take(8)}... → ${configuration.name}")
        
        showEventNotification(event, eventUri, configuration, subscriptionId)
    }
    
    /**
     * Show event notification
     */
    private fun showEventNotification(event: NostrEvent, uri: Uri, configuration: Configuration, subscriptionId: String) {
        eventNotificationManager.showEventNotification(event, uri, configuration, subscriptionId)
    }
}
