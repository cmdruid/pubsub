package com.cmdruid.pubsub.service.health

import com.cmdruid.pubsub.service.ConnectionState
import com.cmdruid.pubsub.service.RelayHealth
import com.cmdruid.pubsub.logging.LogDomain
import com.cmdruid.pubsub.logging.UnifiedLogger
import io.mockk.*
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for HealthCheckOrchestrator - focused on coordination logic
 */
class HealthCheckOrchestratorTest {

    private lateinit var mockHealthEvaluator: HealthEvaluator
    private lateinit var mockLogger: UnifiedLogger
    private lateinit var orchestrator: HealthCheckOrchestrator
    private lateinit var logCapture: MutableList<Pair<String, String>>

    @Before
    fun setup() {
        mockHealthEvaluator = mockk()
        mockLogger = mockk(relaxed = true)
        logCapture = mutableListOf()

        // Capture logs for verification
        every { mockLogger.warn(LogDomain.HEALTH, any<String>(), any()) } answers {
            logCapture.add("WARN" to secondArg<String>())
        }
        every { mockLogger.debug(LogDomain.HEALTH, any<String>()) } answers {
            logCapture.add("DEBUG" to secondArg<String>())
        }

        orchestrator = HealthCheckOrchestrator(mockHealthEvaluator, mockLogger)
    }

    @Test
    fun `should trigger refresh connections for unhealthy connections`() {
        // Given: Unhealthy connections
        val connections = mapOf(
            "wss://relay.primal.net" to RelayHealth(
                state = ConnectionState.FAILED,
                lastMessageAge = 100000L,
                reconnectAttempts = 5,
                subscriptionConfirmed = false
            )
        )

        val unhealthyResult = HealthEvaluationResult(
            totalConnections = 1,
            healthyConnections = 0,
            unhealthyConnections = mapOf("wss://relay.primal.net" to "disconnected (FAILED)"),
            thresholds = mockk()
        )

        // Mock evaluator behavior
        every { mockHealthEvaluator.evaluateConnections(connections, any()) } returns unhealthyResult
        every { mockHealthEvaluator.isHealthy(any(), any()) } returns false
        every { mockHealthEvaluator.getUnhealthyReason(any(), any()) } returns "disconnected (FAILED)"

        // When: Performing health check
        val result = orchestrator.performHealthCheck(
            connections = connections,
            batteryLevel = 85,
            pingInterval = 30L,
            networkQuality = "high"
        )

        // Then: Should trigger refresh connections
        assertTrue("Should have refresh connections action", result.shouldRefreshConnections)
        assertEquals("Should have one action", 1, result.actions.size)
        assertEquals("Should be refresh action", HealthCheckAction.REFRESH_CONNECTIONS, result.actions[0])

        // Should log unhealthy connections found
        assertTrue("Should log unhealthy connections found",
            logCapture.any { it.first == "WARN" && it.second.contains("Enhanced health check found 1 unhealthy connections") })

        // Should log specific reconnection trigger
        assertTrue("Should log reconnection trigger",
            logCapture.any { it.first == "WARN" && it.second.contains("Triggering reconnection for relay.primal.net") })
    }

    @Test
    fun `should NOT trigger refresh connections for healthy connections`() {
        // Given: All healthy connections
        val connections = mapOf(
            "wss://relay.primal.net" to RelayHealth(
                state = ConnectionState.CONNECTED,
                lastMessageAge = 30000L,
                reconnectAttempts = 0,
                subscriptionConfirmed = true
            )
        )

        val healthyResult = HealthEvaluationResult(
            totalConnections = 1,
            healthyConnections = 1,
            unhealthyConnections = emptyMap(),
            thresholds = mockk()
        )

        // Mock evaluator behavior
        every { mockHealthEvaluator.evaluateConnections(connections, any()) } returns healthyResult
        every { mockHealthEvaluator.isHealthy(any(), any()) } returns true

        // When: Performing health check
        val result = orchestrator.performHealthCheck(
            connections = connections,
            batteryLevel = 85,
            pingInterval = 30L,
            networkQuality = "high"
        )

        // Then: Should NOT trigger refresh connections
        assertFalse("Should NOT have refresh connections action", result.shouldRefreshConnections)
        assertEquals("Should have no actions", 0, result.actions.size)

        // Should log all connections healthy
        assertTrue("Should log all connections healthy",
            logCapture.any { it.first == "DEBUG" && it.second.contains("All connections healthy with dynamic thresholds") })
    }

