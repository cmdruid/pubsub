package com.cmdruid.pubsub.data

import com.cmdruid.pubsub.nostr.NostrFilter
import com.google.gson.Gson
import org.junit.Test
import org.junit.Assert.*

/**
 * Integration tests for Import/Export functionality
 * Tests end-to-end data flows and real-world scenarios
 */
class ImportExportIntegrationTest {

    private val gson = Gson()
    private val validRelays = listOf("wss://relay1.example.com", "wss://relay2.example.com")
    private val validTargetUri = "https://example.com/events"
    
    private fun createTestConfiguration(
        name: String,
        enabled: Boolean = true,
        keywords: List<String>? = null
    ) = Configuration(
        name = name,
        relayUrls = validRelays,
        filter = NostrFilter(kinds = listOf(1)),
        targetUri = validTargetUri,
        isEnabled = enabled,
        keywordFilter = keywords?.let { KeywordFilter.from(it) }
    )

    @Test
    fun testCompleteExportImportDataFlow() {
        // Step 1: Start with user configurations
        val originalConfigs = listOf(
            createTestConfiguration("Bitcoin Alerts", true, listOf("bitcoin", "hodl")),
            createTestConfiguration("Dev Updates", true, listOf("development", "coding")),
            createTestConfiguration("News Feed", false, listOf("news", "breaking"))
        )
        
        // Step 2: Export to JSON (simulating file export)
        val exportData = ExportData.create(originalConfigs, "1.2.3")
        val exportJson = gson.toJson(exportData)
        
        // Verify export structure
        assertTrue("Should contain all config names", exportJson.contains("Bitcoin Alerts"))
        assertTrue("Should contain keywords", exportJson.contains("bitcoin"))
        assertTrue("Should contain version", exportJson.contains("1.2.3"))
        assertFalse("Should not contain app-managed fields", exportJson.contains("since"))
        
        // Step 3: Import from JSON (simulating file import)
        val importData = gson.fromJson(exportJson, ExportData::class.java)
        val restoredConfigs = importData.subscriptions.map { it.toConfiguration() }
        
        // Step 4: Verify complete data preservation
        assertEquals("Should restore all configurations", 3, restoredConfigs.size)
        
        val configsByName = restoredConfigs.associateBy { it.name }
        
        // Verify Bitcoin Alerts
        val bitcoinConfig = configsByName["Bitcoin Alerts"]
        assertNotNull("Should restore Bitcoin Alerts", bitcoinConfig)
        assertTrue("Should preserve enabled state", bitcoinConfig!!.isEnabled)
        assertEquals("Should preserve keywords", listOf("bitcoin", "hodl"), bitcoinConfig.keywordFilter!!.keywords)
        assertEquals("Should preserve relays", validRelays, bitcoinConfig.relayUrls)
        
        println("✅ Complete export/import data flow successful")
    }

    @Test
    fun testFilterComplexityPreservation() {
        // Test that complex filters are fully preserved through export/import
        val complexFilter = NostrFilter(
            kinds = listOf(1, 3, 7),
            authors = listOf("author1", "author2", "author3"),
            eventRefs = listOf("event1", "event2"),
            pubkeyRefs = listOf("pubkey1", "pubkey2"),
            hashtagEntries = listOf(
                HashtagEntry("n", "bitcoin"),
                HashtagEntry("n", "nostr"), // Multiple values for same tag
                HashtagEntry("l", "news"),
                HashtagEntry("c", "crypto")
            )
        )
        
        val config = Configuration(
            name = "Complex Filter Config",
            relayUrls = listOf("wss://relay1.com", "wss://relay2.com", "wss://relay3.com"),
            filter = complexFilter,
            targetUri = validTargetUri,
            keywordFilter = KeywordFilter.from(listOf("bitcoin", "lightning", "nostr"))
        )
        
        // Export
        val exportData = ExportData.create(listOf(config), "1.0.0")
        val json = gson.toJson(exportData)
        
        // Import back
        val parsedData = gson.fromJson(json, ExportData::class.java)
        val restoredConfig = parsedData.subscriptions[0].toConfiguration()
        
        // Verify complete preservation
        assertEquals("Should preserve all kinds", listOf(1, 3, 7), restoredConfig.filter.kinds)
        assertEquals("Should preserve all authors", listOf("author1", "author2", "author3"), restoredConfig.filter.authors)
        assertEquals("Should preserve event refs", listOf("event1", "event2"), restoredConfig.filter.eventRefs)
        assertEquals("Should preserve pubkey refs", listOf("pubkey1", "pubkey2"), restoredConfig.filter.pubkeyRefs)
        assertNotNull("Should have hashtag entries", restoredConfig.filter.hashtagEntries)
        assertTrue("Should preserve hashtag entries", restoredConfig.filter.hashtagEntries!!.size >= 3)
        assertEquals("Should preserve all relays", 3, restoredConfig.relayUrls.size)
        assertEquals("Should preserve all keywords", 3, restoredConfig.keywordFilter!!.keywords.size)
        
        println("✅ Complex filter preservation successful")
    }

    @Test
    fun testAppManagedFieldsExclusion() {
        // Test that app-managed fields are properly excluded from export/import
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
        
        println("✅ App-managed fields properly excluded")
    }
}
