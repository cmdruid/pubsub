package com.cmdruid.pubsub.integration

import com.cmdruid.pubsub.testing.IntegrationTestBase
import com.cmdruid.pubsub.testing.TestServiceContainer
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

/**
 * Real service startup integration tests
 * Tests actual service startup with real WebSocket connections to our test relay
 */
class RealServiceStartupIntegrationTest : IntegrationTestBase() {
    
    @Test
    fun `service should connect to test relay and establish subscription`() = runTest {
        // Given: Test relay server and configuration
        val testRelayUrl = testWebSocketServer.start()
        val config = createTestConfiguration(
            name = "Real Connection Test",
            relayUrls = listOf(testRelayUrl)
        )
        
        // When: Service starts and connects to our test relay
        val result = workflowBuilder
            .givenConfiguration(config)
            .givenNetworkState(TestServiceContainer.NetworkState.CONNECTED)
            .whenServiceStarts()
            .thenExpectSubscriptionRegistered(config.subscriptionId)
            .execute()
        
        // Then: Should successfully connect and subscribe
        assertTrue("Service should start successfully", result.isSuccess)
        
        // Verify subscription was registered in our test relay
        waitMedium() // Allow connection time
        
        // Check if subscription was created in the service
        val subscriptionManager = result.serviceComponents!!.subscriptionManager
        assertTrue("Subscription should be active in service", 
            subscriptionManager.isActiveSubscription(config.subscriptionId))
        
        println("✅ Real service startup test completed successfully")
        println("   Relay URL: $testRelayUrl")
        println("   Subscription ID: ${config.subscriptionId}")
        println("   Execution time: ${result.executionTimeMs}ms")
    }
    
    @Test
    fun `service should handle relay connection failures gracefully`() = runTest {
        // Given: Configuration with non-existent relay
        val config = createTestConfiguration(
            name = "Connection Failure Test",
            relayUrls = listOf("ws://non-existent-relay.com")
        )
        
        // When: Service starts with unreachable relay
        val result = workflowBuilder
            .givenConfiguration(config)
            .givenNetworkState(TestServiceContainer.NetworkState.CONNECTED)
            .whenServiceStarts()
            .execute()
        
        // Then: Service should start despite connection failure
        assertTrue("Service should handle connection failures gracefully", result.isSuccess)
        assertNotNull("Service components should be created", result.serviceComponents)
        
        // Subscription should still be registered (even if connection fails)
        val subscriptionManager = result.serviceComponents!!.subscriptionManager
        assertTrue("Subscription should be registered despite connection failure",
            subscriptionManager.isActiveSubscription(config.subscriptionId))
    }
    
    @Test
    fun `service should establish multiple relay connections`() = runTest {
        // Given: Multiple test relays
        val testRelay1 = testWebSocketServer.start()
        // For this test, we'll use one real test relay and one mock URL
        val testRelay2 = "ws://localhost:8080" // This will fail but should be handled gracefully
        
        val config = createTestConfiguration(
            name = "Multi-Relay Connection Test",
            relayUrls = listOf(testRelay1, testRelay2)
        )
        
        // When: Service starts with multiple relays
        val result = workflowBuilder
            .givenConfiguration(config)
            .whenServiceStarts()
            .thenExpectSubscriptionRegistered(config.subscriptionId)
            .execute()
        
        // Then: Should handle multiple relay connections
        assertTrue("Multi-relay service startup should succeed", result.isSuccess)
        
        // Should attempt connections to both relays
        val subscriptionManager = result.serviceComponents!!.subscriptionManager
        assertTrue("Subscription should be active",
            subscriptionManager.isActiveSubscription(config.subscriptionId))
    }
    
    @Test
    fun `service should load persisted timestamps on startup`() = runTest {
        // Given: Configuration and some existing timestamp data
        val config = createTestConfiguration("Timestamp Persistence Test")
        
        // Pre-populate some timestamp data
        val subscriptionManager = testContainer.getSubscriptionManager()
        subscriptionManager.updateRelayTimestamp(
            config.subscriptionId, 
            "wss://test-relay.com", 
            System.currentTimeMillis() / 1000 - 3600 // 1 hour ago
        )
        
        // When: Service starts
        val result = workflowBuilder
            .givenConfiguration(config)
            .whenServiceStarts()
            .execute()
        
        // Then: Should load persisted timestamps
        assertTrue("Service should start with persisted data", result.isSuccess)
        
        val loadedTimestamp = result.serviceComponents!!.subscriptionManager
            .getRelayTimestamp(config.subscriptionId, "wss://test-relay.com")
        
        assertNotNull("Should load persisted timestamp", loadedTimestamp)
        assertTrue("Timestamp should be from the past", loadedTimestamp!! < System.currentTimeMillis() / 1000)
    }
    
    @Test
    fun `service should initialize event cache correctly`() = runTest {
        // Given: Configuration
        val config = createTestConfiguration("Event Cache Test")
        
        // When: Service starts
        val result = workflowBuilder
            .givenConfiguration(config)
            .whenServiceStarts()
            .execute()
        
        // Then: Event cache should be initialized and functional
        assertTrue("Service should start successfully", result.isSuccess)
        
        val eventCache = result.serviceComponents!!.eventCache
        
        // Test event cache functionality
        val testEventId = generateUniqueEventId()
        assertFalse("New event should not be seen", eventCache.hasSeenEvent(testEventId))
        
        eventCache.markEventSeen(testEventId)
        assertTrue("Marked event should be seen", eventCache.hasSeenEvent(testEventId))
        
        // Cache stats should be available
        val stats = eventCache.getStats()
        assertNotNull("Cache stats should be available", stats)
        assertTrue("Cache should have at least one entry", stats.currentSize >= 1)
    }
    
    @Test
    fun `service should handle startup with existing configurations`() = runTest {
        // Given: Pre-existing configurations in ConfigurationManager
        val existingConfig = createTestConfiguration("Existing Config")
        testContainer.getConfigurationManager().addConfiguration(existingConfig)
        
        val newConfig = createTestConfiguration("New Config")
        
        // When: Service starts with both existing and new configurations
        val result = workflowBuilder
            .givenConfiguration(newConfig)
            .whenServiceStarts()
            .execute()
        
        // Then: Should handle both configurations
        assertTrue("Service should handle mixed configurations", result.isSuccess)
        
        val configManager = result.serviceComponents!!.configurationManager
        val allConfigs = configManager.getEnabledConfigurations()
        
        assertTrue("Should have at least 2 configurations", allConfigs.size >= 2)
        assertTrue("Should include existing config",
            allConfigs.any { it.name == existingConfig.name })
        assertTrue("Should include new config",
            allConfigs.any { it.name == newConfig.name })
    }
    
    @Test
    fun `service should handle component initialization failures gracefully`() = runTest {
        // Given: Configuration that might cause issues
        val config = createTestConfiguration(
            name = "Component Failure Test",
            relayUrls = listOf("invalid://not-a-websocket")
        )
        
        // When: Service starts with problematic configuration
        val result = workflowBuilder
            .givenConfiguration(config)
            .givenNetworkState(TestServiceContainer.NetworkState.POOR_QUALITY)
            .whenServiceStarts()
            .execute()
        
        // Then: Should handle gracefully
        assertTrue("Service should handle component failures gracefully", result.isSuccess)
        assertNotNull("Service components should still be created", result.serviceComponents)
        
        // Core components should still work
        val configManager = result.serviceComponents!!.configurationManager
        assertNotNull("Configuration manager should work", configManager.getEnabledConfigurations())
    }
}
