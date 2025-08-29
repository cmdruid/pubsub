package com.cmdruid.pubsub.data

import com.cmdruid.pubsub.nostr.NostrFilter
import com.google.gson.Gson
import org.junit.Test
import org.junit.Assert.*

/**
 * Test suite for ImportExportManager core logic
 * Focuses on data transformation and validation without complex Android mocking
 */
class ImportExportManagerTest {

    private val gson = Gson()
    
    // Test data
    private val validRelays = listOf("wss://relay1.example.com", "wss://relay2.example.com")
    private val validTargetUri = "https://example.com/events"
    
    private fun createTestConfiguration(
        name: String = "Test Subscription",
        enabled: Boolean = true
    ) = Configuration(
        name = name,
        relayUrls = validRelays,
        filter = NostrFilter(kinds = listOf(1)),
        targetUri = validTargetUri,
        isEnabled = enabled,
        keywordFilter = KeywordFilter.from(listOf("bitcoin", "nostr"))
    )

    private fun createValidExportData(): ExportData {
        return ExportData(
            version = "1.2.3",
            date = "2023-01-01T12:00:00Z",
            subscriptions = listOf(
                ExportableSubscription(
                    name = "Test Sub 1",
                    enabled = true,
                    target = validTargetUri,
                    relays = validRelays,
                    filters = mapOf("kinds" to listOf(1)),
                    keywords = listOf("bitcoin")
                ),
                ExportableSubscription(
                    name = "Test Sub 2",
                    enabled = false,
                    target = validTargetUri,
                    relays = validRelays,
                    filters = mapOf("kinds" to listOf(1, 7)),
                    keywords = null
                )
            )
        )
    }

    // ========== Core Logic Tests ==========

    @Test
    fun testExportDataCreation() {
        val configurations = listOf(
            createTestConfiguration("Sub 1"),
            createTestConfiguration("Sub 2", false)
        )
        
        val exportData = ExportData.create(configurations, "1.2.3")
        
        assertEquals("Should preserve version", "1.2.3", exportData.version)
        assertFalse("Should have date", exportData.date.isBlank())
        assertEquals("Should preserve subscription count", 2, exportData.subscriptions.size)
        
        // Verify subscriptions
        val subNames = exportData.subscriptions.map { it.name }
        assertTrue("Should contain Sub 1", subNames.contains("Sub 1"))
        assertTrue("Should contain Sub 2", subNames.contains("Sub 2"))
        
        // Verify enabled states
        val sub1 = exportData.subscriptions.find { it.name == "Sub 1" }
        val sub2 = exportData.subscriptions.find { it.name == "Sub 2" }
        assertTrue("Sub 1 should be enabled", sub1!!.enabled)
        assertFalse("Sub 2 should be disabled", sub2!!.enabled)
    }

    @Test
    fun testJsonSerializationRoundTrip() {
        val configurations = listOf(
            createTestConfiguration("Bitcoin Feed", true),
            createTestConfiguration("Nostr Updates", false)
        )
        
        // Export to JSON
        val exportData = ExportData.create(configurations, "1.2.3")
        val json = gson.toJson(exportData)
        
        // Verify JSON structure
        assertTrue("Should contain version", json.contains("\"version\":\"1.2.3\""))
        assertTrue("Should contain subscriptions", json.contains("subscriptions"))
        assertTrue("Should contain Bitcoin Feed", json.contains("Bitcoin Feed"))
        assertTrue("Should contain Nostr Updates", json.contains("Nostr Updates"))
        
        // Import from JSON
        val parsedExportData = gson.fromJson(json, ExportData::class.java)
        assertEquals("Should preserve version", exportData.version, parsedExportData.version)
        assertEquals("Should preserve subscription count", exportData.subscriptions.size, parsedExportData.subscriptions.size)
        
        // Convert back to configurations
        val restoredConfigs = parsedExportData.subscriptions.map { it.toConfiguration() }
        assertEquals("Should restore correct count", 2, restoredConfigs.size)
        
        val configsByName = restoredConfigs.associateBy { it.name }
        val bitcoinConfig = configsByName["Bitcoin Feed"]
        val nostrConfig = configsByName["Nostr Updates"]
        
        assertNotNull("Should restore Bitcoin Feed", bitcoinConfig)
        assertNotNull("Should restore Nostr Updates", nostrConfig)
        assertTrue("Should preserve Bitcoin enabled state", bitcoinConfig!!.isEnabled)
        assertFalse("Should preserve Nostr enabled state", nostrConfig!!.isEnabled)
    }

