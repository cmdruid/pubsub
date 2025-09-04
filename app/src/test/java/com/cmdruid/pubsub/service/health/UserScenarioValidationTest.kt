package com.cmdruid.pubsub.service.health

import com.cmdruid.pubsub.service.ConnectionState
import com.cmdruid.pubsub.service.RelayHealth
import com.cmdruid.pubsub.logging.UnifiedLogger
import io.mockk.*
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Integration test that validates our fixes work for the user's reported scenario
 * Uses the new testable architecture to clearly demonstrate the fix
 */
class UserScenarioValidationTest {

    private lateinit var healthEvaluator: HealthEvaluator
    private lateinit var mockLogger: UnifiedLogger
    private lateinit var orchestrator: HealthCheckOrchestrator

    @Before
    fun setup() {
        healthEvaluator = HealthEvaluator()
        mockLogger = mockk(relaxed = true)
        orchestrator = HealthCheckOrchestrator(healthEvaluator, mockLogger)
    }

    @Test
    fun `should detect and fix user reported scenario - early morning failed connections`() {
        // Given: Exact conditions from user's logs at 03:53:49
        // Battery: 85%, Network: "none", both relays in FAILED state
        val userScenarioConnections = mapOf(
            "wss://relay.primal.net" to RelayHealth(
                state = ConnectionState.FAILED, // Key issue: FAILED state
                lastMessageAge = 14400000L, // 4 hours of silence
                reconnectAttempts = 8,
                subscriptionConfirmed = false
            ),
            "wss://relay.damus.io" to RelayHealth(
                state = ConnectionState.FAILED, // Key issue: FAILED state
                lastMessageAge = 13800000L, // 3.8 hours of silence
                reconnectAttempts = 6,
                subscriptionConfirmed = false
            )
        )

        // When: Health check runs (this is what happens every 3 minutes)
        val result = orchestrator.performHealthCheck(
            connections = userScenarioConnections,
            batteryLevel = 85, // From user's logs
            pingInterval = 30L, // Default
            networkQuality = "none" // From user's logs
        )

        // Then: Should detect both connections as unhealthy
        assertEquals("Should detect 2 unhealthy connections", 2, result.evaluation.unhealthyCount)
        assertEquals("Should have 0 healthy connections", 0, result.evaluation.healthyConnections)
        assertTrue("Should have unhealthy connections", result.evaluation.hasUnhealthyConnections)

        // Then: Should trigger refresh connections (THE KEY FIX!)
        assertTrue("Should trigger refresh connections", result.shouldRefreshConnections)
        assertEquals("Should have refresh action", HealthCheckAction.REFRESH_CONNECTIONS, result.actions[0])

        // Then: Should identify correct reasons
        assertTrue("Should identify primal.net as FAILED", 
            result.evaluation.unhealthyConnections["wss://relay.primal.net"]?.contains("disconnected (FAILED)") == true)
        assertTrue("Should identify damus.io as FAILED", 
            result.evaluation.unhealthyConnections["wss://relay.damus.io"]?.contains("disconnected (FAILED)") == true)

        // Verify logging behavior (what we should see in logs now)
        verify {
            mockLogger.warn(
                any(),
                "Enhanced health check found 2 unhealthy connections",
                any()
            )
        }
        verify {
            mockLogger.warn(any(), match { it.contains("Triggering reconnection for relay.primal.net") })
        }
        verify {
            mockLogger.warn(any(), match { it.contains("Triggering reconnection for relay.damus.io") })
        }
    }

    @Test
    fun `should handle improved network conditions from user logs`() {
        // Given: Later conditions from user's logs at 08:14:51
        // Network improved to "high", one relay silent but connected
        val improvedNetworkScenario = mapOf(
            "wss://relay.primal.net" to RelayHealth(
                state = ConnectionState.CONNECTED, // Now connected but silent
                lastMessageAge = 387000L, // 387 seconds from user's log
                reconnectAttempts = 0,
                subscriptionConfirmed = true
            ),
            "wss://relay.damus.io" to RelayHealth(
                state = ConnectionState.CONNECTED, // Healthy
                lastMessageAge = 200000L, // 200 seconds - within threshold
                reconnectAttempts = 0,
                subscriptionConfirmed = true
            )
        )

        // When: Health check runs with improved network
        val result = orchestrator.performHealthCheck(
            connections = improvedNetworkScenario,
            batteryLevel = 100, // From user's logs
            pingInterval = 30L,
            networkQuality = "high" // Improved network
        )

        // Then: Should detect connections based on actual thresholds
        // With battery=100, ping=30s, network="high": threshold = 30 * 2500 = 75000ms = 75s
        // primal.net: 387s > 75s = unhealthy (silent too long)
        // damus.io: 200s > 75s = also unhealthy (silent too long)
        // Both are actually unhealthy based on the thresholds
        assertEquals("Should detect 2 unhealthy connections", 2, result.evaluation.unhealthyCount)
        assertEquals("Should have 0 healthy connections", 0, result.evaluation.healthyConnections)

        // Should still trigger refresh connections for the silent one
        assertTrue("Should trigger refresh connections", result.shouldRefreshConnections)

        // Should identify correct reasons (both silent too long)
        assertTrue("Should identify primal.net as silent too long", 
            result.evaluation.unhealthyConnections["wss://relay.primal.net"]?.contains("silent too long") == true)
        assertTrue("Should identify damus.io as silent too long", 
            result.evaluation.unhealthyConnections["wss://relay.damus.io"]?.contains("silent too long") == true)
    }

