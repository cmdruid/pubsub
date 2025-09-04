package com.cmdruid.pubsub.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.cmdruid.pubsub.logging.LogFilter
import com.cmdruid.pubsub.logging.LogType
import com.cmdruid.pubsub.logging.LogDomain
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.File

/**
 * Manages app settings using SharedPreferences with JSON serialization
 * Follows the same pattern as ConfigurationManager for consistency
 */
class SettingsManager(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "pubsub_app_settings"
        private const val KEY_SETTINGS = "app_settings"
        private const val KEY_LOG_FILTER = "log_filter"
    }
    
    private val sharedPrefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // Cache settings in memory to avoid repeated SharedPreferences reads
    private var cachedSettings: AppSettings? = null
    
    /**
     * Get current app settings
     */
    fun getSettings(): AppSettings {
        // Return cached settings if available
        cachedSettings?.let { return it }
        
        val json = sharedPrefs.getString(KEY_SETTINGS, null)
        
        val settings = if (json != null) {
            try {
                gson.fromJson(json, AppSettings::class.java) ?: AppSettings()
            } catch (e: JsonSyntaxException) {
                // If JSON is corrupted, return default settings
                AppSettings()
            }
        } else {
            // No settings saved yet, return defaults
            AppSettings()
        }
        
        // Cache and validate settings
        cachedSettings = settings.validated()
        return cachedSettings!!
    }
    
    /**
     * Save app settings
     */
    fun saveSettings(settings: AppSettings) {
        val validatedSettings = settings.validated()
        val json = gson.toJson(validatedSettings)
        
        sharedPrefs.edit()
            .putString(KEY_SETTINGS, json)
            .apply()
        
        // Update cache
        cachedSettings = validatedSettings
    }
    
    /**
     * Update a specific setting and save
     */
    fun updateBatteryMode(batteryMode: BatteryMode) {
        val currentSettings = getSettings()
        saveSettings(currentSettings.copy(batteryMode = batteryMode))
    }
    
    fun updateNotificationFrequency(frequency: NotificationFrequency) {
        val currentSettings = getSettings()
        saveSettings(currentSettings.copy(notificationFrequency = frequency))
    }
    
    fun updateDefaultEventViewer(eventViewer: String) {
        val currentSettings = getSettings()
        saveSettings(currentSettings.copy(defaultEventViewer = eventViewer))
    }
    
    fun updateDefaultRelayServer(relayServer: String) {
        val currentSettings = getSettings()
        saveSettings(currentSettings.copy(defaultRelayServer = relayServer))
    }
    
    fun updateShowDebugConsole(show: Boolean) {
        val currentSettings = getSettings()
        saveSettings(currentSettings.copy(showDebugConsole = show))
    }
    
    /**
     * Get ping intervals based on current battery mode
     */
    fun getCurrentPingIntervals(): PingIntervals {
        return getSettings().getPingIntervals()
    }
    
    /**
     * Get notification rate limit based on current frequency setting
     */
    fun getCurrentNotificationRateLimit(): Long {
        return getSettings().getNotificationRateLimit()
    }
    
    /**
     * Check if debug console should be visible
     */
    fun shouldShowDebugConsole(): Boolean {
        return getSettings().showDebugConsole
    }
    
    /**
     * Get default event viewer URL
     */
    fun getDefaultEventViewer(): String {
        return getSettings().defaultEventViewer
    }
    
    /**
     * Get default relay server URL
     */
    fun getDefaultRelayServer(): String {
        return getSettings().defaultRelayServer
    }
    
    /**
     * Reset all settings to defaults
     */
    fun resetToDefaults() {
        saveSettings(AppSettings())
    }
    
    /**
     * Clear all settings (mainly for testing)
     */
    fun clear() {
        sharedPrefs.edit().clear().apply()
        cachedSettings = null
    }
    
    /**
     * Check if URL is valid (basic validation)
     */
    fun isValidUrl(url: String): Boolean {
        return url.isNotBlank() && 
               (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("wss://") || url.startsWith("ws://"))
    }
    
    /**
     * Get settings summary for debugging
     */
    fun getSettingsSummary(): String {
        val settings = getSettings()
        return """
            Battery Mode: ${settings.batteryMode.displayName}
            Notification Frequency: ${settings.notificationFrequency.displayName}
            Default Event Viewer: ${settings.defaultEventViewer}
            Default Relay Server: ${settings.defaultRelayServer}
            Show Debug Console: ${settings.showDebugConsole}
        """.trimIndent()
    }
    
    /**
     * Interface for components that need to be notified of settings changes
     */
    interface SettingsChangeListener {
        fun onBatteryModeChanged(newMode: BatteryMode)
        fun onNotificationFrequencyChanged(newFrequency: NotificationFrequency)
        fun onDebugConsoleVisibilityChanged(visible: Boolean)
    }
    
    private val listeners = mutableSetOf<SettingsChangeListener>()
    
    /**
     * Register a listener for settings changes
     */
    fun addSettingsChangeListener(listener: SettingsChangeListener) {
        listeners.add(listener)
    }
    
    /**
     * Unregister a listener for settings changes
     */
    fun removeSettingsChangeListener(listener: SettingsChangeListener) {
        listeners.remove(listener)
    }
    
    /**
     * Save settings and notify listeners of changes
     */
    fun saveSettingsWithNotification(newSettings: AppSettings) {
        val oldSettings = getSettings()
        saveSettings(newSettings)
        
        // Notify listeners of specific changes
        if (oldSettings.batteryMode != newSettings.batteryMode) {
            listeners.forEach { it.onBatteryModeChanged(newSettings.batteryMode) }
        }
        
        if (oldSettings.notificationFrequency != newSettings.notificationFrequency) {
            listeners.forEach { it.onNotificationFrequencyChanged(newSettings.notificationFrequency) }
        }
        
        if (oldSettings.showDebugConsole != newSettings.showDebugConsole) {
            listeners.forEach { it.onDebugConsoleVisibilityChanged(newSettings.showDebugConsole) }
        }
    }
    
    /**
     * Save log filter preferences
     */
    fun saveLogFilter(filter: LogFilter) {
        try {
            // VALIDATION: Don't save corrupted filters
            if (!isValidLogFilter(filter)) {
                // Log the issue but don't save the corrupted filter
                return
            }
            
            val json = gson.toJson(filter)
            sharedPrefs.edit().putString(KEY_LOG_FILTER, json).apply()
        } catch (e: Exception) {
            // Fail silently if we can't save filter preferences
        }
    }
    
    /**
     * Load saved log filter preferences, or return default if none saved
     */
    fun getLogFilter(): LogFilter {
        return try {
            val json = sharedPrefs.getString(KEY_LOG_FILTER, null)
            if (json != null) {
                val loadedFilter = gson.fromJson(json, LogFilter::class.java)
                
                // VALIDATION: Ensure loaded filter is not corrupted
                if (loadedFilter != null && isValidLogFilter(loadedFilter)) {
                    loadedFilter
                } else {
                    // Corrupted filter detected - return default and clear the bad data
                    sharedPrefs.edit().remove(KEY_LOG_FILTER).apply()
                    LogFilter.DEFAULT
                }
            } else {
                LogFilter.DEFAULT
            }
        } catch (e: Exception) {
            // Return default filter if loading fails and clear corrupted data
            try {
                sharedPrefs.edit().remove(KEY_LOG_FILTER).apply()
            } catch (clearException: Exception) {
                // Ignore cleanup errors
            }
            LogFilter.DEFAULT
        }
    }
    
    /**
     * Validate that a log filter is not corrupted
     */
    private fun isValidLogFilter(filter: LogFilter): Boolean {
        return filter.enabledTypes.isNotEmpty() && 
               filter.enabledDomains.isNotEmpty() && 
               filter.maxLogs > 0
    }
    
    /**
     * Get current performance metrics settings
     */
    fun getPerformanceMetricsSettings(): PerformanceMetricsSettings {
        return getSettings().performanceMetrics
    }
    
    /**
     * Simple toggle for metrics on/off with data cleanup when disabled
     */
    fun setPerformanceMetricsEnabled(enabled: Boolean) {
        val currentSettings = getSettings()
        val newMetricsSettings = PerformanceMetricsSettings(enabled = enabled)
        saveSettingsWithNotification(currentSettings.copy(performanceMetrics = newMetricsSettings))
        
        // PRIVACY: When disabling metrics, delete all stored metrics data
        if (!enabled) {
            clearAllMetricsData()
        }
    }
    
    /**
     * Clear all stored metrics data from device storage
     */
    private fun clearAllMetricsData() {
        try {
            // Clear metrics-related SharedPreferences
            val metricsPrefs = context.getSharedPreferences("metrics_data", Context.MODE_PRIVATE)
            metricsPrefs.edit().clear().apply()
            
            val conditionalMetricsPrefs = context.getSharedPreferences("conditional_metrics", Context.MODE_PRIVATE)
            conditionalMetricsPrefs.edit().clear().apply()
            
            // Clear any cached metrics files
            val metricsDir = File(context.cacheDir, "metrics")
            if (metricsDir.exists()) {
                metricsDir.deleteRecursively()
            }
            
            val performanceMetricsDir = File(context.cacheDir, "performance_metrics")
            if (performanceMetricsDir.exists()) {
                performanceMetricsDir.deleteRecursively()
            }
            
            // Clear any temporary metrics logs
            val logFiles = context.filesDir.listFiles { file -> 
                file.name.startsWith("metrics_") || 
                file.name.contains("_metrics_") ||
                file.name.contains("performance") ||
                file.name.contains("battery_optimization") ||
                file.name.contains("network_optimization")
            }
            logFiles?.forEach { it.delete() }
            
            Log.i("SettingsManager", "All metrics data cleared from device storage")
        } catch (e: Exception) {
            Log.w("SettingsManager", "Failed to clear some metrics data: ${e.message}")
        }
    }
    
    /**
     * Check if metrics collection should be active (fast check)
     */
    fun isMetricsCollectionActive(): Boolean {
        return getPerformanceMetricsSettings().enabled
    }
    
    /**
     * Invalidate cached settings to force reload from storage
     * Call this when returning from settings screen
     */
    fun invalidateCache() {
        cachedSettings = null
    }
}
