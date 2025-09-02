package com.cmdruid.pubsub.logging

import org.junit.Test
import org.junit.Assert.*

/**
 * Pure unit tests for logging system core logic (no Android dependencies)
 */
class LoggingCoreLogicTest {

    @Test
    fun `log types have correct priority ordering`() {
        assertTrue("TRACE should have lowest priority", LogType.TRACE.priority < LogType.DEBUG.priority)
        assertTrue("DEBUG should have lower priority than INFO", LogType.DEBUG.priority < LogType.INFO.priority)
        assertTrue("INFO should have lower priority than WARN", LogType.INFO.priority < LogType.WARN.priority)
        assertTrue("WARN should have lower priority than ERROR", LogType.WARN.priority < LogType.ERROR.priority)
        
        // Verify actual values
        assertEquals("TRACE priority", 1, LogType.TRACE.priority)
        assertEquals("DEBUG priority", 2, LogType.DEBUG.priority)
        assertEquals("INFO priority", 3, LogType.INFO.priority)
        assertEquals("WARN priority", 4, LogType.WARN.priority)
        assertEquals("ERROR priority", 5, LogType.ERROR.priority)
    }

    @Test
    fun `log types have correct icons and names`() {
        assertEquals("TRACE icon", "🔍", LogType.TRACE.icon)
        assertEquals("DEBUG icon", "🐛", LogType.DEBUG.icon)
        assertEquals("INFO icon", "ℹ️", LogType.INFO.icon)
        assertEquals("WARN icon", "⚠️", LogType.WARN.icon)
        assertEquals("ERROR icon", "❌", LogType.ERROR.icon)
        
        assertEquals("TRACE name", "Trace", LogType.TRACE.displayName)
        assertEquals("DEBUG name", "Debug", LogType.DEBUG.displayName)
        assertEquals("INFO name", "Info", LogType.INFO.displayName)
        assertEquals("WARN name", "Warn", LogType.WARN.displayName)
        assertEquals("ERROR name", "Error", LogType.ERROR.displayName)
    }

    @Test
    fun `log domains have correct icons and names`() {
        assertEquals("SERVICE icon", "⚡", LogDomain.SERVICE.icon)
        assertEquals("NETWORK icon", "🌐", LogDomain.NETWORK.icon)
        assertEquals("RELAY icon", "📡", LogDomain.RELAY.icon)
        assertEquals("EVENT icon", "📨", LogDomain.EVENT.icon)
        assertEquals("NOTIFICATION icon", "🔔", LogDomain.NOTIFICATION.icon)
        assertEquals("BATTERY icon", "🔋", LogDomain.BATTERY.icon)
        assertEquals("HEALTH icon", "🩺", LogDomain.HEALTH.icon)
        assertEquals("SUBSCRIPTION icon", "📋", LogDomain.SUBSCRIPTION.icon)
        assertEquals("UI icon", "🖥️", LogDomain.UI.icon)
        assertEquals("SYSTEM icon", "⚙️", LogDomain.SYSTEM.icon)
    }

    @Test
    fun `structured log entry formatting works correctly`() {
        val entry = StructuredLogEntry(
            timestamp = 1693607400000L, // Fixed timestamp for predictable testing
            type = LogType.WARN,
            domain = LogDomain.NETWORK,
            message = "Connection unstable",
            data = mapOf("retries" to 3, "url" to "relay.example.com")
        )

        val displayString = entry.toDisplayString()
        
        assertTrue("Should contain type icon", displayString.contains("⚠️"))
        assertTrue("Should contain domain icon", displayString.contains("🌐"))
        assertTrue("Should contain message", displayString.contains("Connection unstable"))
        assertTrue("Should contain data", displayString.contains("retries=3"))
        assertTrue("Should contain data", displayString.contains("url=relay.example.com"))
    }

    @Test
    fun `log filter passes method works correctly`() {
        val filter = LogFilter(
            enabledTypes = setOf(LogType.INFO, LogType.ERROR),
            enabledDomains = setOf(LogDomain.SERVICE, LogDomain.NETWORK),
            maxLogs = 100
        )

        // Should pass
        val validEntry = StructuredLogEntry(
            timestamp = System.currentTimeMillis(),
            type = LogType.INFO,
            domain = LogDomain.SERVICE,
            message = "Valid message"
        )
        assertTrue("Valid entry should pass", filter.passes(validEntry))

        // Should fail - wrong type
        val wrongTypeEntry = StructuredLogEntry(
            timestamp = System.currentTimeMillis(),
            type = LogType.DEBUG,
            domain = LogDomain.SERVICE,
            message = "Wrong type"
        )
        assertFalse("Wrong type should not pass", filter.passes(wrongTypeEntry))

        // Should fail - wrong domain
        val wrongDomainEntry = StructuredLogEntry(
            timestamp = System.currentTimeMillis(),
            type = LogType.INFO,
            domain = LogDomain.BATTERY,
            message = "Wrong domain"
        )
        assertFalse("Wrong domain should not pass", filter.passes(wrongDomainEntry))
    }

