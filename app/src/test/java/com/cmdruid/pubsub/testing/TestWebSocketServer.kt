package com.cmdruid.pubsub.testing

import com.cmdruid.pubsub.nostr.NostrEvent
import com.cmdruid.pubsub.nostr.NostrFilter
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * NIP-01 compliant test WebSocket server for integration testing
 * Implements minimal relay functionality with in-memory storage
 * 
 * This is a simplified approach that focuses on message processing
 * rather than complex WebSocket mocking for beta testing needs.
 */
class TestWebSocketServer {
    
    companion object {
        private const val TAG = "TestWebSocketServer"
    }
    
    // HTTP server infrastructure (simplified approach)
    private val server = MockWebServer()
    private val gson = Gson()
    
    // NIP-01 relay state (in-memory)
    private val eventStore = ConcurrentHashMap<String, NostrEvent>()
    private val activeSubscriptions = ConcurrentHashMap<String, NostrFilter>()
    
    // Test control
    private var isStarted = false
    private val processedMessages = mutableListOf<String>()
    private val generatedResponses = mutableListOf<String>()
    
    // Advanced testing features
    private var networkInterrupted = false
    private val eventQueue = mutableListOf<Pair<NostrEvent, String>>()
    
    /**
     * Start the test server
     * Returns the base URL for testing
     */
    fun start(): String {
        if (isStarted) {
            throw IllegalStateException("Server already started")
        }
        
        server.start()
        isStarted = true
        
        val url = server.url("/").toString()
        println("[$TAG] Test server started at: $url")
        return url
    }
    
    /**
     * Stop the test server
     */
    fun stop() {
        if (isStarted) {
            server.shutdown()
            cleanup()
            isStarted = false
            println("[$TAG] Test server stopped")
        }
    }
    
    /**
     * Process incoming NIP-01 client message and return relay responses
     * This is the core method that implements NIP-01 relay behavior
     */
    fun processClientMessage(message: String): List<String> {
        println("[$TAG] Processing client message: $message")
        processedMessages.add(message)
        
        return try {
            val messageArray = gson.fromJson(message, Array::class.java)
            if (messageArray.isEmpty()) {
                return listOf(buildNoticeMessage("error: empty message"))
            }
            
            when (val messageType = messageArray[0] as? String) {
                "EVENT" -> handleEventMessage(messageArray)
                "REQ" -> handleReqMessage(messageArray)
                "CLOSE" -> handleCloseMessage(messageArray)
                null -> listOf(buildNoticeMessage("error: invalid message format"))
                else -> listOf(buildNoticeMessage("unsupported: unknown message type '$messageType'"))
            }
        } catch (e: JsonSyntaxException) {
            listOf(buildNoticeMessage("error: invalid JSON format"))
        } catch (e: Exception) {
            listOf(buildNoticeMessage("error: message processing failed - ${e.message}"))
        }
    }
    
    /**
     * Handle EVENT message: ["EVENT", <event JSON>]
     * Returns OK message indicating acceptance or rejection
     */
    private fun handleEventMessage(messageArray: Array<*>): List<String> {
        if (messageArray.size < 2) {
            return listOf(buildNoticeMessage("error: EVENT message requires event data"))
        }
        
        return try {
            val eventJson = messageArray[1]
            val event = when (eventJson) {
                is String -> gson.fromJson(eventJson, NostrEvent::class.java)
                else -> gson.fromJson(gson.toJson(eventJson), NostrEvent::class.java)
            }
            
            // Validate event according to NIP-01
            val response = if (!event.isValid()) {
                buildOkMessage(event.id, false, "invalid: event validation failed")
            } else if (eventStore.containsKey(event.id)) {
                buildOkMessage(event.id, true, "duplicate: already have this event")
            } else {
                // Store event and return success
                eventStore[event.id] = event
                println("[$TAG] Stored event: ${event.id.take(8)}... (kind: ${event.kind})")
                
                // Send event to matching subscriptions
                notifyMatchingSubscriptions(event)
                
                buildOkMessage(event.id, true, "")
            }
            
            generatedResponses.add(response)
            listOf(response)
            
        } catch (e: Exception) {
            val errorResponse = buildOkMessage("unknown", false, "error: failed to parse event - ${e.message}")
            generatedResponses.add(errorResponse)
            listOf(errorResponse)
        }
    }
    
