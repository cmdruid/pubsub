package com.cmdruid.pubsub.data

import com.cmdruid.pubsub.nostr.NostrFilter
import org.junit.Test
import org.junit.Assert.*

/**
 * Test class for Configuration data model
 * Tests validation logic, subscription ID generation, and state management
 */
class ConfigurationTest {

    @Test
    fun `should validate required fields`() {
        // Valid configuration
        val validConfig = Configuration(
            id = "valid-id",
            name = "Valid Config",
            isEnabled = true,
            relayUrls = listOf("wss://relay.com"),
            filter = NostrFilter(kinds = listOf(1)),
            targetUri = "https://app.com",
            subscriptionId = "valid-sub"
        )
        
        assertTrue("Valid configuration should be valid", validConfig.isValid())
        
        // Invalid configurations
        val invalidConfigs = listOf(
            validConfig.copy(name = ""), // Empty name
            validConfig.copy(relayUrls = emptyList()), // No relay URLs
            validConfig.copy(targetUri = ""), // Empty target URI
            validConfig.copy(relayUrls = listOf("", "wss://valid.relay")) // Contains blank relay URL
        )
        
        invalidConfigs.forEach { config ->
            assertFalse("Invalid configuration should fail validation: ${config.name}", config.isValid())
        }
    }

    @Test
    fun `should generate consistent subscription IDs`() {
        val config = Configuration(
            name = "Test Config",
            relayUrls = listOf("wss://relay.com"),
            filter = NostrFilter(kinds = listOf(1)),
            targetUri = "https://app.com"
        )
        
        val subId1 = config.subscriptionId
        val subId2 = config.subscriptionId
        
        assertEquals("Subscription ID should be consistent", subId1, subId2)
        assertFalse("Subscription ID should not be empty", subId1.isEmpty())
        assertTrue("Subscription ID should be reasonable length", subId1.length >= 8)
    }

    @Test
    fun `should handle enabled disabled state`() {
        val config = Configuration(
            name = "State Test",
            relayUrls = listOf("wss://relay.com"),
            filter = NostrFilter(kinds = listOf(1)),
            targetUri = "https://app.com",
            isEnabled = true
        )
        
        assertTrue("Should be enabled", config.isEnabled)
        
        val disabledConfig = config.copy(isEnabled = false)
        assertFalse("Should be disabled", disabledConfig.isEnabled)
    }

    @Test
    fun `should validate relay URLs`() {
        val validUrls = listOf(
            "wss://relay.example.com",
            "ws://localhost:8080",
            "wss://relay.damus.io"
        )
        
        val invalidUrls = listOf(
            "",
            "not-a-url",
            "http://not-websocket.com",
            "ftp://wrong-protocol.com"
        )
        
        // Valid URLs should create valid configuration
        val validConfig = Configuration(
            name = "URL Test",
            relayUrls = validUrls,
            filter = NostrFilter(kinds = listOf(1)),
            targetUri = "https://app.com"
        )
        
        assertTrue("Configuration with valid URLs should be valid", validConfig.isValid())
        
        // Invalid URLs should create invalid configuration
        val invalidConfig = Configuration(
            name = "Invalid URL Test",
            relayUrls = invalidUrls,
            filter = NostrFilter(kinds = listOf(1)),
            targetUri = "https://app.com"
        )
        
        assertFalse("Configuration with invalid URLs should be invalid", invalidConfig.isValid())
    }

    @Test
    fun `should validate target URIs`() {
        val validTargetUris = listOf(
            "https://app.example.com",
            "http://localhost:3000",
            "https://app.com/events",
            "app://custom-scheme"
        )
        
        val invalidTargetUris = listOf(
            "",
            "not-a-uri",
            "ftp://unsupported.com"
        )
        
        validTargetUris.forEach { uri ->
            val config = Configuration(
                name = "Target URI Test",
                relayUrls = listOf("wss://relay.com"),
                filter = NostrFilter(kinds = listOf(1)),
                targetUri = uri
            )
            
            assertTrue("URI $uri should be valid", config.isValid())
        }
        
        invalidTargetUris.forEach { uri ->
            val config = Configuration(
                name = "Invalid Target URI Test",
                relayUrls = listOf("wss://relay.com"),
                filter = NostrFilter(kinds = listOf(1)),
                targetUri = uri
            )
            
            assertFalse("URI $uri should be invalid", config.isValid())
        }
    }

    @Test
    fun `should handle keyword filters correctly`() {
        val keywordFilter = KeywordFilter.from(listOf("bitcoin", "nostr"))
        
        val config = Configuration(
            name = "Keyword Test",
            relayUrls = listOf("wss://relay.com"),
            filter = NostrFilter(kinds = listOf(1)),
            targetUri = "https://app.com",
            keywordFilter = keywordFilter
        )
        
        assertTrue("Configuration with keyword filter should be valid", config.isValid())
        assertEquals("Should preserve keyword filter", keywordFilter, config.keywordFilter)
    }

    @Test
    fun `should handle filter exclusion options`() {
        val config = Configuration(
            name = "Exclusion Test",
            relayUrls = listOf("wss://relay.com"),
            filter = NostrFilter(kinds = listOf(1)),
            targetUri = "https://app.com",
            excludeMentionsToSelf = true,
            excludeRepliesToEvents = true
        )
        
        assertTrue("Configuration with exclusions should be valid", config.isValid())
        assertTrue("Should preserve mentions exclusion", config.excludeMentionsToSelf)
        assertTrue("Should preserve replies exclusion", config.excludeRepliesToEvents)
    }

    @Test
    fun `should create copy with modifications`() {
        val original = Configuration(
            name = "Original",
            relayUrls = listOf("wss://relay1.com"),
            filter = NostrFilter(kinds = listOf(1)),
            targetUri = "https://app.com",
            isEnabled = true
        )
        
        val modified = original.copy(
            name = "Modified",
            isEnabled = false,
            relayUrls = listOf("wss://relay1.com", "wss://relay2.com")
        )
        
        // Original should be unchanged
        assertEquals("Original name should be unchanged", "Original", original.name)
        assertTrue("Original enabled state should be unchanged", original.isEnabled)
        assertEquals("Original relay count should be unchanged", 1, original.relayUrls.size)
        
        // Modified should have changes
        assertEquals("Modified name should be updated", "Modified", modified.name)
        assertFalse("Modified enabled state should be updated", modified.isEnabled)
        assertEquals("Modified relay count should be updated", 2, modified.relayUrls.size)
        
        // Other fields should be preserved
        assertEquals("Filter should be preserved", original.filter, modified.filter)
        assertEquals("Target URI should be preserved", original.targetUri, modified.targetUri)
    }

    @Test
    fun `should handle complex filter configurations`() {
        val complexFilter = NostrFilter(
            kinds = listOf(1, 3, 7),
            authors = listOf("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"),
            hashtagEntries = listOf(
                HashtagEntry("t", "bitcoin"),
                HashtagEntry("t", "nostr"),
                HashtagEntry("l", "en")
            ),
            since = 1640995200L,
            limit = 100
        )
        
        val config = Configuration(
            name = "Complex Filter Test",
            relayUrls = listOf("wss://relay.com"),
            filter = complexFilter,
            targetUri = "https://app.com"
        )
        
        assertTrue("Configuration with complex filter should be valid", config.isValid())
        assertEquals("Should preserve complex filter", complexFilter, config.filter)
    }
}