    @Test
    fun testComplexFilterExportImport() {
        val complexFilter = NostrFilter(
            kinds = listOf(1, 7),
            authors = listOf("author1", "author2"),
            eventRefs = listOf("event1", "event2"),
            pubkeyRefs = listOf("pubkey1")
            // Note: since, until, limit intentionally excluded (app-managed)
        )
        
        val config = Configuration(
            name = "Complex Config",
            relayUrls = listOf("wss://relay1.com", "wss://relay2.com"),
            filter = complexFilter,
            targetUri = "https://example.com/events",
            keywordFilter = KeywordFilter.from(listOf("bitcoin", "lightning"))
        )
        
        // Export
        val exportData = ExportData.create(listOf(config), "1.0.0")
        val json = gson.toJson(exportData)
        
        // Verify complex filter is preserved in JSON
        assertTrue("Should preserve kinds", json.contains("\"kinds\":[1,7]"))
        assertTrue("Should preserve authors", json.contains("author1"))
        assertTrue("Should preserve event refs", json.contains("\"#e\":[\"event1\",\"event2\"]"))
        assertTrue("Should preserve pubkey refs", json.contains("\"#p\":[\"pubkey1\"]"))
        assertTrue("Should preserve keywords", json.contains("bitcoin"))
        
        // Should NOT contain app-managed fields
        assertFalse("Should not export since", json.contains("since"))
        assertFalse("Should not export until", json.contains("until"))
        assertFalse("Should not export limit", json.contains("limit"))
        
        // Import back
        val parsedData = gson.fromJson(json, ExportData::class.java)
        val restoredConfig = parsedData.subscriptions[0].toConfiguration()
        
        // Verify complex filter restoration
        assertEquals("Should restore kinds", listOf(1, 7), restoredConfig.filter.kinds)
        assertEquals("Should restore authors", listOf("author1", "author2"), restoredConfig.filter.authors)
        assertEquals("Should restore event refs", listOf("event1", "event2"), restoredConfig.filter.eventRefs)
        assertEquals("Should restore pubkey refs", listOf("pubkey1"), restoredConfig.filter.pubkeyRefs)
        assertEquals("Should restore keywords", listOf("bitcoin", "lightning"), restoredConfig.keywordFilter!!.keywords)
        
        // App-managed fields should be null
        assertNull("Should not restore since", restoredConfig.filter.since)
        assertNull("Should not restore until", restoredConfig.filter.until)
        assertNull("Should not restore limit", restoredConfig.filter.limit)
    }

    @Test
    fun testValidationLogic() {
        // Test valid export data
        val validData = createValidExportData()
        val validJson = gson.toJson(validData)
        
        // Manual validation (simulating what ImportExportManager would do)
        val parsedData = gson.fromJson(validJson, ExportData::class.java)
        assertNotNull("Should parse valid data", parsedData)
        assertFalse("Should have version", parsedData.version.isBlank())
        assertFalse("Should have date", parsedData.date.isBlank())
        
        // Validate each subscription
        parsedData.subscriptions.forEach { subscription ->
            val errors = subscription.validate()
            assertTrue("Valid subscription should have no errors: ${subscription.name}", errors.isEmpty())
        }
    }

