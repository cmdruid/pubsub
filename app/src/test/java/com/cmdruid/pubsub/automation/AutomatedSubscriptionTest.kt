package com.cmdruid.pubsub.automation

import com.cmdruid.pubsub.nostr.NostrEvent
import com.cmdruid.pubsub.nostr.NostrFilter
import com.cmdruid.pubsub.testing.TestWebSocketServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

/**
 * Automated subscription testing using TestWebSocketServer
 * These tests can be run with adb to validate real device functionality
 */
class AutomatedSubscriptionTest {

    private lateinit var testServer: TestWebSocketServer
    private lateinit var relayUrl: String

    @Before
    fun setup() {
        testServer = TestWebSocketServer()
        relayUrl = testServer.start().replace("http://", "ws://")
        println("🧪 [AUTOMATED TEST] Test relay started at: $relayUrl")
    }

    @After
    fun teardown() {
        testServer.stop()
        println("🧪 [AUTOMATED TEST] Test relay stopped")
    }

    @Test
    fun `test_relay_must_accept_subscriptions`() = runTest {
        // CRITICAL: Test relay must properly handle subscription requests
        
        val subscriptionId = "automated-test-sub-001"
        val filter = NostrFilter(kinds = listOf(1), since = System.currentTimeMillis() / 1000)
        
        // Simulate subscription request that would come from the app
        val subscriptionMessage = """["REQ","$subscriptionId",${com.google.gson.Gson().toJson(filter)}]"""
        
        // When: Processing subscription request
        val responses = testServer.processClientMessage(subscriptionMessage)
        
        // Then: Should handle subscription without errors
        assertTrue("Test relay must handle subscription requests", responses.isNotEmpty())
        
        // Should not contain error messages
        val hasErrors = responses.any { it.contains("\"error\"") || it.contains("\"NOTICE\"") }
        assertFalse("Subscription request must not generate errors: $responses", hasErrors)
        
        println("✅ [AUTOMATED TEST] Test relay accepts subscriptions correctly")
    }