    /**
     * Handle REQ message: ["REQ", <subscription_id>, <filters...>]
     * Returns matching events followed by EOSE
     */
    private fun handleReqMessage(messageArray: Array<*>): List<String> {
        if (messageArray.size < 2) {
            return listOf(buildNoticeMessage("error: REQ message requires subscription ID"))
        }
        
        val subscriptionId = messageArray[1] as? String
        if (subscriptionId.isNullOrBlank()) {
            return listOf(buildNoticeMessage("error: subscription ID cannot be empty"))
        }
        
        return try {
            // Parse filters (everything after subscription_id)
            val filterJsons = messageArray.drop(2)
            val filters = filterJsons.map { filterJson ->
                when (filterJson) {
                    is String -> gson.fromJson(filterJson, NostrFilter::class.java)
                    else -> gson.fromJson(gson.toJson(filterJson), NostrFilter::class.java)
                }
            }
            
            // Store subscription
            if (filters.isNotEmpty()) {
                activeSubscriptions[subscriptionId] = filters.first() // Use first filter for simplicity
                println("[$TAG] Registered subscription: $subscriptionId with ${filters.size} filter(s)")
            }
            
            // Find matching events
            val matchingEvents = eventStore.values.filter { event ->
                filters.any { filter -> matchesFilter(event, filter) }
            }.sortedByDescending { it.createdAt } // Newest first per NIP-01
            
            // Apply limit if specified
            val limitedEvents = if (filters.isNotEmpty() && filters.first().limit != null) {
                matchingEvents.take(filters.first().limit!!)
            } else {
                matchingEvents
            }
            
            // Build response messages
            val responses = mutableListOf<String>()
            
            // Send matching events
            limitedEvents.forEach { event ->
                val eventMessage = buildEventMessage(subscriptionId, event)
                responses.add(eventMessage)
                generatedResponses.add(eventMessage)
            }
            
            // Send EOSE (End of Stored Events)
            val eoseMessage = buildEoseMessage(subscriptionId)
            responses.add(eoseMessage)
            generatedResponses.add(eoseMessage)
            
            println("[$TAG] Sent ${limitedEvents.size} events for subscription: $subscriptionId")
            responses
            
        } catch (e: Exception) {
            val errorMessage = buildNoticeMessage("error: failed to process subscription - ${e.message}")
            generatedResponses.add(errorMessage)
            listOf(errorMessage)
        }
    }
    
    /**
     * Handle CLOSE message: ["CLOSE", <subscription_id>]
     */
    private fun handleCloseMessage(messageArray: Array<*>): List<String> {
        if (messageArray.size < 2) {
            return listOf(buildNoticeMessage("error: CLOSE message requires subscription ID"))
        }
        
        val subscriptionId = messageArray[1] as? String
        if (subscriptionId.isNullOrBlank()) {
            return listOf(buildNoticeMessage("error: subscription ID cannot be empty"))
        }
        
        // Remove subscription
        val removed = activeSubscriptions.remove(subscriptionId)
        if (removed != null) {
            println("[$TAG] Closed subscription: $subscriptionId")
        }
        
        // CLOSE messages typically don't require a response per NIP-01
        return emptyList()
    }
    
    /**
     * Check if event matches filter according to NIP-01 specification
     */
    private fun matchesFilter(event: NostrEvent, filter: NostrFilter): Boolean {
        // Kind filtering
        if (filter.kinds != null && event.kind !in filter.kinds) {
            return false
        }
        
        // Author filtering  
        if (filter.authors != null && event.pubkey !in filter.authors) {
            return false
        }
        
        // Timestamp filtering
        if (filter.since != null && event.createdAt < filter.since) {
            return false
        }
        
        if (filter.until != null && event.createdAt > filter.until) {
            return false
        }
        
        // Basic hashtag filtering (simplified for testing)
        filter.hashtagEntries?.forEach { hashtagEntry ->
            val matchingTags = event.getAllTags("t") // Look for hashtag tags
            if (!matchingTags.any { it.getOrNull(1) == hashtagEntry.value }) {
                return false
            }
        }
        
        return true
    }
    
    /**
     * Notify matching subscriptions when new event is published
     */
    private fun notifyMatchingSubscriptions(event: NostrEvent) {
        activeSubscriptions.forEach { (subscriptionId, filter) ->
            if (matchesFilter(event, filter)) {
                val eventMessage = buildEventMessage(subscriptionId, event)
                generatedResponses.add(eventMessage)
                println("[$TAG] Notified subscription $subscriptionId about new event")
            }
        }
    }
    
    // === NIP-01 Message Builders ===
    
    private fun buildEventMessage(subscriptionId: String, event: NostrEvent): String {
        return """["EVENT","$subscriptionId",${gson.toJson(event)}]"""
    }
    
    private fun buildOkMessage(eventId: String, accepted: Boolean, message: String): String {
        return """["OK","$eventId",$accepted,"$message"]"""
    }
    
    private fun buildEoseMessage(subscriptionId: String): String {
        return """["EOSE","$subscriptionId"]"""
    }
    
