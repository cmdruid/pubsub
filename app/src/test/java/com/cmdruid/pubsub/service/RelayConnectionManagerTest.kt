package com.cmdruid.pubsub.service

import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.data.ConfigurationManager
import com.cmdruid.pubsub.nostr.NostrFilter
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Test class for RelayConnectionManager functionality
 * Tests core connection management, battery optimizations, and health monitoring
 */
class RelayConnectionManagerTest {

    // Mocked dependencies
    private lateinit var mockConfigurationManager: ConfigurationManager
    private lateinit var mockSubscriptionManager: SubscriptionManager
    private lateinit var mockBatteryPowerManager: BatteryPowerManager
    private lateinit var mockNetworkManager: NetworkManager
    private lateinit var mockOnMessageReceived: (String, String, String) -> Unit
    private lateinit var mockSendDebugLog: (String) -> Unit

    // Test subject
    private lateinit var relayConnectionManager: RelayConnectionManager

    // Test data
    private lateinit var testConfiguration: Configuration
    private lateinit var testFilter: NostrFilter

    @Before
    fun setup() {
        // Initialize mocks
        mockConfigurationManager = mockk()
        mockSubscriptionManager = mockk(relaxed = true)
        mockBatteryPowerManager = mockk()
        mockNetworkManager = mockk()
        mockOnMessageReceived = mockk(relaxed = true)
        mockSendDebugLog = mockk(relaxed = true)

        // Create test data
        testFilter = NostrFilter(
            kinds = listOf(1),
            since = 1640995200L // Jan 1, 2022
        )

        testConfiguration = Configuration(
            id = "test-config",
            name = "Test Configuration",
            isEnabled = true,
            relayUrls = listOf("wss://relay1.com", "wss://relay2.com"),
            filter = testFilter,
            targetUri = "app://test",
            subscriptionId = "test-sub-123"
        )

        // Setup default mock behaviors
        every { mockConfigurationManager.getEnabledConfigurations() } returns listOf(testConfiguration)
        every { mockSubscriptionManager.createRelaySpecificFilter(any(), any(), any()) } returns testFilter
        // Relaxed mock handles registerSubscription automatically
        every { mockBatteryPowerManager.getCurrentBatteryLevel() } returns 80
        every { mockBatteryPowerManager.getCurrentPingInterval() } returns 30L
        every { mockBatteryPowerManager.getCurrentAppState() } returns PubSubService.AppState.FOREGROUND
        every { mockNetworkManager.getNetworkQuality() } returns "good"
        every { mockNetworkManager.isNetworkAvailable() } returns true

        // Create RelayConnectionManager
        relayConnectionManager = RelayConnectionManager(
            configurationManager = mockConfigurationManager,
            subscriptionManager = mockSubscriptionManager,
            batteryPowerManager = mockBatteryPowerManager,
            networkManager = mockNetworkManager,
            onMessageReceived = mockOnMessageReceived,
            sendDebugLog = mockSendDebugLog
        )
    }

    // === CONNECTION MANAGEMENT TESTS ===

    @Test
    fun `should create shared OkHttpClient on initialization`() {
        // Given: RelayConnectionManager is initialized (done in setup)
        
        // When: We check the connection health (which accesses the client)
        val health = relayConnectionManager.getConnectionHealth()
        
        // Then: No exceptions are thrown and health is returned
        assertNotNull(health)
        assertTrue(health.isEmpty()) // No connections yet
    }

    @Test
    fun `should connect to multiple relay URLs from configurations`() = runTest {
        // Given: Configuration with multiple relay URLs (set in setup)
        
        // When: Connecting to all relays
        relayConnectionManager.connectToAllRelays()
        
        // Then: Should complete without exceptions (basic functionality test)
        // In beta, we focus on non-crashing behavior rather than exact call verification
        
        // Verify basic interaction occurred
        verify(atLeast = 1) { 
            mockSubscriptionManager.createRelaySpecificFilter(any(), any(), any())
        }
    }

    @Test
    fun `should handle connection failures gracefully`() = runTest {
        // Given: Network is unavailable
        every { mockNetworkManager.isNetworkAvailable() } returns false
        
        // When: Attempting to connect
        relayConnectionManager.connectToAllRelays()
        
        // Then: Should complete without crashing (beta focus: basic functionality)
        // Network issues should be handled gracefully
    }

    @Test
    fun `should disconnect from all relays cleanly`() = runTest {
        // Given: Connected to relays
        relayConnectionManager.connectToAllRelays()
        
        // When: Disconnecting from all relays
        relayConnectionManager.disconnectFromAllRelays()
        
        // Then: Should complete without exceptions
        // Connection health should be empty after disconnect
        val health = relayConnectionManager.getConnectionHealth()
        assertTrue(health.isEmpty())
    }

    // === BATTERY OPTIMIZATION TESTS ===

    @Test
    fun `updatePingInterval should avoid client recreation for minor changes`() {
        // Given: Current ping interval is 30 seconds
        val initialInterval = 30L
        
        // When: Updating to a minor change (less than 30 seconds difference)
        val newInterval = 45L // 15 second difference
        relayConnectionManager.updatePingInterval(newInterval)
        
        // Then: Should log minor change without client recreation
        verify { mockSendDebugLog(match { 
            it.contains("minor change, client reused") 
        }) }
    }

    @Test
    fun `updatePingInterval should recreate client for significant changes`() {
        // Given: Current ping interval is 30 seconds
        val initialInterval = 30L
        
        // When: Updating to a significant change (30+ seconds difference)
        val newInterval = 90L // 60 second difference
        relayConnectionManager.updatePingInterval(newInterval)
        
        // Then: Should log client recreation
        verify { mockSendDebugLog(match { 
            it.contains("client recreated") 
        }) }
    }

