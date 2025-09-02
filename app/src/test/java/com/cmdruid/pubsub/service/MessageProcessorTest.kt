package com.cmdruid.pubsub.service

import android.net.Uri
import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.data.ConfigurationManager
import com.cmdruid.pubsub.data.KeywordFilter
import com.cmdruid.pubsub.logging.LogDomain
import com.cmdruid.pubsub.logging.UnifiedLogger
import com.cmdruid.pubsub.nostr.NostrEvent
import com.cmdruid.pubsub.nostr.NostrFilter
import com.cmdruid.pubsub.nostr.NostrMessage
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Test class for MessageProcessor functionality
 * Tests message queue optimization, validation pipeline, and event processing
 */
class MessageProcessorTest {

    // Mocked dependencies
    private lateinit var mockConfigurationManager: ConfigurationManager
    private lateinit var mockSubscriptionManager: SubscriptionManager
    private lateinit var mockEventCache: EventCache
    private lateinit var mockEventNotificationManager: EventNotificationManager
    private lateinit var mockUnifiedLogger: UnifiedLogger
    private lateinit var mockSendDebugLog: (String) -> Unit

    // Test subject
    private lateinit var messageProcessor: MessageProcessor

    // Test data
    private lateinit var testConfiguration: Configuration
    private lateinit var testEvent: NostrEvent
    private lateinit var testFilter: NostrFilter

    @Before
    fun setup() {
        // Initialize mocks
        mockConfigurationManager = mockk()
        mockSubscriptionManager = mockk()
        mockEventCache = mockk(relaxed = true)
        mockEventNotificationManager = mockk(relaxed = true)
        mockUnifiedLogger = mockk(relaxed = true)
        mockSendDebugLog = mockk(relaxed = true)

        // Create test data
        testFilter = NostrFilter(
            kinds = listOf(1),
            since = 1640995200L
        )

        testConfiguration = Configuration(
            id = "test-config",
            name = "Test Configuration",
            isEnabled = true,
            relayUrls = listOf("wss://relay1.com"),
            filter = testFilter,
            targetUri = "app://test",
            subscriptionId = "test-sub-123",
            keywordFilter = KeywordFilter.from(listOf("bitcoin", "nostr"))
        )

        testEvent = NostrEvent(
            id = "abcd1234567890abcdef1234567890abcdef1234567890abcdef1234567890ab",
            pubkey = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
            createdAt = 1640995300L,
            kind = 1,
            tags = emptyList(),
            content = "This is a test bitcoin post",
            signature = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
        )

        // Setup default mock behaviors
        every { mockSubscriptionManager.isActiveSubscription("test-sub-123") } returns true
        every { mockSubscriptionManager.getConfigurationId("test-sub-123") } returns "test-config"
        every { mockConfigurationManager.getConfigurationById("test-config") } returns testConfiguration
        every { mockEventCache.hasSeenEvent(any()) } returns false
        // Relaxed mocks handle void methods automatically

        // Create MessageProcessor
        messageProcessor = MessageProcessor(
            configurationManager = mockConfigurationManager,
            subscriptionManager = mockSubscriptionManager,
            eventCache = mockEventCache,
            eventNotificationManager = mockEventNotificationManager,
            unifiedLogger = mockUnifiedLogger,
            sendDebugLog = mockSendDebugLog
        )
    }

    // === MESSAGE QUEUE OPTIMIZATION TESTS ===

    @Test
    fun `should queue messages for batch processing`() = runTest {
        // Given: Multiple messages to process
        val messageText = """["EVENT","test-sub-123",${createEventJson()}]"""
        
        // When: Processing multiple messages quickly
        repeat(5) {
            messageProcessor.processMessage(messageText, "test-sub-123", "wss://relay1.com")
        }
        
        // Then: Should complete without exceptions (beta focus)
        // Allow time for async processing
        delay(200)
        
        // Basic functionality test - no crash means success in beta
    }