    private fun buildClosedMessage(subscriptionId: String, message: String): String {
        return """["CLOSED","$subscriptionId","$message"]"""
    }
    
    private fun buildNoticeMessage(message: String): String {
        return """["NOTICE","$message"]"""
    }
    
    // === Test Control Methods ===
    
    /**
     * Simulate external event publication to the relay
     * This triggers event delivery to matching subscriptions
     */
    fun publishEvent(event: NostrEvent) {
        if (!event.isValid()) {
            println("[$TAG] Cannot publish invalid event: ${event.id}")
            return
        }
        
        // Store event
        eventStore[event.id] = event
        println("[$TAG] Published event: ${event.id.take(8)}... (kind: ${event.kind})")
        
        // Notify matching subscriptions
        notifyMatchingSubscriptions(event)
    }
    
    /**
     * Simulate relay notice
     */
    fun simulateRelayNotice(message: String) {
        val noticeMessage = buildNoticeMessage(message)
        generatedResponses.add(noticeMessage)
        println("[$TAG] Sent notice: $message")
    }
    
    /**
     * Simulate network disconnection
     */
    fun simulateDisconnection() {
        // Clear all active subscriptions (simulates connection loss)
        val subscriptionCount = activeSubscriptions.size
        activeSubscriptions.clear()
        println("[$TAG] Simulated disconnection - cleared $subscriptionCount subscriptions")
    }
    
    /**
     * Simulate network error
     */
    fun simulateNetworkError(errorMessage: String = "network: connection failed") {
        val noticeMessage = buildNoticeMessage("error: $errorMessage")
        generatedResponses.add(noticeMessage)
        println("[$TAG] Simulated network error: $errorMessage")
    }
    
    /**
     * Get the last N generated responses (for test verification)
     */
    fun getLastResponses(count: Int): List<String> {
        return generatedResponses.takeLast(count)
    }
    
    /**
     * Check if a specific message was generated
     */
    fun hasGeneratedMessage(messagePattern: String): Boolean {
        return generatedResponses.any { it.contains(messagePattern) }
    }
    
    /**
     * Get all stored events (for test verification)
     */
    fun getStoredEvents(): List<NostrEvent> {
        return eventStore.values.toList()
    }
    
    /**
     * Get active subscriptions (for test verification)
     */
    fun getActiveSubscriptions(): Map<String, NostrFilter> {
        return activeSubscriptions.toMap()
    }
    
    /**
     * Get all messages processed by the server (for test verification)
     */
    fun getProcessedMessages(): List<String> {
        return processedMessages.toList()
    }
    
    /**
     * Get all responses generated by the server (for test verification)
     */
    fun getGeneratedResponses(): List<String> {
        return generatedResponses.toList()
    }
    
    /**
     * Send an event to a specific subscription (for testing)
     */
    fun sendEvent(event: NostrEvent, subscriptionId: String) {
        if (networkInterrupted) {
            // Queue event for later delivery
            eventQueue.add(event to subscriptionId)
            println("[$TAG] Event queued due to network interruption: ${event.content.take(50)}")
            return
        }
        
        // Store event in relay
        eventStore[event.id] = event
        
        // Send to active subscription if it exists
        if (activeSubscriptions.containsKey(subscriptionId)) {
            val eventMessage = buildEventMessage(subscriptionId, event)
            generatedResponses.add(eventMessage)
            println("[$TAG] Event sent to subscription $subscriptionId: ${event.content.take(50)}")
        } else {
            println("[$TAG] No active subscription $subscriptionId for event: ${event.content.take(50)}")
        }
    }
    
    /**
     * Simulate network interruption
     */
    fun simulateNetworkInterruption(duration: Long) {
        networkInterrupted = true
        println("[$TAG] Network interruption started for ${duration}ms")
    }
    
    /**
     * Restore network and send queued events
     */
    fun restoreNetwork() {
        networkInterrupted = false
        println("[$TAG] Network restored, sending ${eventQueue.size} queued events")
        
        // Send all queued events
        eventQueue.forEach { (event, subscriptionId) ->
            sendEvent(event, subscriptionId)
        }
        eventQueue.clear()
    }
    
    /**
     * Clear all server state
     */
    fun clearState() {
        eventStore.clear()
        activeSubscriptions.clear()
        processedMessages.clear()
        generatedResponses.clear()
        eventQueue.clear()
        networkInterrupted = false
        println("[$TAG] Cleared all server state")
    }
    
    /**
     * Wait for next client request (for test synchronization)
     */
    fun takeRequest(timeout: Long = 5, unit: TimeUnit = TimeUnit.SECONDS): RecordedRequest? {
        return try {
            server.takeRequest(timeout, unit)
        } catch (e: Exception) {
            null
        }
    }
    
    private fun cleanup() {
        clearState()
    }
}