    @Test
    fun `should handle subscription not confirmed scenario`() {
        // Given: Connection that's connected but subscription not confirmed
        val unconfirmedScenario = mapOf(
            "wss://relay.primal.net" to RelayHealth(
                state = ConnectionState.CONNECTED,
                lastMessageAge = 30000L, // Recent messages
                reconnectAttempts = 0,
                subscriptionConfirmed = false // THE ISSUE: Not confirmed
            )
        )

        // When: Health check runs
        val result = orchestrator.performHealthCheck(
            connections = unconfirmedScenario,
            batteryLevel = 85,
            pingInterval = 30L,
            networkQuality = "high"
        )

        // Then: Should detect as unhealthy due to unconfirmed subscription
        assertEquals("Should detect 1 unhealthy connection", 1, result.evaluation.unhealthyCount)
        assertTrue("Should trigger refresh connections", result.shouldRefreshConnections)
        
        // Should identify correct reason
        assertEquals("Should identify subscription not confirmed", "subscription not confirmed",
            result.evaluation.unhealthyConnections["wss://relay.primal.net"])
    }

    @Test
    fun `should handle too many reconnect attempts scenario`() {
        // Given: Connection that has exceeded reconnect attempt limits
        val tooManyAttemptsScenario = mapOf(
            "wss://relay.primal.net" to RelayHealth(
                state = ConnectionState.CONNECTED,
                lastMessageAge = 30000L,
                reconnectAttempts = 15, // > 10 threshold
                subscriptionConfirmed = true
            )
        )

        // When: Health check runs
        val result = orchestrator.performHealthCheck(
            connections = tooManyAttemptsScenario,
            batteryLevel = 85,
            pingInterval = 30L,
            networkQuality = "high"
        )

        // Then: Should detect as unhealthy due to too many attempts
        assertEquals("Should detect 1 unhealthy connection", 1, result.evaluation.unhealthyCount)
        assertTrue("Should trigger refresh connections", result.shouldRefreshConnections)
        
        // Should identify correct reason
        assertTrue("Should identify too many attempts", 
            result.evaluation.unhealthyConnections["wss://relay.primal.net"]?.contains("too many reconnect attempts") == true)
    }

    @Test
    fun `should NOT trigger refresh for healthy connections`() {
        // Given: All healthy connections (what should happen after our fixes work)
        val healthyScenario = mapOf(
            "wss://relay.primal.net" to RelayHealth(
                state = ConnectionState.CONNECTED,
                lastMessageAge = 30000L, // 30 seconds - recent
                reconnectAttempts = 0,
                subscriptionConfirmed = true
            ),
            "wss://relay.damus.io" to RelayHealth(
                state = ConnectionState.CONNECTED,
                lastMessageAge = 45000L, // 45 seconds - still recent
                reconnectAttempts = 0,
                subscriptionConfirmed = true
            )
        )

        // When: Health check runs
        val result = orchestrator.performHealthCheck(
            connections = healthyScenario,
            batteryLevel = 85,
            pingInterval = 30L,
            networkQuality = "high"
        )

        // Then: Should NOT trigger refresh connections
        assertEquals("Should have 0 unhealthy connections", 0, result.evaluation.unhealthyCount)
        assertEquals("Should have 2 healthy connections", 2, result.evaluation.healthyConnections)
        assertFalse("Should NOT trigger refresh connections", result.shouldRefreshConnections)
        assertTrue("Should have no actions", result.actions.isEmpty())

        // Should log all connections healthy
        verify {
            mockLogger.debug(any(), "All connections healthy with dynamic thresholds")
        }
    }

    @Test
    fun `should demonstrate the fix - before and after comparison`() {
        // This test demonstrates what was broken and how our fix resolves it
        
        // BEFORE THE FIX: User's scenario would not trigger reconnections
        val userProblemScenario = mapOf(
            "wss://relay.primal.net" to RelayHealth(
                state = ConnectionState.FAILED,
                lastMessageAge = 1000000L, // Very long silence
                reconnectAttempts = 8, // Many failed attempts
                subscriptionConfirmed = false
            )
        )

        // AFTER THE FIX: Our new architecture correctly detects and acts
        val result = orchestrator.performHealthCheck(
            connections = userProblemScenario,
            batteryLevel = 85,
            pingInterval = 30L,
            networkQuality = "high"
        )

        // THE FIX: This now correctly triggers refresh connections
        assertTrue("THE FIX: Should trigger refresh connections", result.shouldRefreshConnections)
        assertEquals("Should detect unhealthy connection", 1, result.evaluation.unhealthyCount)
        assertTrue("Should identify FAILED state", 
            result.evaluation.unhealthyConnections.values.any { it.contains("disconnected (FAILED)") })

        // This means RelayConnectionManager.refreshConnections() will be called
        // Which will call resetReconnectionAttempts() and reconnect()
        // Which will use updated subscription filters
        // Which should restore event reception!
    }
}
