package com.cmdruid.pubsub.integration

import com.cmdruid.pubsub.testing.IntegrationTestBase
import com.cmdruid.pubsub.testing.TestServiceContainer
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

/**
 * Integration tests for service startup and lifecycle
 * Tests the complete service initialization process
 */
class ServiceStartupIntegrationTest : IntegrationTestBase() {
    
    @Test
    fun `service should initialize all components correctly`() = runTest {
        // Given: Test configuration
        val config = createTestConfiguration("Service Startup Test")
        
        // When: Service starts
        val result = workflowBuilder
            .givenConfiguration(config)
            .givenNetworkState(TestServiceContainer.NetworkState.CONNECTED)
            .givenBatteryLevel(80)
            .whenServiceStarts()
            .thenExpectSubscriptionRegistered(config.subscriptionId)
            .execute()
        
        // Then: All components should be initialized
        assertTrue("Service startup should succeed", result.isSuccess)
        assertNotNull("Service components should be created", result.serviceComponents)
        
        val components = result.serviceComponents!!
        assertNotNull("Configuration manager should exist", components.configurationManager)
        assertNotNull("Subscription manager should exist", components.subscriptionManager)
        assertNotNull("Event cache should exist", components.eventCache)
        assertNotNull("Relay connection manager should exist", components.relayConnectionManager)
        assertNotNull("Message processor should exist", components.messageProcessor)
        assertNotNull("Health monitor should exist", components.healthMonitor)
        
        // Configuration should be properly registered
        val enabledConfigs = components.configurationManager.getEnabledConfigurations()
        assertTrue("Should have enabled configurations", enabledConfigs.isNotEmpty())
        assertEquals("Should have our test configuration", config.name, enabledConfigs[0].name)
    }
    
    @Test
    fun `service should handle multiple relay configurations`() = runTest {
        // Given: Configuration with multiple relays
        val testRelay1 = testWebSocketServer.start()
        val testRelay2 = "wss://relay2.test.com"
        
        val config = createTestConfiguration(
            name = "Multi-Relay Test",
            relayUrls = listOf(testRelay1, testRelay2)
        )
        
        // When: Service starts with multi-relay configuration
        val result = workflowBuilder
            .givenConfiguration(config)
            .whenServiceStarts()
            .thenExpectSubscriptionRegistered(config.subscriptionId)
            .thenExpectRelayConnection(testRelay1)
            .thenExpectRelayConnection(testRelay2)
            .execute()
        
        // Then: Should handle multiple relays
        assertTrue("Multi-relay startup should succeed", result.isSuccess)
        assertEquals("Should establish connections to both relays", 2, result.establishedConnections.size)
        assertTrue("Should connect to first relay", result.establishedConnections.contains(testRelay1))
        assertTrue("Should connect to second relay", result.establishedConnections.contains(testRelay2))
    }
    
    @Test
    fun `service should handle network unavailable during startup`() = runTest {
        // Given: Configuration but no network
        val config = createTestConfiguration("Network Unavailable Test")
        
        // When: Service starts without network
        val result = workflowBuilder
            .givenConfiguration(config)
            .givenNetworkState(TestServiceContainer.NetworkState.DISCONNECTED)
            .whenServiceStarts()
            .execute()
        
        // Then: Should handle gracefully
        assertTrue("Should handle network unavailable gracefully", result.isSuccess)
        assertNotNull("Service components should still be created", result.serviceComponents)
    }
    
    @Test
    fun `service should handle low battery during startup`() = runTest {
        // Given: Configuration with low battery
        val config = createTestConfiguration("Low Battery Test")
        
        // When: Service starts with low battery
        val result = workflowBuilder
            .givenConfiguration(config)
            .givenBatteryLevel(15) // Low battery
            .whenServiceStarts()
            .thenExpectSubscriptionRegistered(config.subscriptionId)
            .execute()
        
        // Then: Should apply battery optimizations
        assertTrue("Should handle low battery startup", result.isSuccess)
        // Battery optimizations would be tested in more detail later
    }
    
    @Test
    fun `service should handle empty configuration list`() = runTest {
        // Given: No configurations
        
        // When: Service starts with no configurations
        val result = workflowBuilder
            .givenNetworkState(TestServiceContainer.NetworkState.CONNECTED)
            .whenServiceStarts()
            .execute()
        
        // Then: Should start successfully but with no subscriptions
        assertTrue("Should handle empty config list", result.isSuccess)
        assertNotNull("Service components should be created", result.serviceComponents)
        
        val subscriptionManager = result.serviceComponents!!.subscriptionManager
        assertTrue("Should have no active subscriptions", subscriptionManager.getActiveSubscriptionIds().isEmpty())
    }
    
    @Test
    fun `service should handle configuration with invalid relay URLs`() = runTest {
        // Given: Configuration with invalid relay URL
        val config = createTestConfiguration(
            name = "Invalid Relay Test",
            relayUrls = listOf("invalid-url", "not-a-websocket-url")
        )
        
        // When: Service starts with invalid URLs
        val result = workflowBuilder
            .givenConfiguration(config)
            .whenServiceStarts()
            .execute()
        
        // Then: Should handle gracefully (connections will fail but service should start)
        assertTrue("Should handle invalid URLs gracefully", result.isSuccess)
        assertNotNull("Service components should be created", result.serviceComponents)
    }
    
    @Test
    fun `service components should have proper dependencies`() = runTest {
        // Given: Standard configuration
        val config = createTestConfiguration("Dependency Test")
        
        // When: Service starts
        val result = workflowBuilder
            .givenConfiguration(config)
            .whenServiceStarts()
            .execute()
        
        // Then: Components should be properly wired
        assertTrue("Service should start successfully", result.isSuccess)
        
        val components = result.serviceComponents!!
        
        // Verify component dependencies are satisfied
        // (In a real implementation, we would check that components reference each other correctly)
        assertNotNull("RelayConnectionManager should exist", components.relayConnectionManager)
        assertNotNull("MessageProcessor should exist", components.messageProcessor)
        assertNotNull("SubscriptionManager should exist", components.subscriptionManager)
        assertNotNull("EventCache should exist", components.eventCache)
        
        // Test that components can interact
        val subscriptionManager = components.subscriptionManager
        val eventCache = components.eventCache
        
        // These should not throw exceptions
        subscriptionManager.getStats()
        eventCache.getStats()
    }
}
