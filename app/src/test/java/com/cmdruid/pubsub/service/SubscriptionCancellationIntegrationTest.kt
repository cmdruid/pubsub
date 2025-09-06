package com.cmdruid.pubsub.service

import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.data.ConfigurationManager
import com.cmdruid.pubsub.logging.LogDomain
import com.cmdruid.pubsub.logging.UnifiedLogger
import com.cmdruid.pubsub.nostr.NostrEvent
import com.cmdruid.pubsub.nostr.NostrFilter
import com.cmdruid.pubsub.nostr.NostrMessage
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Integration tests for subscription cancellation functionality
 */
class SubscriptionCancellationIntegrationTest {
    
    private lateinit var mockConfigurationManager: ConfigurationManager
    private lateinit var mockSubscriptionManager: SubscriptionManager
    private lateinit var mockEventCache: EventCache
    private lateinit var mockEventNotificationManager: EventNotificationManager
    private lateinit var mockMetricsCollector: MetricsCollector
    private lateinit var mockUnifiedLogger: UnifiedLogger
    private lateinit var mockRelayConnectionManager: RelayConnectionManager
    private lateinit var messageProcessor: MessageProcessor
    
    private val testConfiguration = Configuration(
        id = "test-config-123",
        name = "Test Configuration",
        subscriptionId = "test-subscription-123",
        isEnabled = true,
        targetUri = "https://example.com/callback",
        relayUrls = listOf("wss://relay.example.com"),
        filter = NostrFilter.empty()
    )
    
    @Before
    fun setUp() {
        mockConfigurationManager = mockk(relaxed = true)
        mockSubscriptionManager = mockk(relaxed = true)
        mockEventCache = mockk(relaxed = true)
        mockEventNotificationManager = mockk(relaxed = true)
        mockMetricsCollector = mockk(relaxed = true)
        mockUnifiedLogger = mockk(relaxed = true)
        mockRelayConnectionManager = mockk(relaxed = true)
        
        // Setup mocks
        every { mockSubscriptionManager.isActiveSubscription(any()) } returns true
        every { mockSubscriptionManager.getConfigurationId(any()) } returns testConfiguration.id
        every { mockConfigurationManager.getConfigurationById(any()) } returns testConfiguration
        every { mockEventCache.hasSeenEvent(any()) } returns false
        every { mockEventCache.markEventSeen(any()) } returns true
        
        messageProcessor = MessageProcessor(
            configurationManager = mockConfigurationManager,
            subscriptionManager = mockSubscriptionManager,
            eventCache = mockEventCache,
            eventNotificationManager = mockEventNotificationManager,
            metricsCollector = mockMetricsCollector,
            unifiedLogger = mockUnifiedLogger,
            sendDebugLog = { message -> mockUnifiedLogger.debug(LogDomain.EVENT, message) },
            relayConnectionManager = mockRelayConnectionManager
        )
    }
    
    @Test
    fun `should process valid event messages normally`() = runTest {
        // Given
        val validEvent = createTestEvent("valid-event-123")
        
        // When
        messageProcessor.processMessage(
            messageText = createEventMessage("test-subscription-123", validEvent),
            subscriptionId = "test-subscription-123",
            relayUrl = "wss://relay.example.com"
        )
        
        // Then - Should not attempt cancellation for valid events
        verify(exactly = 0) { mockRelayConnectionManager.cancelSubscription(any(), any()) }
    }
    
    @Test
    fun `should handle unmatched subscription events`() = runTest {
        // Given
        val unmatchedEvent = createTestEvent("unmatched-event-123")
        val unmatchedSubscriptionId = "unmatched-subscription-456"
        
        // When
        messageProcessor.processMessage(
            messageText = createEventMessage(unmatchedSubscriptionId, unmatchedEvent),
            subscriptionId = "test-subscription-123", // Different subscription ID
            relayUrl = "wss://relay.example.com"
        )
        
        // Then - Should not process the event but should not cancel (this is handled in RelayConnectionManager)
        verify(exactly = 0) { mockEventCache.markEventSeen(any()) }
        verify(exactly = 0) { mockEventNotificationManager.showEventNotification(any(), any(), any(), any()) }
    }
    
