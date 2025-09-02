package com.cmdruid.pubsub.testing

import android.content.Context
import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.data.KeywordFilter
import com.cmdruid.pubsub.nostr.NostrEvent
import com.cmdruid.pubsub.nostr.NostrFilter
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Base class for all integration tests
 * Provides common setup, cleanup, and helper methods
 */
@RunWith(RobolectricTestRunner::class)
abstract class IntegrationTestBase {
    
    protected lateinit var context: Context
    protected lateinit var testContainer: TestServiceContainer
    protected lateinit var testWebSocketServer: TestWebSocketServer
    protected lateinit var workflowBuilder: WorkflowTestBuilder
    
    @Before
    fun setupIntegrationTest() {
        // Get Robolectric context
        context = RuntimeEnvironment.getApplication()
        
        // Initialize test components
        testContainer = TestServiceContainer(context)
        testWebSocketServer = TestWebSocketServer()
        workflowBuilder = WorkflowTestBuilder(testContainer, testWebSocketServer)
        
        println("[IntegrationTestBase] Setup completed")
    }
    
    @After
    fun cleanupIntegrationTest() {
        // Clean up in reverse order
        workflowBuilder.cleanup()
        testWebSocketServer.stop()
        testContainer.cleanup()
        
        println("[IntegrationTestBase] Cleanup completed")
    }
    
    // === Helper Methods for Test Data Creation ===
    
    /**
     * Create a test configuration with sensible defaults
     */
    protected fun createTestConfiguration(
        name: String,
        enabled: Boolean = true,
        relayUrls: List<String> = listOf("wss://relay1.test.com", "wss://relay2.test.com"),
        targetUri: String = "https://test-app.com/events",
        keywords: List<String>? = null,
        kinds: List<Int> = listOf(1),
        authors: List<String>? = null
    ): Configuration {
        return Configuration(
            id = "test-config-${System.currentTimeMillis()}",
            name = name,
            isEnabled = enabled,
            relayUrls = relayUrls,
            filter = NostrFilter(
                kinds = kinds,
                authors = authors,
                since = null, // Will be set by SubscriptionManager
                until = null,
                limit = null
            ),
            targetUri = targetUri,
            subscriptionId = "test-sub-${name.lowercase().replace(" ", "-")}-${System.currentTimeMillis()}",
            keywordFilter = keywords?.let { KeywordFilter.from(it) },
            excludeMentionsToSelf = false,
            excludeRepliesToEvents = false
        )
    }
    
    /**
     * Create a test event with sensible defaults
     */
    protected fun createTestEvent(
        content: String = "This is a test event",
        kind: Int = 1,
        id: String = generateValidEventId(),
        pubkey: String = generateValidPubkey(),
        tags: List<List<String>> = emptyList(),
        createdAt: Long = System.currentTimeMillis() / 1000
    ): NostrEvent {
        return NostrEvent(
            id = id,
            pubkey = pubkey,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            signature = generateValidSignature()
        )
    }
    
    /**
     * Create a Bitcoin-related test event
     */
    protected fun createBitcoinEvent(
        content: String = "Bitcoin reaches new all-time high! #bitcoin",
        kind: Int = 1
    ): NostrEvent {
        return createTestEvent(
            content = content,
            kind = kind,
            tags = listOf(listOf("t", "bitcoin"))
        )
    }
    
    /**
     * Create a test event with hashtags
     */
    protected fun createHashtagEvent(
        hashtags: List<String>,
        content: String = "Test event with hashtags"
    ): NostrEvent {
        val hashtagTags = hashtags.map { listOf("t", it) }
        val hashtagContent = "$content ${hashtags.joinToString(" ") { "#$it" }}"
        
        return createTestEvent(
            content = hashtagContent,
            tags = hashtagTags
        )
    }
    
    /**
     * Wait for async operations to complete
     */
    protected suspend fun waitForAsyncOperations(timeout: Duration = 5.seconds) {
        delay(timeout.inWholeMilliseconds)
    }
    
    /**
     * Wait for a short time (for quick async operations)
     */
    protected suspend fun waitShort() {
        delay(100) // 100ms
    }
    
    /**
     * Wait for a medium time (for component initialization)
     */
    protected suspend fun waitMedium() {
        delay(500) // 500ms
    }
    
    /**
     * Wait for a long time (for complex workflows)
     */
    protected suspend fun waitLong() {
        delay(1000) // 1 second
    }
    
    // === Test Data Generators ===
    
    private fun generateValidEventId(): String {
        return "abcd1234567890abcdef1234567890abcdef1234567890abcdef1234567890ab"
    }
    
    private fun generateValidPubkey(): String {
        return "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
    }
    
    private fun generateValidSignature(): String {
        return "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
    }
    
    /**
     * Generate unique event ID for testing
     */
    protected fun generateUniqueEventId(): String {
        val timestamp = System.currentTimeMillis().toString(16).padStart(16, '0')
        val random = (1..48).map { "0123456789abcdef".random() }.joinToString("")
        return timestamp + random
    }
    
    /**
     * Generate unique pubkey for testing
     */
    protected fun generateUniquePubkey(): String {
        return (1..64).map { "0123456789abcdef".random() }.joinToString("")
    }
    
    // === Assertion Helpers ===
    
    /**
     * Assert that a list contains a message matching the pattern
     */
    protected fun assertContainsMessage(messages: List<String>, pattern: String, description: String) {
        val found = messages.any { it.contains(pattern) }
        if (!found) {
            println("Available messages:")
            messages.forEach { println("  $it") }
        }
        assert(found) { "$description - Expected to find message containing '$pattern'" }
    }
    
    /**
     * Assert that a list does not contain a message matching the pattern
     */
    protected fun assertNotContainsMessage(messages: List<String>, pattern: String, description: String) {
        val found = messages.any { it.contains(pattern) }
        assert(!found) { "$description - Expected NOT to find message containing '$pattern'" }
    }
    
    /**
     * Assert that an event matches a filter
     */
    protected fun assertEventMatchesFilter(event: NostrEvent, filter: NostrFilter, description: String) {
        val matches = checkEventMatchesFilter(event, filter)
        assert(matches) { "$description - Event ${event.id.take(8)}... should match filter" }
    }
    
    private fun checkEventMatchesFilter(event: NostrEvent, filter: NostrFilter): Boolean {
        // Kind filtering
        if (filter.kinds != null && event.kind !in filter.kinds) return false
        
        // Author filtering
        if (filter.authors != null && event.pubkey !in filter.authors) return false
        
        // Timestamp filtering
        if (filter.since != null && event.createdAt < filter.since) return false
        if (filter.until != null && event.createdAt > filter.until) return false
        
        return true
    }
}