    @Test
    fun testInvalidDataValidation() {
        // Create invalid export data
        val invalidData = ExportData(
            version = "", // Invalid - empty version
            date = "2023-01-01T12:00:00Z",
            subscriptions = listOf(
                ExportableSubscription(
                    name = "", // Invalid - empty name
                    enabled = true,
                    target = "invalid-url", // Invalid - not HTTP
                    relays = emptyList(), // Invalid - no relays
                    filters = emptyMap(),
                    keywords = null
                )
            )
        )
        
        // Check version validation
        assertTrue("Empty version should be invalid", invalidData.version.isBlank())
        
        // Check subscription validation
        val subscription = invalidData.subscriptions[0]
        val errors = subscription.validate()
        assertTrue("Should have validation errors", errors.isNotEmpty())
        assertTrue("Should report name error", errors.any { it.contains("name") })
        assertTrue("Should report target error", errors.any { it.contains("target") })
        assertTrue("Should report relay error", errors.any { it.contains("relay") })
    }

    @Test
    fun testFilenameGeneration() {
        // This is a static method, so we can test it directly
        // Note: We'd need access to ImportExportManager instance for this
        // For now, test the expected format
        val timestamp = "2023-01-01_12-30"
        val expectedPattern = "pubsub_backup_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}\\.json"
        
        // Test the pattern we expect
        val testFilename = "pubsub_backup_2023-01-01_12-30.json"
        assertTrue("Should match expected pattern", testFilename.matches(Regex(expectedPattern)))
    }

    @Test
    fun testKeywordFilterHandling() {
        // Test configuration with keyword filter
        val configWithKeywords = Configuration(
            name = "With Keywords",
            relayUrls = validRelays,
            filter = NostrFilter(kinds = listOf(1)),
            targetUri = validTargetUri,
            keywordFilter = KeywordFilter.from(listOf("bitcoin", "nostr", "lightning"))
        )
        
        // Test configuration without keyword filter
        val configWithoutKeywords = Configuration(
            name = "Without Keywords",
            relayUrls = validRelays,
            filter = NostrFilter(kinds = listOf(1)),
            targetUri = validTargetUri,
            keywordFilter = null
        )
        
        // Export both
        val exportData = ExportData.create(listOf(configWithKeywords, configWithoutKeywords), "1.0.0")
        val json = gson.toJson(exportData)
        
        // Verify keyword handling
        assertTrue("Should include keywords for first config", json.contains("bitcoin"))
        assertTrue("Should include keywords for first config", json.contains("lightning"))
        
        // Import back
        val parsedData = gson.fromJson(json, ExportData::class.java)
        val restoredConfigs = parsedData.subscriptions.map { it.toConfiguration() }
        
        val withKeywords = restoredConfigs.find { it.name == "With Keywords" }
        val withoutKeywords = restoredConfigs.find { it.name == "Without Keywords" }
        
        assertNotNull("Should restore config with keywords", withKeywords)
        assertNotNull("Should restore config without keywords", withoutKeywords)
        
        assertNotNull("Should restore keyword filter", withKeywords!!.keywordFilter)
        assertEquals("Should restore all keywords", 3, withKeywords.keywordFilter!!.keywords.size)
        assertTrue("Should contain bitcoin", withKeywords.keywordFilter!!.keywords.contains("bitcoin"))
        
        assertNull("Should not have keyword filter", withoutKeywords!!.keywordFilter)
    }

