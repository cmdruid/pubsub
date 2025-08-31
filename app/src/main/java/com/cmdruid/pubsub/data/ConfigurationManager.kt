package com.cmdruid.pubsub.data

import android.content.Context
import android.content.SharedPreferences
import com.cmdruid.pubsub.nostr.NostrFilter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages multiple configurations using SharedPreferences with JSON serialization
 */
class ConfigurationManager(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "pubsub_configurations"
        private const val KEY_CONFIGURATIONS = "configurations"
        private const val KEY_SERVICE_RUNNING = "service_running"
        private const val KEY_DEBUG_LOGS = "debug_logs"
        
        // Migration from old preferences
        private const val OLD_PREFS_NAME = "pubsub_prefs"
        private const val OLD_KEY_RELAY_URL = "relay_url"
        private const val OLD_KEY_PUBKEY = "pubkey"
        private const val OLD_KEY_TARGET_URI = "target_uri"
        private const val OLD_KEY_DIRECT_MODE = "direct_mode"
    }
    
    private val sharedPrefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val oldPrefs: SharedPreferences = 
        context.getSharedPreferences(OLD_PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    init {
        migrateOldPreferences()
    }
    
    /**
     * Get configuration by ID
     */
    fun getConfigurationById(id: String): Configuration? {
        return getConfigurations().find { it.id == id }
    }
    
    /**
     * Get all configurations
     */
    fun getConfigurations(): List<Configuration> {
        val json = sharedPrefs.getString(KEY_CONFIGURATIONS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Configuration>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Save all configurations
     */
    fun saveConfigurations(configurations: List<Configuration>) {
        val json = gson.toJson(configurations)
        sharedPrefs.edit().putString(KEY_CONFIGURATIONS, json).apply()
    }
    
    /**
     * Add a new configuration
     */
    fun addConfiguration(configuration: Configuration) {
        val configurations = getConfigurations().toMutableList()
        configurations.add(configuration)
        saveConfigurations(configurations)
    }
    
    /**
     * Update an existing configuration
     */
    fun updateConfiguration(configuration: Configuration) {
        val configurations = getConfigurations().toMutableList()
        val index = configurations.indexOfFirst { it.id == configuration.id }
        if (index != -1) {
            configurations[index] = configuration
            saveConfigurations(configurations)
        }
    }
    
    /**
     * Delete a configuration
     */
    fun deleteConfiguration(configurationId: String) {
        val configurations = getConfigurations().toMutableList()
        configurations.removeAll { it.id == configurationId }
        saveConfigurations(configurations)
    }
    
    /**
     * Get enabled configurations
     */
    fun getEnabledConfigurations(): List<Configuration> {
        return getConfigurations().filter { it.isEnabled }
    }
    
    /**
     * Check if there are any valid enabled configurations
     */
    fun hasValidEnabledConfigurations(): Boolean {
        return getEnabledConfigurations().any { it.isValid() }
    }
    
    /**
     * Service running state
     */
    var isServiceRunning: Boolean
        get() = sharedPrefs.getBoolean(KEY_SERVICE_RUNNING, false)
        set(value) = sharedPrefs.edit().putBoolean(KEY_SERVICE_RUNNING, value).apply()
    
    /**
     * Debug logs for display in UI
     */
    var debugLogs: List<String>
        get() {
            val json = sharedPrefs.getString(KEY_DEBUG_LOGS, null) ?: return emptyList()
            return try {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val json = gson.toJson(value)
            sharedPrefs.edit().putString(KEY_DEBUG_LOGS, json).apply()
        }
    
    /**
     * Add a debug log entry
     */
    fun addDebugLog(message: String) {
        // Limit message length to prevent extremely long entries
        val truncatedMessage = if (message.length > 500) {
            message.take(497) + "..."
        } else {
            message
        }
        
        val logs = debugLogs.toMutableList()
        logs.add(0, "${System.currentTimeMillis()}: $truncatedMessage") // Add to beginning
        
        // Keep only last 100 logs for comprehensive debugging
        while (logs.size > 100) {
            logs.removeAt(logs.size - 1)
        }
        
        debugLogs = logs
    }
    
    /**
     * Clear debug logs
     */
    fun clearDebugLogs() {
        debugLogs = emptyList()
    }
    
    /**
     * Get debug log statistics
     */
    fun getDebugLogStats(): String {
        val logs = debugLogs
        val totalSize = logs.sumOf { it.length }
        return "${logs.size}/100 logs, ${totalSize} chars"
    }
    
    /**
     * Get formatted debug logs for export
     */
    fun getFormattedDebugLogsForExport(): String {
        val logs = debugLogs
        if (logs.isEmpty()) {
            return "No debug logs available."
        }
        
        val sb = StringBuilder()
        sb.appendLine("PubSub Debug Logs Export")
        sb.appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        sb.appendLine("Total logs: ${logs.size}")
        sb.appendLine("=".repeat(50))
        sb.appendLine()
        
        logs.reversed().forEach { log ->
            // Parse timestamp and message
            val parts = log.split(": ", limit = 2)
            if (parts.size == 2) {
                val timestamp = try {
                    val millis = parts[0].toLong()
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(millis))
                } catch (e: Exception) {
                    parts[0]
                }
                sb.appendLine("[$timestamp] ${parts[1]}")
            } else {
                sb.appendLine(log)
            }
        }
        
        return sb.toString()
    }
    
    /**
     * Clear all data
     */
    fun clear() {
        sharedPrefs.edit().clear().apply()
    }
    
    /**
     * Migrate from old single-configuration format
     */
    private fun migrateOldPreferences() {
        if (getConfigurations().isNotEmpty()) return // Already have new format
        
        val relayUrl = oldPrefs.getString(OLD_KEY_RELAY_URL, null)
        val pubkey = oldPrefs.getString(OLD_KEY_PUBKEY, null)
        val targetUri = oldPrefs.getString(OLD_KEY_TARGET_URI, null)
        
        if (!relayUrl.isNullOrBlank() && !pubkey.isNullOrBlank() && !targetUri.isNullOrBlank()) {
            val filter = NostrFilter.forMentions(pubkey)
            val configuration = Configuration(
                name = "Migrated Subscription",
                relayUrls = listOf(relayUrl),
                filter = filter,
                targetUri = targetUri
            )
            addConfiguration(configuration)
            
            // Clear old preferences
            oldPrefs.edit().clear().apply()
        }
    }
}
