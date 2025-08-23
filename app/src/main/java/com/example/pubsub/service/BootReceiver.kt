package com.example.pubsub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.pubsub.utils.PreferencesManager

/**
 * BroadcastReceiver to auto-start the service on device boot
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received broadcast: ${intent.action}")
        
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                val preferencesManager = PreferencesManager(context)
                
                // Only restart service if it was previously running and has valid configuration
                if (preferencesManager.isServiceRunning && preferencesManager.hasValidConfiguration()) {
                    Log.d(TAG, "Starting PubSub service after boot")
                    
                    val serviceIntent = Intent(context, PubSubService::class.java)
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start service after boot", e)
                    }
                } else {
                    Log.d(TAG, "Service was not running or not configured, not starting")
                }
            }
        }
    }
}