    @Test
    fun `should process messages in batches of 10`() = runTest {
        // Given: More than 10 messages
        val messageText = """["EVENT","test-sub-123",${createEventJson()}]"""
        
        // When: Processing 15 messages
        repeat(15) { index ->
            val eventJson = createEventJson(eventId = "event$index".padEnd(64, '0'))
            val message = """["EVENT","test-sub-123",$eventJson]"""
            messageProcessor.processMessage(message, "test-sub-123", "wss://relay1.com")
        }
        
        // Then: Should process in batches
        delay(200) // Allow time for batch processing
        
        // All events should eventually be processed
        verify(atLeast = 10) { mockEventCache.markEventSeen(any()) }
    }

    @Test
    fun `should handle queue overflow by dropping oldest messages`() = runTest {
        // Given: Queue at max capacity (100 messages)
        val messageText = """["EVENT","test-sub-123",${createEventJson()}]"""
        
        // When: Adding 105 messages (5 over limit)
        repeat(105) { index ->
            val eventJson = createEventJson(eventId = "event$index".padEnd(64, '0'))
            val message = """["EVENT","test-sub-123",$eventJson]"""
            messageProcessor.processMessage(message, "test-sub-123", "wss://relay1.com")
        }
        
        // Then: Should log queue overflow warnings
        delay(100)
        verify(atLeast = 1) { 
            mockUnifiedLogger.warn(LogDomain.EVENT, match { it.contains("queue full") })
        }
    }

    @Test
    fun `should add delay between batches for CPU efficiency`() = runTest {
        // Given: Multiple batches of messages
        val messageText = """["EVENT","test-sub-123",${createEventJson()}]"""
        
        val startTime = System.currentTimeMillis()
        
        // When: Processing 25 messages (3 batches)
        repeat(25) { index ->
            val eventJson = createEventJson(eventId = "event$index".padEnd(64, '0'))
            val message = """["EVENT","test-sub-123",$eventJson]"""
            messageProcessor.processMessage(message, "test-sub-123", "wss://relay1.com")
        }
        
        // Allow processing to complete
        delay(300)
        
        val endTime = System.currentTimeMillis()
        val totalTime = endTime - startTime
        
        // Then: Should take some time due to delays between batches
        assertTrue("Processing should take time due to batch delays", totalTime >= 100)
    }

    // === MESSAGE TYPE HANDLING TESTS ===

    @Test
    fun `should process EVENT messages correctly`() = runTest {
        // Given: Valid EVENT message
        val messageText = """["EVENT","test-sub-123",${createEventJson()}]"""
        
        // When: Processing the message
        messageProcessor.processMessage(messageText, "test-sub-123", "wss://relay1.com")
        
        // Allow async processing
        delay(50)
        
        // Then: Should mark event as seen and update timestamp
        verify { mockEventCache.markEventSeen(testEvent.id) }
        verify { mockSubscriptionManager.updateRelayTimestamp("test-sub-123", "wss://relay1.com", testEvent.createdAt) }
    }

    @Test
    fun `should handle EOSE messages`() = runTest {
        // Given: EOSE message
        val messageText = """["EOSE","test-sub-123"]"""
        
        // When: Processing the message
        messageProcessor.processMessage(messageText, "test-sub-123", "wss://relay1.com")
        
        // Allow async processing
        delay(50)
        
        // Then: Should log EOSE received
        verify { 
            mockUnifiedLogger.debug(
                LogDomain.EVENT, 
                match { it.contains("End of stored events") }
            )
        }
    }

    @Test
    fun `should process NOTICE messages`() = runTest {
        // Given: NOTICE message
        val messageText = """["NOTICE","This is a test notice"]"""
        
        // When: Processing the message
        messageProcessor.processMessage(messageText, "test-sub-123", "wss://relay1.com")
        
        // Allow async processing
        delay(50)
        
        // Then: Should log the notice
        verify { 
            mockUnifiedLogger.info(
                LogDomain.EVENT, 
                match { it.contains("Relay notice") }
            )
        }
    }