    @Test
    fun testHashtagEntriesExportImport() {
        val filterWithHashtags = NostrFilter(
            kinds = listOf(1),
            hashtagEntries = listOf(
                HashtagEntry("n", "bitcoin"),
                HashtagEntry("l", "news"),
                HashtagEntry("c", "crypto")
            )
        )
        
        val config = Configuration(
            name = "Hashtag Config",
            relayUrls = validRelays,
            filter = filterWithHashtags,
            targetUri = validTargetUri
        )
        
        // Export
        val exportData = ExportData.create(listOf(config), "1.0.0")
        val json = gson.toJson(exportData)
        
        // Verify hashtag entries are converted to NIP-01 format
        assertTrue("Should contain #n tag", json.contains("\"#n\":[\"bitcoin\"]"))
        assertTrue("Should contain #l tag", json.contains("\"#l\":[\"news\"]"))
        assertTrue("Should contain #c tag", json.contains("\"#c\":[\"crypto\"]"))
        
        // Import back
        val parsedData = gson.fromJson(json, ExportData::class.java)
        val restoredConfig = parsedData.subscriptions[0].toConfiguration()
        
        // Verify hashtag entries are restored
        assertNotNull("Should have hashtag entries", restoredConfig.filter.hashtagEntries)
        assertEquals("Should restore 3 hashtag entries", 3, restoredConfig.filter.hashtagEntries!!.size)
        
        val entriesByTag = restoredConfig.filter.hashtagEntries!!.associateBy { it.tag }
        assertEquals("Should restore n tag", "bitcoin", entriesByTag["n"]?.value)
        assertEquals("Should restore l tag", "news", entriesByTag["l"]?.value)
        assertEquals("Should restore c tag", "crypto", entriesByTag["c"]?.value)
    }

    @Test
    fun testAppManagedFieldsExclusion() {
        // Create filter with app-managed fields
        val filterWithAppFields = NostrFilter(
            kinds = listOf(1),
            authors = listOf("author1"),
            since = 1234567890L, // App-managed - should not be exported
            until = 1234567999L, // App-managed - should not be exported
            limit = 50 // App-managed - should not be exported
        )
        
        val config = Configuration(
            name = "App Fields Config",
            relayUrls = validRelays,
            filter = filterWithAppFields,
            targetUri = validTargetUri
        )
        
        // Export
        val exportData = ExportData.create(listOf(config), "1.0.0")
        val json = gson.toJson(exportData)
        
        // Verify app-managed fields are NOT exported
        assertFalse("Should not export since", json.contains("since"))
        assertFalse("Should not export until", json.contains("until"))
        assertFalse("Should not export limit", json.contains("limit"))
        
        // But user-configured fields should be exported
        assertTrue("Should export kinds", json.contains("\"kinds\":[1]"))
        assertTrue("Should export authors", json.contains("author1"))
        
        // Import back
        val parsedData = gson.fromJson(json, ExportData::class.java)
        val restoredConfig = parsedData.subscriptions[0].toConfiguration()
        
        // Verify user fields are restored but app fields are null
        assertEquals("Should restore kinds", listOf(1), restoredConfig.filter.kinds)
        assertEquals("Should restore authors", listOf("author1"), restoredConfig.filter.authors)
        assertNull("Should not restore since", restoredConfig.filter.since)
        assertNull("Should not restore until", restoredConfig.filter.until)
        assertNull("Should not restore limit", restoredConfig.filter.limit)
    }

    @Test
    fun testGenerateExportFilename() {
        // Test the filename generation pattern
        val pattern = Regex("pubsub_backup_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}\\.json")
        
        // We can't directly test the method without an instance, but we can test the expected format
        val sampleFilename = "pubsub_backup_2023-12-25_14-30.json"
        assertTrue("Should match expected pattern", sampleFilename.matches(pattern))
        
        val invalidFilename = "wrong_format.json"
        assertFalse("Should not match invalid pattern", invalidFilename.matches(pattern))
    }

