package com.cmdruid.pubsub.testing

import android.content.Context
import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.data.ConfigurationManager
import com.cmdruid.pubsub.data.SettingsManager
import com.cmdruid.pubsub.logging.UnifiedLogger
import com.cmdruid.pubsub.logging.UnifiedLoggerImpl
import com.cmdruid.pubsub.service.*
import io.mockk.*

/**
 * Test container for service components
 * Manages initialization and configuration of service components for testing
 */
class TestServiceContainer(private val context: Context) {
    
    companion object {
        private const val TAG = "TestServiceContainer"
    }
    
    // Core components (using real implementations where possible)
    private lateinit var configurationManager: ConfigurationManager
    private lateinit var settingsManager: SettingsManager
    private lateinit var unifiedLogger: UnifiedLogger
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var eventCache: EventCache
    
    // Service components (will be created with test-friendly configurations)
    private var batteryPowerManager: BatteryPowerManager? = null
    private var networkManager: NetworkManager? = null
    private var eventNotificationManager: EventNotificationManager? = null
    private var relayConnectionManager: RelayConnectionManager? = null
    private var messageProcessor: MessageProcessor? = null
    private var healthMonitor: HealthMonitor? = null
    private var metricsCollector: MetricsCollector? = null
    
    // Test configuration
    private var testConfigurations = mutableListOf<Configuration>()
    private var testNetworkState = NetworkState.CONNECTED
    private var testBatteryLevel = 80
    
    enum class NetworkState {
        CONNECTED, DISCONNECTED, POOR_QUALITY
    }
    
    init {
        initializeCoreComponents()
    }
    
    /**
     * Initialize core components that are always needed
     */
    private fun initializeCoreComponents() {
        // Initialize real core components
        configurationManager = ConfigurationManager(context)
        settingsManager = SettingsManager(context)
        unifiedLogger = UnifiedLoggerImpl(context, configurationManager)
        subscriptionManager = SubscriptionManager(context)
        eventCache = EventCache(context)
        
        println("[$TAG] Core components initialized")
    }
    
    /**
     * Add a test configuration
     */
    fun withConfiguration(config: Configuration): TestServiceContainer {
        testConfigurations.add(config)
        
        // Add to real configuration manager for integration testing
        configurationManager.addConfiguration(config)
        
        println("[$TAG] Added test configuration: ${config.name}")
        return this
    }
    
    /**
     * Set test network state
     */
    fun withNetworkState(state: NetworkState): TestServiceContainer {
        testNetworkState = state
        println("[$TAG] Set network state: $state")
        return this
    }
    
    /**
     * Set test battery level
     */
    fun withBatteryLevel(level: Int): TestServiceContainer {
        testBatteryLevel = level
        println("[$TAG] Set battery level: $level")
        return this
    }
    
    /**
     * Create full service stack with all components
     */
    fun createFullServiceStack(): PubSubServiceComponents {
        // Initialize metrics collector
        metricsCollector = MetricsCollector(context, settingsManager)
        
        // Create mock-enhanced components for testing
        batteryPowerManager = createTestBatteryPowerManager()
        networkManager = createTestNetworkManager()
        eventNotificationManager = createTestEventNotificationManager()
        
        // Create real relay connection manager with test components
        relayConnectionManager = RelayConnectionManager(
            configurationManager = configurationManager,
            subscriptionManager = subscriptionManager,
            batteryPowerManager = batteryPowerManager!!,
            networkManager = networkManager!!,
            onMessageReceived = { messageText, subscriptionId, relayUrl ->
                messageProcessor?.processMessage(messageText, subscriptionId, relayUrl)
            },
            sendDebugLog = { message -> unifiedLogger.debug(com.cmdruid.pubsub.logging.LogDomain.NETWORK, message) }
        )
        
        // Create real message processor
        messageProcessor = MessageProcessor(
            configurationManager = configurationManager,
            subscriptionManager = subscriptionManager,
            eventCache = eventCache,
            eventNotificationManager = eventNotificationManager!!,
            unifiedLogger = unifiedLogger,
            sendDebugLog = { message -> unifiedLogger.debug(com.cmdruid.pubsub.logging.LogDomain.EVENT, message) }
        )
        
        // Create health monitor
        healthMonitor = HealthMonitor(
            relayConnectionManager = relayConnectionManager!!,
            batteryPowerManager = batteryPowerManager!!,
            metricsCollector = metricsCollector!!,
            networkManager = networkManager!!,
            unifiedLogger = unifiedLogger
        )
        
        println("[$TAG] Full service stack created")
        
        return PubSubServiceComponents(
            configurationManager = configurationManager,
            settingsManager = settingsManager,
            unifiedLogger = unifiedLogger,
            subscriptionManager = subscriptionManager,
            eventCache = eventCache,
            batteryPowerManager = batteryPowerManager!!,
            networkManager = networkManager!!,
            eventNotificationManager = eventNotificationManager!!,
            relayConnectionManager = relayConnectionManager!!,
            messageProcessor = messageProcessor!!,
            healthMonitor = healthMonitor!!,
            metricsCollector = metricsCollector!!
        )
    }
    
