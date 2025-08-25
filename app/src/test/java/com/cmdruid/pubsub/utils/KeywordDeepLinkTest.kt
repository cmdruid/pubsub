package com.cmdruid.pubsub.utils

import android.net.Uri
import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.data.KeywordFilter
import com.cmdruid.pubsub.nostr.NostrFilter
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Test class for keyword deep link functionality
 * Uses Robolectric for Android URI parsing
 */
@RunWith(RobolectricTestRunner::class)
class KeywordDeepLinkTest {

    @Test
    fun testDeepLinkParsingWithKeywords() {
        // Create a sample deep link with keywords
        val sampleDeepLink = DeepLinkHandler.generateSampleDeepLink()
        val uri = Uri.parse(sampleDeepLink)
        
        // Should contain keyword parameters
        assertTrue(sampleDeepLink.contains("keyword=bitcoin"))
        assertTrue(sampleDeepLink.contains("keyword=nostr"))
        
        val result = DeepLinkHandler.parseRegisterDeepLink(uri)
        
        assertTrue(result.success)
        assertNotNull(result.configuration)
        
        val configuration = result.configuration!!
        assertNotNull(configuration.keywordFilter)
        
        val keywordFilter = configuration.keywordFilter!!
        assertFalse(keywordFilter.isEmpty())
        assertTrue(keywordFilter.keywords.contains("bitcoin"))
        assertTrue(keywordFilter.keywords.contains("nostr"))
    }

    @Test
    fun testDeepLinkParsingWithCommaSeparatedKeywords() {
        val filter = NostrFilter(kinds = listOf(1))
        val deepLink = UriBuilder.buildRegisterDeepLink(
            label = "Test App",
            relayUrls = listOf("wss://relay.example.com"),
            filter = filter,
            targetUri = "https://example.com",
            keywords = listOf("bitcoin", "nostr", "lightning")
        )
        
        assertNotNull(deepLink)
        val uri = Uri.parse(deepLink!!)
        
        val result = DeepLinkHandler.parseRegisterDeepLink(uri)
        
        assertTrue(result.success)
        val configuration = result.configuration!!
        assertNotNull(configuration.keywordFilter)
        
        val keywordFilter = configuration.keywordFilter!!
        assertEquals(3, keywordFilter.keywords.size)
        assertTrue(keywordFilter.keywords.contains("bitcoin"))
        assertTrue(keywordFilter.keywords.contains("nostr"))
        assertTrue(keywordFilter.keywords.contains("lightning"))
    }

    @Test
    fun testDeepLinkParsingWithoutKeywords() {
        // Create a deep link without keywords
        val filter = NostrFilter(kinds = listOf(1))
        val baseUri = "pubsub://register?label=Test&relay=wss://relay.example.com&uri=https://example.com&filter=eyJraW5kcyI6WzFdfQ"
        val uri = Uri.parse(baseUri)
        
        val result = DeepLinkHandler.parseRegisterDeepLink(uri)
        
        assertTrue(result.success)
        val configuration = result.configuration!!
        assertNull(configuration.keywordFilter) // Should be null when no keywords
    }

    @Test
    fun testUriBuilderWithConfiguration() {
        val keywordFilter = KeywordFilter.from(listOf("bitcoin", "nostr"))
        val configuration = Configuration(
            name = "Test Config",
            relayUrls = listOf("wss://relay1.com", "wss://relay2.com"),
            filter = NostrFilter(kinds = listOf(1, 7)),
            targetUri = "https://example.com",
            keywordFilter = keywordFilter
        )
        
        val deepLink = UriBuilder.buildRegisterDeepLink(configuration)
        assertNotNull(deepLink)
        
        val uri = Uri.parse(deepLink!!)
        
        // Verify the generated deep link contains keywords
        val keywordParams = uri.getQueryParameters("keyword")
        assertEquals(2, keywordParams.size)
        assertTrue(keywordParams.contains("bitcoin"))
        assertTrue(keywordParams.contains("nostr"))
        
        // Verify round-trip parsing
        val result = DeepLinkHandler.parseRegisterDeepLink(uri)
        assertTrue(result.success)
        
        val parsedConfig = result.configuration!!
        assertEquals(configuration.name, parsedConfig.name)
        assertEquals(configuration.relayUrls, parsedConfig.relayUrls)
        assertEquals(configuration.targetUri, parsedConfig.targetUri)
        assertNotNull(parsedConfig.keywordFilter)
        assertEquals(keywordFilter.keywords.sorted(), parsedConfig.keywordFilter!!.keywords.sorted())
    }