    @Test
    fun `test_relay_must_send_events_to_subscribers`() = runTest {
        // CRITICAL: Test relay must send events to active subscriptions
        
        val subscriptionId = "automated-test-sub-002"
        val filter = NostrFilter(kinds = listOf(1))
        
        // When: Setting up subscription
        val subscriptionMessage = """["REQ","$subscriptionId",${com.google.gson.Gson().toJson(filter)}]"""
        testServer.processClientMessage(subscriptionMessage)
        
        // When: Sending event through test relay
        val testEvent = NostrEvent(
            id = "automated_test_event_${System.currentTimeMillis()}",
            pubkey = "test_pubkey_automated",
            createdAt = System.currentTimeMillis() / 1000,
            kind = 1,
            tags = emptyList(),
            content = "Automated test event content",
            signature = "test_signature_automated"
        )
        
        testServer.sendEvent(testEvent, subscriptionId)
        
        // Then: Should generate event message for subscription
        val responses = testServer.getGeneratedResponses()
        val eventResponses = responses.filter { it.startsWith("""["EVENT","$subscriptionId"""") }
        
        assertTrue("Test relay must send events to active subscriptions", eventResponses.isNotEmpty())
        assertTrue("Event response must contain test content", 
            eventResponses.any { it.contains("Automated test event content") })
        
        println("✅ [AUTOMATED TEST] Test relay sends events to subscribers")
    }

    @Test
    fun `test_relay_must_handle_multiple_subscriptions`() = runTest {
        // CRITICAL: Test relay must isolate multiple subscriptions
        
        val sub1 = "automated-multi-sub-001"
        val sub2 = "automated-multi-sub-002"
        val filter = NostrFilter(kinds = listOf(1))
        
        // When: Setting up multiple subscriptions
        val subscription1 = """["REQ","$sub1",${com.google.gson.Gson().toJson(filter)}]"""
        val subscription2 = """["REQ","$sub2",${com.google.gson.Gson().toJson(filter)}]"""
        
        testServer.processClientMessage(subscription1)
        testServer.processClientMessage(subscription2)
        
        // When: Sending events to different subscriptions
        val event1 = createTestEvent("Event for subscription 1")
        val event2 = createTestEvent("Event for subscription 2")
        
        testServer.sendEvent(event1, sub1)
        testServer.sendEvent(event2, sub2)
        
        // Then: Each subscription should receive only its events
        val responses = testServer.getGeneratedResponses()
        val sub1Responses = responses.filter { it.startsWith("""["EVENT","$sub1"""") }
        val sub2Responses = responses.filter { it.startsWith("""["EVENT","$sub2"""") }
        
        assertTrue("Subscription 1 must receive its events", sub1Responses.isNotEmpty())
        assertTrue("Subscription 2 must receive its events", sub2Responses.isNotEmpty())
        
        // Verify content isolation
        assertTrue("Sub1 must receive sub1 content", 
            sub1Responses.any { it.contains("Event for subscription 1") })
        assertTrue("Sub2 must receive sub2 content",
            sub2Responses.any { it.contains("Event for subscription 2") })
        
        // CRITICAL: No cross-contamination
        assertFalse("Sub1 must NOT receive sub2 content",
            sub1Responses.any { it.contains("Event for subscription 2") })
        assertFalse("Sub2 must NOT receive sub1 content", 
            sub2Responses.any { it.contains("Event for subscription 1") })
        
        println("✅ [AUTOMATED TEST] Test relay handles multiple subscriptions correctly")
    }

    @Test
    fun `test_relay_must_handle_network_interruption_simulation`() = runTest {
        // CRITICAL: Test relay must support network interruption testing
        
        val subscriptionId = "automated-network-test-sub"
        val filter = NostrFilter(kinds = listOf(1))
        
        // When: Setting up subscription
        val subscriptionMessage = """["REQ","$subscriptionId",${com.google.gson.Gson().toJson(filter)}]"""
        testServer.processClientMessage(subscriptionMessage)
        
        // When: Simulating network interruption
        testServer.simulateNetworkInterruption(duration = 3000L)
        
        val interruptedEvent = createTestEvent("Event during interruption")
        testServer.sendEvent(interruptedEvent, subscriptionId)
        
        // Should queue the event
        delay(500)
        
        // When: Restoring network
        testServer.restoreNetwork()
        delay(500)
        
        // Then: Should send queued events after restoration
        val responses = testServer.getGeneratedResponses()
        val eventResponses = responses.filter { it.contains("Event during interruption") }
        
        assertTrue("Test relay must handle network interruption and send queued events", 
            eventResponses.isNotEmpty())
        
        println("✅ [AUTOMATED TEST] Test relay handles network interruption simulation")
    }

    @Test
    fun `test_relay_must_provide_subscription_confirmation`() = runTest {
        // CRITICAL: Test relay must confirm subscriptions (EOSE messages)
        
        val subscriptionId = "automated-confirmation-sub"
        val filter = NostrFilter(kinds = listOf(1), limit = 10)
        
        // When: Setting up subscription with limit (should trigger EOSE)
        val subscriptionMessage = """["REQ","$subscriptionId",${com.google.gson.Gson().toJson(filter)}]"""
        val responses = testServer.processClientMessage(subscriptionMessage)
        
        // Then: Should provide some form of confirmation
        assertTrue("Test relay must respond to subscription requests", responses.isNotEmpty())
        
        // For a REQ with limit, relay should eventually send EOSE
        // Our test relay should support this for realistic testing
        println("✅ [AUTOMATED TEST] Test relay provides subscription responses")
    }

    private fun createTestEvent(content: String): NostrEvent {
        return NostrEvent(
            id = "auto_test_${System.currentTimeMillis()}_${(0..999).random()}",
            pubkey = "automated_test_pubkey_${(0..99).random()}",
            createdAt = System.currentTimeMillis() / 1000,
            kind = 1,
            tags = emptyList(),
            content = content,
            signature = "automated_test_signature_${System.currentTimeMillis()}"
        )
    }
}
