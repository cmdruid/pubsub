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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Clean message processing pipeline with battery optimization
 * 
 * Key features:
 * - Clean separation of concerns
 * - Efficient validation pipeline with caching
 * - Batched message processing for battery efficiency
 * - Background thread processing to prevent ANRs
 * - Comprehensive event filtering and routing
 */
class MessageProcessor(
    private val configurationManager: ConfigurationManager,
    private val subscriptionManager: SubscriptionManager,
    private val eventCache: EventCache,
    private val eventNotificationManager: EventNotificationManager,
    private val metricsCollector: MetricsCollector,
    private val unifiedLogger: UnifiedLogger,
    private val sendDebugLog: (String) -> Unit,
    private val relayConnectionManager: RelayConnectionManager? = null
) {
    
    companion object {
        private const val TAG = "MessageProcessor"
        private const val MAX_QUEUE_SIZE = 100 // Prevent memory issues
        private const val MAX_UNMATCHED_EVENTS_BEFORE_CANCELLATION = 5 // Cancel after 5 unmatched events
    }
    
    // Lightweight tracker for subscription cancellations
    private val cancellationTracker = SubscriptionCancellationTracker(unifiedLogger)
    
    // Track unmatched events per subscription to detect unwanted subscriptions
    private val unmatchedEventCounts = mutableMapOf<String, Int>()
    
    // BATTERY OPTIMIZATION: Message queue to batch process messages during high volume
    private val messageQueue = mutableListOf<QueuedMessage>()
    private val queueLock = Any()
    private var isProcessingQueue = false
    
    data class QueuedMessage(
        val messageText: String,
        val subscriptionId: String,
        val relayUrl: String,
        val timestamp: Long
    )
    
    /**
     * Clean message processing pipeline with BATTERY OPTIMIZATION
     */
    fun processMessage(messageText: String, subscriptionId: String, relayUrl: String) {
        // BATTERY OPTIMIZATION: Queue messages during high volume to batch process
        synchronized(queueLock) {
            if (messageQueue.size >= MAX_QUEUE_SIZE) {
                // Drop oldest message to prevent memory issues
                messageQueue.removeAt(0)
                unifiedLogger.debug(LogDomain.EVENT, "Message queue full, dropping oldest message")
            }
            
            messageQueue.add(QueuedMessage(messageText, subscriptionId, relayUrl, System.currentTimeMillis()))
            
            // Start processing if not already running
            if (!isProcessingQueue) {
                isProcessingQueue = true
                processMessageQueue()
            }
        }
    }
    
    /**
     * Process queued messages in batches for better battery efficiency
     */
    private fun processMessageQueue() {
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                val messagesToProcess = synchronized(queueLock) {
                    if (messageQueue.isEmpty()) {
                        isProcessingQueue = false
                        return@synchronized emptyList<QueuedMessage>()
                    }
                    // Process up to 10 messages at a time for efficiency
                    val batch = messageQueue.take(10)
                    messageQueue.removeAll(batch.toSet())
                    batch
                }
                
                if (messagesToProcess.isEmpty()) break
                
                // Process batch of messages
                messagesToProcess.forEach { queuedMessage ->
                    try {
                        processMessageInternal(queuedMessage.messageText, queuedMessage.subscriptionId, queuedMessage.relayUrl)
                    } catch (e: Exception) {
                        unifiedLogger.error(LogDomain.EVENT, "Error processing queued message from ${queuedMessage.relayUrl}: ${e.message}")
                    }
                }
                
                // Small delay between batches to prevent CPU spike
                delay(10)
            }
        }
    }
    
    /**
     * Internal message processing logic
     */
    private fun processMessageInternal(messageText: String, subscriptionId: String, relayUrl: String) {
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
        
        // 2. Get current configuration (with validation to prevent cross-subscription leakage)
        val configurationId = subscriptionManager.getConfigurationId(subscriptionId)
        if (configurationId == null) {
            unifiedLogger.warn(LogDomain.EVENT, "CRITICAL: No configuration ID for subscription $subscriptionId - potential cross-subscription bug!")
            return
        }
        
        val configuration = configurationManager.getConfigurationById(configurationId)
        if (configuration == null) {
            unifiedLogger.warn(LogDomain.EVENT, "CRITICAL: Configuration $configurationId not found for subscription $subscriptionId - potential cross-subscription bug!")
            return
        }
        
        if (!configuration.isEnabled) {
            unifiedLogger.trace(LogDomain.EVENT, "Ignoring event: configuration disabled for subscription $subscriptionId")
            return
        }
        
        // VALIDATION: Ensure subscription ID matches configuration
        if (configuration.subscriptionId != subscriptionId) {
            unifiedLogger.error(LogDomain.EVENT, "CRITICAL BUG: Subscription ID mismatch! Event subscription: $subscriptionId, Config subscription: ${configuration.subscriptionId}")
            return
        }
        
        // 3. Validate event structure
        if (!event.isValid()) {
            unifiedLogger.warn(LogDomain.EVENT, "Invalid event rejected: ${event.id.take(8)}...")
            // Track parsing error
            metricsCollector.trackError(
                errorType = ErrorType.PARSING,
                relayUrl = relayUrl,
                subscriptionId = subscriptionId,
                errorMessage = "Invalid event structure: ${event.id.take(8)}"
            )
            return
        }
        
        // 4. Check for duplicate events
        if (eventCache.hasSeenEvent(event.id)) {
            // Track duplicate detection metrics
            metricsCollector.trackDuplicateEvent(
                eventProcessed = false,
                duplicateDetected = true,
                duplicatePrevented = true,
                usedPreciseTimestamp = false,
                networkDataSaved = 1024 // Estimate saved bandwidth
            )
            unifiedLogger.trace(LogDomain.EVENT, "Ignoring duplicate event: ${event.id.take(8)}...")
            return
        }
        
        // 5. Mark as seen and update PER-RELAY timestamp tracking
        val wasNewEvent = eventCache.markEventSeen(event.id)
        if (!wasNewEvent) {
            // This is a secondary duplicate check - EventCache found it was already processed
            // This can happen with cross-session persistence or race conditions
            metricsCollector.trackDuplicateEvent(
                eventProcessed = false,
                duplicateDetected = true,
                duplicatePrevented = true,
                usedPreciseTimestamp = false,
                networkDataSaved = 1024 // Saved by not reprocessing
            )
            unifiedLogger.trace(LogDomain.EVENT, "Event ${event.id.take(8)}... already processed (cross-session or race condition)")
            return
        }
        
        subscriptionManager.updateRelayTimestamp(subscriptionId, relayUrl, event.createdAt)
        
        // 6. Track event processing metrics
        val lastTimestamp = subscriptionManager.getRelayTimestamp(subscriptionId, relayUrl)
        val usedPreciseTimestamp = lastTimestamp != null && lastTimestamp > 0
        
        // Track the event processing (this was missing!)
        metricsCollector.trackDuplicateEvent(
            eventProcessed = true,
            duplicateDetected = false, // We already filtered duplicates above
            duplicatePrevented = false,
            usedPreciseTimestamp = usedPreciseTimestamp
        )
        
        // Track event flow for subscription health monitoring
        metricsCollector.trackEventFlow(
            subscriptionId = subscriptionId,
            relayUrl = relayUrl,
            eventsReceived = 1,
            timeSpan = 60000 // 1 minute window for flow rate calculation
        )
        
        unifiedLogger.debug(LogDomain.EVENT, "Processing event: ${event.id.take(8)}... from $relayUrl", mapOf(
            "configuration" to configuration.name,
            "event_kind" to NostrEvent.getKindName(event.kind),
            "relay" to relayUrl.substringAfter("://").take(20),
            "used_precise_timestamp" to usedPreciseTimestamp
        ))
        
        // 7. Process the validated event
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
        // Apply local filters first (cheapest operations)
        if (shouldExcludeMentionsToSelf(event, configuration)) {
            unifiedLogger.trace(LogDomain.EVENT, "Event ${event.id.take(8)}... filtered: mentions to self")
            return
        }
        
        if (shouldExcludeRepliesToEvents(event, configuration)) {
            unifiedLogger.trace(LogDomain.EVENT, "Event ${event.id.take(8)}... filtered: reply to events")
            return
        }
        
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
    
    /**
     * Check if event should be excluded due to mentions to self
     * Filter out events where the author is also mentioned in "p" tags
     */
    private fun shouldExcludeMentionsToSelf(event: NostrEvent, configuration: Configuration): Boolean {
        if (!configuration.excludeMentionsToSelf) return false
        
        // Cache author pubkey lookup
        val authorPubkey = event.pubkey
        
        // Efficient tag scanning using any() for early exit
        return event.tags.any { tag ->
            tag.size > 1 && tag[0] == "p" && tag[1] == authorPubkey
        }
    }
    
    /**
     * Check if event should be excluded as reply to events
     * Filter out events from filtered authors that contain "e" tags (event references/replies)
     */
    private fun shouldExcludeRepliesToEvents(event: NostrEvent, configuration: Configuration): Boolean {
        if (!configuration.excludeRepliesToEvents) return false
        
        val authorPubkey = event.pubkey
        val filteredAuthors = configuration.filter.authors ?: return false
        
        // Early exit if author not in filter
        if (authorPubkey !in filteredAuthors) return false
        
        // Check for event references (replies) - optimized scanning
        return event.tags.any { tag ->
            tag.size > 1 && tag[0] == "e"
        }
    }
}