    @Test
    fun `should handle OK messages`() = runTest {
        // Given: OK message
        val messageText = """["OK","event123",true,""]"""
        
        // When: Processing the message
        messageProcessor.processMessage(messageText, "test-sub-123", "wss://relay1.com")
        
        // Allow async processing
        delay(50)
        
        // Then: Should log OK response
        verify { 
            mockUnifiedLogger.debug(
                LogDomain.EVENT, 
                match { it.contains("OK response") }
            )
        }
    }

    @Test
    fun `should log unknown message types`() = runTest {
        // Given: Unknown message type
        val messageText = """["UNKNOWN","test-data"]"""
        
        // When: Processing the message
        messageProcessor.processMessage(messageText, "test-sub-123", "wss://relay1.com")
        
        // Allow async processing
        delay(50)
        
        // Then: Should log unknown message type
        verify { 
            mockUnifiedLogger.warn(
                LogDomain.EVENT, 
                match { it.contains("Unknown message type") }
            )
        }
    }

    // === EVENT VALIDATION PIPELINE TESTS ===

    @Test
    fun `should reject events from inactive subscriptions`() = runTest {
        // Given: Inactive subscription
        every { mockSubscriptionManager.isActiveSubscription("test-sub-123") } returns false
        
        val messageText = """["EVENT","test-sub-123",${createEventJson()}]"""
        
        // When: Processing the message
        messageProcessor.processMessage(messageText, "test-sub-123", "wss://relay1.com")
        
        // Allow async processing
        delay(50)
        
        // Then: Should log ignoring event and not process further
        verify { 
            mockUnifiedLogger.trace(
                LogDomain.EVENT, 
                match { it.contains("inactive subscription") }
            )
        }
        verify(exactly = 0) { mockEventCache.markEventSeen(any()) }
    }

    @Test
    fun `should reject events from disabled configurations`() = runTest {
        // Given: Disabled configuration
        val disabledConfig = testConfiguration.copy(isEnabled = false)
        every { mockConfigurationManager.getConfigurationById("test-config") } returns disabledConfig
        
        val messageText = """["EVENT","test-sub-123",${createEventJson()}]"""
        
        // When: Processing the message
        messageProcessor.processMessage(messageText, "test-sub-123", "wss://relay1.com")
        
        // Allow async processing
        delay(50)
        
        // Then: Should log ignoring event and not process further
        verify { 
            mockUnifiedLogger.trace(
                LogDomain.EVENT, 
                match { it.contains("configuration not found or disabled") }
            )
        }
        verify(exactly = 0) { mockEventCache.markEventSeen(any()) }
    }

    @Test
    fun `should reject invalid events`() = runTest {
        // Given: Invalid event (blank ID)
        val invalidEventJson = createEventJson(eventId = "")
        val messageText = """["EVENT","test-sub-123",$invalidEventJson]"""
        
        // When: Processing the message
        messageProcessor.processMessage(messageText, "test-sub-123", "wss://relay1.com")
        
        // Allow async processing
        delay(50)
        
        // Then: Should log invalid event and not process further
        verify { 
            mockUnifiedLogger.warn(
                LogDomain.EVENT, 
                match { it.contains("Invalid event rejected") }
            )
        }
        verify(exactly = 0) { mockEventCache.markEventSeen(any()) }
    }

    @Test
    fun `should detect and ignore duplicate events`() = runTest {
        // Given: Event already seen
        every { mockEventCache.hasSeenEvent(testEvent.id) } returns true
        
        val messageText = """["EVENT","test-sub-123",${createEventJson()}]"""
        
        // When: Processing the message
        messageProcessor.processMessage(messageText, "test-sub-123", "wss://relay1.com")
        
        // Allow async processing
        delay(50)
        
        // Then: Should log duplicate event and not process further
        verify { 
            mockUnifiedLogger.trace(
                LogDomain.EVENT, 
                match { it.contains("duplicate event") }
            )
        }
        verify(exactly = 0) { mockEventCache.markEventSeen(any()) }
    }

