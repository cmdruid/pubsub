package com.cmdruid.pubsub.logging

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cmdruid.pubsub.data.ConfigurationManager
import com.cmdruid.pubsub.data.SettingsManager
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import org.junit.Before

/**
 * Integration tests for logging system with Android components
 */
@RunWith(AndroidJUnit4::class)
class LoggingIntegrationTest {

    private lateinit var context: Context
    private lateinit var settingsManager: SettingsManager
    private lateinit var configurationManager: ConfigurationManager
    private lateinit var unifiedLogger: UnifiedLoggerImpl

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        settingsManager = SettingsManager(context)
        configurationManager = ConfigurationManager(context)
        unifiedLogger = UnifiedLoggerImpl(context, configurationManager)
    }

    @Test
    fun `filter persistence works across sessions`() {
        // Create a custom filter
        val customFilter = LogFilter(
            enabledTypes = setOf(LogType.ERROR, LogType.WARN),
            enabledDomains = setOf(LogDomain.SERVICE, LogDomain.BATTERY),
            maxLogs = 250
        )

        // Save the filter
        settingsManager.saveLogFilter(customFilter)

        // Load the filter
        val loadedFilter = settingsManager.getLogFilter()

        assertEquals("Types should persist", customFilter.enabledTypes, loadedFilter.enabledTypes)
        assertEquals("Domains should persist", customFilter.enabledDomains, loadedFilter.enabledDomains)
        assertEquals("Max logs should persist", customFilter.maxLogs, loadedFilter.maxLogs)
    }

    @Test
    fun `unified logger integrates with configuration manager`() {
        // Clear any existing logs
        configurationManager.clearStructuredLogs()

        // Log some entries
        unifiedLogger.info(LogDomain.SERVICE, "Test service message")
        unifiedLogger.warn(LogDomain.NETWORK, "Test network warning")

        // Give background thread time to complete
        Thread.sleep(100)

        // Verify logs were stored
        val storedLogs = configurationManager.structuredLogs
        assertTrue("Should have stored logs", storedLogs.isNotEmpty())
        assertTrue("Should contain service message", storedLogs.any { it.message.contains("Test service message") })
        assertTrue("Should contain network warning", storedLogs.any { it.message.contains("Test network warning") })
    }

    @Test
    fun `early filtering prevents log creation and storage`() {
        // Clear any existing logs
        configurationManager.clearStructuredLogs()

        // Set a restrictive filter
        val restrictiveFilter = LogFilter(
            enabledTypes = setOf(LogType.ERROR),
            enabledDomains = setOf(LogDomain.SERVICE)
        )
        unifiedLogger.setFilter(restrictiveFilter)

        // Log entries that should be filtered out
        unifiedLogger.debug(LogDomain.NETWORK, "This should be filtered")
        unifiedLogger.info(LogDomain.BATTERY, "This should also be filtered")

        // Log entry that should pass
        unifiedLogger.error(LogDomain.SERVICE, "This should pass")

        // Give background thread time to complete
        Thread.sleep(100)

        // Verify only the passing log was stored
        val storedLogs = configurationManager.structuredLogs
        assertEquals("Should only have one stored log", 1, storedLogs.size)
        assertEquals("Should be the error message", LogType.ERROR, storedLogs[0].type)
        assertEquals("Should be service domain", LogDomain.SERVICE, storedLogs[0].domain)
        assertTrue("Should contain correct message", storedLogs[0].message.contains("This should pass"))
    }

    @Test
    fun `build config affects default filter types`() {
        // Test that the default filter respects build configuration
        val defaultFilter = LogFilter.DEFAULT
        
        // In debug builds, should include DEBUG and INFO
        // In release builds, should only include WARN and ERROR
        assertTrue("Default filter should include WARN", LogType.WARN in defaultFilter.enabledTypes)
        assertTrue("Default filter should include ERROR", LogType.ERROR in defaultFilter.enabledTypes)
        
        // The specific inclusion of DEBUG/INFO depends on BuildConfig.DEBUG
        // This test verifies the mechanism works
        assertNotNull("Default filter should have enabled types", defaultFilter.enabledTypes)
        assertTrue("Default filter should have at least WARN and ERROR", 
            defaultFilter.enabledTypes.containsAll(setOf(LogType.WARN, LogType.ERROR)))
    }

    @Test
    fun `configuration manager handles structured logs correctly`() {
        configurationManager.clearStructuredLogs()

        val testEntry = StructuredLogEntry(
            timestamp = System.currentTimeMillis(),
            type = LogType.INFO,
            domain = LogDomain.UI,
            message = "Test message",
            data = mapOf("test" to "value")
        )

        configurationManager.addStructuredLog(testEntry)

        val storedLogs = configurationManager.structuredLogs
        assertEquals("Should have one stored log", 1, storedLogs.size)
        assertEquals("Should preserve message", testEntry.message, storedLogs[0].message)
        assertEquals("Should preserve type", testEntry.type, storedLogs[0].type)
        assertEquals("Should preserve domain", testEntry.domain, storedLogs[0].domain)
    }
}
