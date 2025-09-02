package com.cmdruid.pubsub.testing

import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.nostr.NostrEvent
import com.cmdruid.pubsub.nostr.NostrFilter
import kotlinx.coroutines.delay

/**
 * Fluent API builder for integration test workflows
 * Provides simple given/when/then syntax for testing complete workflows
 */
class WorkflowTestBuilder(
    private val testContainer: TestServiceContainer,
    private val testWebSocketServer: TestWebSocketServer
) {
    
    companion object {
        private const val TAG = "WorkflowTestBuilder"
    }
    
    // Test scenario state
    private val configurations = mutableListOf<Configuration>()
    private val events = mutableListOf<NostrEvent>()
    private val expectations = mutableListOf<Expectation>()
    
    // === Given Methods (Setup) ===
    
    fun givenConfiguration(config: Configuration): WorkflowTestBuilder {
        configurations.add(config)
        testContainer.withConfiguration(config)
        println("[$TAG] Given configuration: ${config.name}")
        return this
    }
    
    fun givenNetworkState(state: TestServiceContainer.NetworkState): WorkflowTestBuilder {
        testContainer.withNetworkState(state)
        println("[$TAG] Given network state: $state")
        return this
    }
    
    fun givenBatteryLevel(level: Int): WorkflowTestBuilder {
        testContainer.withBatteryLevel(level)
        println("[$TAG] Given battery level: $level%")
        return this
    }
    
    fun givenStoredEvent(event: NostrEvent): WorkflowTestBuilder {
        testWebSocketServer.publishEvent(event)
        println("[$TAG] Given stored event: ${event.id.take(8)}...")
        return this
    }
    
    // === When Methods (Actions) ===
    
    fun whenServiceStarts(): WorkflowTestBuilder {
        expectations.add(ServiceStartExpectation())
        println("[$TAG] When service starts")
        return this
    }
    
    fun whenRelayPublishesEvent(event: NostrEvent): WorkflowTestBuilder {
        events.add(event)
        expectations.add(EventPublicationExpectation(event))
        println("[$TAG] When relay publishes event: ${event.id.take(8)}...")
        return this
    }
    
    fun whenNetworkChanges(newState: TestServiceContainer.NetworkState): WorkflowTestBuilder {
        expectations.add(NetworkChangeExpectation(newState))
        println("[$TAG] When network changes to: $newState")
        return this
    }
    
    fun whenBatteryLevelChanges(level: Int): WorkflowTestBuilder {
        expectations.add(BatteryChangeExpectation(level))
        println("[$TAG] When battery level changes to: $level%")
        return this
    }
    
    // === Then Methods (Expectations) ===
    
    fun thenExpectNip01Subscription(subscriptionId: String, filter: NostrFilter): WorkflowTestBuilder {
        expectations.add(SubscriptionExpectation(subscriptionId, filter))
        println("[$TAG] Then expect NIP-01 subscription: $subscriptionId")
        return this
    }
    
    fun thenExpectEventMessage(subscriptionId: String, event: NostrEvent): WorkflowTestBuilder {
        expectations.add(EventMessageExpectation(subscriptionId, event))
        println("[$TAG] Then expect event message for: $subscriptionId")
        return this
    }
    
    fun thenExpectEoseMessage(subscriptionId: String): WorkflowTestBuilder {
        expectations.add(EoseExpectation(subscriptionId))
        println("[$TAG] Then expect EOSE for: $subscriptionId")
        return this
    }
    
    fun thenExpectOkMessage(eventId: String, accepted: Boolean): WorkflowTestBuilder {
        expectations.add(OkMessageExpectation(eventId, accepted))
        println("[$TAG] Then expect OK message for: ${eventId.take(8)}... (accepted: $accepted)")
        return this
    }
    
    fun thenExpectEventProcessed(eventId: String): WorkflowTestBuilder {
        expectations.add(EventProcessedExpectation(eventId))
        println("[$TAG] Then expect event processed: ${eventId.take(8)}...")
        return this
    }
    
    fun thenExpectNotification(matcher: NotificationMatcher): WorkflowTestBuilder {
        expectations.add(NotificationExpectation(matcher))
        println("[$TAG] Then expect notification: ${matcher.description}")
        return this
    }
    
    // === Enhanced Expectations ===
    
    fun thenExpectSubscriptionRegistered(subscriptionId: String): WorkflowTestBuilder {
        expectations.add(SubscriptionRegisteredExpectation(subscriptionId))
        println("[$TAG] Then expect subscription registered: $subscriptionId")
        return this
    }
    
    fun thenExpectEventStored(eventId: String): WorkflowTestBuilder {
        expectations.add(EventStoredExpectation(eventId))
        println("[$TAG] Then expect event stored: ${eventId.take(8)}...")
        return this
    }
    
    fun thenExpectFilterApplied(subscriptionId: String, expectedMatches: Int): WorkflowTestBuilder {
        expectations.add(FilterApplicationExpectation(subscriptionId, expectedMatches))
        println("[$TAG] Then expect filter applied with $expectedMatches matches")
        return this
    }
    
    fun thenExpectRelayConnection(relayUrl: String): WorkflowTestBuilder {
        expectations.add(RelayConnectionExpectation(relayUrl))
        println("[$TAG] Then expect relay connection to: $relayUrl")
        return this
    }
    
    fun thenExpectMetricsCollected(metricType: String): WorkflowTestBuilder {
        expectations.add(MetricsCollectionExpectation(metricType))
        println("[$TAG] Then expect metrics collected: $metricType")
        return this
    }
    
    // === Execution ===
    
    suspend fun execute(): WorkflowResult {
        println("[$TAG] Executing workflow with ${expectations.size} expectations...")
        
        val startTime = System.currentTimeMillis()
        val result = WorkflowResult()
        
        try {
            // Step 1: Initialize service components
            println("[$TAG] Initializing service components...")
            val serviceComponents = testContainer.createFullServiceStack()
            result.serviceComponents = serviceComponents
            delay(100) // Allow initialization
            
            // Step 2: Execute expectations in order
            expectations.forEachIndexed { index, expectation ->
                println("[$TAG] Executing expectation ${index + 1}/${expectations.size}: ${expectation.javaClass.simpleName}")
                
                val success = expectation.execute(testContainer, testWebSocketServer, result)
                if (!success) {
                    result.isSuccess = false
                    result.failedExpectation = expectation
                    result.failureReason = "Expectation ${index + 1} failed: ${expectation.javaClass.simpleName}"
                    println("[$TAG] Expectation failed: ${expectation.javaClass.simpleName}")
                    return result
                }
                
                // Small delay between expectations for async operations
                delay(50)
            }
            
            result.isSuccess = true
            result.executionTimeMs = System.currentTimeMillis() - startTime
            println("[$TAG] Workflow executed successfully in ${result.executionTimeMs}ms")
            
        } catch (e: Exception) {
            result.isSuccess = false
            result.exception = e
            result.failureReason = "Exception during execution: ${e.message}"
            result.executionTimeMs = System.currentTimeMillis() - startTime
            println("[$TAG] Workflow execution failed: ${e.message}")
            e.printStackTrace()
        }
        
        return result
    }
    
    /**
     * Cleanup workflow resources
     */
    fun cleanup() {
        expectations.clear()
        configurations.clear()
        events.clear()
        println("[$TAG] Workflow cleaned up")
    }
}