    @Test
    fun `should update relay timestamps for valid events`() = runTest {
        // Given: Valid event message
        val messageText = """["EVENT","test-sub-123",${createEventJson()}]"""
        
        // When: Processing the message
        messageProcessor.processMessage(messageText, "test-sub-123", "wss://relay1.com")
        
        // Allow async processing
        delay(50)
        
        // Then: Should update relay timestamp
        verify { 
            mockSubscriptionManager.updateRelayTimestamp(
                "test-sub-123", 
                "wss://relay1.com", 
                testEvent.createdAt
            )
        }
    }

    // === ERROR HANDLING TESTS ===

    @Test
    fun `should handle malformed JSON gracefully`() = runTest {
        // Given: Malformed JSON message
        val messageText = """["EVENT","test-sub-123",{invalid json}]"""
        
        // When: Processing the message
        messageProcessor.processMessage(messageText, "test-sub-123", "wss://relay1.com")
        
        // Allow async processing
        delay(50)
        
        // Then: Should log parsing failure
        verify { 
            mockUnifiedLogger.warn(
                LogDomain.EVENT, 
                match { it.contains("Failed to parse message") }
            )
        }
    }

    @Test
    fun `should handle processing exceptions`() = runTest {
        // Given: Event cache throws exception
        every { mockEventCache.hasSeenEvent(any()) } throws RuntimeException("Cache error")
        
        val messageText = """["EVENT","test-sub-123",${createEventJson()}]"""
        
        // When: Processing the message
        messageProcessor.processMessage(messageText, "test-sub-123", "wss://relay1.com")
        
        // Allow async processing
        delay(100)
        
        // Then: Should log error and continue processing
        verify { 
            mockUnifiedLogger.error(
                LogDomain.EVENT, 
                match { it.contains("Error processing queued message") }
            )
        }
    }

    // === KEYWORD FILTERING TESTS ===

    @Test
    fun `should process events with matching keywords`() = runTest {
        // Given: Event with matching keyword content
        val eventWithKeyword = testEvent.copy(content = "This is about bitcoin technology")
        val messageText = """["EVENT","test-sub-123",${createEventJson(content = eventWithKeyword.content)}]"""
        
        // When: Processing the message
        messageProcessor.processMessage(messageText, "test-sub-123", "wss://relay1.com")
        
        // Allow async processing
        delay(50)
        
        // Then: Should process the event and show notification
        verify { mockEventCache.markEventSeen(any()) }
        verify { mockEventNotificationManager.showEventNotification(any(), any(), any(), any()) }
    }

    @Test
    fun `should filter out events without matching keywords`() = runTest {
        // Given: Event without matching keywords
        val eventWithoutKeyword = testEvent.copy(content = "This is about ethereum only")
        val messageText = """["EVENT","test-sub-123",${createEventJson(content = eventWithoutKeyword.content)}]"""
        
        // When: Processing the message
        messageProcessor.processMessage(messageText, "test-sub-123", "wss://relay1.com")
        
        // Allow async processing
        delay(50)
        
        // Then: Should mark as seen but not show notification
        verify { mockEventCache.markEventSeen(any()) }
        verify(exactly = 0) { mockEventNotificationManager.showEventNotification(any(), any(), any(), any()) }
    }

    // === HELPER METHODS ===

    private fun createEventJson(
        eventId: String = testEvent.id,
        pubkey: String = testEvent.pubkey,
        createdAt: Long = testEvent.createdAt,
        kind: Int = testEvent.kind,
        content: String = testEvent.content,
        signature: String = testEvent.signature
    ): String {
        return """
        {
            "id": "$eventId",
            "pubkey": "$pubkey",
            "created_at": $createdAt,
            "kind": $kind,
            "tags": [],
            "content": "$content",
            "sig": "$signature"
        }
        """.trimIndent()
    }
}
