package com.cmdruid.pubsub.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages app preferences using SharedPreferences
 */
class PreferencesManager(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "pubsub_prefs"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_PUBKEY = "pubkey"
        private const val KEY_TARGET_URI = "target_uri"
        private const val KEY_DIRECT_MODE = "direct_mode"
        private const val KEY_SERVICE_RUNNING = "service_running"
        
        // Default values
        private const val DEFAULT_RELAY_URL = "wss://relay.damus.io"
        private const val DEFAULT_TARGET_URI = "https://example.com/handle-event"
    }
    
    private val sharedPrefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    var relayUrl: String
        get() = sharedPrefs.getString(KEY_RELAY_URL, DEFAULT_RELAY_URL) ?: DEFAULT_RELAY_URL
        set(value) = sharedPrefs.edit().putString(KEY_RELAY_URL, value).apply()
    
    var pubkey: String
        get() = sharedPrefs.getString(KEY_PUBKEY, "") ?: ""
        set(value) = sharedPrefs.edit().putString(KEY_PUBKEY, value).apply()
    
    var targetUri: String
        get() = sharedPrefs.getString(KEY_TARGET_URI, DEFAULT_TARGET_URI) ?: DEFAULT_TARGET_URI
        set(value) = sharedPrefs.edit().putString(KEY_TARGET_URI, value).apply()
    
    var isDirectMode: Boolean
        get() = sharedPrefs.getBoolean(KEY_DIRECT_MODE, true)
        set(value) = sharedPrefs.edit().putBoolean(KEY_DIRECT_MODE, value).apply()
    
    var isServiceRunning: Boolean
        get() = sharedPrefs.getBoolean(KEY_SERVICE_RUNNING, false)
        set(value) = sharedPrefs.edit().putBoolean(KEY_SERVICE_RUNNING, value).apply()
    
    /**
     * Check if all required settings are configured
     */
    fun hasValidConfiguration(): Boolean {
        return relayUrl.isNotBlank() && 
               pubkey.isNotBlank() && 
               targetUri.isNotBlank()
    }
    
    /**
     * Clear all preferences
     */
    fun clear() {
        sharedPrefs.edit().clear().apply()
    }
}