    /**
     * Create test-friendly BatteryPowerManager
     */
    private fun createTestBatteryPowerManager(): BatteryPowerManager {
        // For now, return a mock that provides basic functionality
        // In the future, this could be a test-specific implementation
        return mockk<BatteryPowerManager>(relaxed = true).apply {
            every { getCurrentBatteryLevel() } returns testBatteryLevel
            every { getCurrentPingInterval() } returns 30L
            every { getCurrentAppState() } returns PubSubService.AppState.FOREGROUND
            every { acquireSmartWakeLock(any(), any(), any()) } returns true
        }
    }
    
    /**
     * Create test-friendly NetworkManager
     */
    private fun createTestNetworkManager(): NetworkManager {
        return mockk<NetworkManager>(relaxed = true).apply {
            every { isNetworkAvailable() } returns (testNetworkState != NetworkState.DISCONNECTED)
            every { getNetworkQuality() } returns when (testNetworkState) {
                NetworkState.CONNECTED -> "good"
                NetworkState.POOR_QUALITY -> "poor"
                NetworkState.DISCONNECTED -> "disconnected"
            }
        }
    }
    
    /**
     * Create test-friendly EventNotificationManager
     */
    private fun createTestEventNotificationManager(): EventNotificationManager {
        return mockk<EventNotificationManager>(relaxed = true)
    }
    
    /**
     * Get the configuration manager (for test assertions)
     */
    fun getConfigurationManager(): ConfigurationManager = configurationManager
    
    /**
     * Get the subscription manager (for test assertions)
     */
    fun getSubscriptionManager(): SubscriptionManager = subscriptionManager
    
    /**
     * Get the event cache (for test assertions)
     */
    fun getEventCache(): EventCache = eventCache
    
    /**
     * Cleanup all test components
     */
    fun cleanup() {
        // Clean up service components
        healthMonitor?.stop()
        relayConnectionManager?.disconnectFromAllRelays()
        metricsCollector?.cleanup()
        
        // Clear test data
        configurationManager.clear()
        subscriptionManager.clearAll()
        eventCache.clear()
        
        println("[$TAG] All components cleaned up")
    }
}

/**
 * Data class representing all service components
 */
data class PubSubServiceComponents(
    val configurationManager: ConfigurationManager,
    val settingsManager: SettingsManager,
    val unifiedLogger: UnifiedLogger,
    val subscriptionManager: SubscriptionManager,
    val eventCache: EventCache,
    val batteryPowerManager: BatteryPowerManager,
    val networkManager: NetworkManager,
    val eventNotificationManager: EventNotificationManager,
    val relayConnectionManager: RelayConnectionManager,
    val messageProcessor: MessageProcessor,
    val healthMonitor: HealthMonitor,
    val metricsCollector: MetricsCollector
)
