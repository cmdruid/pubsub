package com.cmdruid.pubsub.data

import com.cmdruid.pubsub.nostr.NostrFilter
import org.junit.Test
import org.junit.Assert.*

/**
 * Comprehensive test suite for Import/Export data classes and validation
 * Tests ExportData, ExportableSubscription, and related validation logic
 */
class ImportExportDataTest {

    // Test data
    private val validRelays = listOf("wss://relay1.example.com", "wss://relay2.example.com")
    private val validTargetUri = "https://example.com/events"
    private val validKeywords = listOf("bitcoin", "nostr")
    
    private fun createTestConfiguration(
        name: String = "Test Subscription",
        enabled: Boolean = true
    ) = Configuration(
        name = name,
        relayUrls = validRelays,
        filter = NostrFilter(kinds = listOf(1)),
        targetUri = validTargetUri,
        isEnabled = enabled,
        keywordFilter = KeywordFilter.from(validKeywords)
    )

    // ========== ExportData Tests ==========

    @Test
    fun testExportData_Create() {
        val configurations = listOf(
            createTestConfiguration("Sub 1"),
            createTestConfiguration("Sub 2", false)
        )
        val appVersion = "1.2.3"
        
        val exportData = ExportData.create(configurations, appVersion)
        
        assertEquals("Should preserve version", appVersion, exportData.version)
        assertFalse("Should have date", exportData.date.isBlank())
        assertEquals("Should preserve subscription count", 2, exportData.subscriptions.size)
        
        // Verify date format (should be ISO format)
        assertTrue("Date should be ISO format", exportData.date.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")))
    }

    @Test
    fun testExportData_EmptySubscriptions() {
        val exportData = ExportData.create(emptyList(), "1.0.0")
        
        assertEquals("Should handle empty subscriptions", 0, exportData.subscriptions.size)
        assertEquals("Should preserve version", "1.0.0", exportData.version)
        assertFalse("Should still have date", exportData.date.isBlank())
    }

    @Test
    fun testExportData_DateFormat() {
        val exportData = ExportData.create(listOf(createTestConfiguration()), "1.0.0")
        
        // Date should be in UTC ISO format
        val dateRegex = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")
        assertTrue("Date should match ISO UTC format: ${exportData.date}", 
                   exportData.date.matches(dateRegex))
    }

    // ========== ExportableSubscription Tests ==========

    @Test
    fun testExportableSubscription_FromConfiguration() {
        val config = createTestConfiguration("Test Sub", false)
        val exportable = ExportableSubscription.fromConfiguration(config)
        
        assertEquals("Should preserve name", "Test Sub", exportable.name)
        assertEquals("Should preserve enabled state", false, exportable.enabled)
        assertEquals("Should preserve target", validTargetUri, exportable.target)
        assertEquals("Should preserve relays", validRelays, exportable.relays)
        assertEquals("Should preserve keywords", validKeywords, exportable.keywords)
        
        // Verify filters map
        assertTrue("Should have filters", exportable.filters.isNotEmpty())
        assertTrue("Should contain kinds", exportable.filters.containsKey("kinds"))
    }

    @Test
    fun testExportableSubscription_WithoutKeywords() {
        val configNoKeywords = Configuration(
            name = "No Keywords",
            relayUrls = validRelays,
            filter = NostrFilter(kinds = listOf(1)),
            targetUri = validTargetUri,
            keywordFilter = null
        )
        
        val exportable = ExportableSubscription.fromConfiguration(configNoKeywords)
        assertNull("Should handle null keywords", exportable.keywords)
    }

    @Test
    fun testExportableSubscription_WithEmptyKeywords() {
        val configEmptyKeywords = Configuration(
            name = "Empty Keywords",
            relayUrls = validRelays,
            filter = NostrFilter(kinds = listOf(1)),
            targetUri = validTargetUri,
            keywordFilter = KeywordFilter.empty()
        )
        
        val exportable = ExportableSubscription.fromConfiguration(configEmptyKeywords)
        assertNull("Should handle empty keywords", exportable.keywords)
    }

    @Test
    fun testExportableSubscription_ComplexFilter() {
        val complexFilter = NostrFilter(
            kinds = listOf(1, 7),
            authors = listOf("author1", "author2"),
            since = 1234567890L, // This should NOT be exported
            until = 1234567999L, // This should NOT be exported
            limit = 50 // This should NOT be exported
        )
        
        val config = Configuration(
            name = "Complex Filter",
            relayUrls = validRelays,
            filter = complexFilter,
            targetUri = validTargetUri
        )
        
        val exportable = ExportableSubscription.fromConfiguration(config)
        val filters = exportable.filters
        
        assertEquals("Should preserve kinds", listOf(1, 7), filters["kinds"])
        assertEquals("Should preserve authors", listOf("author1", "author2"), filters["authors"])
        
        // App-managed fields should NOT be exported
        assertFalse("Should not export since (app-managed)", filters.containsKey("since"))
        assertFalse("Should not export until (app-managed)", filters.containsKey("until"))
        assertFalse("Should not export limit (app-managed)", filters.containsKey("limit"))
    }

    // ========== ExportableSubscription.toConfiguration Tests ==========

    @Test
    fun testToConfiguration_BasicConversion() {
        val exportable = ExportableSubscription(
            name = "Test Sub",
            enabled = true,
            target = validTargetUri,
            relays = validRelays,
            filters = mapOf("kinds" to listOf(1)),
            keywords = validKeywords
        )
        
        val config = exportable.toConfiguration()
        
        assertEquals("Should preserve name", "Test Sub", config.name)
        assertEquals("Should preserve enabled state", true, config.isEnabled)
        assertEquals("Should preserve target URI", validTargetUri, config.targetUri)
        assertEquals("Should preserve relays", validRelays, config.relayUrls)
        
        // Verify filter conversion
        assertNotNull("Should have filter", config.filter)
        assertEquals("Should preserve kinds", listOf(1), config.filter.kinds)
        
        // Verify keyword filter conversion
        assertNotNull("Should have keyword filter", config.keywordFilter)
        assertEquals("Should preserve keywords", validKeywords, config.keywordFilter!!.keywords)
    }

    @Test
    fun testToConfiguration_ComplexFilter() {
        val exportable = ExportableSubscription(
            name = "Complex Sub",
            enabled = false,
            target = validTargetUri,
            relays = validRelays,
            filters = mapOf(
                "kinds" to listOf(1, 7),
                "authors" to listOf("author1")
                // Note: since, until, limit should not be in import data
                // as they are app-managed fields
            ),
            keywords = null
        )
        
        val config = exportable.toConfiguration()
        
        assertEquals("Should preserve enabled state", false, config.isEnabled)
        
        val filter = config.filter
        assertEquals("Should preserve kinds", listOf(1, 7), filter.kinds)
        assertEquals("Should preserve authors", listOf("author1"), filter.authors)
        
        // App-managed fields should be null (not imported)
        assertNull("Should not import since (app-managed)", filter.since)
        assertNull("Should not import until (app-managed)", filter.until)
        assertNull("Should not import limit (app-managed)", filter.limit)
        
        assertNull("Should handle null keywords", config.keywordFilter)
    }

    @Test
    fun testToConfiguration_EmptyFilter() {
        val exportable = ExportableSubscription(
            name = "Empty Filter",
            enabled = true,
            target = validTargetUri,
            relays = validRelays,
            filters = emptyMap(),
            keywords = null
        )
        
        val config = exportable.toConfiguration()
        
        // Should create a basic filter even if filters map is empty
        assertNotNull("Should create filter even if empty", config.filter)
    }

    // ========== Validation Tests ==========

    @Test
    fun testValidation_ValidSubscription() {
        val exportable = ExportableSubscription(
            name = "Valid Sub",
            enabled = true,
            target = "https://example.com/events",
            relays = listOf("wss://relay.example.com"),
            filters = mapOf("kinds" to listOf(1)),
            keywords = listOf("bitcoin")
        )
        
        val errors = exportable.validate()
        assertTrue("Valid subscription should have no errors", errors.isEmpty())
    }

    @Test
    fun testValidation_EmptyName() {
        val exportable = ExportableSubscription(
            name = "",
            enabled = true,
            target = validTargetUri,
            relays = validRelays,
            filters = mapOf("kinds" to listOf(1)),
            keywords = null
        )
        
        val errors = exportable.validate()
        assertTrue("Should report empty name error", 
                   errors.any { it.contains("name cannot be empty") })
    }

    @Test
    fun testValidation_BlankName() {
        val exportable = ExportableSubscription(
            name = "   ",
            enabled = true,
            target = validTargetUri,
            relays = validRelays,
            filters = mapOf("kinds" to listOf(1)),
            keywords = null
        )
        
        val errors = exportable.validate()
        assertTrue("Should report blank name error", 
                   errors.any { it.contains("name cannot be empty") })
    }

    @Test
    fun testValidation_NoRelays() {
        val exportable = ExportableSubscription(
            name = "No Relays",
            enabled = true,
            target = validTargetUri,
            relays = emptyList(),
            filters = mapOf("kinds" to listOf(1)),
            keywords = null
        )
        
        val errors = exportable.validate()
        assertTrue("Should report no relays error", 
                   errors.any { it.contains("at least one relay") })
    }

    @Test
    fun testValidation_InvalidRelayUrls() {
        val exportable = ExportableSubscription(
            name = "Invalid Relays",
            enabled = true,
            target = validTargetUri,
            relays = listOf("wss://valid.com", "invalid-url", "http://not-websocket.com"),
            filters = mapOf("kinds" to listOf(1)),
            keywords = null
        )
        
        val errors = exportable.validate()
        assertTrue("Should report invalid relay URL errors", errors.size >= 2)
        assertTrue("Should identify invalid-url", 
                   errors.any { it.contains("invalid-url") })
        assertTrue("Should identify http URL", 
                   errors.any { it.contains("http://not-websocket.com") })
    }

    @Test
    fun testValidation_InvalidTargetUri() {
        val exportable = ExportableSubscription(
            name = "Invalid Target",
            enabled = true,
            target = "not-a-valid-url",
            relays = validRelays,
            filters = mapOf("kinds" to listOf(1)),
            keywords = null
        )
        
        val errors = exportable.validate()
        assertTrue("Should report invalid target URI error", 
                   errors.any { it.contains("Invalid target URI") })
    }

    @Test
    fun testValidation_MultipleErrors() {
        val exportable = ExportableSubscription(
            name = "",
            enabled = true,
            target = "invalid-target",
            relays = listOf("invalid-relay"),
            filters = mapOf("kinds" to listOf(1)),
            keywords = null
        )
        
        val errors = exportable.validate()
        assertTrue("Should report multiple errors", errors.size >= 3)
        assertTrue("Should report name error", errors.any { it.contains("name") })
        assertTrue("Should report target error", errors.any { it.contains("target") })
        assertTrue("Should report relay error", errors.any { it.contains("relay") })
    }

    // ========== URL Validation Helper Tests ==========

    @Test
    fun testRelayUrlValidation() {
        val validExportable = ExportableSubscription(
            name = "Test",
            enabled = true,
            target = validTargetUri,
            relays = listOf("wss://example.com", "ws://insecure.com"),
            filters = emptyMap(),
            keywords = null
        )
        
        val errors = validExportable.validate()
        // Should accept both wss:// and ws:// for relay URLs
        assertFalse("Should accept wss:// and ws:// URLs", 
                    errors.any { it.contains("relay") })
    }

    @Test
    fun testTargetUriValidation() {
        val validTargets = listOf(
            "https://example.com",
            "http://localhost:8080",
            "https://example.com/path/to/events"
        )
        
        validTargets.forEach { target ->
            val exportable = ExportableSubscription(
                name = "Test",
                enabled = true,
                target = target,
                relays = validRelays,
                filters = emptyMap(),
                keywords = null
            )
            
            val errors = exportable.validate()
            assertFalse("Should accept valid target: $target", 
                        errors.any { it.contains("target") })
        }
    }

    // ========== Round Trip Tests ==========

    @Test
    fun testRoundTrip_ConfigurationToExportableAndBack() {
        val originalConfig = createTestConfiguration("Round Trip Test")
        
        // Convert to exportable
        val exportable = ExportableSubscription.fromConfiguration(originalConfig)
        
        // Convert back to configuration
        val convertedConfig = exportable.toConfiguration()
        
        // Verify preservation of key fields
        assertEquals("Should preserve name", originalConfig.name, convertedConfig.name)
        assertEquals("Should preserve enabled state", originalConfig.isEnabled, convertedConfig.isEnabled)
        assertEquals("Should preserve target URI", originalConfig.targetUri, convertedConfig.targetUri)
        assertEquals("Should preserve relays", originalConfig.relayUrls, convertedConfig.relayUrls)
        
        // Verify filter preservation
        assertEquals("Should preserve filter kinds", originalConfig.filter.kinds, convertedConfig.filter.kinds)
        
        // Verify keyword filter preservation
        assertEquals("Should preserve keyword filter", 
                    originalConfig.keywordFilter?.keywords, 
                    convertedConfig.keywordFilter?.keywords)
    }

    @Test
    fun testRoundTrip_ExportDataCreationAndParsing() {
        val configs = listOf(
            createTestConfiguration("Sub 1", true),
            createTestConfiguration("Sub 2", false)
        )
        
        val exportData = ExportData.create(configs, "1.2.3")
        
        // Verify all subscriptions can be converted back
        exportData.subscriptions.forEachIndexed { index, exportable ->
            val convertedConfig = exportable.toConfiguration()
            val originalConfig = configs[index]
            
            assertEquals("Should preserve name for sub $index", 
                        originalConfig.name, convertedConfig.name)
            assertEquals("Should preserve enabled state for sub $index", 
                        originalConfig.isEnabled, convertedConfig.isEnabled)
        }
    }

    // ========== Edge Cases ==========

    @Test
    fun testEdgeCase_VeryLongName() {
        val longName = "a".repeat(1000)
        val exportable = ExportableSubscription(
            name = longName,
            enabled = true,
            target = validTargetUri,
            relays = validRelays,
            filters = emptyMap(),
            keywords = null
        )
        
        val errors = exportable.validate()
        // Should handle very long names (validation might limit length in the future)
        assertTrue("Should handle very long names", errors.isEmpty() || errors.any { it.contains("name") })
    }

    @Test
    fun testEdgeCase_ManyRelays() {
        val manyRelays = (1..50).map { "wss://relay$it.example.com" }
        val exportable = ExportableSubscription(
            name = "Many Relays",
            enabled = true,
            target = validTargetUri,
            relays = manyRelays,
            filters = emptyMap(),
            keywords = null
        )
        
        val errors = exportable.validate()
        assertTrue("Should handle many relays", errors.isEmpty())
    }

    @Test
    fun testEdgeCase_ManyKeywords() {
        val manyKeywords = (1..100).map { "keyword$it" }
        val exportable = ExportableSubscription(
            name = "Many Keywords",
            enabled = true,
            target = validTargetUri,
            relays = validRelays,
            filters = emptyMap(),
            keywords = manyKeywords
        )
        
        val config = exportable.toConfiguration()
        assertEquals("Should preserve many keywords", manyKeywords, config.keywordFilter?.keywords)
    }

    // ========== Result Types Tests ==========

    @Test
    fun testExportResult_Success() {
        val result = ExportResult.Success("backup.json", 5)
        assertEquals("Should preserve filename", "backup.json", result.filename)
        assertEquals("Should preserve count", 5, result.subscriptionCount)
    }

    @Test
    fun testExportResult_Error() {
        val exception = Exception("Test error")
        val result = ExportResult.Error("Export failed", exception)
        assertEquals("Should preserve message", "Export failed", result.message)
        assertEquals("Should preserve cause", exception, result.cause)
    }

    @Test
    fun testImportResult_Success() {
        val result = ImportResult.Success(importedCount = 3, duplicateCount = 2, skippedCount = 1)
        assertEquals("Should preserve imported count", 3, result.importedCount)
        assertEquals("Should preserve duplicate count", 2, result.duplicateCount)
        assertEquals("Should preserve skipped count", 1, result.skippedCount)
    }

    @Test
    fun testImportResult_Error() {
        val exception = Exception("Import error")
        val result = ImportResult.Error("Import failed", exception)
        assertEquals("Should preserve message", "Import failed", result.message)
        assertEquals("Should preserve cause", exception, result.cause)
    }

    @Test
    fun testValidationResult_Valid() {
        val result = ValidationResult.Valid
        assertTrue("Valid result should be Valid type", result is ValidationResult.Valid)
    }

    @Test
    fun testValidationResult_Invalid() {
        val errors = listOf("Error 1", "Error 2")
        val result = ValidationResult.Invalid(errors)
        assertEquals("Should preserve errors", errors, result.errors)
    }

    @Test
    fun testImportPreview() {
        val preview = ImportPreview(
            totalSubscriptions = 5,
            newSubscriptions = listOf("New 1", "New 2"),
            duplicateSubscriptions = listOf("Duplicate 1"),
            version = "1.2.3",
            date = "2023-01-01T12:00:00Z"
        )
        
        assertEquals("Should preserve total", 5, preview.totalSubscriptions)
        assertEquals("Should preserve new subscriptions", 2, preview.newSubscriptions.size)
        assertEquals("Should preserve duplicates", 1, preview.duplicateSubscriptions.size)
        assertEquals("Should preserve version", "1.2.3", preview.version)
        assertEquals("Should preserve date", "2023-01-01T12:00:00Z", preview.date)
    }

    @Test
    fun testImportMode_Values() {
        // Test that all import modes are available
        val modes = ImportMode.values()
        assertTrue("Should have ADD_NEW_ONLY", modes.contains(ImportMode.ADD_NEW_ONLY))
        assertTrue("Should have REPLACE_ALL", modes.contains(ImportMode.REPLACE_ALL))
        assertTrue("Should have REPLACE_DUPLICATES", modes.contains(ImportMode.REPLACE_DUPLICATES))
    }
}
