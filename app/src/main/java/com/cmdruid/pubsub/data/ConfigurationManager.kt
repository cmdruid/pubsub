package com.cmdruid.pubsub.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.cmdruid.pubsub.nostr.NostrFilter
import com.cmdruid.pubsub.logging.StructuredLogEntry
import com.cmdruid.pubsub.logging.LogFilter
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
        private const val KEY_STRUCTURED_LOGS = "structured_logs"
        

    }
    
    private val sharedPrefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val gson = Gson()
    

    
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
     * Structured debug logs for display in UI
     */
    var structuredLogs: List<StructuredLogEntry>
        get() {
            val json = sharedPrefs.getString(KEY_STRUCTURED_LOGS, null) ?: return emptyList()
            return try {
                val type = object : TypeToken<List<StructuredLogEntry>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val json = gson.toJson(value)
            sharedPrefs.edit().putString(KEY_STRUCTURED_LOGS, json).apply()
        }
    
    /**
     * Add a structured log entry (optimized for background thread)
     */
    fun addStructuredLog(entry: StructuredLogEntry) {
        try {
            val logs = structuredLogs.toMutableList()
            logs.add(0, entry) // Add to beginning
            
            // Keep only last 100 logs for comprehensive debugging
            while (logs.size > 100) {
                logs.removeAt(logs.size - 1)
            }
            
            structuredLogs = logs
        } catch (e: Exception) {
            // Fail silently to avoid crashing on storage issues
            Log.w("ConfigurationManager", "Failed to store log entry: ${e.message}")
        }
    }
    
    /**
     * Get filtered structured logs
     */
    fun getFilteredLogs(filter: LogFilter): List<StructuredLogEntry> {
        return structuredLogs.filter { filter.passes(it) }.take(filter.maxLogs)
    }
    
    /**
     * Clear structured logs
     */
    fun clearStructuredLogs() {
        structuredLogs = emptyList()
    }
    
    /**
     * Get debug log statistics
     */
    fun getDebugLogStats(): String {
        val logs = structuredLogs
        return "${logs.size}/100 logs"
    }
    
    /**
     * Get quick log count without parsing (for performance)
     */
    fun getLogCount(): Int {
        val json = sharedPrefs.getString(KEY_STRUCTURED_LOGS, null) ?: return 0
        // Quick count by checking if we have data without parsing
        return if (json.length > 10) {
            // Rough estimate based on JSON length (much faster than parsing)
            kotlin.math.min(100, kotlin.math.max(0, (json.length - 2) / 200))
        } else {
            0
        }
    }
    
    /**
     * Get formatted structured logs for export with comprehensive diagnostics
     */
    fun getFormattedDebugLogsForExport(filter: LogFilter? = null): String {
        val logs = if (filter != null) getFilteredLogs(filter) else structuredLogs
        val timestamp = System.currentTimeMillis()
        val dateFormatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        
        val sb = StringBuilder()
        sb.appendLine("PubSub Structured Debug Logs Export")
        sb.appendLine("Generated: ${dateFormatter.format(java.util.Date(timestamp))}")
        sb.appendLine("Total logs: ${logs.size}")
        if (filter != null) {
            sb.appendLine("Filter: Types=${filter.enabledTypes.joinToString(",") { it.name }}, Domains=${filter.enabledDomains.joinToString(",") { it.name }}")
        }
        sb.appendLine("=".repeat(80))
        sb.appendLine()
        
        // BASIC: Add essential device and system information
        sb.appendLine("📱 DEVICE INFORMATION:")
        sb.appendLine("   Export Timestamp: $timestamp")
        sb.appendLine("   App Version: 0.9.7")
        sb.appendLine("   Android Version: ${android.os.Build.VERSION.RELEASE}")
        sb.appendLine("   Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine("   Total Configurations: ${getConfigurations().size}")
        sb.appendLine("   Enabled Configurations: ${getEnabledConfigurations().size}")
        sb.appendLine()
        
        // Basic log statistics
        val logsByType = logs.groupBy { it.type }.mapValues { it.value.size }
        val logsByDomain = logs.groupBy { it.domain }.mapValues { it.value.size }
        sb.appendLine("📈 LOG SUMMARY:")
        sb.appendLine("   By Type: $logsByType")
        sb.appendLine("   By Domain: $logsByDomain")
        sb.appendLine()
        
        sb.appendLine("=".repeat(80))
        sb.appendLine()
        
        // Original logs
        logs.reversed().forEach { entry ->
            sb.appendLine(entry.toDetailedString())
        }
        
        return sb.toString()
    }
    
    /**
     * Clear all data
     */
    fun clear() {
        sharedPrefs.edit().clear().apply()
    }
    

}
