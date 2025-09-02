package com.cmdruid.pubsub.logging

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

/**
 * Structured log entry with type, domain, timestamp and contextual data
 */
data class StructuredLogEntry(
    val timestamp: Long,
    val type: LogType,
    val domain: LogDomain,
    val message: String,
    val data: Map<String, Any> = emptyMap(),
    val threadName: String = Thread.currentThread().name,
    val className: String? = null
) {
    companion object {
        private val gson = Gson()
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        
        /**
         * Deserialize from JSON string
         */
        fun fromJson(json: String): StructuredLogEntry? {
            return try {
                gson.fromJson(json, StructuredLogEntry::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }
    
    /**
     * Format as display string for UI
     */
    fun toDisplayString(): String {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        val dataStr = if (data.isNotEmpty()) {
            " {${data.entries.joinToString(", ") { "${it.key}=${it.value}" }}}"
        } else ""
        
        return "[$timeStr] ${type.icon} ${domain.icon} ${type.displayName.uppercase()}: $message$dataStr"
    }
    
    /**
     * Format as detailed string for export
     */
    fun toDetailedString(): String {
        val timeStr = dateFormat.format(Date(timestamp))
        val dataStr = if (data.isNotEmpty()) {
            " | Data: ${data.entries.joinToString(", ") { "${it.key}=${it.value}" }}"
        } else ""
        val threadStr = if (threadName.isNotEmpty()) " | Thread: $threadName" else ""
        val classStr = if (!className.isNullOrEmpty()) " | Class: $className" else ""
        
        return "[$timeStr] [${type.icon} ${type.displayName}] [${domain.icon} ${domain.displayName}] $message$dataStr$threadStr$classStr"
    }
    
    /**
     * Serialize to JSON string
     */
    fun toJson(): String {
        return gson.toJson(this)
    }
}