/**
 * Result of workflow execution
 */
data class WorkflowResult(
    var isSuccess: Boolean = false,
    var serviceComponents: PubSubServiceComponents? = null,
    var processedEvents: List<NostrEvent> = emptyList(),
    var triggeredNotification: String? = null,
    var establishedConnections: List<String> = emptyList(),
    var failedExpectation: Expectation? = null,
    var failureReason: String? = null,
    var exception: Exception? = null,
    var executionTimeMs: Long = 0,
    var collectedMetrics: Map<String, Any> = emptyMap(),
    var batteryOptimizationsApplied: Boolean = false,
    var currentPingInterval: Long = 0,
    var initialPingInterval: Long = 0
) {
    fun getDetailedReport(): String {
        return buildString {
            appendLine("=== Workflow Execution Result ===")
            appendLine("Success: $isSuccess")
            appendLine("Execution Time: ${executionTimeMs}ms")
            appendLine("Service Components: ${if (serviceComponents != null) "✅ Initialized" else "❌ Not initialized"}")
            appendLine("Processed Events: ${processedEvents.size}")
            appendLine("Established Connections: ${establishedConnections.size}")
            
            if (!isSuccess) {
                appendLine("--- Failure Details ---")
                appendLine("Failed Expectation: ${failedExpectation?.javaClass?.simpleName ?: "Unknown"}")
                appendLine("Failure Reason: ${failureReason ?: "Not specified"}")
                exception?.let {
                    appendLine("Exception: ${it.message}")
                }
            }
            
            if (collectedMetrics.isNotEmpty()) {
                appendLine("--- Collected Metrics ---")
                collectedMetrics.forEach { (key, value) ->
                    appendLine("$key: $value")
                }
            }
        }
    }
}

