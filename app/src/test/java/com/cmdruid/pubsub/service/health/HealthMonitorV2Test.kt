package com.cmdruid.pubsub.service.health

import com.cmdruid.pubsub.service.*
import io.mockk.*
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for HealthMonitorV2 - focused on coordination and action execution
 */
class HealthMonitorV2Test {

    private lateinit var mockRelayConnectionManager: RelayConnectionManager
    private lateinit var mockBatteryPowerManager: BatteryPowerManager
    private lateinit var mockMetricsCollector: MetricsCollector
    private lateinit var mockNetworkManager: NetworkManager
    private lateinit var mockOrchestrator: HealthCheckOrchestrator
    private lateinit var healthMonitor: HealthMonitorV2
    private var refreshConnectionsCalled = false

    @Before
    fun setup() {
        mockRelayConnectionManager = mockk(relaxed = true)
        mockBatteryPowerManager = mockk(relaxed = true)
        mockMetricsCollector = mockk(relaxed = true)
        mockNetworkManager = mockk(relaxed = true)
        mockOrchestrator = mockk()
        refreshConnectionsCalled = false

        // Setup default returns
        every { mockBatteryPowerManager.getCurrentBatteryLevel() } returns 85
        every { mockBatteryPowerManager.getCurrentPingInterval() } returns 30L
        every { mockNetworkManager.getNetworkQuality() } returns "high"
        every { mockRelayConnectionManager.getConnectionHealth() } returns emptyMap()

        // Capture refresh connections calls
        every { mockRelayConnectionManager.refreshConnections() } answers {
            refreshConnectionsCalled = true
        }

        healthMonitor = HealthMonitorV2(
            relayConnectionManager = mockRelayConnectionManager,
            batteryPowerManager = mockBatteryPowerManager,
            metricsCollector = mockMetricsCollector,
            networkManager = mockNetworkManager,
            orchestrator = mockOrchestrator
        )
    }

    @Test
    fun `should execute refresh connections action`() {
        // Given: Orchestrator returns refresh connections action
        val healthCheckResult = HealthCheckResult(
            evaluation = mockk(),
            actions = listOf(HealthCheckAction.REFRESH_CONNECTIONS),
            batteryLevel = 85,
            networkQuality = "high"
        )

        every { 
            mockOrchestrator.performHealthCheck(
                connections = any(),
                batteryLevel = 85,
                pingInterval = 30L,
                networkQuality = "high"
            )
        } returns healthCheckResult

        // When: Running health check synchronously
        val result = healthMonitor.runHealthCheckSync()

        // Then: Should execute refresh connections action
        assertTrue("Should have called refreshConnections", refreshConnectionsCalled)
        assertTrue("Result should indicate refresh connections", result.shouldRefreshConnections)
        
        // Should track metrics
        verify { mockMetricsCollector.trackHealthCheck(isBatchCheck = true) }
    }

    @Test
    fun `should NOT execute refresh connections when no action needed`() {
        // Given: Orchestrator returns no actions
        val healthCheckResult = HealthCheckResult(
            evaluation = mockk(),
            actions = emptyList(),
            batteryLevel = 85,
            networkQuality = "high"
        )

        every { 
            mockOrchestrator.performHealthCheck(any(), any(), any(), any())
        } returns healthCheckResult

        // When: Running health check synchronously
        val result = healthMonitor.runHealthCheckSync()

        // Then: Should NOT execute refresh connections
        assertFalse("Should NOT have called refreshConnections", refreshConnectionsCalled)
        assertFalse("Result should NOT indicate refresh connections", result.shouldRefreshConnections)
        
        // Should still track metrics
        verify { mockMetricsCollector.trackHealthCheck(isBatchCheck = true) }
    }

    @Test
    fun `should pass correct parameters to orchestrator`() {
        // Given: Specific system state
        val connections = mapOf(
            "wss://relay.primal.net" to RelayHealth(
                state = ConnectionState.CONNECTED,
                lastMessageAge = 30000L,
                reconnectAttempts = 0,
                subscriptionConfirmed = true
            )
        )

        every { mockRelayConnectionManager.getConnectionHealth() } returns connections
        every { mockBatteryPowerManager.getCurrentBatteryLevel() } returns 75
        every { mockBatteryPowerManager.getCurrentPingInterval() } returns 45L
        every { mockNetworkManager.getNetworkQuality() } returns "medium"

        val healthCheckResult = HealthCheckResult(
            evaluation = mockk(),
            actions = emptyList(),
            batteryLevel = 75,
            networkQuality = "medium"
        )

        every { mockOrchestrator.performHealthCheck(any(), any(), any(), any()) } returns healthCheckResult

        // When: Running health check
        healthMonitor.runHealthCheckSync()

        // Then: Should pass correct parameters
        verify {
            mockOrchestrator.performHealthCheck(
                connections = connections,
                batteryLevel = 75,
                pingInterval = 45L,
                networkQuality = "medium"
            )
        }
    }

    @Test
    fun `should handle empty connections gracefully`() {
        // Given: No connections
        every { mockRelayConnectionManager.getConnectionHealth() } returns emptyMap()

        val healthCheckResult = HealthCheckResult(
            evaluation = mockk(),
            actions = emptyList(),
            batteryLevel = 85,
            networkQuality = "high"
        )

        every { mockOrchestrator.performHealthCheck(any(), any(), any(), any()) } returns healthCheckResult

        // When: Running health check
        val result = healthMonitor.runHealthCheckSync()

        // Then: Should handle gracefully without crashing
        assertNotNull("Should return result", result)
        assertFalse("Should not trigger refresh connections", refreshConnectionsCalled)
        
        // Should still track metrics
        verify { mockMetricsCollector.trackHealthCheck(isBatchCheck = true) }
    }

    @Test
    fun `should return orchestrator result directly`() {
        // Given: Specific orchestrator result
        val evaluation = HealthEvaluationResult(
            totalConnections = 2,
            healthyConnections = 1,
            unhealthyConnections = mapOf("wss://relay.damus.io" to "disconnected (FAILED)"),
            thresholds = mockk()
        )

        val expectedResult = HealthCheckResult(
            evaluation = evaluation,
            actions = listOf(HealthCheckAction.REFRESH_CONNECTIONS),
            batteryLevel = 85,
            networkQuality = "high"
        )

        every { mockOrchestrator.performHealthCheck(any(), any(), any(), any()) } returns expectedResult

        // When: Running health check
        val result = healthMonitor.runHealthCheckSync()

        // Then: Should return the orchestrator result
        assertEquals("Should return orchestrator result", expectedResult, result)
        assertEquals("Should have correct evaluation", evaluation, result.evaluation)
        assertEquals("Should have correct battery level", 85, result.batteryLevel)
        assertEquals("Should have correct network quality", "high", result.networkQuality)
    }
}