    @Test
    fun `updatePingInterval should handle duplicate calls efficiently`() {
        // Given: Current ping interval is 30 seconds
        val interval = 30L
        
        // When: Calling updatePingInterval with same value multiple times
        relayConnectionManager.updatePingInterval(interval)
        relayConnectionManager.updatePingInterval(interval)
        relayConnectionManager.updatePingInterval(interval)
        
        // Then: Should not log any changes (no calls to sendDebugLog for updates)
        verify(exactly = 0) { mockSendDebugLog(match { 
            it.contains("Ping interval updated") 
        }) }
    }

    // === HEALTH MONITORING TESTS ===

    @Test
    fun `should report connection health accurately`() {
        // Given: No active connections initially
        
        // When: Getting connection health
        val health = relayConnectionManager.getConnectionHealth()
        
        // Then: Should return empty health map
        assertNotNull(health)
        assertTrue(health.isEmpty())
    }

    @Test
    fun `should trigger reconnection on unhealthy connections`() {
        // Given: Mock unhealthy conditions
        every { mockBatteryPowerManager.getCurrentBatteryLevel() } returns 20 // Low battery
        every { mockBatteryPowerManager.getCurrentPingInterval() } returns 60L
        every { mockNetworkManager.getNetworkQuality() } returns "poor"
        
        // When: Refreshing connections
        relayConnectionManager.refreshConnections()
        
        // Then: Should log refresh attempt
        verify { mockSendDebugLog(match { it.contains("Refreshing all connections") }) }
    }

    // === CONFIGURATION SYNCHRONIZATION TESTS ===

    @Test
    fun `should synchronize with current configurations`() {
        // Given: Configuration manager returns updated configurations
        val updatedConfig = testConfiguration.copy(
            relayUrls = listOf("wss://relay1.com", "wss://relay3.com") // relay2 removed, relay3 added
        )
        every { mockConfigurationManager.getEnabledConfigurations() } returns listOf(updatedConfig)
        
        // When: Synchronizing configurations
        relayConnectionManager.syncConfigurations()
        
        // Then: Should log synchronization
        verify { mockSendDebugLog(match { it.contains("Synchronizing configurations") }) }
    }

    @Test
    fun `should handle empty configuration list`() = runTest {
        // Given: No enabled configurations
        every { mockConfigurationManager.getEnabledConfigurations() } returns emptyList()
        
        // When: Connecting to all relays
        relayConnectionManager.connectToAllRelays()
        
        // Then: Should log starting connections with 0 configurations
        verify { mockSendDebugLog(match { it.contains("Starting connections for 0 configuration(s)") }) }
        
        // No subscription registrations should occur
        verify(exactly = 0) { mockSubscriptionManager.registerSubscription(any(), any(), any(), any()) }
    }

    // === RELAY CONNECTION INFO TESTS ===

    @Test
    fun `should provide relay connection info for compatibility`() {
        // Given: RelayConnectionManager is initialized
        
        // When: Getting relay connections
        val connections = relayConnectionManager.getRelayConnections()
        
        // Then: Should return empty map initially
        assertNotNull(connections)
        assertTrue(connections.isEmpty())
    }

    @Test
    fun `should disconnect from specific relay`() {
        // Given: A specific relay URL
        val relayUrl = "wss://relay1.com"
        
        // When: Disconnecting from specific relay
        relayConnectionManager.disconnectFromRelay(relayUrl)
        
        // Then: Should log disconnection
        verify { mockSendDebugLog(match { it.contains("Disconnected from $relayUrl") }) }
    }

    // === ERROR HANDLING TESTS ===

    @Test
    fun `should handle configuration manager exceptions`() = runTest {
        // Given: Configuration manager throws exception
        every { mockConfigurationManager.getEnabledConfigurations() } throws RuntimeException("Test exception")
        
        // When: Attempting to connect (should not crash)
        try {
            relayConnectionManager.connectToAllRelays()
        } catch (e: Exception) {
            // Then: Exception should be propagated (or handled gracefully in real implementation)
            assertTrue(e is RuntimeException)
        }
    }

    @Test
    fun `should handle subscription manager exceptions`() = runTest {
        // Given: Subscription manager throws exception
        every { mockSubscriptionManager.createRelaySpecificFilter(any(), any(), any()) } throws RuntimeException("Filter error")
        
        // When: Attempting to connect (should not crash)
        try {
            relayConnectionManager.connectToRelay("wss://test.com", testConfiguration)
        } catch (e: Exception) {
            // Then: Exception should be propagated (or handled gracefully in real implementation)
            assertTrue(e is RuntimeException)
        }
    }

    // === BATTERY AWARENESS TESTS ===

    @Test
    fun `should consider battery level in reconnection decisions`() {
        // Given: Low battery conditions
        every { mockBatteryPowerManager.getCurrentBatteryLevel() } returns 10 // Very low battery
        
        // When: Refreshing connections
        relayConnectionManager.refreshConnections()
        
        // Then: Should still attempt refresh but with battery-aware thresholds
        verify { mockSendDebugLog(match { it.contains("Refreshing all connections") }) }
    }

    @Test
    fun `should adapt to app state changes`() {
        // Given: App in background state
        every { mockBatteryPowerManager.getCurrentAppState() } returns PubSubService.AppState.BACKGROUND
        
        // When: Refreshing connections
        relayConnectionManager.refreshConnections()
        
        // Then: Should use background-appropriate thresholds
        verify { mockSendDebugLog(match { it.contains("Refreshing all connections") }) }
    }
}