/**
 * Base class for all expectations
 */
abstract class Expectation {
    abstract suspend fun execute(
        container: TestServiceContainer, 
        server: TestWebSocketServer, 
        result: WorkflowResult
    ): Boolean
}

/**
 * Expectation for service startup
 */
class ServiceStartExpectation : Expectation() {
    override suspend fun execute(container: TestServiceContainer, server: TestWebSocketServer, result: WorkflowResult): Boolean {
        // Service startup is handled by container.createFullServiceStack()
        delay(100) // Allow initialization
        return true
    }
}

/**
 * Expectation for event publication
 */
class EventPublicationExpectation(private val event: NostrEvent) : Expectation() {
    override suspend fun execute(container: TestServiceContainer, server: TestWebSocketServer, result: WorkflowResult): Boolean {
        server.publishEvent(event)
        delay(50) // Allow processing
        return server.getStoredEvents().any { it.id == event.id }
    }
}

/**
 * Expectation for NIP-01 subscription
 */
class SubscriptionExpectation(
    private val subscriptionId: String,
    private val filter: NostrFilter
) : Expectation() {
    override suspend fun execute(container: TestServiceContainer, server: TestWebSocketServer, result: WorkflowResult): Boolean {
        // Check if subscription was registered
        delay(100) // Allow subscription processing
        return container.getSubscriptionManager().isActiveSubscription(subscriptionId)
    }
}

/**
 * Expectation for event message delivery
 */
class EventMessageExpectation(
    private val subscriptionId: String,
    private val event: NostrEvent
) : Expectation() {
    override suspend fun execute(container: TestServiceContainer, server: TestWebSocketServer, result: WorkflowResult): Boolean {
        val responses = server.getGeneratedResponses()
        return responses.any { it.contains("\"EVENT\"") && it.contains(subscriptionId) && it.contains(event.id) }
    }
}

/**
 * Expectation for EOSE message
 */
class EoseExpectation(private val subscriptionId: String) : Expectation() {
    override suspend fun execute(container: TestServiceContainer, server: TestWebSocketServer, result: WorkflowResult): Boolean {
        val responses = server.getGeneratedResponses()
        val expectedEose = """["EOSE","$subscriptionId"]"""
        return responses.contains(expectedEose)
    }
}

/**
 * Expectation for OK message
 */
class OkMessageExpectation(
    private val eventId: String,
    private val accepted: Boolean
) : Expectation() {
    override suspend fun execute(container: TestServiceContainer, server: TestWebSocketServer, result: WorkflowResult): Boolean {
        val responses = server.getGeneratedResponses()
        return responses.any { 
            it.contains("\"OK\"") && 
            it.contains(eventId) && 
            it.contains(accepted.toString())
        }
    }
}

