package com.cmdruid.pubsub.service

import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.cmdruid.pubsub.data.BatteryMode
import com.cmdruid.pubsub.data.NotificationFrequency
import com.cmdruid.pubsub.data.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages battery optimization, power states, and ping interval calculations
 * Extracted from PubSubService to improve maintainability
 */
class BatteryPowerManager(
    private val context: Context,
    private val batteryOptimizationLogger: BatteryOptimizationLogger,
    private val batteryMetricsCollector: BatteryMetricsCollector,
    private val settingsManager: SettingsManager,
    private val onAppStateChange: (PubSubService.AppState, Long) -> Unit,
    private val onPingIntervalChange: () -> Unit,
    private val sendDebugLog: (String) -> Unit
) : SettingsManager.SettingsChangeListener {
    
    companion object {
        private const val TAG = "BatteryPowerManager"
        
        // Battery optimization: Different ping intervals based on app state
        const val PING_INTERVAL_FOREGROUND_SECONDS = 30L    // Active use
        const val PING_INTERVAL_BACKGROUND_SECONDS = 120L   // App in background  
        const val PING_INTERVAL_DOZE_SECONDS = 180L         // Device in doze mode
        
        // Battery level optimization: Additional intervals for low battery scenarios
        const val PING_INTERVAL_LOW_BATTERY_SECONDS = 300L   // Low battery (≤30%)
        const val PING_INTERVAL_CRITICAL_BATTERY_SECONDS = 600L // Critical battery (≤15%)
        
        // App standby optimization: Intervals for standby buckets
        const val PING_INTERVAL_RARE_SECONDS = 300L        // Rare usage (5 minutes)
        const val PING_INTERVAL_RESTRICTED_SECONDS = 300L   // Restricted (5 minutes)
        
        // Battery level thresholds
        const val BATTERY_LEVEL_CRITICAL = 15 // 15% - ultra-conservative mode
        const val BATTERY_LEVEL_LOW = 30      // 30% - conservative mode  
        const val BATTERY_LEVEL_HIGH = 80     // 80% - can be more aggressive when charging
    }
    
    // Power management
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockAcquiredTime: Long = 0
    private var wakeLockReason: String = ""
    private val wakeLockTimeoutMs = 30000L // 30 seconds max hold time

    // Doze mode detection
    private var isDozeMode: Boolean = false
    private var dozeStateChangedTime: Long = System.currentTimeMillis()

    // Battery level awareness
    private var currentBatteryLevel: Int = 100
    private var previousBatteryLevel: Int = 100
    private var isCharging: Boolean = false
    private var batteryLevelChangedTime: Long = System.currentTimeMillis()
    private var lastBatteryOptimizationTime: Long = 0L

    // App standby bucket awareness
    private var currentStandbyBucket: Int = UsageStatsManager.STANDBY_BUCKET_ACTIVE
    private var previousStandbyBucket: Int = UsageStatsManager.STANDBY_BUCKET_ACTIVE
    private var standbyBucketChangedTime: Long = System.currentTimeMillis()
    private var lastStandbyCheckTime: Long = 0L
    private lateinit var usageStatsManager: UsageStatsManager
    
    // App state tracking
    private var currentAppState: PubSubService.AppState = PubSubService.AppState.FOREGROUND
    private var appStateChangedTime: Long = System.currentTimeMillis()
    private var currentPingInterval: Long = PING_INTERVAL_FOREGROUND_SECONDS
    
    // Doze mode broadcast receiver
    private val dozeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    handleDozeStateChange()
                }
            }
        }
    }

    // Battery level broadcast receiver
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
    
    /**
     * Initialize battery and power management
     */
    fun initialize() {
        setupPowerManagement()
        setupDozeDetection()
        setupBatteryMonitoring()
        setupStandbyMonitoring()
        registerReceivers()
        
        // Register for settings changes
        settingsManager.addSettingsChangeListener(this)
    }
    
    /**
     * Clean up resources and unregister receivers
     */
    fun cleanup() {
        releaseWakeLock()
        unregisterReceivers()
        
        // Unregister settings listener
        settingsManager.removeSettingsChangeListener(this)
    }
    
    // Getters for current state
    fun getCurrentAppState(): PubSubService.AppState = currentAppState
    fun getCurrentPingInterval(): Long = currentPingInterval
    fun getCurrentBatteryLevel(): Int = currentBatteryLevel
    fun isCharging(): Boolean = isCharging
    fun isDozeMode(): Boolean = isDozeMode
    
    /**
     * Setup power management
     */
    private fun setupPowerManagement() {
        powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        
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
     * Setup doze mode detection and initial state
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
        if (isDozeMode && currentAppState != PubSubService.AppState.DOZE) {
            val previousState = currentAppState
            currentAppState = PubSubService.AppState.DOZE
            updatePingInterval()
            
            batteryOptimizationLogger.logOptimization(
                category = BatteryOptimizationLogger.LogCategory.APP_STATE,
                message = "Initial doze mode detected",
                data = mapOf(
                    "previous_state" to previousState.name,
                    "current_state" to PubSubService.AppState.DOZE.name,
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
     * Setup battery level monitoring and initial state
     */
    private fun setupBatteryMonitoring() {
        // Get initial battery status
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
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
     * Setup app standby bucket monitoring and initial state
     */
    private fun setupStandbyMonitoring() {
        // Initialize UsageStatsManager
        usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        
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
     * Register broadcast receivers
     */
    private fun registerReceivers() {
        // Register for doze mode change broadcasts
        val dozeFilter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(dozeReceiver, dozeFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(dozeReceiver, dozeFilter)
        }
        
        // Register for battery level and charging state broadcasts
        val batteryFilter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(batteryReceiver, batteryFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(batteryReceiver, batteryFilter)
        }
    }
    
    /**
     * Unregister broadcast receivers
     */
    private fun unregisterReceivers() {
        try {
            context.unregisterReceiver(dozeReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering doze receiver: ${e.message}")
        }
        
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering battery receiver: ${e.message}")
        }
    }
    
    /**
     * Handle app state changes from MainActivity
     */
    fun handleAppStateChange(newState: PubSubService.AppState, previousStateDuration: Long) {
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
            
            // Notify service of the change
            onAppStateChange(newState, previousStateDuration)
        }
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
            isDozeMode -> PubSubService.AppState.DOZE
            currentAppState == PubSubService.AppState.DOZE -> PubSubService.AppState.BACKGROUND // Exit doze to background
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
            
            // Notify service of the change
            onAppStateChange(newAppState, previousStateDuration)
        }
        
        // Track doze mode effectiveness
        batteryMetricsCollector.trackNetworkActivity(
            eventType = if (isDozeMode) "doze_mode_entered" else "doze_mode_exited",
            optimized = true
        )
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
        val newPingInterval = calculateOptimalPingInterval()
        
        if (oldPingInterval != newPingInterval) {
            lastBatteryOptimizationTime = currentTime
            currentPingInterval = newPingInterval
            onPingIntervalChange()
            
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
     * Check and handle app standby bucket changes
     */
    fun checkStandbyBucket() {
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
            isDozeMode -> PubSubService.AppState.DOZE // Doze mode takes highest priority
            currentStandbyBucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> PubSubService.AppState.RESTRICTED
            currentStandbyBucket == UsageStatsManager.STANDBY_BUCKET_RARE -> PubSubService.AppState.RARE
            currentAppState == PubSubService.AppState.FOREGROUND -> PubSubService.AppState.FOREGROUND // Don't override foreground
            else -> PubSubService.AppState.BACKGROUND // Default to background
        }
        
        if (newAppState != currentAppState) {
            val oldPingInterval = currentPingInterval
            currentAppState = newAppState
            updatePingInterval()
            
            val optimizationReason = when (newAppState) {
                PubSubService.AppState.RESTRICTED -> "app_standby_restricted"
                PubSubService.AppState.RARE -> "app_standby_rare"
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
            
            // Notify service of the change
            onAppStateChange(newAppState, System.currentTimeMillis() - appStateChangedTime)
        }
    }
    
    /**
     * Update ping interval based on current app state
     */
    private fun updatePingInterval() {
        val oldInterval = currentPingInterval
        val newInterval = calculateOptimalPingInterval()
        
        if (oldInterval != newInterval) {
            currentPingInterval = newInterval
            onPingIntervalChange()
            
            sendDebugLog("🔋 Ping interval updated: ${oldInterval}s → ${newInterval}s (${currentAppState.name})")
        }
    }
    
    /**
     * Calculate optimal ping interval based on app state and battery conditions
     */
    private fun calculateOptimalPingInterval(): Long {
        // Get ping intervals from settings based on current battery mode
        val pingIntervals = settingsManager.getCurrentPingIntervals()
        
        // Start with base interval based on app state
        val baseInterval = when (currentAppState) {
            PubSubService.AppState.FOREGROUND -> pingIntervals.foreground
            PubSubService.AppState.BACKGROUND -> pingIntervals.background
            PubSubService.AppState.DOZE -> pingIntervals.doze
            PubSubService.AppState.RARE -> pingIntervals.rare
            PubSubService.AppState.RESTRICTED -> pingIntervals.restricted
        }
        
        // Apply battery level optimization
        val batteryOptimizedInterval = when {
            currentBatteryLevel <= BATTERY_LEVEL_CRITICAL -> {
                // Critical battery - use ultra-conservative interval regardless of state
                maxOf(baseInterval, pingIntervals.criticalBattery)
            }
            currentBatteryLevel <= BATTERY_LEVEL_LOW -> {
                // Low battery - use conservative interval regardless of state
                maxOf(baseInterval, pingIntervals.lowBattery)
            }
            isCharging && currentBatteryLevel >= BATTERY_LEVEL_HIGH -> {
                // High battery and charging - can be more aggressive in foreground
                if (currentAppState == PubSubService.AppState.FOREGROUND) {
                    minOf(baseInterval, pingIntervals.foreground / 2) // Half the foreground interval when charging
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
     * Acquire wake lock for critical operations with automatic timeout
     */
    fun acquireWakeLock(reason: String, durationMs: Long = wakeLockTimeoutMs) {
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
    fun releaseWakeLock() {
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
    fun isWakeLockHeld(): Boolean {
        return wakeLock?.isHeld == true
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
    
    // SettingsChangeListener implementation
    override fun onBatteryModeChanged(newMode: BatteryMode) {
        sendDebugLog("🔋 Battery mode changed to: ${newMode.displayName}")
        
        // Recalculate ping interval with new battery mode
        val oldInterval = currentPingInterval
        val newInterval = calculateOptimalPingInterval()
        
        if (oldInterval != newInterval) {
            currentPingInterval = newInterval
            onPingIntervalChange()
            
            sendDebugLog("🔋 Ping interval updated due to battery mode change: ${oldInterval}s → ${newInterval}s")
            
            batteryOptimizationLogger.logOptimization(
                category = BatteryOptimizationLogger.LogCategory.OPTIMIZATION_DECISIONS,
                message = "Battery mode changed by user",
                data = mapOf(
                    "new_mode" to newMode.displayName,
                    "old_ping_interval" to "${oldInterval}s",
                    "new_ping_interval" to "${newInterval}s",
                    "app_state" to currentAppState.name
                )
            )
        }
    }
    
    override fun onNotificationFrequencyChanged(newFrequency: NotificationFrequency) {
        // Battery manager doesn't need to handle notification frequency changes
        // This is handled by the EventNotificationManager
        sendDebugLog("🔔 Notification frequency changed to: ${newFrequency.displayName}")
    }
    
    override fun onDebugConsoleVisibilityChanged(visible: Boolean) {
        // Battery manager doesn't need to handle debug console visibility changes
        // This is handled by the UI components
        sendDebugLog("🐛 Debug console visibility changed to: $visible")
    }
}
