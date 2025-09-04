package com.cmdruid.pubsub.service

import org.junit.Test
import org.junit.Assert.*

/**
 * Debug test to understand threshold calculations
 */
class HealthThresholdDebugTest {

    @Test
    fun `should calculate thresholds correctly for normal battery level`() {
        // Given: Normal battery level (85%), ping interval 30s, high network quality
        val batteryLevel = 85
        val pingInterval = 30L
        val networkQuality = "high"

        // When: Calculate thresholds
        val thresholds = HealthThresholds.forBatteryLevel(batteryLevel, pingInterval, networkQuality)

        // Then: Should use normal behavior thresholds
        val expectedMaxSilenceMs = pingInterval * 2500 // 30 * 2500 = 75000ms = 75 seconds
        assertEquals("Max silence should be 75 seconds", 75000L, thresholds.maxSilenceMs)
        assertEquals("Max reconnect attempts should be 10", 10, thresholds.maxReconnectAttempts)
        assertEquals("Subscription timeout should be 15s for high quality", 15000L, thresholds.subscriptionTimeoutMs)

        println("Calculated thresholds: ${thresholds.getSummary()}")
    }

    @Test
    fun `should detect connection as unhealthy with long silence`() {
        // Given: Normal thresholds
        val thresholds = HealthThresholds.forBatteryLevel(85, 30L, "high")
        
        // Given: Connection with very long silence
        val health = RelayHealth(
            state = ConnectionState.FAILED,
            lastMessageAge = 1000000L, // 1000 seconds - way longer than 75s threshold
            reconnectAttempts = 5,
            subscriptionConfirmed = false
        )

        // When: Check if healthy using the same logic as HealthMonitor
        val isHealthy = health.state == ConnectionState.CONNECTED &&
                health.subscriptionConfirmed &&
                health.lastMessageAge < thresholds.maxSilenceMs &&
                health.reconnectAttempts < thresholds.maxReconnectAttempts

        // Then: Should be unhealthy
        assertFalse("Connection should be unhealthy due to FAILED state", isHealthy)
        
        println("Health check result: isHealthy=$isHealthy")
        println("State: ${health.state}")
        println("Subscription confirmed: ${health.subscriptionConfirmed}")
        println("Last message age: ${health.lastMessageAge}ms (${health.lastMessageAge/1000}s)")
        println("Max silence threshold: ${thresholds.maxSilenceMs}ms (${thresholds.maxSilenceMs/1000}s)")
        println("Reconnect attempts: ${health.reconnectAttempts} (max: ${thresholds.maxReconnectAttempts})")
    }

    @Test
    fun `should detect connection as healthy with recent activity`() {
        // Given: Normal thresholds
        val thresholds = HealthThresholds.forBatteryLevel(85, 30L, "high")
        
        // Given: Connection with recent activity
        val health = RelayHealth(
            state = ConnectionState.CONNECTED,
            lastMessageAge = 30000L, // 30 seconds - less than 75s threshold
            reconnectAttempts = 0,
            subscriptionConfirmed = true
        )

        // When: Check if healthy
        val isHealthy = health.state == ConnectionState.CONNECTED &&
                health.subscriptionConfirmed &&
                health.lastMessageAge < thresholds.maxSilenceMs &&
                health.reconnectAttempts < thresholds.maxReconnectAttempts

        // Then: Should be healthy
        assertTrue("Connection should be healthy", isHealthy)
        
        println("Healthy connection check: isHealthy=$isHealthy")
    }
}
