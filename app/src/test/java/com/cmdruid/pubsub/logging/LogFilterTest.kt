package com.cmdruid.pubsub.logging

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for LogFilter functionality
 */
class LogFilterTest {

    @Test
    fun `filter creation with explicit parameters works correctly`() {
        val filter = LogFilter(
            enabledTypes = setOf(LogType.DEBUG, LogType.INFO, LogType.WARN, LogType.ERROR),
            enabledDomains = setOf(LogDomain.SERVICE, LogDomain.NETWORK),
            maxLogs = 150
        )
        
        assertEquals("Should have specified types", setOf(LogType.DEBUG, LogType.INFO, LogType.WARN, LogType.ERROR), filter.enabledTypes)
        assertEquals("Should have specified domains", setOf(LogDomain.SERVICE, LogDomain.NETWORK), filter.enabledDomains)
        assertEquals("Should have specified max logs", 150, filter.maxLogs)
    }

    @Test
    fun `filter passes correct log entries`() {
        val filter = LogFilter(
            enabledTypes = setOf(LogType.INFO, LogType.ERROR),
            enabledDomains = setOf(LogDomain.SERVICE, LogDomain.NETWORK)
        )

        val validEntry = StructuredLogEntry(
            timestamp = System.currentTimeMillis(),
            type = LogType.INFO,
            domain = LogDomain.SERVICE,
            message = "Test message"
        )

        val invalidTypeEntry = StructuredLogEntry(
            timestamp = System.currentTimeMillis(),
            type = LogType.DEBUG,
            domain = LogDomain.SERVICE,
            message = "Test message"
        )

        val invalidDomainEntry = StructuredLogEntry(
            timestamp = System.currentTimeMillis(),
            type = LogType.INFO,
            domain = LogDomain.BATTERY,
            message = "Test message"
        )

        assertTrue("Valid entry should pass filter", filter.passes(validEntry))
        assertFalse("Invalid type should not pass filter", filter.passes(invalidTypeEntry))
        assertFalse("Invalid domain should not pass filter", filter.passes(invalidDomainEntry))
    }

    @Test
    fun `withTypes creates filter with specified types`() {
        val originalFilter = LogFilter.DEFAULT
        val newFilter = originalFilter.withTypes(LogType.ERROR, LogType.WARN)

        assertEquals("Should have only specified types", setOf(LogType.ERROR, LogType.WARN), newFilter.enabledTypes)
        assertEquals("Should preserve domains", originalFilter.enabledDomains, newFilter.enabledDomains)
        assertEquals("Should preserve maxLogs", originalFilter.maxLogs, newFilter.maxLogs)
    }

    @Test
    fun `withDomains creates filter with specified domains`() {
        val originalFilter = LogFilter.DEFAULT
        val newFilter = originalFilter.withDomains(LogDomain.SERVICE, LogDomain.NETWORK)

        assertEquals("Should preserve types", originalFilter.enabledTypes, newFilter.enabledTypes)
        assertEquals("Should have only specified domains", setOf(LogDomain.SERVICE, LogDomain.NETWORK), newFilter.enabledDomains)
        assertEquals("Should preserve maxLogs", originalFilter.maxLogs, newFilter.maxLogs)
    }

    @Test
    fun `withMaxLogs creates filter with specified max logs`() {
        val originalFilter = LogFilter.DEFAULT
        val newFilter = originalFilter.withMaxLogs(500)

        assertEquals("Should preserve types", originalFilter.enabledTypes, newFilter.enabledTypes)
        assertEquals("Should preserve domains", originalFilter.enabledDomains, newFilter.enabledDomains)
        assertEquals("Should have specified maxLogs", 500, newFilter.maxLogs)
    }

    @Test
    fun `predefined filters work correctly`() {
        val errorsAndWarnings = LogFilter.ERRORS_AND_WARNINGS
        assertEquals("Should have only errors and warnings", setOf(LogType.ERROR, LogType.WARN), errorsAndWarnings.enabledTypes)

        val infoAndAbove = LogFilter.INFO_AND_ABOVE
        assertEquals("Should have info and above", setOf(LogType.INFO, LogType.WARN, LogType.ERROR), infoAndAbove.enabledTypes)

        val allTypes = LogFilter.ALL_TYPES
        assertEquals("Should have all types", LogType.values().toSet(), allTypes.enabledTypes)
    }
}