    @Test
    fun `should handle EOSE messages normally`() = runTest {
        // Given
        val eoseMessage = "[\"EOSE\",\"test-subscription-123\"]"
        
        // When
        messageProcessor.processMessage(
            messageText = eoseMessage,
            subscriptionId = "test-subscription-123",
            relayUrl = "wss://relay.example.com"
        )
        
        // Then - Should process EOSE normally (no specific verification needed for this test)
    }
    
    @Test
    fun `should handle notice messages normally`() = runTest {
        // Given
        val noticeMessage = "[\"NOTICE\",\"Test notice message\"]"
        
        // When
        messageProcessor.processMessage(
            messageText = noticeMessage,
            subscriptionId = "test-subscription-123",
            relayUrl = "wss://relay.example.com"
        )
        
        // Then - Should process notice normally
        verify { mockUnifiedLogger.info(LogDomain.EVENT, any()) }
    }
    
    @Test
    fun `should handle invalid messages gracefully`() = runTest {
        // Given
        val invalidMessage = "invalid json message"
        
        // When
        messageProcessor.processMessage(
            messageText = invalidMessage,
            subscriptionId = "test-subscription-123",
            relayUrl = "wss://relay.example.com"
        )
        
        // Then - Should not attempt cancellation for invalid messages
        verify(exactly = 0) { mockRelayConnectionManager.cancelSubscription(any(), any()) }
    }
    
    @Test
    fun `should handle disabled configuration`() = runTest {
        // Given
        val disabledConfig = testConfiguration.copy(isEnabled = false)
        every { mockConfigurationManager.getConfigurationById(any()) } returns disabledConfig
        
        val validEvent = createTestEvent("disabled-config-event")
        
        // When
        messageProcessor.processMessage(
            messageText = createEventMessage("test-subscription-123", validEvent),
            subscriptionId = "test-subscription-123",
            relayUrl = "wss://relay.example.com"
        )
        
        // Then - Should not process event for disabled configuration
        verify(exactly = 0) { mockEventCache.markEventSeen(any()) }
        verify(exactly = 0) { mockEventNotificationManager.showEventNotification(any(), any(), any(), any()) }
    }
    
    @Test
    fun `should handle inactive subscription`() = runTest {
        // Given
        every { mockSubscriptionManager.isActiveSubscription(any()) } returns false
        
        val validEvent = createTestEvent("inactive-subscription-event")
        
        // When
        messageProcessor.processMessage(
            messageText = createEventMessage("test-subscription-123", validEvent),
            subscriptionId = "test-subscription-123",
            relayUrl = "wss://relay.example.com"
        )
        
        // Then - Should not process event for inactive subscription
        verify(exactly = 0) { mockEventCache.markEventSeen(any()) }
        verify(exactly = 0) { mockEventNotificationManager.showEventNotification(any(), any(), any(), any()) }
    }
    
    @Test
    fun `should handle duplicate events`() = runTest {
        // Given
        every { mockEventCache.hasSeenEvent(any()) } returns true
        
        val duplicateEvent = createTestEvent("duplicate-event-123")
        
        // When
        messageProcessor.processMessage(
            messageText = createEventMessage("test-subscription-123", duplicateEvent),
            subscriptionId = "test-subscription-123",
            relayUrl = "wss://relay.example.com"
        )
        
        // Then - Should not attempt cancellation for duplicates
        verify(exactly = 0) { mockRelayConnectionManager.cancelSubscription(any(), any()) }
    }
    
    private fun createTestEvent(eventId: String): NostrEvent {
        return NostrEvent(
            id = eventId,
            pubkey = "test-pubkey-123",
            createdAt = System.currentTimeMillis() / 1000,
            kind = 1,
            tags = emptyList(),
            content = "Test event content",
            signature = "test-signature-123"
        )
    }
    
    private fun createEventMessage(subscriptionId: String, event: NostrEvent): String {
        return "[\"EVENT\",\"$subscriptionId\",${com.google.gson.Gson().toJson(event)}]"
    }
}
