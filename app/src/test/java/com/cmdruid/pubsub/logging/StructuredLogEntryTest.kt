package com.cmdruid.pubsub.logging

import org.junit.Test
import org.junit.Assert.*
import java.util.Date

/**
 * Unit tests for StructuredLogEntry functionality
 */
class StructuredLogEntryTest {

    @Test
    fun `toDisplayString formats correctly`() {
        val entry = StructuredLogEntry(
            timestamp = 1693607400000L, // Fixed timestamp for testing
            type = LogType.INFO,
            domain = LogDomain.SERVICE,
            message = "Test message"
        )

        val displayString = entry.toDisplayString()
        
        assertTrue("Should contain type icon", displayString.contains("ℹ️"))
        assertTrue("Should contain domain icon", displayString.contains("⚡"))
        assertTrue("Should contain message", displayString.contains("Test message"))
        assertTrue("Should contain timestamp", displayString.contains(":"))
    }

    @Test
    fun `toDisplayString includes data when present`() {
        val entry = StructuredLogEntry(
            timestamp = System.currentTimeMillis(),
            type = LogType.WARN,
            domain = LogDomain.NETWORK,
            message = "Connection issue",
            data = mapOf("url" to "relay.example.com", "attempts" to 3)
        )

        val displayString = entry.toDisplayString()
        
        assertTrue("Should contain data", displayString.contains("url=relay.example.com"))
        assertTrue("Should contain data", displayString.contains("attempts=3"))
    }

    @Test
    fun `toDetailedString includes all information`() {
        val entry = StructuredLogEntry(
            timestamp = 1693607400000L,
            type = LogType.ERROR,
            domain = LogDomain.BATTERY,
            message = "Battery critical",
            data = mapOf("level" to 5),
            threadName = "main",
            className = "TestClass"
        )

        val detailedString = entry.toDetailedString()
        
        assertTrue("Should contain type with icon", detailedString.contains("[❌ Error]"))
        assertTrue("Should contain domain with icon", detailedString.contains("[🔋 Battery]"))
        assertTrue("Should contain message", detailedString.contains("Battery critical"))
        assertTrue("Should contain data", detailedString.contains("level=5"))
        assertTrue("Should contain thread", detailedString.contains("Thread: main"))
        assertTrue("Should contain class", detailedString.contains("Class: TestClass"))
    }

    @Test
    fun `toJson and fromJson work correctly`() {
        val originalEntry = StructuredLogEntry(
            timestamp = System.currentTimeMillis(),
            type = LogType.DEBUG,
            domain = LogDomain.EVENT,
            message = "Event processed",
            data = mapOf("eventId" to "abc123", "processed" to true)
        )

        val json = originalEntry.toJson()
        val reconstructedEntry = StructuredLogEntry.fromJson(json)

        assertNotNull("Should successfully deserialize", reconstructedEntry)
        assertEquals("Should preserve type", originalEntry.type, reconstructedEntry?.type)
        assertEquals("Should preserve domain", originalEntry.domain, reconstructedEntry?.domain)
        assertEquals("Should preserve message", originalEntry.message, reconstructedEntry?.message)
        assertEquals("Should preserve data", originalEntry.data, reconstructedEntry?.data)
    }

    @Test
    fun `fromJson handles invalid JSON gracefully`() {
        val invalidJson = "{ invalid json }"
        val entry = StructuredLogEntry.fromJson(invalidJson)
        
        assertNull("Should return null for invalid JSON", entry)
    }

    @Test
    fun `log types have correct priorities`() {
        assertTrue("TRACE should have lowest priority", LogType.TRACE.priority < LogType.DEBUG.priority)
        assertTrue("DEBUG should have lower priority than INFO", LogType.DEBUG.priority < LogType.INFO.priority)
        assertTrue("INFO should have lower priority than WARN", LogType.INFO.priority < LogType.WARN.priority)
        assertTrue("WARN should have lower priority than ERROR", LogType.WARN.priority < LogType.ERROR.priority)
    }

    @Test
    fun `log types have icons`() {
        assertEquals("TRACE should have search icon", "🔍", LogType.TRACE.icon)
        assertEquals("DEBUG should have bug icon", "🐛", LogType.DEBUG.icon)
        assertEquals("INFO should have info icon", "ℹ️", LogType.INFO.icon)
        assertEquals("WARN should have warning icon", "⚠️", LogType.WARN.icon)
        assertEquals("ERROR should have error icon", "❌", LogType.ERROR.icon)
    }

    @Test
    fun `log domains have icons`() {
        assertEquals("SERVICE should have lightning icon", "⚡", LogDomain.SERVICE.icon)
        assertEquals("NETWORK should have globe icon", "🌐", LogDomain.NETWORK.icon)
        assertEquals("BATTERY should have battery icon", "🔋", LogDomain.BATTERY.icon)
        // Add more assertions as needed
    }
}