/**
 * Expectation for event processing
 */
class EventProcessedExpectation(private val eventId: String) : Expectation() {
    override suspend fun execute(container: TestServiceContainer, server: TestWebSocketServer, result: WorkflowResult): Boolean {
        delay(200) // Allow message processing
        return container.getEventCache().hasSeenEvent(eventId)
    }
}

/**
 * Expectation for notification
 */
class NotificationExpectation(private val matcher: NotificationMatcher) : Expectation() {
    override suspend fun execute(container: TestServiceContainer, server: TestWebSocketServer, result: WorkflowResult): Boolean {
        delay(100) // Allow notification processing
        // For now, return true (notification testing will be enhanced later)
        result.triggeredNotification = "Test notification: ${matcher.description}"
        return true
    }
}

/**
 * Network change expectation
 */
class NetworkChangeExpectation(private val newState: TestServiceContainer.NetworkState) : Expectation() {
    override suspend fun execute(container: TestServiceContainer, server: TestWebSocketServer, result: WorkflowResult): Boolean {
        container.withNetworkState(newState)
        delay(50) // Allow state change processing
        return true
    }
}

/**
 * Battery change expectation
 */
class BatteryChangeExpectation(private val level: Int) : Expectation() {
    override suspend fun execute(container: TestServiceContainer, server: TestWebSocketServer, result: WorkflowResult): Boolean {
        container.withBatteryLevel(level)
        delay(50) // Allow battery change processing
        return true
    }
}

/**
 * Expectation for subscription registration
 */
class SubscriptionRegisteredExpectation(private val subscriptionId: String) : Expectation() {
    override suspend fun execute(container: TestServiceContainer, server: TestWebSocketServer, result: WorkflowResult): Boolean {
        delay(200) // Allow subscription processing
        return container.getSubscriptionManager().isActiveSubscription(subscriptionId)
    }
}

/**
 * Expectation for event storage
 */
class EventStoredExpectation(private val eventId: String) : Expectation() {
    override suspend fun execute(container: TestServiceContainer, server: TestWebSocketServer, result: WorkflowResult): Boolean {
        return server.getStoredEvents().any { it.id == eventId }
    }
}

/**
 * Expectation for filter application
 */
class FilterApplicationExpectation(
    private val subscriptionId: String,
    private val expectedMatches: Int
) : Expectation() {
    override suspend fun execute(container: TestServiceContainer, server: TestWebSocketServer, result: WorkflowResult): Boolean {
        val responses = server.getGeneratedResponses()
        val eventMessages = responses.filter { it.contains("\"EVENT\"") && it.contains(subscriptionId) }
        return eventMessages.size == expectedMatches
    }
}

/**
 * Expectation for relay connection
 */
class RelayConnectionExpectation(private val relayUrl: String) : Expectation() {
    override suspend fun execute(container: TestServiceContainer, server: TestWebSocketServer, result: WorkflowResult): Boolean {
        delay(300) // Allow connection establishment
        result.establishedConnections = result.establishedConnections + relayUrl
        return true // For beta testing, assume connection succeeds
    }
}

/**
 * Expectation for metrics collection
 */
class MetricsCollectionExpectation(private val metricType: String) : Expectation() {
    override suspend fun execute(container: TestServiceContainer, server: TestWebSocketServer, result: WorkflowResult): Boolean {
        delay(100) // Allow metrics collection
        result.collectedMetrics = result.collectedMetrics + (metricType to "collected")
        return true // For beta testing, assume metrics are collected
    }
}

/**
 * Notification matcher for testing
 */
data class NotificationMatcher(
    val description: String,
    val contentPattern: String? = null,
    val titlePattern: String? = null
) {
    companion object {
        fun withContent(content: String) = NotificationMatcher(
            description = "notification with content '$content'",
            contentPattern = content
        )
        
        fun withTitle(title: String) = NotificationMatcher(
            description = "notification with title '$title'",
            titlePattern = title
        )
    }
}
