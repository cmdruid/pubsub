package com.cmdruid.pubsub.service

import android.content.Context
import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.data.ConfigurationManager
import com.cmdruid.pubsub.data.KeywordFilter
import com.cmdruid.pubsub.nostr.NostrEvent
import com.cmdruid.pubsub.nostr.NostrFilter
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Simple, focused tests for service functionality without complex mocking
 * Tests core behaviors that are important for the application
 */
@RunWith(RobolectricTestRunner::class)
class SimpleServiceTest {

    private lateinit var context: Context
    private lateinit var configurationManager: ConfigurationManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        configurationManager = ConfigurationManager(context)
    }

    @Test
    fun `should create and store configurations`() {
        // Given: A valid configuration
        val config = Configuration(
            name = "Test Config",
            relayUrls = listOf("wss://relay.damus.io"),
            filter = NostrFilter(kinds = listOf(1)),
            targetUri = "https://njump.me"
        )

        // When: Adding the configuration
        configurationManager.addConfiguration(config)

        // Then: Configuration should be stored
        val configurations = configurationManager.getConfigurations()
        assertEquals("Should have 1 configuration", 1, configurations.size)
        assertEquals("Should have correct name", "Test Config", configurations[0].name)
    }

    @Test
    fun `should validate configuration requirements`() {
        // Given: Invalid configuration (no relays)
        val invalidConfig = Configuration(
            name = "Invalid Config",
            relayUrls = emptyList(),
            filter = NostrFilter(kinds = listOf(1)),
            targetUri = "https://njump.me"
        )

        // When: Validating
        // Then: Should be invalid
        assertFalse("Configuration without relays should be invalid", invalidConfig.isValid())

        // Given: Valid configuration
        val validConfig = Configuration(
            name = "Valid Config",
            relayUrls = listOf("wss://relay.damus.io"),
            filter = NostrFilter(kinds = listOf(1)),
            targetUri = "https://njump.me"
        )

        // When: Validating
        // Then: Should be valid
        assertTrue("Valid configuration should be valid", validConfig.isValid())
    }

    @Test
    fun `should filter enabled configurations`() {
        // Given: Mix of enabled and disabled configurations
        val enabledConfig = Configuration(
            name = "Enabled Config",
            relayUrls = listOf("wss://relay.damus.io"),
            filter = NostrFilter(kinds = listOf(1)),
            targetUri = "https://njump.me",
            isEnabled = true
        )

        val disabledConfig = Configuration(
            name = "Disabled Config",
            relayUrls = listOf("wss://relay.damus.io"),
            filter = NostrFilter(kinds = listOf(1)),
            targetUri = "https://njump.me",
            isEnabled = false
        )

        configurationManager.addConfiguration(enabledConfig)
        configurationManager.addConfiguration(disabledConfig)

        // When: Getting enabled configurations
        val enabledConfigurations = configurationManager.getEnabledConfigurations()

        // Then: Should only return enabled ones
        assertEquals("Should have 1 enabled configuration", 1, enabledConfigurations.size)
        assertEquals("Should be the enabled config", "Enabled Config", enabledConfigurations[0].name)
    }

    @Test
    fun `should handle keyword filtering`() {
        // Given: Keyword filter
        val keywordFilter = KeywordFilter(keywords = listOf("bitcoin", "nostr"))

        // Test events
        val matchingEvent = NostrEvent(
            id = "match123",
            pubkey = "pubkey123",
            createdAt = System.currentTimeMillis() / 1000,
            kind = 1,
            tags = emptyList(),
            content = "This is about bitcoin development",
            signature = "sig123"
        )

        val nonMatchingEvent = NostrEvent(
            id = "nomatch123",
            pubkey = "pubkey123",
            createdAt = System.currentTimeMillis() / 1000,
            kind = 1,
            tags = emptyList(),
            content = "Just a regular post about cats",
            signature = "sig123"
        )

        // When: Testing matches
        val matches1 = keywordFilter.matches(matchingEvent.content)
        val matches2 = keywordFilter.matches(nonMatchingEvent.content)

        // Then: Should filter correctly
        assertTrue("Should match bitcoin content", matches1)
        assertFalse("Should not match non-relevant content", matches2)
    }

    @Test
    fun `should validate nostr filters`() {
        // Given: Different filter types
        val kindFilter = NostrFilter(kinds = listOf(1))
        val authorFilter = NostrFilter(authors = listOf("pubkey123"))
        val timeFilter = NostrFilter(since = System.currentTimeMillis() / 1000)
        val emptyFilter = NostrFilter()

        // When: Validating filters
        // Then: Non-empty filters should be valid
        assertTrue("Kind filter should be valid", kindFilter.isValid())
        assertTrue("Author filter should be valid", authorFilter.isValid())
        assertTrue("Time filter should be valid", timeFilter.isValid())
        assertFalse("Empty filter should be invalid", emptyFilter.isValid())
    }

    @Test
    fun `should create subscription manager`() {
        // Given: Context
        // When: Creating subscription manager
        val subscriptionManager = SubscriptionManager(context)

        // Then: Should create without exceptions
        assertNotNull("Should create subscription manager", subscriptionManager)
    }

    @Test
    fun `should handle configuration updates`() {
        // Given: Original configuration
        val originalConfig = Configuration(
            name = "Original Name",
            relayUrls = listOf("wss://relay.damus.io"),
            filter = NostrFilter(kinds = listOf(1)),
            targetUri = "https://njump.me"
        )

        configurationManager.addConfiguration(originalConfig)

        // When: Updating configuration
        val updatedConfig = originalConfig.copy(name = "Updated Name")
        configurationManager.updateConfiguration(updatedConfig)

        // Then: Should reflect updates
        val configurations = configurationManager.getConfigurations()
        assertTrue("Should find updated configuration", 
            configurations.any { it.name == "Updated Name" })
    }

    @Test
    fun `should provide configuration summaries`() {
        // Given: Configuration with multiple relays
        val config = Configuration(
            name = "Multi Relay Config",
            relayUrls = listOf("wss://relay1.com", "wss://relay2.com"),
            filter = NostrFilter(kinds = listOf(1, 6)),
            targetUri = "https://njump.me"
        )

        // When: Getting summary
        val summary = config.getSummary()

        // Then: Should provide meaningful summary
        assertNotNull("Should provide summary", summary)
        assertTrue("Should contain config name", summary.contains("Multi Relay Config"))
        assertTrue("Should mention relay count", summary.contains("2 relay"))
    }

    @Test
    fun `should handle empty keyword filter`() {
        // Given: Empty keyword filter
        val emptyFilter = KeywordFilter.empty()

        // When: Testing if empty
        val isEmpty = emptyFilter.isEmpty()

        // Then: Should be empty
        assertTrue("Empty filter should be empty", isEmpty)
    }

    @Test
    fun `should validate URI schemes correctly`() {
        // Given: Different URI types
        val validConfigs = listOf(
            Configuration(name = "HTTPS", relayUrls = listOf("wss://relay.com"), 
                filter = NostrFilter(kinds = listOf(1)), targetUri = "https://njump.me"),
            Configuration(name = "HTTP", relayUrls = listOf("wss://relay.com"), 
                filter = NostrFilter(kinds = listOf(1)), targetUri = "http://localhost:3000"),
            Configuration(name = "App", relayUrls = listOf("wss://relay.com"), 
                filter = NostrFilter(kinds = listOf(1)), targetUri = "app://custom-handler")
        )

        val invalidConfigs = listOf(
            Configuration(name = "FTP", relayUrls = listOf("wss://relay.com"), 
                filter = NostrFilter(kinds = listOf(1)), targetUri = "ftp://files.com"),
            Configuration(name = "Invalid", relayUrls = listOf("wss://relay.com"), 
                filter = NostrFilter(kinds = listOf(1)), targetUri = "not-a-uri")
        )

        // When/Then: Valid URIs should be valid
        validConfigs.forEach { config ->
            assertTrue("${config.name} should be valid", config.isValid())
        }

        // When/Then: Invalid URIs should be invalid
        invalidConfigs.forEach { config ->
            assertFalse("${config.name} should be invalid", config.isValid())
        }
    }
}