    @Test
    fun testUriBuilderWithEmptyKeywords() {
        val configuration = Configuration(
            name = "Test Config",
            relayUrls = listOf("wss://relay.com"),
            filter = NostrFilter(kinds = listOf(1)),
            targetUri = "https://example.com",
            keywordFilter = null
        )
        
        val deepLink = UriBuilder.buildRegisterDeepLink(configuration)
        assertNotNull(deepLink)
        
        val uri = Uri.parse(deepLink!!)
        val keywordParams = uri.getQueryParameters("keyword")
        assertEquals(0, keywordParams.size)
    }

    @Test
    fun testKeywordSanitizationInDeepLinks() {
        // Test with keywords that need sanitization
        val dirtyKeywords = listOf("  bitcoin  ", "", "nostr", "a", "x".repeat(150))
        
        val filter = NostrFilter(kinds = listOf(1))
        val deepLink = UriBuilder.buildRegisterDeepLink(
            label = "Test",
            relayUrls = listOf("wss://relay.com"),
            filter = filter,
            targetUri = "https://example.com",
            keywords = dirtyKeywords
        )
        
        assertNotNull(deepLink)
        val uri = Uri.parse(deepLink!!)
        
        val result = DeepLinkHandler.parseRegisterDeepLink(uri)
        assertTrue(result.success)
        
        val configuration = result.configuration!!
        assertNotNull(configuration.keywordFilter)
        
        // Should have sanitized keywords
        val sanitizedKeywords = configuration.keywordFilter!!.keywords
        assertTrue(sanitizedKeywords.contains("bitcoin"))
        assertTrue(sanitizedKeywords.contains("nostr"))
        assertFalse(sanitizedKeywords.contains("a")) // Too short
        assertFalse(sanitizedKeywords.contains("")) // Empty
        
        // Long keyword should be truncated
        val hasLongKeyword = sanitizedKeywords.any { it.length > 100 }
        assertFalse(hasLongKeyword)
    }

    @Test
    fun testSpecialCharactersInKeywords() {
        val keywords = listOf("bitcoin-cash", "peer2peer", "web3.0")
        
        val filter = NostrFilter(kinds = listOf(1))
        val deepLink = UriBuilder.buildRegisterDeepLink(
            label = "Test",
            relayUrls = listOf("wss://relay.com"),
            filter = filter,
            targetUri = "https://example.com",
            keywords = keywords
        )
        
        assertNotNull(deepLink)
        val uri = Uri.parse(deepLink!!)
        
        val result = DeepLinkHandler.parseRegisterDeepLink(uri)
        assertTrue(result.success)
        
        val configuration = result.configuration!!
        assertNotNull(configuration.keywordFilter)
        
        // Should handle special characters properly
        val parsedKeywords = configuration.keywordFilter!!.keywords
        assertTrue(parsedKeywords.contains("bitcoin-cash"))
        assertTrue(parsedKeywords.contains("peer2peer"))
        assertTrue(parsedKeywords.contains("web3.0"))
    }

    @Test
    fun testLargeNumberOfKeywords() {
        // Test with many keywords (should be limited)
        val manyKeywords = (1..30).map { "keyword$it" }
        
        val filter = NostrFilter(kinds = listOf(1))
        val deepLink = UriBuilder.buildRegisterDeepLink(
            label = "Test",
            relayUrls = listOf("wss://relay.com"),
            filter = filter,
            targetUri = "https://example.com",
            keywords = manyKeywords
        )
        
        assertNotNull(deepLink)
        val uri = Uri.parse(deepLink!!)
        
        val result = DeepLinkHandler.parseRegisterDeepLink(uri)
        assertTrue(result.success)
        
        val configuration = result.configuration!!
        assertNotNull(configuration.keywordFilter)
        
        // Should be limited to max keywords (20)
        val keywords = configuration.keywordFilter!!.keywords
        assertTrue(keywords.size <= 20)
    }
}