    @Test
    fun `should log individual connection statuses`() {
        // Given: Mix of connections
        val connections = mapOf(
            "wss://relay.primal.net" to RelayHealth(
                state = ConnectionState.CONNECTED,
                lastMessageAge = 30000L,
                reconnectAttempts = 0,
                subscriptionConfirmed = true
            ),
            "wss://relay.damus.io" to RelayHealth(
                state = ConnectionState.FAILED,
                lastMessageAge = 100000L,
                reconnectAttempts = 5,
                subscriptionConfirmed = false
            )
        )

        val mixedResult = HealthEvaluationResult(
            totalConnections = 2,
            healthyConnections = 1,
            unhealthyConnections = mapOf("wss://relay.damus.io" to "disconnected (FAILED)"),
            thresholds = mockk()
        )

        // Mock evaluator behavior
        every { mockHealthEvaluator.evaluateConnections(connections, any()) } returns mixedResult
        every { mockHealthEvaluator.isHealthy(connections["wss://relay.primal.net"]!!, any()) } returns true
        every { mockHealthEvaluator.isHealthy(connections["wss://relay.damus.io"]!!, any()) } returns false
        every { mockHealthEvaluator.getUnhealthyReason(connections["wss://relay.damus.io"]!!, any()) } returns "disconnected (FAILED)"

        // When: Performing health check
        orchestrator.performHealthCheck(
            connections = connections,
            batteryLevel = 85,
            pingInterval = 30L,
            networkQuality = "high"
        )

        // Then: Should log individual statuses
        assertTrue("Should log healthy connection",
            logCapture.any { it.first == "DEBUG" && it.second.contains("relay.primal.net: ✅") })
        assertTrue("Should log unhealthy connection",
            logCapture.any { it.first == "WARN" && it.second.contains("relay.damus.io: Unhealthy - disconnected (FAILED)") })
    }

    @Test
    fun `should handle empty connections gracefully`() {
        // Given: No connections
        val emptyConnections = emptyMap<String, RelayHealth>()

        val emptyResult = HealthEvaluationResult(
            totalConnections = 0,
            healthyConnections = 0,
            unhealthyConnections = emptyMap(),
            thresholds = mockk()
        )

        every { mockHealthEvaluator.evaluateConnections(emptyConnections, any()) } returns emptyResult

        // When: Performing health check
        val result = orchestrator.performHealthCheck(
            connections = emptyConnections,
            batteryLevel = 85,
            pingInterval = 30L,
            networkQuality = "high"
        )

        // Then: Should handle gracefully
        assertFalse("Should not trigger refresh connections", result.shouldRefreshConnections)
        assertEquals("Should have no actions", 0, result.actions.size)
        
        // Should log all connections healthy (since there are no unhealthy ones)
        assertTrue("Should log all connections healthy",
            logCapture.any { it.first == "DEBUG" && it.second.contains("All connections healthy with dynamic thresholds") })
    }

    @Test
    fun `should always log effectiveness metrics`() {
        // Given: Any connections
        val connections = mapOf(
            "wss://relay.primal.net" to RelayHealth(
                state = ConnectionState.CONNECTED,
                lastMessageAge = 30000L,
                reconnectAttempts = 0,
                subscriptionConfirmed = true
            )
        )

        val result = HealthEvaluationResult(
            totalConnections = 1,
            healthyConnections = 1,
            unhealthyConnections = emptyMap(),
            thresholds = mockk()
        )

        every { mockHealthEvaluator.evaluateConnections(connections, any()) } returns result
        every { mockHealthEvaluator.isHealthy(any(), any()) } returns true

        // When: Performing health check
        orchestrator.performHealthCheck(
            connections = connections,
            batteryLevel = 85,
            pingInterval = 30L,
            networkQuality = "high"
        )

        // Then: Should log effectiveness
        verify {
            mockLogger.debug(
                LogDomain.HEALTH,
                "Enhanced health check effectiveness",
                any()
            )
        }
    }
}
