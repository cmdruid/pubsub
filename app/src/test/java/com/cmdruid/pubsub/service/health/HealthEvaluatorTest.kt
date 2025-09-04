package com.cmdruid.pubsub.service.health

import com.cmdruid.pubsub.service.ConnectionState
import com.cmdruid.pubsub.service.RelayHealth
import com.cmdruid.pubsub.service.HealthThresholds
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Pure unit tests for HealthEvaluator - no mocking needed!
 */
class HealthEvaluatorTest {

    private lateinit var healthEvaluator: HealthEvaluator
    private lateinit var standardThresholds: HealthThresholds

    @Before
    fun setup() {
        healthEvaluator = HealthEvaluator()
        standardThresholds = HealthThresholds(
            maxSilenceMs = 75000L, // 75 seconds
            maxReconnectAttempts = 10,
            healthCheckInterval = 45000L,
            subscriptionTimeoutMs = 15000L
        )
    }

    @Test
    fun `should identify healthy connection`() {
        // Given: A healthy connection
        val healthyConnection = RelayHealth(
            state = ConnectionState.CONNECTED,
            lastMessageAge = 30000L, // 30 seconds - less than 75s threshold
            reconnectAttempts = 0,
            subscriptionConfirmed = true
        )

        // When: Evaluating health
        val isHealthy = healthEvaluator.isHealthy(healthyConnection, standardThresholds)

        // Then: Should be healthy
        assertTrue("Connection should be healthy", isHealthy)
    }

    @Test
    fun `should identify unhealthy connection - FAILED state`() {
        // Given: Failed connection
        val failedConnection = RelayHealth(
            state = ConnectionState.FAILED,
            lastMessageAge = 30000L, // Recent, but state is FAILED
            reconnectAttempts = 0,
            subscriptionConfirmed = false
        )

        // When: Evaluating health
        val isHealthy = healthEvaluator.isHealthy(failedConnection, standardThresholds)
        val reason = healthEvaluator.getUnhealthyReason(failedConnection, standardThresholds)

        // Then: Should be unhealthy with correct reason
        assertFalse("Failed connection should be unhealthy", isHealthy)
        assertEquals("Should identify FAILED state", "disconnected (FAILED)", reason)
    }

    @Test
    fun `should identify unhealthy connection - unconfirmed subscription`() {
        // Given: Connected but unconfirmed subscription
        val unconfirmedConnection = RelayHealth(
            state = ConnectionState.CONNECTED,
            lastMessageAge = 30000L,
            reconnectAttempts = 0,
            subscriptionConfirmed = false // Not confirmed
        )

        // When: Evaluating health
        val isHealthy = healthEvaluator.isHealthy(unconfirmedConnection, standardThresholds)
        val reason = healthEvaluator.getUnhealthyReason(unconfirmedConnection, standardThresholds)

        // Then: Should be unhealthy
        assertFalse("Unconfirmed subscription should be unhealthy", isHealthy)
        assertEquals("Should identify unconfirmed subscription", "subscription not confirmed", reason)
    }

    @Test
    fun `should identify unhealthy connection - too long silence`() {
        // Given: Connection silent too long
        val silentConnection = RelayHealth(
            state = ConnectionState.CONNECTED,
            lastMessageAge = 100000L, // 100 seconds - more than 75s threshold
            reconnectAttempts = 0,
            subscriptionConfirmed = true
        )

        // When: Evaluating health
        val isHealthy = healthEvaluator.isHealthy(silentConnection, standardThresholds)
        val reason = healthEvaluator.getUnhealthyReason(silentConnection, standardThresholds)

        // Then: Should be unhealthy
        assertFalse("Silent connection should be unhealthy", isHealthy)
        assertTrue("Should identify silence reason", reason.contains("silent too long"))
        assertTrue("Should show correct timing", reason.contains("100s > 75s"))
    }

    @Test
    fun `should identify unhealthy connection - too many reconnect attempts`() {
        // Given: Connection with too many attempts
        val retriedConnection = RelayHealth(
            state = ConnectionState.CONNECTED,
            lastMessageAge = 30000L,
            reconnectAttempts = 15, // More than 10 threshold
            subscriptionConfirmed = true
        )

        // When: Evaluating health
        val isHealthy = healthEvaluator.isHealthy(retriedConnection, standardThresholds)
        val reason = healthEvaluator.getUnhealthyReason(retriedConnection, standardThresholds)

        // Then: Should be unhealthy
        assertFalse("Connection with too many attempts should be unhealthy", isHealthy)
        assertTrue("Should identify too many attempts", reason.contains("too many reconnect attempts"))
        assertTrue("Should show attempt count", reason.contains("15"))
    }

