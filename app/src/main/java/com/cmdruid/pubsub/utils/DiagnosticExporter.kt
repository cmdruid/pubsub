package com.cmdruid.pubsub.utils

import android.content.Context
import com.cmdruid.pubsub.data.ConfigurationManager
import com.cmdruid.pubsub.data.SettingsManager
import com.cmdruid.pubsub.logging.LogFilter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Simple diagnostic export tool for immediate debugging capability
 * Provides comprehensive system state for agent analysis
 */
class DiagnosticExporter(
    private val context: Context,
    private val configurationManager: ConfigurationManager,
    private val settingsManager: SettingsManager
) {
    
    /**
     * Export comprehensive diagnostic information
     */
    fun exportDiagnostics(): String {
        val timestamp = System.currentTimeMillis()
        val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        
        val sb = StringBuilder()
        sb.appendLine("=".repeat(80))
        sb.appendLine("PUBSUB DIAGNOSTIC EXPORT FOR AGENT ANALYSIS")
        sb.appendLine("Generated: ${dateFormatter.format(Date(timestamp))}")
        sb.appendLine("=".repeat(80))
        sb.appendLine()
        
        // System Information
        sb.appendLine("📱 SYSTEM INFORMATION:")
        sb.appendLine("   App Version: 0.9.7")
        sb.appendLine("   Android Version: ${android.os.Build.VERSION.RELEASE}")
        sb.appendLine("   Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine("   System Time: $timestamp")
        sb.appendLine("   Timezone: ${TimeZone.getDefault().id}")
        sb.appendLine()
        
        // Battery State
        try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            sb.appendLine("🔋 BATTERY STATE:")
            sb.appendLine("   Level: ${batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)}%")
            sb.appendLine("   Charging: ${batteryManager.isCharging}")
            sb.appendLine("   Status: ${getBatteryStatus(batteryManager)}")
        } catch (e: Exception) {
            sb.appendLine("🔋 BATTERY STATE: Error retrieving battery info: ${e.message}")
        }
        sb.appendLine()
        
        // Network State
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork
            val networkInfo = connectivityManager.activeNetworkInfo
            
            sb.appendLine("🌐 NETWORK STATE:")
            sb.appendLine("   Network Available: ${activeNetwork != null}")
            sb.appendLine("   Network Type: ${networkInfo?.typeName ?: "unknown"}")
            sb.appendLine("   Network Subtype: ${networkInfo?.subtypeName ?: "unknown"}")
            sb.appendLine("   Is Connected: ${networkInfo?.isConnected ?: false}")
        } catch (e: Exception) {
            sb.appendLine("🌐 NETWORK STATE: Error retrieving network info: ${e.message}")
        }
        sb.appendLine()
        
        // Configuration State
        try {
            val allConfigurations = configurationManager.getEnabledConfigurations()
            sb.appendLine("📋 CONFIGURATION STATE:")
            sb.appendLine("   Total Enabled Configurations: ${allConfigurations.size}")
            
            allConfigurations.forEachIndexed { index, config ->
                sb.appendLine("   📌 Configuration ${index + 1}:")
                sb.appendLine("      ID: ${config.id}")
                sb.appendLine("      Name: ${config.name}")
                sb.appendLine("      Enabled: ${config.isEnabled}")
                sb.appendLine("      Subscription ID: ${config.subscriptionId}")
                sb.appendLine("      Relay URLs: ${config.relayUrls}")
                sb.appendLine("      Target URI: ${config.targetUri}")
                sb.appendLine("      Filter Valid: ${config.filter.isValid()}")
                sb.appendLine("      Filter: kinds=${config.filter.kinds}, since=${config.filter.since}")
            }
        } catch (e: Exception) {
            sb.appendLine("📋 CONFIGURATION STATE: Error retrieving configurations: ${e.message}")
        }
        sb.appendLine()
        
        // Debug Console State
        try {
            val logFilter = settingsManager.getLogFilter()
            sb.appendLine("🐛 DEBUG CONSOLE STATE:")
            sb.appendLine("   Filter Valid: ${logFilter.enabledTypes.isNotEmpty() && logFilter.enabledDomains.isNotEmpty()}")
            sb.appendLine("   Enabled Types: ${logFilter.enabledTypes.map { it.name }}")
            sb.appendLine("   Enabled Domains: ${logFilter.enabledDomains.map { it.name }}")
            sb.appendLine("   Max Logs: ${logFilter.maxLogs}")
            
            // Test filter functionality
            val testEntry = com.cmdruid.pubsub.logging.StructuredLogEntry(
                timestamp = System.currentTimeMillis(),
                type = com.cmdruid.pubsub.logging.LogType.ERROR,
                domain = com.cmdruid.pubsub.logging.LogDomain.SYSTEM,
                message = "Test message"
            )
            sb.appendLine("   Filter Passes Test Entry: ${logFilter.passes(testEntry)}")
            
        } catch (e: Exception) {
            sb.appendLine("🐛 DEBUG CONSOLE STATE: Error retrieving debug console state: ${e.message}")
        }
        sb.appendLine()
        
        // App Settings
        try {
            val settings = settingsManager.getSettings()
            sb.appendLine("⚙️ APP SETTINGS:")
            sb.appendLine("   Battery Mode: ${settings.batteryMode.displayName}")
            sb.appendLine("   Notification Frequency: ${settings.notificationFrequency.displayName}")
            sb.appendLine("   Show Debug Console: ${settings.showDebugConsole}")
            sb.appendLine("   Default Event Viewer: ${settings.defaultEventViewer}")
            sb.appendLine("   Default Relay Server: ${settings.defaultRelayServer}")
        } catch (e: Exception) {
            sb.appendLine("⚙️ APP SETTINGS: Error retrieving settings: ${e.message}")
        }
        sb.appendLine()
        
        // Memory Usage
        try {
            val runtime = Runtime.getRuntime()
            val totalMemory = runtime.totalMemory()
            val freeMemory = runtime.freeMemory()
            val usedMemory = totalMemory - freeMemory
            
            sb.appendLine("💾 MEMORY USAGE:")
            sb.appendLine("   Used: ${usedMemory / 1024 / 1024} MB")
            sb.appendLine("   Free: ${freeMemory / 1024 / 1024} MB")
            sb.appendLine("   Total: ${totalMemory / 1024 / 1024} MB")
            sb.appendLine("   Max: ${runtime.maxMemory() / 1024 / 1024} MB")
        } catch (e: Exception) {
            sb.appendLine("💾 MEMORY USAGE: Error retrieving memory info: ${e.message}")
        }
        sb.appendLine()
        
        sb.appendLine("=".repeat(80))
        sb.appendLine("END DIAGNOSTIC EXPORT")
        sb.appendLine("=".repeat(80))
        
        return sb.toString()
    }
    
    private fun getBatteryStatus(batteryManager: android.os.BatteryManager): String {
        return try {
            val status = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)
            when (status) {
                android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                android.os.BatteryManager.BATTERY_STATUS_FULL -> "Full"
                android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
                android.os.BatteryManager.BATTERY_STATUS_UNKNOWN -> "Unknown"
                else -> "Unknown ($status)"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
