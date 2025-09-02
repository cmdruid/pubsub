package com.cmdruid.pubsub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cmdruid.pubsub.R
import com.cmdruid.pubsub.data.BatteryMode
import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.data.NotificationFrequency
import com.cmdruid.pubsub.data.SettingsManager
import com.cmdruid.pubsub.logging.UnifiedLogger
import com.cmdruid.pubsub.logging.LogDomain
import com.cmdruid.pubsub.nostr.NostrEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages event notifications, grouping, rate limiting, and cleanup
 * Extracted from PubSubService to improve maintainability
 */
class EventNotificationManager(
    private val context: Context,
    private val settingsManager: SettingsManager,
    private val sendDebugLog: (String) -> Unit,
    private val unifiedLogger: UnifiedLogger
) : SettingsManager.SettingsChangeListener {
    
    companion object {
        private const val TAG = "EventNotificationManager"
        private const val NOTIFICATION_ID = 1001
        private const val EVENT_NOTIFICATION_ID_BASE = 2000
        private const val SUMMARY_NOTIFICATION_ID = 3000
        private const val CHANNEL_ID = "pubsub_service_channel"
        private const val EVENT_CHANNEL_ID = "pubsub_event_channel"
        private const val NOTIFICATION_GROUP_KEY = "pubsub_events"
    }
    
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var eventNotificationCounter = EVENT_NOTIFICATION_ID_BASE
    
    // Rate limiting for notifications
    private var lastNotificationTime = 0L
    private var notificationCount = 0
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
    
    /**
     * Initialize notification channels
     */
    fun initialize() {
        createNotificationChannels()
        
        // Register for settings changes
        settingsManager.addSettingsChangeListener(this)
    }
    
    /**
     * Clean up all notifications
     */
    fun cleanup() {
        try {
            notificationManager.cancelAll()
            activeNotifications.clear()
            
            // Unregister settings listener
            settingsManager.removeSettingsChangeListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing notifications: ${e.message}")
        }
    }
    
    /**
     * Create notification channels for Android O+
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Service notification channel
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            
            // Event notification channel
            val eventChannel = NotificationChannel(
                EVENT_CHANNEL_ID,
                context.getString(R.string.event_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.event_notification_channel_description)
            }
            
            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(eventChannel)
        }
    }
    
    /**
     * Create foreground service notification
     */
    fun createForegroundNotification(pendingIntent: PendingIntent, subscriptionCount: Int = 0): android.app.Notification {
        val contentText = if (subscriptionCount > 0) {
            "Monitoring $subscriptionCount subscription${if (subscriptionCount != 1) "s" else ""}"
        } else {
            context.getString(R.string.service_description)
        }
        
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.service_running))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFEA8F70.toInt()) // Coral background color
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    /**
     * Show event notification with rate limiting and grouping
     */
    fun showEventNotification(event: NostrEvent, uri: Uri, configuration: Configuration, subscriptionId: String) {
        unifiedLogger.debug(LogDomain.NOTIFICATION, "Processing notification for event: ${event.id.take(8)}... (${configuration.name})")
        val currentTime = System.currentTimeMillis()
        
        // Reset notification count every hour
        if (currentTime - lastNotificationTime > 3600000) { // 1 hour
            val oldCount = notificationCount
            notificationCount = 0
            if (oldCount > 0) {
                sendDebugLog("Hourly notification count reset: $oldCount → 0")
            }
        }
        
        // Rate limiting: max notifications per hour and minimum time between notifications
        if (notificationCount >= maxNotificationsPerHour) {
            sendDebugLog("Rate limit: ${configuration.name} (${notificationCount}/${maxNotificationsPerHour})")
            return
        }
        
        val notificationRateLimit = settingsManager.getCurrentNotificationRateLimit()
        if (currentTime - lastNotificationTime < notificationRateLimit) {
            sendDebugLog("⏱️ Too frequent: ${configuration.name} (${(currentTime - lastNotificationTime)/1000}s < ${notificationRateLimit/1000}s)")
            return
        }
        
        unifiedLogger.trace(LogDomain.NOTIFICATION, "Rate limit passed for: ${configuration.name} (count: $notificationCount/$maxNotificationsPerHour)")
        
        // Create an intent that will definitely open externally
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            // Force it to not use this app
            component = null
            setPackage(null)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context, 
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
            sendDebugLog("Skipping duplicate notification for event: ${event.id.take(8)}...")
            return
        }
        
        // Double check we have space for new notifications
        if (activeNotifications.size >= 20) {
            sendDebugLog("️ Notification limit reached (${activeNotifications.size}/20), forcing cleanup")
            // Force cleanup and try again
            val oldestEntry = activeNotifications.toList().minByOrNull { it.second.timestamp }
            oldestEntry?.let { (oldNotificationId, _) ->
                activeNotifications.remove(oldNotificationId)
                notificationManager.cancel(oldNotificationId)
                sendDebugLog("Forced removal of oldest notification to make space")
            }
        }
        
        val notification = NotificationCompat.Builder(context, EVENT_CHANNEL_ID)
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
        
        unifiedLogger.info(LogDomain.NOTIFICATION, "Notification sent: ${configuration.name} [Event: ${event.id.take(8)}...] (#$notificationCount) [${activeNotifications.size}/20]")
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
                sendDebugLog("️ Removed notification: ${notificationInfo.configurationName} [${notificationInfo.eventContent.take(20)}...]")
            }
            
            if (toRemove.isNotEmpty()) {
                sendDebugLog("Cleaned up ${toRemove.size} old notifications (${activeNotifications.size}/${maxNotifications} remaining)")
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
        
        val summaryNotification = NotificationCompat.Builder(context, EVENT_CHANNEL_ID)
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
        
        sendDebugLog("Summary notification updated: $notificationCount events")
    }
    
    /**
     * Get current notification statistics
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "active_notifications" to activeNotifications.size,
            "notification_count" to notificationCount,
            "last_notification_time" to lastNotificationTime,
            "rate_limit" to settingsManager.getCurrentNotificationRateLimit(),
            "max_per_hour" to maxNotificationsPerHour
        )
    }
    
    // SettingsChangeListener implementation
    override fun onBatteryModeChanged(newMode: BatteryMode) {
        // Notification manager doesn't need to handle battery mode changes directly
        sendDebugLog("Battery mode changed to: ${newMode.displayName}")
    }
    
    override fun onNotificationFrequencyChanged(newFrequency: NotificationFrequency) {
        sendDebugLog("Notification frequency changed to: ${newFrequency.displayName}")
        
        // Log the new rate limit for debugging
        val newRateLimit = settingsManager.getCurrentNotificationRateLimit()
        sendDebugLog("New notification rate limit: ${newRateLimit / 1000}s between notifications")
    }
    
    override fun onDebugConsoleVisibilityChanged(visible: Boolean) {
        // Notification manager doesn't need to handle debug console visibility changes
        sendDebugLog("🐛 Debug console visibility changed to: $visible")
    }
}
