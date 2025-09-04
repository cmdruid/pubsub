package com.cmdruid.pubsub.logging

import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for log filter validation and corruption prevention
 */
class LogFilterValidationTest {

    @Test
    fun `should validate LogFilter DEFAULT is always valid`() {
        // Given: Default filter
        val defaultFilter = LogFilter.DEFAULT

        // Then: Should always be valid
        assertTrue("Default filter should have enabled types", defaultFilter.enabledTypes.isNotEmpty())
        assertTrue("Default filter should have enabled domains", defaultFilter.enabledDomains.isNotEmpty())
        assertTrue("Default filter should have positive max logs", defaultFilter.maxLogs > 0)
        
        // Should contain essential log types
        assertTrue("Should contain ERROR type", LogType.ERROR in defaultFilter.enabledTypes)
        assertTrue("Should contain WARN type", LogType.WARN in defaultFilter.enabledTypes)
        
        // Should contain all domains by default
        assertEquals("Should contain all domains", LogDomain.values().toSet(), defaultFilter.enabledDomains)
    }

    @Test
    fun `should create valid filters with different configurations`() {
        // Given: Different filter configurations
        val errorOnlyFilter = LogFilter(
            enabledTypes = setOf(LogType.ERROR),
            enabledDomains = setOf(LogDomain.SYSTEM),
            maxLogs = 50
        )
        
        val allTypesFilter = LogFilter(
            enabledTypes = LogType.values().toSet(),
            enabledDomains = LogDomain.values().toSet(),
            maxLogs = 200
        )

        // Then: Should be valid
        assertTrue("Error-only filter should have enabled types", errorOnlyFilter.enabledTypes.isNotEmpty())
        assertTrue("All-types filter should have enabled types", allTypesFilter.enabledTypes.isNotEmpty())
        assertTrue("Error-only filter should have enabled domains", errorOnlyFilter.enabledDomains.isNotEmpty())
        assertTrue("All-types filter should have enabled domains", allTypesFilter.enabledDomains.isNotEmpty())
    }

    @Test
    fun `should detect corrupted filters with empty sets`() {
        // Given: Corrupted filters
        val emptyTypesFilter = LogFilter(
            enabledTypes = emptySet(), // CORRUPTED
            enabledDomains = setOf(LogDomain.SYSTEM),
            maxLogs = 100
        )
        
        val emptyDomainsFilter = LogFilter(
            enabledTypes = setOf(LogType.ERROR),
            enabledDomains = emptySet(), // CORRUPTED
            maxLogs = 100
        )

        // Then: Should be detected as invalid
        assertTrue("Empty types filter should be invalid", emptyTypesFilter.enabledTypes.isEmpty())
        assertTrue("Empty domains filter should be invalid", emptyDomainsFilter.enabledDomains.isEmpty())
    }

    @Test
    fun `should validate filter passes method works correctly`() {
        // Given: Filter with specific types and domains
        val specificFilter = LogFilter(
            enabledTypes = setOf(LogType.ERROR, LogType.WARN),
            enabledDomains = setOf(LogDomain.HEALTH),
            maxLogs = 100
        )
        
        // Create test log entries
        val errorHealthEntry = StructuredLogEntry(
            timestamp = System.currentTimeMillis(),
            type = LogType.ERROR,
            domain = LogDomain.HEALTH,
            message = "Test error",
            data = emptyMap(),
            threadName = "test"
        )
        
        val debugSystemEntry = StructuredLogEntry(
            timestamp = System.currentTimeMillis(),
            type = LogType.DEBUG, // Not enabled
            domain = LogDomain.SYSTEM, // Not enabled
            message = "Test debug",
            data = emptyMap(),
            threadName = "test"
        )

        // When: Checking if entries pass the filter
        val errorPasses = specificFilter.passes(errorHealthEntry)
        val debugPasses = specificFilter.passes(debugSystemEntry)

        // Then: Should filter correctly
        assertTrue("Error in HEALTH domain should pass", errorPasses)
        assertFalse("Debug in SYSTEM domain should not pass", debugPasses)
    }
}