    @Test
    fun `should evaluate multiple connections correctly`() {
        // Given: Mix of healthy and unhealthy connections
        val connections = mapOf(
            "wss://relay.primal.net" to RelayHealth(
                state = ConnectionState.CONNECTED,
                lastMessageAge = 30000L, // Healthy
                reconnectAttempts = 0,
                subscriptionConfirmed = true
            ),
            "wss://relay.damus.io" to RelayHealth(
                state = ConnectionState.FAILED, // Unhealthy
                lastMessageAge = 30000L,
                reconnectAttempts = 5,
                subscriptionConfirmed = false
            ),
            "wss://relay.nostr.band" to RelayHealth(
                state = ConnectionState.CONNECTED,
                lastMessageAge = 100000L, // Unhealthy - too silent
                reconnectAttempts = 0,
                subscriptionConfirmed = true
            )
        )

        // When: Evaluating all connections
        val result = healthEvaluator.evaluateConnections(connections, standardThresholds)

        // Then: Should correctly identify healthy vs unhealthy
        assertEquals("Should have 3 total connections", 3, result.totalConnections)
        assertEquals("Should have 1 healthy connection", 1, result.healthyConnections)
        assertEquals("Should have 2 unhealthy connections", 2, result.unhealthyCount)
        assertTrue("Should have unhealthy connections", result.hasUnhealthyConnections)
        assertEquals("Health percentage should be 33.33%", 33.33, result.healthPercentage, 0.1)

        // Check specific unhealthy reasons
        assertTrue("Should identify damus.io as unhealthy", 
            result.unhealthyConnections.containsKey("wss://relay.damus.io"))
        assertTrue("Should identify nostr.band as unhealthy", 
            result.unhealthyConnections.containsKey("wss://relay.nostr.band"))
        
        assertEquals("Should identify FAILED state reason", "disconnected (FAILED)", 
            result.unhealthyConnections["wss://relay.damus.io"])
        assertTrue("Should identify silence reason", 
            result.unhealthyConnections["wss://relay.nostr.band"]?.contains("silent too long") == true)
    }

    @Test
    fun `should handle empty connections`() {
        // Given: No connections
        val emptyConnections = emptyMap<String, RelayHealth>()

        // When: Evaluating empty connections
        val result = healthEvaluator.evaluateConnections(emptyConnections, standardThresholds)

        // Then: Should handle gracefully
        assertEquals("Should have 0 total connections", 0, result.totalConnections)
        assertEquals("Should have 0 healthy connections", 0, result.healthyConnections)
        assertEquals("Should have 0 unhealthy connections", 0, result.unhealthyCount)
        assertFalse("Should not have unhealthy connections", result.hasUnhealthyConnections)
        assertEquals("Health percentage should be 100%", 100.0, result.healthPercentage, 0.1)
    }

    @Test
    fun `should handle all healthy connections`() {
        // Given: All healthy connections
        val healthyConnections = mapOf(
            "wss://relay1.com" to RelayHealth(
                state = ConnectionState.CONNECTED,
                lastMessageAge = 10000L,
                reconnectAttempts = 0,
                subscriptionConfirmed = true
            ),
            "wss://relay2.com" to RelayHealth(
                state = ConnectionState.CONNECTED,
                lastMessageAge = 20000L,
                reconnectAttempts = 1,
                subscriptionConfirmed = true
            )
        )

        // When: Evaluating all healthy connections
        val result = healthEvaluator.evaluateConnections(healthyConnections, standardThresholds)

        // Then: Should identify all as healthy
        assertEquals("Should have 2 total connections", 2, result.totalConnections)
        assertEquals("Should have 2 healthy connections", 2, result.healthyConnections)
        assertEquals("Should have 0 unhealthy connections", 0, result.unhealthyCount)
        assertFalse("Should not have unhealthy connections", result.hasUnhealthyConnections)
        assertEquals("Health percentage should be 100%", 100.0, result.healthPercentage, 0.1)
    }
}
