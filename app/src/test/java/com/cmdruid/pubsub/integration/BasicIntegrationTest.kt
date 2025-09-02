package com.cmdruid.pubsub.integration

import com.cmdruid.pubsub.testing.IntegrationTestBase
import com.cmdruid.pubsub.testing.NotificationMatcher
import com.cmdruid.pubsub.testing.TestServiceContainer
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

/**
 * Basic integration test to validate our testing framework setup
 * This test ensures all components work together correctly
 */
class BasicIntegrationTest : IntegrationTestBase() {
    
    @Test
    fun `testing framework should initialize correctly`() = runTest {
        // Given: Testing framework is set up (done in IntegrationTestBase)
        
        // When: Creating test configuration and service components
        val config = createTestConfiguration("Framework Test")
        val serviceComponents = testContainer
            .withConfiguration(config)
            .withNetworkState(TestServiceContainer.NetworkState.CONNECTED)
            .withBatteryLevel(80)
            .createFullServiceStack()
        
        // Then: All components should be initialized
        assertNotNull("Configuration manager should be initialized", serviceComponents.configurationManager)
        assertNotNull("Subscription manager should be initialized", serviceComponents.subscriptionManager)
        assertNotNull("Event cache should be initialized", serviceComponents.eventCache)
        assertNotNull("Relay connection manager should be initialized", serviceComponents.relayConnectionManager)
        assertNotNull("Message processor should be initialized", serviceComponents.messageProcessor)
        
        // Configuration should be available
        assertTrue("Configuration should be active", 
            serviceComponents.configurationManager.getEnabledConfigurations().isNotEmpty())
    }
    
    @Test
    fun `test WebSocket server should handle NIP-01 messages`() = runTest {
        // Given: Test WebSocket server
        val serverUrl = testWebSocketServer.start()
        
        // When: Processing NIP-01 messages
        val testEvent = createTestEvent("Test message for NIP-01 validation")
        val eventMessage = """["EVENT",${gson.toJson(testEvent)}]"""
        val responses = testWebSocketServer.processClientMessage(eventMessage)
        
        // Then: Should return proper OK response
        assertEquals(1, responses.size)
        assertTrue("Should be OK message", responses[0].startsWith("""["OK""""))
        assertTrue("Should indicate success", responses[0].contains("true"))
        
        // Event should be stored
        assertEquals(1, testWebSocketServer.getStoredEvents().size)
        assertEquals(testEvent.id, testWebSocketServer.getStoredEvents()[0].id)
    }
    
    @Test
    fun `workflow builder should execute simple workflows`() = runTest {
        // Given: Simple test configuration
        val config = createTestConfiguration("Workflow Test")
        val testEvent = createTestEvent("Workflow test event")
        
        // When: Executing simple workflow
        val result = workflowBuilder
            .givenConfiguration(config)
            .givenNetworkState(TestServiceContainer.NetworkState.CONNECTED)
            .whenServiceStarts()
            .whenRelayPublishesEvent(testEvent)
            .thenExpectEventProcessed(testEvent.id)
            .execute()
        
        // Then: Workflow should complete successfully
        assertTrue("Workflow should succeed", result.isSuccess)
        assertNotNull("Service components should be created", result.serviceComponents)
        assertNull("Should have no exception", result.exception)
    }
    
    @Test
    fun `should handle multiple configurations`() = runTest {
        // Given: Multiple test configurations
        val bitcoinConfig = createTestConfiguration("Bitcoin Alerts", keywords = listOf("bitcoin"))
        val nostrConfig = createTestConfiguration("Nostr Updates", keywords = listOf("nostr"))
        
        // When: Adding multiple configurations
        val result = workflowBuilder
            .givenConfiguration(bitcoinConfig)
            .givenConfiguration(nostrConfig)
            .whenServiceStarts()
            .execute()
        
        // Then: Should handle multiple configurations
        assertTrue("Should handle multiple configs", result.isSuccess)
        
        val configManager = testContainer.getConfigurationManager()
        val enabledConfigs = configManager.getEnabledConfigurations()
        assertEquals("Should have both configurations", 2, enabledConfigs.size)
    }
    
    @Test
    fun `should handle event filtering workflow`() = runTest {
        // Given: Configuration with specific filter
        val config = createTestConfiguration(
            name = "Bitcoin Only",
            keywords = listOf("bitcoin"),
            kinds = listOf(1) // Text notes only
        )
        
        val bitcoinEvent = createBitcoinEvent("Bitcoin price update! #bitcoin")
        val ethereumEvent = createTestEvent("Ethereum update").copy(
            id = generateUniqueEventId(),
            content = "Ethereum price update! #ethereum"
        )
        
        // When: Publishing both events
        val result = workflowBuilder
            .givenConfiguration(config)
            .givenStoredEvent(bitcoinEvent)
            .givenStoredEvent(ethereumEvent)
            .whenServiceStarts()
            .execute()
        
        // Then: Both events should be stored (filtering happens in MessageProcessor)
        assertTrue("Workflow should succeed", result.isSuccess)
        assertEquals("Both events should be in server", 2, testWebSocketServer.getStoredEvents().size)
    }
    
    @Test
    fun `should handle network state changes`() = runTest {
        // Given: Configuration with network dependency
        val config = createTestConfiguration("Network Test")
        
        // When: Changing network state during workflow
        val result = workflowBuilder
            .givenConfiguration(config)
            .givenNetworkState(TestServiceContainer.NetworkState.CONNECTED)
            .whenServiceStarts()
            .whenNetworkChanges(TestServiceContainer.NetworkState.DISCONNECTED)
            .execute()
        
        // Then: Should handle network changes gracefully
        assertTrue("Should handle network changes", result.isSuccess)
    }
    
    // === Helper Methods ===
    
    private val gson = com.google.gson.Gson()
}
