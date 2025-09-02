package com.cmdruid.pubsub.service

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cmdruid.pubsub.data.SettingsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CRITICAL integration tests for MetricsCollector/MetricsReader architecture
 * Tests the most important functionality: cross-instance data sharing
 */
@RunWith(AndroidJUnit4::class)
class MetricsIntegrationTest {
    
    private lateinit var context: Context
    private lateinit var settingsManager: SettingsManager
    private lateinit var serviceMetricsCollector: MetricsCollector
    private lateinit var uiMetricsReader: MetricsReader
    
    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        settingsManager = SettingsManager(context)
        
        // Enable metrics for testing
        settingsManager.setPerformanceMetricsEnabled(true)
        
        // Create separate instances to simulate service vs UI usage
        serviceMetricsCollector = MetricsCollector(context, settingsManager)
        uiMetricsReader = MetricsReader(context, settingsManager)
        
        // Clear any existing data
        runBlocking {
            uiMetricsReader.clearAllData()
            delay(200) // Wait for async clear to complete
        }
    }
    
    @After
    fun cleanup() {
        serviceMetricsCollector.cleanup()
        uiMetricsReader.cleanup()
    }
    
    @Test
    fun `CRITICAL - service data collection appears in UI reader`() = runBlocking {
        // Simulate service collecting metrics
        serviceMetricsCollector.trackBatteryOptimization("service_optimization", 75, true)
        serviceMetricsCollector.trackConnectionEvent("attempt", "wss://test.relay", true)
        serviceMetricsCollector.trackConnectionEvent("success", "wss://test.relay", true)
        serviceMetricsCollector.trackDuplicateEvent(
            eventProcessed = true,
            duplicateDetected = true,
            duplicatePrevented = true,
            networkDataSaved = 2048
        )
        
        // Wait for async persistence
        delay(300)
        
        // UI reader should see the data
        val report = uiMetricsReader.generateMetricsReport()
        assertNotNull("UI should see service data", report)
        
        report?.let {
            // Verify battery metrics from service
            assertEquals("UI should see battery check from service", 1L, it.batteryReport?.batteryChecks)
            assertEquals("UI should see optimization from service", 1L, it.batteryReport?.optimizationsApplied)
            assertEquals("UI should see battery level from service", 75, it.batteryReport?.currentBatteryLevel)
            
            // Verify connection metrics from service
            assertEquals("UI should see connection attempts from service", 2L, it.connectionReport?.connectionAttempts)
            assertEquals("UI should see successful connections from service", 1L, it.connectionReport?.successfulConnections)
            
            // Verify duplicate event metrics from service
            assertEquals("UI should see processed events from service", 1L, it.duplicateEventReport?.eventsProcessed)
            assertEquals("UI should see network data saved from service", 2048L, it.duplicateEventReport?.networkDataSavedBytes)
        }
    }
    
    @Test
    fun `CRITICAL - metrics toggle disables collection with zero overhead`() = runBlocking {
        // Enable metrics and collect some data
        settingsManager.setPerformanceMetricsEnabled(true)
        serviceMetricsCollector.trackBatteryOptimization("enabled_test", 80, true)
        delay(100)
        
        // Verify data exists
        var report = uiMetricsReader.generateMetricsReport()
        assertNotNull("Data should exist when enabled", report)
        assertTrue("Should have battery data when enabled", (report?.batteryReport?.batteryChecks ?: 0) > 0)
        
        // Disable metrics
        settingsManager.setPerformanceMetricsEnabled(false)
        delay(100) // Wait for settings to propagate
        
        // Create new instances after disabling (simulates service restart)
        val disabledCollector = MetricsCollector(context, settingsManager)
        val disabledReader = MetricsReader(context, settingsManager)
        
        // Try to collect metrics (should be no-op with zero overhead)
        val startTime = System.currentTimeMillis()
        repeat(50) {
            disabledCollector.trackBatteryOptimization("disabled_test", 70, true)
            disabledCollector.trackConnectionEvent("disabled_test", "test", true)
        }
        val endTime = System.currentTimeMillis()
        
        // Should be extremely fast when disabled (< 5ms for 100 calls)
        assertTrue("Disabled metrics should have zero overhead", (endTime - startTime) < 5)
        
        // Should return null report when disabled
        val disabledReport = disabledReader.generateMetricsReport()
        assertNull("Report should be null when disabled", disabledReport)
        
        disabledCollector.cleanup()
        disabledReader.cleanup()
    }
    
    @Test
    fun `CRITICAL - data persists across app restarts`() = runBlocking {
        // Collect initial data
        serviceMetricsCollector.trackBatteryOptimization("persistence_test", 85, true)
        serviceMetricsCollector.trackConnectionEvent("attempt", "persistent_relay", true)
        delay(200) // Wait for persistence
        
        // Cleanup original instances (simulate app restart)
        serviceMetricsCollector.cleanup()
        uiMetricsReader.cleanup()
        
        // Create new instances (simulate app restart)
        val newServiceCollector = MetricsCollector(context, settingsManager)
        val newUIReader = MetricsReader(context, settingsManager)
        
        // New UI reader should see persisted data
        val report = newUIReader.generateMetricsReport()
        assertNotNull("Data should persist across restarts", report)
        
        report?.let {
            assertEquals("Should have persisted battery check", 1L, it.batteryReport?.batteryChecks)
            assertEquals("Should have persisted optimization", 1L, it.batteryReport?.optimizationsApplied)
            assertEquals("Should have persisted battery level", 85, it.batteryReport?.currentBatteryLevel)
            assertEquals("Should have persisted connection attempt", 1L, it.connectionReport?.connectionAttempts)
        }
        
        // Add more data with new collector
        newServiceCollector.trackBatteryOptimization("post_restart", 90, true)
        delay(100)
        
        // Should accumulate with existing data
        val updatedReport = newUIReader.generateMetricsReport()
        assertEquals("Should accumulate data after restart", 2L, updatedReport?.batteryReport?.batteryChecks)
        
        newServiceCollector.cleanup()
        newUIReader.cleanup()
    }
    
    @Test
    fun `CRITICAL - clear data removes all traces`() = runBlocking {
        // Collect substantial data
        repeat(5) { i ->
            serviceMetricsCollector.trackBatteryOptimization("clear_test_$i", 60 + i, true)
            serviceMetricsCollector.trackConnectionEvent("attempt", "clear_relay_$i", true)
            serviceMetricsCollector.trackDuplicateEvent(true, true, true, networkDataSaved = 1024L * i)
        }
        delay(200)
        
        // Verify data exists
        val reportBefore = uiMetricsReader.generateMetricsReport()
        assertNotNull("Data should exist before clear", reportBefore)
        assertEquals("Should have 5 battery checks", 5L, reportBefore?.batteryReport?.batteryChecks)
        assertEquals("Should have 5 connection attempts", 5L, reportBefore?.connectionReport?.connectionAttempts)
        
        // Clear all data
        uiMetricsReader.clearAllData()
        delay(200) // Wait for async clear
        
        // Create new instances to verify complete cleanup
        val newCollector = MetricsCollector(context, settingsManager)
        val newReader = MetricsReader(context, settingsManager)
        
        // Should have no data
        val reportAfter = newReader.generateMetricsReport()
        reportAfter?.let {
            assertEquals("Battery checks should be zero after clear", 0L, it.batteryReport?.batteryChecks)
            assertEquals("Connection attempts should be zero after clear", 0L, it.connectionReport?.connectionAttempts)
            assertEquals("Events processed should be zero after clear", 0L, it.duplicateEventReport?.eventsProcessed)
            assertEquals("Network data saved should be zero after clear", 0L, it.duplicateEventReport?.networkDataSavedBytes)
        }
        
        newCollector.cleanup()
        newReader.cleanup()
    }
    
    @Test
    fun `CRITICAL - background operations never block main thread`() = runBlocking {
        // Test on main thread to verify non-blocking behavior
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val startTime = System.currentTimeMillis()
            
            // These should return immediately (non-blocking)
            repeat(20) {
                serviceMetricsCollector.trackBatteryOptimization("main_thread_test", 75, true)
                serviceMetricsCollector.trackConnectionEvent("main_thread_attempt", "test", true)
                serviceMetricsCollector.trackHealthCheck(false)
            }
            
            val endTime = System.currentTimeMillis()
            val duration = endTime - startTime
            
            // Should be extremely fast on main thread (< 5ms for 60 calls)
            assertTrue("Main thread operations should be non-blocking", duration < 5)
        }
        
        // Wait for background operations to complete
        delay(300)
        
        // Verify data was actually collected in background
        val report = uiMetricsReader.generateMetricsReport()
        assertNotNull("Data should be collected despite main thread calls", report)
        assertTrue("Should have battery data from main thread calls", (report?.batteryReport?.batteryChecks ?: 0) > 0)
    }
}
