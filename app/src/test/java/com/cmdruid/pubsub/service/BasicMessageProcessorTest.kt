package com.cmdruid.pubsub.service

import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.data.ConfigurationManager
import com.cmdruid.pubsub.logging.UnifiedLogger
import com.cmdruid.pubsub.nostr.NostrFilter
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Simplified test class for MessageProcessor - Beta Focus
 * Tests core functionality without complex async verification
 */
class BasicMessageProcessorTest {

    private lateinit var mockConfigurationManager: ConfigurationManager
    private lateinit var mockSubscriptionManager: SubscriptionManager
    private lateinit var mockEventCache: EventCache
    private lateinit var mockEventNotificationManager: EventNotificationManager
    private lateinit var mockUnifiedLogger: UnifiedLogger
    private lateinit var messageProcessor: MessageProcessor

    @Before
    fun setup() {
        // Create relaxed mocks for beta testing
        mockConfigurationManager = mockk(relaxed = true)
        mockSubscriptionManager = mockk(relaxed = true)
        mockEventCache = mockk(relaxed = true)
        mockEventNotificationManager = mockk(relaxed = true)
        mockUnifiedLogger = mockk(relaxed = true)

        // Setup basic configuration
        val testConfig = Configuration(
            id = "test-config",
            name = "Test",
            isEnabled = true,
            relayUrls = listOf("wss://relay1.com"),
            filter = NostrFilter(kinds = listOf(1)),
            targetUri = "app://test",
            subscriptionId = "test-sub-123"
        )

        every { mockSubscriptionManager.isActiveSubscription("test-sub-123") } returns true
        every { mockSubscriptionManager.getConfigurationId("test-sub-123") } returns "test-config"
        every { mockConfigurationManager.getConfigurationById("test-config") } returns testConfig
        every { mockEventCache.hasSeenEvent(any()) } returns false

        messageProcessor = MessageProcessor(
            configurationManager = mockConfigurationManager,
            subscriptionManager = mockSubscriptionManager,
            eventCache = mockEventCache,
            eventNotificationManager = mockEventNotificationManager,
            unifiedLogger = mockUnifiedLogger,
            sendDebugLog = { _ -> }
        )
    }

    @Test
    fun `should handle valid EVENT messages without exceptions`() = runTest {
        // Given: Valid EVENT message
        val eventJson = """
        {
            "id": "abcd1234567890abcdef1234567890abcdef1234567890abcdef1234567890ab",
            "pubkey": "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
            "created_at": 1640995300,
            "kind": 1,
            "tags": [],
            "content": "test content",
            "sig": "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
        }
        """.trimIndent()
        val messageText = """["EVENT","test-sub-123",$eventJson]"""
        
        // When: Processing the message
        messageProcessor.processMessage(messageText, "test-sub-123", "wss://relay1.com")
        
        // Then: Should complete without exceptions
        delay(100) // Allow async processing
    }

    @Test
    fun `should handle EOSE messages without exceptions`() = runTest {
        // Given: EOSE message
        val messageText = """["EOSE","test-sub-123"]"""
        
        // When: Processing the message
        messageProcessor.processMessage(messageText, "test-sub-123", "wss://relay1.com")
        
        // Then: Should complete without exceptions
        delay(50)
    }

    @Test
    fun `should handle NOTICE messages without exceptions`() = runTest {
        // Given: NOTICE message
        val messageText = """["NOTICE","Test notice"]"""
        
        // When: Processing the message
        messageProcessor.processMessage(messageText, "test-sub-123", "wss://relay1.com")
        
        // Then: Should complete without exceptions
        delay(50)
    }

    @Test
    fun `should handle OK messages without exceptions`() = runTest {
        // Given: OK message
        val messageText = """["OK","event123",true,""]"""
        
        // When: Processing the message
        messageProcessor.processMessage(messageText, "test-sub-123", "wss://relay1.com")
        
        // Then: Should complete without exceptions
        delay(50)
    }

    @Test
    fun `should handle malformed messages gracefully`() = runTest {
        // Given: Malformed message
        val messageText = """invalid json"""
        
        // When: Processing the message
        messageProcessor.processMessage(messageText, "test-sub-123", "wss://relay1.com")
        
        // Then: Should complete without exceptions
        delay(50)
    }

    @Test
    fun `should handle multiple messages without exceptions`() = runTest {
        // Given: Multiple valid messages
        val eventJson = """
        {
            "id": "abcd1234567890abcdef1234567890abcdef1234567890abcdef1234567890ab",
            "pubkey": "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
            "created_at": 1640995300,
            "kind": 1,
            "tags": [],
            "content": "test content",
            "sig": "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
        }
        """.trimIndent()
        val messageText = """["EVENT","test-sub-123",$eventJson]"""
        
        // When: Processing multiple messages
        repeat(10) { index ->
            val modifiedJson = eventJson.replace("abcd1234567890abcdef", "event$index".padEnd(20, '0'))
            val message = """["EVENT","test-sub-123",$modifiedJson]"""
            messageProcessor.processMessage(message, "test-sub-123", "wss://relay1.com")
        }
        
        // Then: Should complete without exceptions
        delay(200) // Allow batch processing
    }
}