    @Test
    fun `log filter helper methods work correctly`() {
        val baseFilter = LogFilter()
        
        val typeFilter = baseFilter.withTypes(LogType.ERROR, LogType.WARN)
        assertEquals("Should have specified types", setOf(LogType.ERROR, LogType.WARN), typeFilter.enabledTypes)
        
        val domainFilter = baseFilter.withDomains(LogDomain.SERVICE, LogDomain.BATTERY)
        assertEquals("Should have specified domains", setOf(LogDomain.SERVICE, LogDomain.BATTERY), domainFilter.enabledDomains)
        
        val maxLogsFilter = baseFilter.withMaxLogs(500)
        assertEquals("Should have specified max logs", 500, maxLogsFilter.maxLogs)
    }

    @Test
    fun `predefined filters have correct configurations`() {
        val errorsAndWarnings = LogFilter.ERRORS_AND_WARNINGS
        assertEquals("Should only have errors and warnings", setOf(LogType.ERROR, LogType.WARN), errorsAndWarnings.enabledTypes)
        
        val infoAndAbove = LogFilter.INFO_AND_ABOVE
        assertEquals("Should have info and above", setOf(LogType.INFO, LogType.WARN, LogType.ERROR), infoAndAbove.enabledTypes)
        
        val allTypes = LogFilter.ALL_TYPES
        assertEquals("Should have all types", LogType.values().toSet(), allTypes.enabledTypes)
    }

    @Test
    fun `json serialization basic functionality works`() {
        val originalEntry = StructuredLogEntry(
            timestamp = 1693607400000L,
            type = LogType.ERROR,
            domain = LogDomain.NETWORK,
            message = "Network failure",
            data = emptyMap(), // Simplified for unit test
            threadName = "worker-1",
            className = "NetworkManager"
        )

        val json = originalEntry.toJson()
        assertNotNull("Should generate JSON", json)
        assertTrue("JSON should contain timestamp", json.contains("1693607400000"))
        assertTrue("JSON should contain message", json.contains("Network failure"))
        
        val reconstructedEntry = StructuredLogEntry.fromJson(json)
        assertNotNull("Should successfully reconstruct", reconstructedEntry)
        assertEquals("Should preserve type", originalEntry.type, reconstructedEntry?.type)
        assertEquals("Should preserve domain", originalEntry.domain, reconstructedEntry?.domain)
        assertEquals("Should preserve message", originalEntry.message, reconstructedEntry?.message)
    }

    @Test
    fun `json deserialization handles invalid data gracefully`() {
        assertNull("Should handle empty string", StructuredLogEntry.fromJson(""))
        assertNull("Should handle invalid JSON", StructuredLogEntry.fromJson("{ invalid }"))
        assertNull("Should handle null", StructuredLogEntry.fromJson("null"))
        assertNull("Should handle non-object JSON", StructuredLogEntry.fromJson("\"string\""))
    }

    @Test
    fun `log type fromString works correctly`() {
        assertEquals("Should parse DEBUG", LogType.DEBUG, LogType.fromString("debug"))
        assertEquals("Should parse INFO", LogType.INFO, LogType.fromString("INFO"))
        assertEquals("Should parse WARN", LogType.WARN, LogType.fromString("Warn"))
        assertEquals("Should parse ERROR", LogType.ERROR, LogType.fromString("error"))
        assertEquals("Should parse TRACE", LogType.TRACE, LogType.fromString("TRACE"))
        assertNull("Should return null for invalid", LogType.fromString("invalid"))
    }

    @Test
    fun `log domain fromString works correctly`() {
        assertEquals("Should parse SERVICE", LogDomain.SERVICE, LogDomain.fromString("service"))
        assertEquals("Should parse NETWORK", LogDomain.NETWORK, LogDomain.fromString("NETWORK"))
        assertEquals("Should parse BATTERY", LogDomain.BATTERY, LogDomain.fromString("Battery"))
        assertNull("Should return null for invalid", LogDomain.fromString("invalid"))
    }
}
