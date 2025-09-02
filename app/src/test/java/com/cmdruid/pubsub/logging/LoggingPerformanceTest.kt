package com.cmdruid.pubsub.logging

import org.junit.Test
import org.junit.Assert.*

/**
 * Performance tests for logging system
 */
class LoggingPerformanceTest {

    @Test
    fun `filter passes method is fast for large datasets`() {
        val filter = LogFilter(
            enabledTypes = setOf(LogType.INFO, LogType.ERROR),
            enabledDomains = setOf(LogDomain.SERVICE, LogDomain.NETWORK)
        )

        // Create test entries
        val testEntries = (1..1000).map { i ->
            StructuredLogEntry(
                timestamp = System.currentTimeMillis() + i,
                type = if (i % 2 == 0) LogType.INFO else LogType.DEBUG,
                domain = if (i % 3 == 0) LogDomain.SERVICE else LogDomain.BATTERY,
                message = "Test message $i"
            )
        }

        val startTime = System.currentTimeMillis()
        val filteredCount = testEntries.count { filter.passes(it) }
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime

        assertTrue("Should have some filtered entries", filteredCount > 0)
        assertTrue("Should be fast (< 50ms for 1000 entries)", duration < 50)
    }

    @Test
    fun `log entry creation is lightweight`() {
        val startTime = System.currentTimeMillis()
        
        // Create 1000 log entries
        val entries = (1..1000).map { i ->
            StructuredLogEntry(
                timestamp = System.currentTimeMillis(),
                type = LogType.INFO,
                domain = LogDomain.SERVICE,
                message = "Message $i",
                data = mapOf("index" to i)
            )
        }
        
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime

        assertEquals("Should create all entries", 1000, entries.size)
        assertTrue("Should be fast (< 100ms for 1000 entries)", duration < 100)
    }

    @Test
    fun `display string formatting is efficient`() {
        val entry = StructuredLogEntry(
            timestamp = System.currentTimeMillis(),
            type = LogType.WARN,
            domain = LogDomain.NETWORK,
            message = "Test message with some data",
            data = mapOf("key1" to "value1", "key2" to 42, "key3" to true)
        )

        val startTime = System.currentTimeMillis()
        
        // Format 1000 times to test performance
        repeat(1000) {
            entry.toDisplayString()
        }
        
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime

        assertTrue("Display string formatting should be fast (< 100ms for 1000 calls)", duration < 100)
    }

    @Test
    fun `log type priority comparison is fast`() {
        val startTime = System.currentTimeMillis()
        
        // Test 10000 priority comparisons
        repeat(10000) {
            LogType.DEBUG.priority < LogType.INFO.priority
            LogType.WARN.priority < LogType.ERROR.priority
            LogType.TRACE.priority < LogType.DEBUG.priority
        }
        
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime

        assertTrue("Priority comparisons should be very fast (< 10ms for 10000 comparisons)", duration < 10)
    }

    @Test
    fun `early filter check is efficient`() {
        val filter = LogFilter(
            enabledTypes = setOf(LogType.ERROR),
            enabledDomains = setOf(LogDomain.SERVICE)
        )

        val startTime = System.currentTimeMillis()
        
        // Test 10000 filter checks
        repeat(10000) { i ->
            val type = if (i % 5 == 0) LogType.ERROR else LogType.DEBUG
            val domain = if (i % 3 == 0) LogDomain.SERVICE else LogDomain.NETWORK
            
            // This simulates the early filter check in UnifiedLogger
            val shouldLog = type in filter.enabledTypes && domain in filter.enabledDomains
        }
        
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime

        assertTrue("Early filter checks should be very fast (< 20ms for 10000 checks)", duration < 20)
    }
}