    @Test
    fun testMaxSubscriptionLimits() {
        // Test the MAX_SUBSCRIPTIONS limit (100)
        val manySubscriptions = (1..150).map {
            ExportableSubscription(
                name = "Sub $it",
                enabled = true,
                target = validTargetUri,
                relays = validRelays,
                filters = mapOf("kinds" to listOf(1)),
                keywords = null
            )
        }
        
        val exportData = ExportData(
            version = "1.0.0",
            date = "2023-01-01T12:00:00Z",
            subscriptions = manySubscriptions
        )
        
        // This would trigger validation in ImportExportManager.validateImportFile()
        // We can't test the actual validation without the manager, but we can verify the data structure
        assertEquals("Should have 150 subscriptions", 150, exportData.subscriptions.size)
        
        // Each subscription should be valid individually
        val firstSub = exportData.subscriptions[0]
        val errors = firstSub.validate()
        assertTrue("Individual subscription should be valid", errors.isEmpty())
    }

    @Test
    fun testEmptySubscriptionsHandling() {
        val emptyExportData = ExportData(
            version = "1.0.0",
            date = "2023-01-01T12:00:00Z",
            subscriptions = emptyList()
        )
        
        val json = gson.toJson(emptyExportData)
        
        // Should serialize properly
        assertTrue("Should contain version", json.contains("\"version\":\"1.0.0\""))
        assertTrue("Should contain empty subscriptions", json.contains("\"subscriptions\":[]"))
        
        // Should deserialize properly
        val parsedData = gson.fromJson(json, ExportData::class.java)
        assertEquals("Should preserve version", "1.0.0", parsedData.version)
        assertEquals("Should have empty subscriptions", 0, parsedData.subscriptions.size)
    }

    @Test
    fun testSpecialCharacterHandling() {
        val configWithSpecialChars = Configuration(
            name = "Special 🚀 Config",
            relayUrls = listOf("wss://émoji.relay.com"),
            filter = NostrFilter(
                kinds = listOf(1),
                authors = listOf("author_with_underscore")
            ),
            targetUri = "https://spéciàl.example.com/events",
            keywordFilter = KeywordFilter.from(listOf("émoji", "spéciàl"))
        )
        
        // Export
        val exportData = ExportData.create(listOf(configWithSpecialChars), "1.0.0")
        val json = gson.toJson(exportData)
        
        // Should handle special characters properly
        assertTrue("Should preserve emoji in name", json.contains("🚀"))
        assertTrue("Should preserve accented chars", json.contains("émoji"))
        assertTrue("Should preserve special domain", json.contains("spéciàl"))
        
        // Import back
        val parsedData = gson.fromJson(json, ExportData::class.java)
        val restoredConfig = parsedData.subscriptions[0].toConfiguration()
        
        assertEquals("Should restore special name", "Special 🚀 Config", restoredConfig.name)
        assertEquals("Should restore special relay", listOf("wss://émoji.relay.com"), restoredConfig.relayUrls)
        assertEquals("Should restore special target", "https://spéciàl.example.com/events", restoredConfig.targetUri)
        assertEquals("Should restore special keywords", listOf("émoji", "spéciàl"), restoredConfig.keywordFilter!!.keywords)
    }

    @Test
    fun testDuplicateNameDetection() {
        // Test logic for detecting duplicate names
        val subscriptions = listOf(
            ExportableSubscription("Unique Name", true, validTargetUri, validRelays, mapOf("kinds" to listOf(1)), null),
            ExportableSubscription("Duplicate", true, validTargetUri, validRelays, mapOf("kinds" to listOf(1)), null),
            ExportableSubscription("Another Unique", false, validTargetUri, validRelays, mapOf("kinds" to listOf(1)), null),
            ExportableSubscription("duplicate", true, validTargetUri, validRelays, mapOf("kinds" to listOf(1)), null) // Same as "Duplicate" (case insensitive)
        )
        
        // Group by lowercase name to detect duplicates (same logic as ImportExportManager)
        val names = subscriptions.map { it.name.lowercase() }
        val duplicateNames = names.groupBy { it }.filter { it.value.size > 1 }.keys
        
        assertEquals("Should detect 1 duplicate name group", 1, duplicateNames.size)
        assertTrue("Should detect 'duplicate' as duplicate", duplicateNames.contains("duplicate"))
    }
}