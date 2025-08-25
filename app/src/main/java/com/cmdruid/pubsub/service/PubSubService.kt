package com.cmdruid.pubsub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.app.usage.UsageStatsManager
import android.util.Log
import androidx.core.app.NotificationCompat

import com.cmdruid.pubsub.R
import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.data.ConfigurationManager
import com.cmdruid.pubsub.nostr.NostrEvent
import com.cmdruid.pubsub.nostr.NostrFilter
import com.cmdruid.pubsub.nostr.NostrMessage
import com.cmdruid.pubsub.service.SubscriptionManager
import com.cmdruid.pubsub.ui.MainActivity
import com.cmdruid.pubsub.utils.UriBuilder
import com.cmdruid.pubsub.service.BatteryOptimizationLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class PubSubService : Service() {
    
    companion object {
        private const val TAG = "PubSubService"
        private const val NOTIFICATION_ID = 1001
        private const val EVENT_NOTIFICATION_ID_BASE = 2000
        private const val SUMMARY_NOTIFICATION_ID = 3000
        private const val CHANNEL_ID = "pubsub_service_channel"
        private const val EVENT_CHANNEL_ID = "pubsub_event_channel"
        private const val NOTIFICATION_GROUP_KEY = "pubsub_events"
        
        private const val RECONNECT_DELAY_MS = 5000L
        private const val MAX_RECONNECT_DELAY_MS = 60000L
        
        // Battery optimization: Different ping intervals based on app state
        private const val PING_INTERVAL_FOREGROUND_SECONDS = 30L    // Active use
        private const val PING_INTERVAL_BACKGROUND_SECONDS = 120L   // App in background
        private const val PING_INTERVAL_DOZE_SECONDS = 300L         // Device in doze mode
        
        // Battery level optimization: Additional intervals for low battery scenarios
        private const val PING_INTERVAL_LOW_BATTERY_SECONDS = 600L   // Low battery (≤30%)
        private const val PING_INTERVAL_CRITICAL_BATTERY_SECONDS = 900L // Critical battery (≤15%)
        
        // App standby optimization: Intervals for standby buckets
        private const val PING_INTERVAL_RARE_SECONDS = 1200L        // Rare usage (20 minutes)
        private const val PING_INTERVAL_RESTRICTED_SECONDS = 1800L   // Restricted (30 minutes)
        
        // Battery level thresholds
        private const val BATTERY_LEVEL_CRITICAL = 15 // 15% - ultra-conservative mode
        private const val BATTERY_LEVEL_LOW = 30      // 30% - conservative mode  
        private const val BATTERY_LEVEL_HIGH = 80     // 80% - can be more aggressive when charging
        
        // Legacy constant for backward compatibility
        private const val PING_INTERVAL_SECONDS = PING_INTERVAL_FOREGROUND_SECONDS
    }
    
    enum class AppState {
        FOREGROUND,    // App is actively being used
        BACKGROUND,    // App is in background but not in doze
        DOZE,          // Device is in doze mode
        RARE,          // App in rare standby bucket (infrequent usage)
        RESTRICTED     // App in restricted standby bucket (heavily limited)
    }
    
    private data class RelayConnection(
        val relayUrl: String,
        val configurationId: String,
        var webSocket: WebSocket? = null,
        var subscriptionId: String? = null,
        var reconnectAttempts: Int = 0,
        var reconnectJob: Job? = null
    )
    
    private lateinit var configurationManager: ConfigurationManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var batteryOptimizationLogger: BatteryOptimizationLogger
    private lateinit var batteryMetricsCollector: BatteryMetricsCollector
    private lateinit var networkOptimizationLogger: NetworkOptimizationLogger
    private var okHttpClient: OkHttpClient? = null
    private var serviceJob: Job? = null
    private var eventNotificationCounter = EVENT_NOTIFICATION_ID_BASE
    
    // Battery optimization: App state tracking
    private var currentAppState: AppState = AppState.FOREGROUND
    private var appStateChangedTime: Long = System.currentTimeMillis()
    private var currentPingInterval: Long = PING_INTERVAL_FOREGROUND_SECONDS
    
    // Phase 2: Network state monitoring
    private lateinit var connectivityManager: ConnectivityManager
    private var isNetworkAvailable: Boolean = true
    private var currentNetworkType: String = "unknown"
    private var networkQuality: String = "unknown"
    private var lastNetworkChange: Long = System.currentTimeMillis()
    
    // Phase 2: Wake lock management
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockAcquiredTime: Long = 0
    private var wakeLockReason: String = ""
    private val wakeLockTimeoutMs = 30000L // 30 seconds max hold time

    // Phase 3: Doze mode detection
    private var isDozeMode: Boolean = false
    private var dozeStateChangedTime: Long = System.currentTimeMillis()

    // Phase 3.1: Battery level awareness
    private var currentBatteryLevel: Int = 100
    private var previousBatteryLevel: Int = 100
    private var isCharging: Boolean = false
    private var batteryLevelChangedTime: Long = System.currentTimeMillis()
    private var lastBatteryOptimizationTime: Long = 0L

    // Phase 3.2: App standby bucket awareness
    private var currentStandbyBucket: Int = UsageStatsManager.STANDBY_BUCKET_ACTIVE
    private var previousStandbyBucket: Int = UsageStatsManager.STANDBY_BUCKET_ACTIVE
    private var standbyBucketChangedTime: Long = System.currentTimeMillis()
    private var lastStandbyCheckTime: Long = 0L
    private lateinit var usageStatsManager: UsageStatsManager
    
    // Broadcast receiver for app state changes
    private val appStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MainActivity.ACTION_APP_STATE_CHANGE -> {
                    val newStateString = intent.getStringExtra(MainActivity.EXTRA_APP_STATE) ?: return
                    val duration = intent.getLongExtra(MainActivity.EXTRA_STATE_DURATION, 0L)
                    
                    val newState = when (newStateString) {
                        "FOREGROUND" -> AppState.FOREGROUND
                        "BACKGROUND" -> AppState.BACKGROUND
                        "DOZE" -> AppState.DOZE
                        "RARE" -> AppState.RARE
                        "RESTRICTED" -> AppState.RESTRICTED
                        else -> return
                    }
                    
                    handleAppStateChange(newState, duration)
                }
            }
        }
    }

    // Phase 3: Doze mode broadcast receiver
    private val dozeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    handleDozeStateChange()
                }
            }
        }
    }

    // Phase 3.1: Battery level broadcast receiver
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    handleBatteryStatusChange(intent)
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    handleChargingStateChange(newChargingState = true)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    handleChargingStateChange(newChargingState = false)
                }
            }
        }
    }
    
    // Network connectivity callback for Phase 2 optimization
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkStateChange(available = true, network = network)
        }
        
        override fun onLost(network: Network) {
            handleNetworkStateChange(available = false, network = network)
        }
        
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            handleNetworkCapabilitiesChanged(network, networkCapabilities)
        }
    }
    
    // Map of relay URL to RelayConnection
    private val relayConnections = ConcurrentHashMap<String, RelayConnection>()
    
    // Enhanced subscription and event management
    private val subscriptionManager = SubscriptionManager()
    private val eventCache = EventCache()
    
    // Rate limiting for notifications
    private var lastNotificationTime = 0L
    private var notificationCount = 0
    private val notificationRateLimit = 5000L // 5 seconds between notifications
    private val maxNotificationsPerHour = 200 // Allow many notifications per hour, cleanup manages UI display
    
    // Track active event notifications for grouping
    private val activeNotifications = ConcurrentHashMap<Int, NotificationInfo>()
    
    private data class NotificationInfo(
        val subscriptionId: String,
        val configurationName: String,
        val eventContent: String,
        val uri: Uri,
        val timestamp: Long = System.currentTimeMillis()
    )
    

    
    override fun onCreate() {
        super.onCreate()
        configurationManager = ConfigurationManager(this)
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        batteryOptimizationLogger = BatteryOptimizationLogger(this)
        batteryMetricsCollector = BatteryMetricsCollector(this)
        networkOptimizationLogger = NetworkOptimizationLogger(this)
        
        // Phase 2: Initialize network monitoring and power management
        setupNetworkMonitoring()
        setupPowerManagement()
        
        // Phase 3: Initialize doze mode detection
        setupDozeDetection()
        
        // Phase 3.1: Initialize battery level monitoring
        setupBatteryMonitoring()
        
        // Phase 3.2: Initialize app standby monitoring
        setupStandbyMonitoring()
        
        createNotificationChannels()
        setupOkHttpClient()
        
        // Update service state when service is actually created
        configurationManager.isServiceRunning = true
        
        // Register for app state change broadcasts
        val filter = IntentFilter(MainActivity.ACTION_APP_STATE_CHANGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(appStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(appStateReceiver, filter)
        }
        
        // Register for doze mode change broadcasts
        val dozeFilter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dozeReceiver, dozeFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(dozeReceiver, dozeFilter)
        }
        
        // Register for battery level and charging state broadcasts
        val batteryFilter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, batteryFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, batteryFilter)
        }
        
        // Log service creation with battery optimization context
        batteryOptimizationLogger.logOptimization(
            category = BatteryOptimizationLogger.LogCategory.OPTIMIZATION_DECISIONS,
            message = "Service created",
            data = mapOf(
                "app_state" to currentAppState.name,
                "ping_interval" to "${currentPingInterval}s"
            )
        )
        
        sendDebugLog("⚡ Service created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        when (action) {
            "REFRESH_CONNECTIONS" -> {
                sendDebugLog("🔄 Connection refresh requested")
                refreshConnections()
                return START_STICKY
            }
            "SYNC_CONFIGURATIONS" -> {
                sendDebugLog("🔄 Configuration sync requested")
                syncConfigurations()
                return START_STICKY
            }
            else -> {
                sendDebugLog("🚀 Service started")
                startForeground(NOTIFICATION_ID, createForegroundNotification())
            }
        }

        
        serviceJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Clean up any orphaned subscriptions before starting
                cleanupOrphanedSubscriptions()
                
                connectToAllRelays()
                
                // Log initial stats
                logServiceStats()
            } catch (e: Exception) {
                sendDebugLog("❌ Service startup error: ${e.message}")
                Log.e(TAG, "Service startup failed", e)
            }
        }
        
        return START_STICKY // Restart if killed
    }
    
    override fun onDestroy() {
        sendDebugLog("🛑 Service destroyed")
        
        serviceJob?.cancel()
        disconnectFromAllRelays()
        
        // Clean up subscription management
        subscriptionManager.clearAll()
        eventCache.clear()
        
        okHttpClient = null
        
        // Always update the service state when service is actually destroyed
        configurationManager.isServiceRunning = false
        
        // Unregister broadcast receivers
        try {
            unregisterReceiver(appStateReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering app state receiver: ${e.message}")
        }
        
        try {
            unregisterReceiver(dozeReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering doze receiver: ${e.message}")
        }
        
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering battery receiver: ${e.message}")
        }
        
        // Unregister network callback
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering network callback: ${e.message}")
        }
        
        // Release wake lock if held
        releaseWakeLock()

        
        // Clear any remaining notifications to prevent accumulation
        try {
            notificationManager.cancelAll()
            activeNotifications.clear()
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing notifications: ${e.message}")
        }
        
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Service notification channel
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            
            // Event notification channel
            val eventChannel = NotificationChannel(
                EVENT_CHANNEL_ID,
                getString(R.string.event_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.event_notification_channel_description)
            }
            
            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(eventChannel)
        }
    }
    
    private fun createForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_running))
            .setContentText(getString(R.string.service_description))
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFEA8F70.toInt()) // Coral background color
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    /**
     * Setup network monitoring for Phase 2 battery optimization
     */
    private fun setupNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        // Get initial network state
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        
        isNetworkAvailable = activeNetwork != null
        currentNetworkType = getNetworkTypeName(networkCapabilities)
        networkQuality = getNetworkQuality(networkCapabilities)
        
        // Register network callback for monitoring changes
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
            
            batteryOptimizationLogger.logOptimization(
                category = BatteryOptimizationLogger.LogCategory.NETWORK_STATE,
                message = "Network monitoring initialized",
                data = mapOf(
                    "network_available" to isNetworkAvailable,
                    "network_type" to currentNetworkType,
                    "network_quality" to networkQuality
                )
            )
            
            sendDebugLog("🌐 Network monitoring setup: $currentNetworkType, available: $isNetworkAvailable")
        } catch (e: Exception) {
            batteryOptimizationLogger.logOptimization(
                category = BatteryOptimizationLogger.LogCategory.NETWORK_STATE,
                level = BatteryOptimizationLogger.LogLevel.ERROR,
                message = "Failed to setup network monitoring",
                data = mapOf("error" to e.message.toString())
            )
            sendDebugLog("❌ Network monitoring setup failed: ${e.message}")
        }
    }
    
    /**
     * Setup power management for Phase 2 wake lock optimization
     */
    private fun setupPowerManagement() {
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        
        batteryOptimizationLogger.logOptimization(
            category = BatteryOptimizationLogger.LogCategory.WAKE_LOCK,
            message = "Power management initialized",
            data = mapOf(
                "wake_lock_timeout_ms" to wakeLockTimeoutMs,
                "device_idle_mode" to powerManager.isDeviceIdleMode
            )
        )
        
        sendDebugLog("🔋 Power management setup complete")
    }
    
    /**
     * Phase 3: Setup doze mode detection and initial state
     */
    private fun setupDozeDetection() {
        // Get initial doze mode state
        isDozeMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isDeviceIdleMode
        } else {
            false
        }
        
        dozeStateChangedTime = System.currentTimeMillis()
        
        // Update app state if device is already in doze mode
        if (isDozeMode && currentAppState != AppState.DOZE) {
            val previousState = currentAppState
            currentAppState = AppState.DOZE
            updatePingInterval()
            
            batteryOptimizationLogger.logOptimization(
                category = BatteryOptimizationLogger.LogCategory.APP_STATE,
                message = "Initial doze mode detected",
                data = mapOf(
                    "previous_state" to previousState.name,
                    "current_state" to AppState.DOZE.name,
                    "is_doze_mode" to isDozeMode,
                    "ping_interval_seconds" to currentPingInterval
                )
            )
        }
        
        batteryOptimizationLogger.logOptimization(
            category = BatteryOptimizationLogger.LogCategory.OPTIMIZATION_DECISIONS,
            message = "Doze detection initialized",
            data = mapOf(
                "is_doze_mode" to isDozeMode,
                "current_app_state" to currentAppState.name,
                "ping_interval_seconds" to currentPingInterval,
                "api_level" to Build.VERSION.SDK_INT
            )
        )
        
        sendDebugLog("🌙 Doze detection setup complete (doze: $isDozeMode)")
    }
    
    /**
     * Handle doze mode state changes from broadcast receiver
     */
    private fun handleDozeStateChange() {
        val previousDozeState = isDozeMode
        val previousAppState = currentAppState
        val previousStateDuration = System.currentTimeMillis() - dozeStateChangedTime
        
        // Update doze state
        isDozeMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isDeviceIdleMode
        } else {
            false
        }
        
        dozeStateChangedTime = System.currentTimeMillis()
        
        // Update app state based on doze mode
        val newAppState = when {
            isDozeMode -> AppState.DOZE
            currentAppState == AppState.DOZE -> AppState.BACKGROUND // Exit doze to background
            else -> currentAppState // Keep current state if not transitioning from doze
        }
        
        if (newAppState != currentAppState) {
            currentAppState = newAppState
            updatePingInterval()
            
            batteryOptimizationLogger.logOptimization(
                category = BatteryOptimizationLogger.LogCategory.APP_STATE,
                message = "Doze state changed",
                data = mapOf(
                    "previous_doze_state" to previousDozeState,
                    "current_doze_state" to isDozeMode,
                    "previous_app_state" to previousAppState.name,
                    "current_app_state" to newAppState.name,
                    "state_duration_ms" to previousStateDuration,
                    "ping_interval_seconds" to currentPingInterval
                )
            )
            
            // Update battery metrics
            batteryMetricsCollector.trackAppStateChange(
                fromState = previousAppState,
                toState = newAppState,
                duration = previousStateDuration
            )
            
            sendDebugLog(
                "🌙 Doze ${if (isDozeMode) "ENTERED" else "EXITED"}: " +
                "${previousAppState.name} → ${newAppState.name} " +
                "(${previousStateDuration}ms, ping: ${currentPingInterval}s)"
            )
        }
        
        // Track doze mode effectiveness
        batteryMetricsCollector.trackNetworkActivity(
            eventType = if (isDozeMode) "doze_mode_entered" else "doze_mode_exited",
            optimized = true
        )
    }
    
    /**
     * Check if device is currently in doze mode
     */
    private fun isDeviceInDozeMode(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isDeviceIdleMode
        } else {
            false
        }
    }
    
    /**
     * Phase 3.1: Setup battery level monitoring and initial state
     */
    private fun setupBatteryMonitoring() {
        // Get initial battery status
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            handleBatteryStatusChange(batteryIntent)
        } else {
            // Fallback if battery status not available
            currentBatteryLevel = 100
            isCharging = false
        }
        
        batteryLevelChangedTime = System.currentTimeMillis()
        
        batteryOptimizationLogger.logOptimization(
            category = BatteryOptimizationLogger.LogCategory.OPTIMIZATION_DECISIONS,
            message = "Battery monitoring initialized",
            data = mapOf(
                "initial_battery_level" to currentBatteryLevel,
                "is_charging" to isCharging,
                "battery_thresholds" to mapOf(
                    "critical" to BATTERY_LEVEL_CRITICAL,
                    "low" to BATTERY_LEVEL_LOW,
                    "high" to BATTERY_LEVEL_HIGH
                ),
                "ping_intervals" to mapOf(
                    "critical_battery" to "${PING_INTERVAL_CRITICAL_BATTERY_SECONDS}s",
                    "low_battery" to "${PING_INTERVAL_LOW_BATTERY_SECONDS}s"
                )
            )
        )
        
        sendDebugLog("🔋 Battery monitoring setup complete (level: $currentBatteryLevel%, charging: $isCharging)")
    }
    
    /**
     * Handle battery status changes from broadcast receiver
     */
    private fun handleBatteryStatusChange(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        
        if (level >= 0 && scale > 0) {
            previousBatteryLevel = currentBatteryLevel
            currentBatteryLevel = level * 100 / scale
            isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING || 
                         status == BatteryManager.BATTERY_STATUS_FULL) ||
                         plugged != 0
            
            val batteryLevelChanged = currentBatteryLevel != previousBatteryLevel
            val chargingStateChanged = isCharging != (status == BatteryManager.BATTERY_STATUS_CHARGING)
            
            if (batteryLevelChanged || chargingStateChanged) {
                handleBatteryOptimizationChange()
            }
        }
    }
    
    /**
     * Handle charging state changes from broadcast receiver
     */
    private fun handleChargingStateChange(newChargingState: Boolean) {
        if (isCharging != newChargingState) {
            val previousChargingState = isCharging
            isCharging = newChargingState
            
            batteryOptimizationLogger.logOptimization(
                category = BatteryOptimizationLogger.LogCategory.BATTERY_USAGE,
                message = "Charging state changed",
                data = mapOf(
                    "previous_charging" to previousChargingState,
                    "current_charging" to isCharging,
                    "battery_level" to currentBatteryLevel
                )
            )
            
            handleBatteryOptimizationChange()
            
            sendDebugLog("🔌 Charging ${if (isCharging) "CONNECTED" else "DISCONNECTED"} (battery: $currentBatteryLevel%)")
        }
    }
    
    /**
     * Handle battery level optimization changes and update ping intervals
     */
    private fun handleBatteryOptimizationChange() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastOptimization = currentTime - lastBatteryOptimizationTime
        
        // Avoid too frequent optimizations (minimum 30 seconds between changes)
        if (timeSinceLastOptimization < 30000L) {
            return
        }
        
        val oldPingInterval = currentPingInterval
        val newPingInterval = getCurrentPingInterval()
        
        if (oldPingInterval != newPingInterval) {
            lastBatteryOptimizationTime = currentTime
            updatePingInterval()
            
            // Determine optimization reason
            val optimizationReason = when {
                currentBatteryLevel <= BATTERY_LEVEL_CRITICAL -> "critical_battery"
                currentBatteryLevel <= BATTERY_LEVEL_LOW -> "low_battery"
                isCharging && currentBatteryLevel >= BATTERY_LEVEL_HIGH -> "charging_high_battery"
                else -> "battery_level_change"
            }
            
            batteryOptimizationLogger.logOptimization(
                category = BatteryOptimizationLogger.LogCategory.BATTERY_USAGE,
                message = "Battery optimization applied",
                data = mapOf(
                    "reason" to optimizationReason,
                    "previous_battery_level" to previousBatteryLevel,
                    "current_battery_level" to currentBatteryLevel,
                    "is_charging" to isCharging,
                    "old_ping_interval" to "${oldPingInterval}s",
                    "new_ping_interval" to "${newPingInterval}s",
                    "app_state" to currentAppState.name
                )
            )
            
            // Track battery optimization effectiveness
            batteryMetricsCollector.trackNetworkActivity(
                eventType = "battery_optimization_applied",
                optimized = true
            )
            
            sendDebugLog(
                "🔋 Battery optimization: $optimizationReason " +
                "(${previousBatteryLevel}% → $currentBatteryLevel%, " +
                "charging: $isCharging, ${oldPingInterval}s → ${newPingInterval}s)"
            )
        }
    }
    
    /**
     * Phase 3.2: Setup app standby bucket monitoring and initial state
     */
    private fun setupStandbyMonitoring() {
        // Initialize UsageStatsManager
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        
        // Get initial standby bucket state
        currentStandbyBucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            usageStatsManager.appStandbyBucket
        } else {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE
        }
        
        previousStandbyBucket = currentStandbyBucket
        standbyBucketChangedTime = System.currentTimeMillis()
        lastStandbyCheckTime = System.currentTimeMillis()
        
        // Update app state if already in restricted bucket
        updateAppStateFromStandbyBucket()
        
        batteryOptimizationLogger.logOptimization(
            category = BatteryOptimizationLogger.LogCategory.OPTIMIZATION_DECISIONS,
            message = "App standby monitoring initialized",
            data = mapOf(
                "initial_standby_bucket" to getStandbyBucketName(currentStandbyBucket),
                "current_app_state" to currentAppState.name,
                "ping_intervals" to mapOf(
                    "rare" to "${PING_INTERVAL_RARE_SECONDS}s",
                    "restricted" to "${PING_INTERVAL_RESTRICTED_SECONDS}s"
                ),
                "api_level" to Build.VERSION.SDK_INT
            )
        )
        
        sendDebugLog("📱 App standby monitoring setup complete (bucket: ${getStandbyBucketName(currentStandbyBucket)})")
    }
    
    /**
     * Check and handle app standby bucket changes
     */
    private fun checkStandbyBucket() {
        val currentTime = System.currentTimeMillis()
        
        // Check at most once per minute to avoid excessive API calls
        if (currentTime - lastStandbyCheckTime < 60000L) {
            return
        }
        
        lastStandbyCheckTime = currentTime
        
        val newStandbyBucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            usageStatsManager.appStandbyBucket
        } else {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE
        }
        
        if (newStandbyBucket != currentStandbyBucket) {
            val bucketDuration = currentTime - standbyBucketChangedTime
            
            batteryOptimizationLogger.logOptimization(
                category = BatteryOptimizationLogger.LogCategory.APP_STATE,
                message = "App standby bucket changed",
                data = mapOf(
                    "previous_bucket" to getStandbyBucketName(currentStandbyBucket),
                    "current_bucket" to getStandbyBucketName(newStandbyBucket),
                    "bucket_duration_ms" to bucketDuration,
                    "previous_app_state" to currentAppState.name
                )
            )
            
            previousStandbyBucket = currentStandbyBucket
            currentStandbyBucket = newStandbyBucket
            standbyBucketChangedTime = currentTime
            
            updateAppStateFromStandbyBucket()
            
            sendDebugLog(
                "📱 Standby bucket changed: ${getStandbyBucketName(previousStandbyBucket)} → " +
                "${getStandbyBucketName(currentStandbyBucket)} (${bucketDuration}ms)"
            )
        }
    }
    
    /**
     * Update app state based on current standby bucket
     */
    private fun updateAppStateFromStandbyBucket() {
        val newAppState = when {
            isDozeMode -> AppState.DOZE // Doze mode takes highest priority
            currentStandbyBucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> AppState.RESTRICTED
            currentStandbyBucket == UsageStatsManager.STANDBY_BUCKET_RARE -> AppState.RARE
            currentAppState == AppState.FOREGROUND -> AppState.FOREGROUND // Don't override foreground
            else -> AppState.BACKGROUND // Default to background
        }
        
        if (newAppState != currentAppState) {
            val oldPingInterval = currentPingInterval
            currentAppState = newAppState
            updatePingInterval()
            
            val optimizationReason = when (newAppState) {
                AppState.RESTRICTED -> "app_standby_restricted"
                AppState.RARE -> "app_standby_rare"
                else -> "standby_bucket_change"
            }
            
            batteryOptimizationLogger.logOptimization(
                category = BatteryOptimizationLogger.LogCategory.APP_STATE,
                message = "App state updated from standby bucket",
                data = mapOf(
                    "reason" to optimizationReason,
                    "standby_bucket" to getStandbyBucketName(currentStandbyBucket),
                    "app_state" to newAppState.name,
                    "old_ping_interval" to "${oldPingInterval}s",
                    "new_ping_interval" to "${currentPingInterval}s"
                )
            )
            
            // Track standby optimization effectiveness
            batteryMetricsCollector.trackNetworkActivity(
                eventType = "standby_optimization_applied",
                optimized = true
            )
            
            sendDebugLog(
                "📱 App state updated: ${getStandbyBucketName(currentStandbyBucket)} → $newAppState " +
                "(${oldPingInterval}s → ${currentPingInterval}s)"
            )
        }
    }
    
    /**
     * Get human-readable standby bucket name
     */
    private fun getStandbyBucketName(bucket: Int): String {
        return when (bucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED"
            else -> "UNKNOWN($bucket)"
        }
    }
    
    /**
     * Acquire wake lock for critical operations with automatic timeout
     */
    private fun acquireWakeLock(reason: String, durationMs: Long = wakeLockTimeoutMs) {
        try {
            // Release existing wake lock if any
            releaseWakeLock()
            
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "PubSubService::$reason"
            ).apply {
                setReferenceCounted(false)
                acquire(durationMs)
            }
            
            wakeLockAcquiredTime = System.currentTimeMillis()
            wakeLockReason = reason
            
            batteryOptimizationLogger.logOptimization(
                category = BatteryOptimizationLogger.LogCategory.WAKE_LOCK,
                message = "Wake lock acquired",
                data = mapOf(
                    "reason" to reason,
                    "duration_ms" to durationMs,
                    "device_idle" to powerManager.isDeviceIdleMode
                )
            )
            
            sendDebugLog("🔒 Wake lock acquired: $reason (${durationMs}ms)")
            
            // Log wake lock acquisition for Phase 2 monitoring
            networkOptimizationLogger.logWakeLockAcquisition(
                reason = reason,
                durationMs = durationMs,
                batteryLevel = batteryOptimizationLogger.getBatteryLevel(),
                networkType = currentNetworkType,
                details = mapOf(
                    "device_idle" to powerManager.isDeviceIdleMode,
                    "app_state" to currentAppState.name
                )
            )
            
            // Schedule automatic release as backup
            CoroutineScope(Dispatchers.IO).launch {
                delay(durationMs + 1000) // Add 1 second buffer
                if (wakeLock?.isHeld == true && wakeLockReason == reason) {
                    sendDebugLog("⚠️ Auto-releasing wake lock timeout: $reason")
                    releaseWakeLock()
                }
            }
        } catch (e: Exception) {
            batteryOptimizationLogger.logOptimization(
                category = BatteryOptimizationLogger.LogCategory.WAKE_LOCK,
                level = BatteryOptimizationLogger.LogLevel.ERROR,
                message = "Failed to acquire wake lock",
                data = mapOf(
                    "reason" to reason,
                    "error" to e.message.toString()
                )
            )
            sendDebugLog("❌ Wake lock acquisition failed: ${e.message}")
        }
    }
    
    /**
     * Release wake lock and log duration
     */
    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                try {
                    val heldDuration = System.currentTimeMillis() - wakeLockAcquiredTime
                    
                    lock.release()
                    
                    batteryOptimizationLogger.logOptimization(
                        category = BatteryOptimizationLogger.LogCategory.WAKE_LOCK,
                        message = "Wake lock released",
                        data = mapOf(
                            "reason" to wakeLockReason,
                            "held_duration_ms" to heldDuration,
                            "was_timeout" to (heldDuration >= wakeLockTimeoutMs)
                        )
                    )
                    
                    sendDebugLog("🔓 Wake lock released: $wakeLockReason (held ${heldDuration}ms)")
                    
                    // Log wake lock release for Phase 2 monitoring
                    networkOptimizationLogger.logWakeLockRelease(
                        reason = wakeLockReason,
                        heldDurationMs = heldDuration,
                        batteryLevel = batteryOptimizationLogger.getBatteryLevel(),
                        networkType = currentNetworkType,
                        wasTimeout = heldDuration >= wakeLockTimeoutMs,
                        details = mapOf(
                            "was_effective" to (heldDuration < wakeLockTimeoutMs),
                            "app_state" to currentAppState.name
                        )
                    )
                    
                    // Track wake lock effectiveness
                    val wasEffective = heldDuration < wakeLockTimeoutMs
                    batteryMetricsCollector.trackNetworkActivity("wake_lock_released", optimized = wasEffective)
                } catch (e: Exception) {
                    batteryOptimizationLogger.logOptimization(
                        category = BatteryOptimizationLogger.LogCategory.WAKE_LOCK,
                        level = BatteryOptimizationLogger.LogLevel.WARN,
                        message = "Error releasing wake lock",
                        data = mapOf("error" to e.message.toString())
                    )
                    sendDebugLog("⚠️ Wake lock release error: ${e.message}")
                }
            }
            
            wakeLock = null
            wakeLockReason = ""
            wakeLockAcquiredTime = 0
        }
    }
    
    /**
     * Check if wake lock is currently held
     */
    private fun isWakeLockHeld(): Boolean {
        return wakeLock?.isHeld == true
    }
    
    private fun setupOkHttpClient() {
        okHttpClient = createOkHttpClient(currentPingInterval)
    }
    
    /**
     * Create OkHttp client with specified ping interval for battery optimization
     */
    private fun createOkHttpClient(pingIntervalSeconds: Long): OkHttpClient {
        batteryOptimizationLogger.logOptimization(
            category = BatteryOptimizationLogger.LogCategory.PING_INTERVAL,
            message = "Creating OkHttp client",
            data = mapOf(
                "ping_interval" to "${pingIntervalSeconds}s",
                "app_state" to currentAppState.name
            )
        )
        
        // Track ping frequency for metrics
        batteryMetricsCollector.trackPingFrequency(pingIntervalSeconds, currentAppState)
        
        return OkHttpClient.Builder()
            .pingInterval(pingIntervalSeconds, TimeUnit.SECONDS)
            .build()
    }
    
    /**
     * Get current ping interval based on app state
     */
    private fun getCurrentPingInterval(): Long {
        // Start with base interval based on app state
        val baseInterval = when (currentAppState) {
            AppState.FOREGROUND -> PING_INTERVAL_FOREGROUND_SECONDS
            AppState.BACKGROUND -> PING_INTERVAL_BACKGROUND_SECONDS
            AppState.DOZE -> PING_INTERVAL_DOZE_SECONDS
            AppState.RARE -> PING_INTERVAL_RARE_SECONDS
            AppState.RESTRICTED -> PING_INTERVAL_RESTRICTED_SECONDS
        }
        
        // Apply battery level optimization
        val batteryOptimizedInterval = when {
            currentBatteryLevel <= BATTERY_LEVEL_CRITICAL -> {
                // Critical battery - use ultra-conservative interval regardless of state
                maxOf(baseInterval, PING_INTERVAL_CRITICAL_BATTERY_SECONDS)
            }
            currentBatteryLevel <= BATTERY_LEVEL_LOW -> {
                // Low battery - use conservative interval regardless of state
                maxOf(baseInterval, PING_INTERVAL_LOW_BATTERY_SECONDS)
            }
            isCharging && currentBatteryLevel >= BATTERY_LEVEL_HIGH -> {
                // High battery and charging - can be more aggressive in foreground
                if (currentAppState == AppState.FOREGROUND) {
                    minOf(baseInterval, 15L) // 15 seconds when charging and high battery
                } else {
                    baseInterval
                }
            }
            else -> {
                // Normal battery level - use base interval
                baseInterval
            }
        }
        
        batteryOptimizationLogger.logOptimization(
            category = BatteryOptimizationLogger.LogCategory.PING_INTERVAL,
            level = BatteryOptimizationLogger.LogLevel.DEBUG,
            message = "Current ping interval calculated",
            data = mapOf(
                "base_interval" to "${baseInterval}s",
                "battery_optimized_interval" to "${batteryOptimizedInterval}s",
                "app_state" to currentAppState.name,
                "battery_level" to currentBatteryLevel,
                "is_charging" to isCharging,
                "optimization_applied" to (baseInterval != batteryOptimizedInterval)
            )
        )
        
        return batteryOptimizedInterval
    }
    
    /**
     * Update ping interval based on current app state and refresh connections
     */
    private fun updatePingInterval() {
        val oldInterval = currentPingInterval
        val newInterval = getCurrentPingInterval()
        
        if (oldInterval != newInterval) {
            currentPingInterval = newInterval
            
            // Log the ping interval change
            batteryOptimizationLogger.logPingIntervalChange(
                fromInterval = oldInterval,
                toInterval = newInterval,
                reason = "app_state_change",
                appState = currentAppState.name
            )
            
            // Recreate OkHttp client with new ping interval
            val oldClient = okHttpClient
            okHttpClient = createOkHttpClient(newInterval)
            
            // Schedule connection refresh to use new client
            CoroutineScope(Dispatchers.IO).launch {
                refreshConnections()
            }
            
            sendDebugLog("🔋 Ping interval updated: ${oldInterval}s → ${newInterval}s (${currentAppState.name})")
        }
    }
    
    /**
     * Handle app state changes from MainActivity
     */
    private fun handleAppStateChange(newState: AppState, previousStateDuration: Long) {
        val oldState = currentAppState
        val currentTime = System.currentTimeMillis()
        
        if (oldState != newState) {
            // Log the app state change
            batteryOptimizationLogger.logAppStateChange(
                fromState = oldState.name,
                toState = newState.name,
                duration = previousStateDuration
            )
            
            // Track metrics for effectiveness measurement
            batteryMetricsCollector.trackAppStateChange(oldState, newState, previousStateDuration)
            
            // Update state tracking
            currentAppState = newState
            appStateChangedTime = currentTime
            
            sendDebugLog("🔋 App state: ${oldState.name} → ${newState.name} (${previousStateDuration}ms)")
            
            // Update ping interval based on new state
            updatePingInterval()
            
            // Log connection health after state change
            batteryOptimizationLogger.logConnectionHealth(
                relayUrl = "all_relays",
                status = "state_change_applied",
                reconnectAttempts = 0
            )
        }
    }
    
    /**
     * Handle network state changes for Phase 2 optimization
     */
    private fun handleNetworkStateChange(available: Boolean, network: Network) {
        val previousState = isNetworkAvailable
        val currentTime = System.currentTimeMillis()
        val networkDownTime = if (!previousState && available) currentTime - lastNetworkChange else 0L
        
        isNetworkAvailable = available
        lastNetworkChange = currentTime
        
        // Get network capabilities for detailed info
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
        val newNetworkType = getNetworkTypeName(networkCapabilities)
        val newNetworkQuality = getNetworkQuality(networkCapabilities)
        
        if (previousState != available || currentNetworkType != newNetworkType) {
            currentNetworkType = newNetworkType
            networkQuality = newNetworkQuality
            
            batteryOptimizationLogger.logOptimization(
                category = BatteryOptimizationLogger.LogCategory.NETWORK_STATE,
                message = "Network state changed",
                data = mapOf(
                    "available" to available,
                    "network_type" to currentNetworkType,
                    "quality" to networkQuality,
                    "down_time_ms" to networkDownTime
                )
            )
            
            val statusEmoji = if (available) "✅" else "❌"
            sendDebugLog("🌐 $statusEmoji Network: $currentNetworkType, quality: $networkQuality")
            
            // Log network state change for Phase 2 monitoring
            networkOptimizationLogger.logNetworkStateChange(
                networkType = currentNetworkType,
                networkQuality = networkQuality,
                connectionCount = relayConnections.size,
                batteryLevel = batteryOptimizationLogger.getBatteryLevel(),
                appState = currentAppState.name,
                details = mapOf(
                    "previous_type" to (if (previousState != available) "connection_change" else "type_change"),
                    "down_time_ms" to networkDownTime
                )
            )
            
            // Handle network-aware reconnection logic
            handleNetworkAwareReconnection(available, networkDownTime)
            
            // Track network activity for metrics
            batteryMetricsCollector.trackNetworkActivity("state_change", optimized = true)
        }
    }
    
    /**
     * Handle network capabilities changes
     */
    private fun handleNetworkCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        val newNetworkType = getNetworkTypeName(networkCapabilities)
        val newNetworkQuality = getNetworkQuality(networkCapabilities)
        
        if (currentNetworkType != newNetworkType || networkQuality != newNetworkQuality) {
            val oldType = currentNetworkType
            val oldQuality = networkQuality
            
            currentNetworkType = newNetworkType
            networkQuality = newNetworkQuality
            
            batteryOptimizationLogger.logOptimization(
                category = BatteryOptimizationLogger.LogCategory.NETWORK_STATE,
                message = "Network capabilities changed",
                data = mapOf(
                    "old_type" to oldType,
                    "new_type" to currentNetworkType,
                    "old_quality" to oldQuality,
                    "new_quality" to networkQuality
                )
            )
            
            sendDebugLog("🌐 Network capabilities: $oldType → $currentNetworkType, quality: $oldQuality → $networkQuality")
            
            // Adjust optimization based on network quality
            adjustOptimizationForNetworkQuality(newNetworkQuality)
        }
    }
    
    /**
     * Get human-readable network type name
     */
    private fun getNetworkTypeName(networkCapabilities: NetworkCapabilities?): String {
        return when {
            networkCapabilities == null -> "none"
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }
    }
    
    /**
     * Determine network quality based on capabilities
     */
    private fun getNetworkQuality(networkCapabilities: NetworkCapabilities?): String {
        if (networkCapabilities == null) return "none"
        
        return when {
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "high" else "medium"
            }
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "high" else "low"
            }
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "high"
            else -> "medium"
        }
    }
    
    /**
     * Handle network-aware reconnection logic
     */
    private fun handleNetworkAwareReconnection(networkAvailable: Boolean, downTime: Long) {
        if (networkAvailable && downTime > 0) {
            // Network came back online - consider reconnecting
            val shouldReconnect = downTime > 5000L // Only if offline for more than 5 seconds
            
            batteryOptimizationLogger.logOptimization(
                category = BatteryOptimizationLogger.LogCategory.CONNECTION_HEALTH,
                message = "Network-aware reconnection decision",
                data = mapOf(
                    "should_reconnect" to shouldReconnect,
                    "down_time_ms" to downTime,
                    "active_connections" to relayConnections.size
                )
            )
            
            if (shouldReconnect) {
                sendDebugLog("🔄 Network restored, refreshing connections (offline for ${downTime}ms)")
                CoroutineScope(Dispatchers.IO).launch {
                    refreshConnections()
                }
            }
        } else if (!networkAvailable) {
            // Network lost - avoid unnecessary reconnection attempts
            sendDebugLog("📡 Network lost, avoiding reconnection attempts")
            
            // Cancel any pending reconnection jobs to save battery
            relayConnections.values.forEach { connection ->
                connection.reconnectJob?.cancel()
            }
        }
    }
    
    /**
     * Adjust optimization based on network quality
     */
    private fun adjustOptimizationForNetworkQuality(quality: String) {
        // For Phase 2, we can adjust ping intervals based on network quality
        // High quality networks can handle more frequent pings
        // Low quality networks should use longer intervals to save battery
        
        val qualityMultiplier = when (quality) {
            "high" -> 1.0    // No adjustment for high quality
            "medium" -> 1.2  // 20% longer intervals for medium quality
            "low" -> 1.5     // 50% longer intervals for low quality
            else -> 1.0
        }
        
        batteryOptimizationLogger.logOptimization(
            category = BatteryOptimizationLogger.LogCategory.OPTIMIZATION_DECISIONS,
            message = "Network quality adjustment",
            data = mapOf(
                "quality" to quality,
                "multiplier" to qualityMultiplier,
                "current_ping_interval" to currentPingInterval
            )
        )
        
        if (qualityMultiplier != 1.0) {
            sendDebugLog("🌐 Adjusting optimization for $quality network quality (${qualityMultiplier}x)")
        }
    }
    
    private suspend fun connectToAllRelays() {
        try {
            val configurations = configurationManager.getEnabledConfigurations()
            sendDebugLog("🔌 Starting connections for ${configurations.size} subscription(s)")
            
            for (configuration in configurations) {
                for (relayUrl in configuration.relayUrls) {
                    try {
                        connectToRelay(relayUrl, configuration)
                    } catch (e: Exception) {
                        sendDebugLog("❌ Failed to connect to $relayUrl: ${e.message}")
                        Log.e(TAG, "Connection failed for $relayUrl", e)
                    }
                }
            }
        } catch (e: Exception) {
            sendDebugLog("❌ Error in connectToAllRelays: ${e.message}")
            Log.e(TAG, "connectToAllRelays failed", e)
        }
    }
    
    private suspend fun connectToRelay(relayUrl: String, configuration: Configuration) {
        sendDebugLog("🔌 Connecting: ${relayUrl.substringAfter("://").take(20)}... (${configuration.name})")
        
        // Acquire wake lock for connection establishment
        acquireWakeLock("connection_${relayUrl.substringAfter("://").take(10)}", 15000L)
        
        val connection = RelayConnection(relayUrl, configuration.id)
        relayConnections[relayUrl] = connection
        
        val request = Request.Builder()
            .url(relayUrl)
            .build()
        
        connection.webSocket = okHttpClient?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendDebugLog("✅ Connected: ${relayUrl.substringAfter("://").take(20)}...")
                connection.reconnectAttempts = 0
                
                // Release wake lock - connection established successfully
                releaseWakeLock()
                
                // Track successful connection
                batteryMetricsCollector.trackConnectionEvent("connect", relayUrl, success = true)
                batteryOptimizationLogger.logConnectionHealth(relayUrl, "connected", 0)
                
                subscribeToEvents(connection, configuration)
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received from $relayUrl: $text")
                handleWebSocketMessage(text, configuration)
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                sendDebugLog("⚠️ $relayUrl closing: $code - $reason")
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                sendDebugLog("📡 $relayUrl closed: $code - $reason")
                scheduleReconnect(connection, configuration)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                sendDebugLog("❌ $relayUrl failed: ${t.message}")
                
                // Release wake lock - connection failed
                releaseWakeLock()
                
                // Track connection failure
                batteryMetricsCollector.trackConnectionEvent("connect", relayUrl, success = false)
                batteryOptimizationLogger.logConnectionHealth(relayUrl, "failed: ${t.message}", connection.reconnectAttempts)
                
                scheduleReconnect(connection, configuration)
            }
        })
    }
    
    private fun subscribeToEvents(connection: RelayConnection, configuration: Configuration) {
        if (configuration.filter.isEmpty()) {
            sendDebugLog("⚠️ No filter configured for ${configuration.name}, cannot subscribe")
            return
        }
        
        // Use the configuration's permanent subscription ID
        val subscriptionId = configuration.subscriptionId
        
        // Always update the "since" timestamp to get only new events
        val filterToUse = if (connection.subscriptionId != null && connection.subscriptionId == subscriptionId) {
            // This is a resubscription with the same ID - use updated filter with latest timestamp
            subscriptionManager.createResubscriptionFilter(subscriptionId) ?: createInitialFilter(configuration.filter)
        } else {
            // New subscription or subscription ID changed - set "since" to current time to get only new events
            createInitialFilter(configuration.filter)
        }
        
        // Register subscription with the subscription manager
        subscriptionManager.registerSubscription(
            subscriptionId = subscriptionId,
            configurationId = configuration.id,
            filter = filterToUse,
            relayUrl = connection.relayUrl
        )
        
        // Update connection with the configuration's subscription ID
        connection.subscriptionId = subscriptionId
        
        val subscriptionMessage = NostrMessage.createSubscription(subscriptionId, filterToUse)
        
        sendDebugLog("📋 Subscribing to ${connection.relayUrl} with filter: ${filterToUse.getSummary()}")
        sendDebugLog("🆔 Subscription ID: $subscriptionId")
        
        connection.webSocket?.send(subscriptionMessage)
    }
    
    /**
     * Create initial filter with "since" set to current time for new subscriptions
     */
    private fun createInitialFilter(baseFilter: NostrFilter): NostrFilter {
        val currentTimestamp = System.currentTimeMillis() / 1000 // Convert to Unix timestamp
        return baseFilter.copy(since = currentTimestamp)
    }
    
    private fun handleWebSocketMessage(messageText: String, originalConfiguration: Configuration) {
        // Process messages on a background thread to prevent ANRs
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val parsedMessage = NostrMessage.parseMessage(messageText)
                
                when (parsedMessage) {
                    is NostrMessage.ParsedMessage.EventMessage -> {
                        val subscriptionId = parsedMessage.subscriptionId
                        val event = parsedMessage.event
                        
                        // Enhanced event processing with subscription tracking and duplicate detection
                        
                        // 1. Check if subscription is still active
                        if (!subscriptionManager.isActiveSubscription(subscriptionId)) {
                            sendDebugLog("🚫 Ignoring event from inactive subscription: $subscriptionId")
                            return@launch
                        }
                        
                        // 2. Get the current configuration for this subscription to avoid stale data
                        val configurationId = subscriptionManager.getConfigurationId(subscriptionId)
                        val currentConfiguration = if (configurationId != null) {
                            configurationManager.getConfigurationById(configurationId)
                        } else {
                            null
                        }
                        
                        if (currentConfiguration == null || !currentConfiguration.isEnabled) {
                            sendDebugLog("🚫 Ignoring event: configuration not found or disabled for subscription $subscriptionId")
                            return@launch
                        }
                        
                        // 3. Validate event structure
                        if (!event.isValid()) {
                            sendDebugLog("❌ Invalid event rejected: ${event.id.take(8)}...")
                            return@launch
                        }
                        
                        // 4. Check for duplicate events
                        if (eventCache.hasSeenEvent(event.id)) {
                            sendDebugLog("🔄 Ignoring duplicate event: ${event.id.take(8)}...")
                            return@launch
                        }
                        
                        // 5. Mark as seen and update timestamp tracking
                        eventCache.markEventSeen(event.id)
                        subscriptionManager.updateLastEventTimestamp(subscriptionId, event.createdAt)
                        
                        sendDebugLog("📨 Event: ${event.id.take(8)}... (${currentConfiguration.name}) [${NostrEvent.getKindName(event.kind)}]")
                        handleNostrEvent(event, currentConfiguration, subscriptionId)
                    }
                    is NostrMessage.ParsedMessage.EoseMessage -> {
                        val subscriptionId = parsedMessage.subscriptionId
                        val configurationId = subscriptionManager.getConfigurationId(subscriptionId)
                        val currentConfiguration = if (configurationId != null) {
                            configurationManager.getConfigurationById(configurationId)
                        } else {
                            originalConfiguration
                        }
                        sendDebugLog("✅ End of stored events for ${currentConfiguration?.name ?: "unknown"}")
                    }
                    is NostrMessage.ParsedMessage.NoticeMessage -> {
                        sendDebugLog("📢 Relay notice for ${originalConfiguration.name}: ${parsedMessage.notice}")
                    }
                    is NostrMessage.ParsedMessage.OkMessage -> {
                        Log.d(TAG, "OK response for ${originalConfiguration.name}: ${parsedMessage.eventId} - ${parsedMessage.success}")
                    }
                    is NostrMessage.ParsedMessage.UnknownMessage -> {
                        sendDebugLog("⚠️ Unknown message type for ${originalConfiguration.name}: ${parsedMessage.type}")
                    }
                    null -> {
                        sendDebugLog("❌ Failed to parse message for ${originalConfiguration.name}: $messageText")
                    }
                }
            } catch (e: Exception) {
                sendDebugLog("❌ Error processing message for ${originalConfiguration.name}: ${e.message}")
            }
        }
    }
    
    private fun handleNostrEvent(event: NostrEvent, configuration: Configuration, subscriptionId: String) {
        // Check event size before processing
        val eventSizeBytes = UriBuilder.getEventSizeBytes(event)
        val eventSizeKB = eventSizeBytes / 1024
        val isEventTooLarge = UriBuilder.isEventTooLarge(event)
        
        val eventUri = UriBuilder.buildEventUri(configuration.targetUri, event)
        if (eventUri == null) {
            sendDebugLog("❌ Failed to build event URI for ${configuration.name}")
            return
        }
        
        // Log event processing with size information
        val sizeInfo = if (isEventTooLarge) {
            "ID only (event ${eventSizeKB}KB > 500KB limit)"
        } else {
            "with full event data (${eventSizeKB}KB)"
        }
        
        sendDebugLog("📤 Event ${event.id.take(8)}... → ${configuration.name}")
        
        showEventNotification(event, eventUri, configuration, subscriptionId)
    }
    

    
    private fun showEventNotification(event: NostrEvent, uri: Uri, configuration: Configuration, subscriptionId: String) {
        sendDebugLog("🔔 Processing notification for event: ${event.id.take(8)}... (${configuration.name})")
        val currentTime = System.currentTimeMillis()
        
        // Reset notification count every hour
        if (currentTime - lastNotificationTime > 3600000) { // 1 hour
            val oldCount = notificationCount
            notificationCount = 0
            if (oldCount > 0) {
                sendDebugLog("🔄 Hourly notification count reset: $oldCount → 0")
            }
        }
        
        // Rate limiting: max notifications per hour and minimum time between notifications
        if (notificationCount >= maxNotificationsPerHour) {
            sendDebugLog("⏸️ Rate limit: ${configuration.name} (${notificationCount}/${maxNotificationsPerHour})")
            return
        }
        
        if (currentTime - lastNotificationTime < notificationRateLimit) {
            sendDebugLog("⏱️ Too frequent: ${configuration.name} (${(currentTime - lastNotificationTime)/1000}s < ${notificationRateLimit/1000}s)")
            return
        }
        
        sendDebugLog("✅ Rate limit passed for: ${configuration.name} (count: $notificationCount/$maxNotificationsPerHour)")
        
        // Create an intent that will definitely open externally
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            // Force it to not use this app
            component = null
            setPackage(null)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 
            eventNotificationCounter++,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Clean up old notifications BEFORE checking duplicates and adding new ones
        cleanupOldNotifications()
        
        // Create a unique notification ID based on event ID to prevent duplicates
        // Use event ID hash combined with configuration ID to ensure uniqueness
        val notificationId = "${event.id}_${configuration.id}".hashCode()
        
        // Check if we already have a notification for this event
        if (activeNotifications.containsKey(notificationId)) {
            sendDebugLog("🔄 Skipping duplicate notification for event: ${event.id.take(8)}...")
            return
        }
        
        // Double check we have space for new notifications
        if (activeNotifications.size >= 20) {
            sendDebugLog("⚠️ Notification limit reached (${activeNotifications.size}/20), forcing cleanup")
            // Force cleanup and try again
            val oldestEntry = activeNotifications.toList().minByOrNull { it.second.timestamp }
            oldestEntry?.let { (oldNotificationId, _) ->
                activeNotifications.remove(oldNotificationId)
                notificationManager.cancel(oldNotificationId)
                sendDebugLog("🧹 Forced removal of oldest notification to make space")
            }
        }
        
        val notification = NotificationCompat.Builder(this, EVENT_CHANNEL_ID)
            .setContentTitle("New Event (${configuration.name})")
            .setContentText(event.getContentPreview())
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFEA8F70.toInt()) // Coral background color
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true) // Only alert once to reduce noise
            .setGroup(NOTIFICATION_GROUP_KEY) // Add explicit grouping back
            .build()
        
        // Track this notification
        activeNotifications[notificationId] = NotificationInfo(
            subscriptionId = subscriptionId,
            configurationName = configuration.name,
            eventContent = event.getContentPreview(),
            uri = uri,
            timestamp = currentTime
        )
        
        // Show the individual notification
        notificationManager.notify(notificationId, notification)
        
        // Create/update summary notification for proper grouping
        createSummaryNotification()
        
        lastNotificationTime = currentTime
        notificationCount++
        
        sendDebugLog("🔔 Notification sent: ${configuration.name} [Event: ${event.id.take(8)}...] (#$notificationCount) [${activeNotifications.size}/20]")
    }
    
    /**
     * Clean up old notifications to prevent memory leaks
     */
    private fun cleanupOldNotifications() {
        val maxNotifications = 20
        if (activeNotifications.size >= maxNotifications) {
            // Remove oldest notifications to make room
            val sortedNotifications = activeNotifications.toList().sortedBy { it.second.timestamp }
            val toRemove = sortedNotifications.take(activeNotifications.size - maxNotifications + 1)
            
            toRemove.forEach { (notificationId, notificationInfo) ->
                activeNotifications.remove(notificationId)
                notificationManager.cancel(notificationId)
                sendDebugLog("🗑️ Removed notification: ${notificationInfo.configurationName} [${notificationInfo.eventContent.take(20)}...]")
            }
            
            if (toRemove.isNotEmpty()) {
                sendDebugLog("🧹 Cleaned up ${toRemove.size} old notifications (${activeNotifications.size}/${maxNotifications} remaining)")
            }
        }
    }

    /**
     * Create or update the summary notification for grouped notifications
     * This allows tapping the grouped notification to expand rather than open the app
     */
    private fun createSummaryNotification() {
        val notificationCount = activeNotifications.size
        
        if (notificationCount <= 1) {
            // Remove summary notification if only one or no notifications
            notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
            return
        }
        
        // Get the most recent notification info for summary content
        val recentNotifications = activeNotifications.values
            .sortedByDescending { it.timestamp }
            .take(3)
        
        val summaryText = when {
            notificationCount == 2 -> "2 new events"
            notificationCount > 2 -> "$notificationCount new events"
            else -> "New events"
        }
        
        val latestEvent = recentNotifications.firstOrNull()
        
        // Create inbox style for better grouped display
        val inboxStyle = NotificationCompat.InboxStyle()
            .setBigContentTitle(summaryText)
        
        // Add lines for recent notifications
        recentNotifications.forEach { notif ->
            inboxStyle.addLine("${notif.configurationName}: ${notif.eventContent}")
        }
        
        if (notificationCount > 3) {
            inboxStyle.setSummaryText("+ ${notificationCount - 3} more")
        }
        
        val summaryNotification = NotificationCompat.Builder(this, EVENT_CHANNEL_ID)
            .setContentTitle(summaryText)
            .setContentText(latestEvent?.let { "${it.configurationName}: ${it.eventContent}" } ?: "Multiple subscriptions")
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFEA8F70.toInt())
            .setGroup(NOTIFICATION_GROUP_KEY)
            .setGroupSummary(true) // This makes it the summary notification
            .setStyle(inboxStyle)
            .setAutoCancel(false) // Don't auto-cancel so it stays for expansion
            // Don't set contentIntent - let the system handle expansion/collapse
            .build()
        
        notificationManager.notify(SUMMARY_NOTIFICATION_ID, summaryNotification)
        
        sendDebugLog("📋 Summary notification updated: $notificationCount events")
    }

    
    private fun scheduleReconnect(connection: RelayConnection, configuration: Configuration) {
        connection.reconnectJob?.cancel()
        connection.reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            // Phase 2: Network-aware reconnection logic
            val networkAwareDecision = makeNetworkAwareReconnectionDecision(connection)
            
            if (!networkAwareDecision.shouldReconnect) {
                batteryOptimizationLogger.logOptimization(
                    category = BatteryOptimizationLogger.LogCategory.CONNECTION_HEALTH,
                    message = "Reconnection skipped",
                    data = mapOf(
                        "reason" to networkAwareDecision.reason,
                        "relay" to connection.relayUrl.substringAfter("://").take(20),
                        "attempts" to connection.reconnectAttempts,
                        "network_available" to isNetworkAvailable
                    )
                )
                sendDebugLog("⏸️ Skipping reconnection: ${networkAwareDecision.reason}")
                
                // Log reconnection skip for Phase 2 monitoring
                networkOptimizationLogger.logReconnectionDecision(
                    relayUrl = connection.relayUrl,
                    decision = "skipped",
                    reason = networkAwareDecision.reason,
                    attemptNumber = connection.reconnectAttempts + 1,
                    delayMs = 0L,
                    networkType = currentNetworkType,
                    networkQuality = networkQuality,
                    appState = currentAppState.name,
                    batteryLevel = batteryOptimizationLogger.getBatteryLevel()
                )
                
                return@launch
            }
            
            val delayMs = calculateOptimalReconnectDelay(connection, networkAwareDecision)
            
            batteryOptimizationLogger.logOptimization(
                category = BatteryOptimizationLogger.LogCategory.CONNECTION_HEALTH,
                message = "Reconnection scheduled",
                data = mapOf(
                    "relay" to connection.relayUrl.substringAfter("://").take(20),
                    "delay_ms" to delayMs,
                    "attempt" to connection.reconnectAttempts + 1,
                    "network_type" to currentNetworkType,
                    "network_quality" to networkQuality,
                    "app_state" to currentAppState.name
                )
            )
            
            sendDebugLog("🔄 Reconnecting to ${connection.relayUrl.substringAfter("://").take(20)}... in ${delayMs}ms (attempt ${connection.reconnectAttempts + 1}, $currentNetworkType)")
            
            // Log reconnection attempt for Phase 2 monitoring
            networkOptimizationLogger.logReconnectionDecision(
                relayUrl = connection.relayUrl,
                decision = "attempted",
                reason = networkAwareDecision.reason,
                attemptNumber = connection.reconnectAttempts + 1,
                delayMs = delayMs,
                networkType = currentNetworkType,
                networkQuality = networkQuality,
                appState = currentAppState.name,
                batteryLevel = batteryOptimizationLogger.getBatteryLevel()
            )
            
            delay(delayMs)
            
            // Double-check network availability before attempting reconnection
            if (!isNetworkAvailable) {
                sendDebugLog("❌ Network unavailable during reconnect attempt, aborting")
                return@launch
            }
            
            connection.reconnectAttempts++
            batteryMetricsCollector.trackConnectionEvent("reconnect", connection.relayUrl, success = true)
            connectToRelay(connection.relayUrl, configuration)
        }
    }
    
    /**
     * Make network-aware decision about whether to attempt reconnection
     */
    private fun makeNetworkAwareReconnectionDecision(connection: RelayConnection): ReconnectionDecision {
        // Don't reconnect if no network
        if (!isNetworkAvailable) {
            return ReconnectionDecision(false, "network_unavailable")
        }
        
        // Don't reconnect if too many attempts
        if (connection.reconnectAttempts >= 10) {
            return ReconnectionDecision(false, "max_attempts_reached")
        }
        
        // Be more conservative on cellular networks
        if (currentNetworkType == "cellular" && networkQuality == "low") {
            if (connection.reconnectAttempts >= 5) {
                return ReconnectionDecision(false, "cellular_low_quality_limit")
            }
        }
        
        // Be more conservative in background/doze/standby modes
        if (currentAppState == AppState.DOZE && connection.reconnectAttempts >= 3) {
            return ReconnectionDecision(false, "doze_mode_limit")
        }
        
        if (currentAppState == AppState.RESTRICTED && connection.reconnectAttempts >= 2) {
            return ReconnectionDecision(false, "restricted_app_limit")
        }
        
        if (currentAppState == AppState.RARE && connection.reconnectAttempts >= 3) {
            return ReconnectionDecision(false, "rare_app_limit")
        }
        
        if (currentAppState == AppState.BACKGROUND && connection.reconnectAttempts >= 7) {
            return ReconnectionDecision(false, "background_mode_limit")
        }
        
        return ReconnectionDecision(true, "network_conditions_favorable")
    }
    
    /**
     * Calculate optimal reconnect delay based on network conditions and app state
     */
    private fun calculateOptimalReconnectDelay(
        connection: RelayConnection, 
        decision: ReconnectionDecision
    ): Long {
        val baseDelay = minOf(
            RECONNECT_DELAY_MS * (1 shl connection.reconnectAttempts),
            MAX_RECONNECT_DELAY_MS
        )
        
        var adjustedDelay = baseDelay
        
        // Adjust based on network type and quality
        when (currentNetworkType) {
            "cellular" -> {
                adjustedDelay = when (networkQuality) {
                    "low" -> (baseDelay * 2.0).toLong()  // Double delay for low quality cellular
                    "medium" -> (baseDelay * 1.5).toLong() // 50% longer for medium cellular
                    else -> baseDelay
                }
            }
            "wifi" -> {
                adjustedDelay = when (networkQuality) {
                    "high" -> (baseDelay * 0.8).toLong() // Faster reconnects on high quality WiFi
                    else -> baseDelay
                }
            }
        }
        
        // Adjust based on app state
        adjustedDelay = when (currentAppState) {
            AppState.FOREGROUND -> adjustedDelay // No adjustment for foreground
            AppState.BACKGROUND -> (adjustedDelay * 1.5).toLong() // 50% longer in background
            AppState.DOZE -> (adjustedDelay * 3.0).toLong() // 3x longer in doze mode
            AppState.RARE -> (adjustedDelay * 2.5).toLong() // 2.5x longer for rare apps
            AppState.RESTRICTED -> (adjustedDelay * 4.0).toLong() // 4x longer for restricted apps
        }
        
        return minOf(adjustedDelay, MAX_RECONNECT_DELAY_MS)
    }
    
    /**
     * Data class for reconnection decisions
     */
    private data class ReconnectionDecision(
        val shouldReconnect: Boolean,
        val reason: String
    )
    
    private fun disconnectFromAllRelays() {
        sendDebugLog("📡 Disconnecting from all relays")
        
        relayConnections.values.forEach { connection ->
            connection.reconnectJob?.cancel()
            connection.subscriptionId?.let { subId ->
                connection.webSocket?.send(NostrMessage.createClose(subId))
            }
            connection.webSocket?.close(1000, "Service stopping")
        }
        
        relayConnections.clear()
        sendDebugLog("📡 All relays disconnected")
    }
    
    /**
     * Clean up orphaned subscriptions that no longer have valid configurations
     */
    private fun cleanupOrphanedSubscriptions() {
        val validConfigurationIds = configurationManager.getConfigurations()
            .filter { it.isEnabled }
            .map { it.id }
            .toSet()
        
        subscriptionManager.cleanupOrphanedSubscriptions(validConfigurationIds)
        sendDebugLog("🧹 Cleaned up orphaned subscriptions")
    }
    
    /**
     * Get service statistics for debugging
     */
    private fun logServiceStats() {
        val subscriptionStats = subscriptionManager.getStats()
        val cacheStats = eventCache.getStats()
        
        sendDebugLog("📊 Service Stats:")
        sendDebugLog("   Active subscriptions: ${subscriptionStats.activeCount}")
        sendDebugLog("   Event cache: ${cacheStats}")
        sendDebugLog("   Relay connections: ${relayConnections.size}")
        
        // Update battery metrics and log Phase 1 effectiveness
        batteryMetricsCollector.updateBatteryMetrics()
        logPhase1Effectiveness()
        
        // Check app standby bucket periodically
        checkStandbyBucket()
    }
    
    /**
     * Log Phase 1 battery optimization effectiveness
     */
    private fun logPhase1Effectiveness() {
        try {
            val report = batteryMetricsCollector.generatePhase1Report()
            
            sendDebugLog("🔋 Phase 1 Effectiveness: ${report.phase1Effectiveness}")
            sendDebugLog("   Ping frequency reduction: ${String.format("%.1f", report.pingFrequencyReduction)}%")
            sendDebugLog("   Connection stability: ${String.format("%.1f", report.connectionStability)}%")
            sendDebugLog("   Battery improvement: ${String.format("%.1f", report.batteryDrainImprovement)}%")
            sendDebugLog("   Network activity reduction: ${String.format("%.1f", report.networkActivityReduction)}%")
            
            // Log detailed metrics for analysis
            batteryOptimizationLogger.logOptimization(
                category = BatteryOptimizationLogger.LogCategory.OPTIMIZATION_DECISIONS,
                message = "Phase 1 effectiveness report",
                data = mapOf(
                    "effectiveness" to report.phase1Effectiveness,
                    "ping_reduction_percent" to report.pingFrequencyReduction,
                    "connection_stability_percent" to report.connectionStability,
                    "battery_improvement_percent" to report.batteryDrainImprovement,
                    "network_reduction_percent" to report.networkActivityReduction,
                    "app_state_transitions" to report.appStateTransitions,
                    "collection_duration_ms" to report.collectionDuration
                )
            )
        } catch (e: Exception) {
            sendDebugLog("❌ Error generating Phase 1 effectiveness report: ${e.message}")
            Log.e(TAG, "Phase 1 effectiveness report failed", e)
        }
    }
    
    /**
     * Synchronize active subscriptions with current enabled configurations
     * This handles adding new subscriptions and removing disabled ones
     */
    private fun syncConfigurations() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendDebugLog("🔄 Synchronizing configurations with active subscriptions...")
                
                val enabledConfigurations = configurationManager.getEnabledConfigurations()
                val currentRelayUrls = relayConnections.keys.toSet()
                
                // Build set of relay URLs that should be active based on enabled configurations
                val expectedRelayUrls = mutableSetOf<String>()
                enabledConfigurations.forEach { config ->
                    expectedRelayUrls.addAll(config.relayUrls)
                }
            
            // Remove connections for disabled configurations
            val relaysToRemove = currentRelayUrls - expectedRelayUrls
            if (relaysToRemove.isNotEmpty()) {
                sendDebugLog("🗑️ Removing ${relaysToRemove.size} disabled subscription(s)")
                relaysToRemove.forEach { relayUrl ->
                    try {
                        val connection = relayConnections[relayUrl]
                        if (connection != null) {
                            // Send close message if subscription exists
                            connection.subscriptionId?.let { subId ->
                                try {
                                    connection.webSocket?.send(NostrMessage.createClose(subId))
                                    subscriptionManager.removeSubscription(subId)
                                    sendDebugLog("🛑 Closed subscription: ${subId.take(8)}... for $relayUrl")
                                } catch (e: Exception) {
                                    sendDebugLog("⚠️ Error closing subscription $subId: ${e.message}")
                                }
                            }
                            
                            // Cancel reconnect job and close connection
                            try {
                                connection.reconnectJob?.cancel()
                                connection.webSocket?.close(1000, "Configuration disabled")
                                relayConnections.remove(relayUrl)
                                sendDebugLog("❌ Removed connection: $relayUrl")
                            } catch (e: Exception) {
                                sendDebugLog("⚠️ Error removing connection $relayUrl: ${e.message}")
                                // Still remove from map even if close failed
                                relayConnections.remove(relayUrl)
                            }
                        }
                    } catch (e: Exception) {
                        sendDebugLog("⚠️ Error processing removal of $relayUrl: ${e.message}")
                    }
                }
            }
            
            // Add connections for newly enabled configurations
            val relaysToAdd = expectedRelayUrls - currentRelayUrls
            if (relaysToAdd.isNotEmpty()) {
                sendDebugLog("➕ Adding ${relaysToAdd.size} new subscription(s)")
                enabledConfigurations.forEach { config ->
                    config.relayUrls.forEach { relayUrl ->
                        if (relayUrl in relaysToAdd) {
                            try {
                                connectToRelay(relayUrl, config)
                                sendDebugLog("✅ Added connection: $relayUrl for ${config.name}")
                            } catch (e: Exception) {
                                sendDebugLog("⚠️ Error adding connection $relayUrl: ${e.message}")
                            }
                        }
                    }
                }
            }
            
            // Update existing connections that might have configuration changes
            val existingRelays = currentRelayUrls.intersect(expectedRelayUrls)
            existingRelays.forEach { relayUrl ->
                try {
                    val connection = relayConnections[relayUrl]
                    val currentConfig = enabledConfigurations.find { it.relayUrls.contains(relayUrl) }
                    
                    if (connection != null && currentConfig != null) {
                        // Check if this connection's configuration has changed
                        if (connection.configurationId != currentConfig.id) {
                            sendDebugLog("🔄 Configuration changed for $relayUrl, resubscribing...")
                            
                            // Close old subscription
                            connection.subscriptionId?.let { subId ->
                                try {
                                    connection.webSocket?.send(NostrMessage.createClose(subId))
                                    subscriptionManager.removeSubscription(subId)
                                } catch (e: Exception) {
                                    sendDebugLog("⚠️ Error closing old subscription: ${e.message}")
                                }
                            }
                            
                            // Update connection config ID and resubscribe
                            try {
                                val updatedConnection = connection.copy(configurationId = currentConfig.id)
                                relayConnections[relayUrl] = updatedConnection
                                subscribeToEvents(updatedConnection, currentConfig)
                            } catch (e: Exception) {
                                sendDebugLog("⚠️ Error resubscribing to $relayUrl: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    sendDebugLog("⚠️ Error updating existing connection $relayUrl: ${e.message}")
                }
            }
            
            // Clean up any orphaned subscriptions
            cleanupOrphanedSubscriptions()
            
                // Log updated stats
                logServiceStats()
                sendDebugLog("✅ Configuration sync completed")
            } catch (e: Exception) {
                sendDebugLog("❌ Error during configuration sync: ${e.message}")
                Log.e(TAG, "syncConfigurations failed", e)
                // Don't crash the service, just log the error
            }
        }
    }

    /**
     * Refresh all WebSocket connections - useful when app is reopened after reinstall/restart
     * Enhanced to also sync configurations
     */
    private fun refreshConnections() {
        CoroutineScope(Dispatchers.IO).launch {
            sendDebugLog("🔄 Refreshing all WebSocket connections...")
            
            // First, sync configurations to ensure we have the right subscriptions
            syncConfigurations()
            
            // Then check connection health for remaining connections
            val staleConnections = mutableListOf<String>()
            
            relayConnections.forEach { (relayUrl, connection) ->
                val webSocket = connection.webSocket
                if (webSocket == null) {
                    sendDebugLog("❌ $relayUrl: No WebSocket instance")
                    staleConnections.add(relayUrl)
                } else {
                    // Try to send a ping to test the connection
                    try {
                        // Check if WebSocket is in OPEN state
                        val isHealthy = isWebSocketHealthy(webSocket)
                        if (!isHealthy) {
                            sendDebugLog("💔 $relayUrl: Connection appears stale")
                            staleConnections.add(relayUrl)
                        } else {
                            sendDebugLog("✅ $relayUrl: Connection appears healthy")
                        }
                    } catch (e: Exception) {
                        sendDebugLog("❌ $relayUrl: Health check failed - ${e.message}")
                        staleConnections.add(relayUrl)
                    }
                }
            }
            
            // Reconnect stale connections
            if (staleConnections.isNotEmpty()) {
                sendDebugLog("🔧 Reconnecting ${staleConnections.size} stale connections...")
                
                staleConnections.forEach { relayUrl ->
                    val connection = relayConnections[relayUrl]
                    if (connection != null) {
                        // Cancel any existing reconnect job
                        connection.reconnectJob?.cancel()
                        
                        // Close old connection if it exists
                        connection.webSocket?.close(1000, "Refreshing connection")
                        
                        // Reset connection state
                        connection.webSocket = null
                        connection.reconnectAttempts = 0
                        
                        // Find the configuration for this relay
                        val configuration = configurationManager.getEnabledConfigurations()
                            .find { config -> config.relayUrls.contains(relayUrl) }
                        
                        if (configuration != null) {
                            // Reconnect
                            connectToRelay(relayUrl, configuration)
                        } else {
                            sendDebugLog("⚠️ No configuration found for $relayUrl, removing connection")
                            relayConnections.remove(relayUrl)
                        }
                    }
                }
            } else {
                sendDebugLog("✅ All connections appear healthy")
            }
            
            // Log updated stats
            logServiceStats()
        }
    }
    
    /**
     * Check if a WebSocket connection is healthy
     */
    private fun isWebSocketHealthy(webSocket: WebSocket): Boolean {
        return try {
            // Try to send a ping frame - this will fail if connection is dead
            webSocket.send("")  // Empty string is a valid WebSocket message
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun sendDebugLog(message: String) {
        Log.d(TAG, message)
        
        // Send broadcast to MainActivity for real-time display
        val intent = Intent(MainActivity.ACTION_DEBUG_LOG).apply {
            putExtra(MainActivity.EXTRA_LOG_MESSAGE, message)
        }
        sendBroadcast(intent)
    }
}
