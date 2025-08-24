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
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.cmdruid.pubsub.R
import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.data.ConfigurationManager
import com.cmdruid.pubsub.nostr.NostrEvent
import com.cmdruid.pubsub.nostr.NostrMessage
import com.cmdruid.pubsub.service.SubscriptionManager
import com.cmdruid.pubsub.ui.MainActivity
import com.cmdruid.pubsub.utils.UriBuilder
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
        private const val PING_INTERVAL_SECONDS = 30L
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
    private var okHttpClient: OkHttpClient? = null
    private var serviceJob: Job? = null
    private var eventNotificationCounter = EVENT_NOTIFICATION_ID_BASE
    
    // Map of relay URL to RelayConnection
    private val relayConnections = ConcurrentHashMap<String, RelayConnection>()
    
    // Enhanced subscription and event management
    private val subscriptionManager = SubscriptionManager()
    private val eventCache = EventCache()
    
    // Rate limiting for notifications
    private var lastNotificationTime = 0L
    private var notificationCount = 0
    private val notificationRateLimit = 5000L // 5 seconds between notifications
    private val maxNotificationsPerHour = 20
    
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
        
        createNotificationChannels()
        setupOkHttpClient()
        
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
            else -> {
                sendDebugLog("🚀 Service started")
                startForeground(NOTIFICATION_ID, createForegroundNotification())
            }
        }

        
        serviceJob = CoroutineScope(Dispatchers.IO).launch {
            // Clean up any orphaned subscriptions before starting
            cleanupOrphanedSubscriptions()
            
            connectToAllRelays()
            
            // Log initial stats
            logServiceStats()
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
        configurationManager.isServiceRunning = false
        

        
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
    
    private fun setupOkHttpClient() {
        okHttpClient = OkHttpClient.Builder()
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .build()
    }
    
    private suspend fun connectToAllRelays() {
        val configurations = configurationManager.getEnabledConfigurations()
        sendDebugLog("🔌 Starting connections for ${configurations.size} subscription(s)")
        
        for (configuration in configurations) {
            for (relayUrl in configuration.relayUrls) {
                connectToRelay(relayUrl, configuration)
            }
        }
    }
    
    private suspend fun connectToRelay(relayUrl: String, configuration: Configuration) {
        sendDebugLog("🔌 Connecting: ${relayUrl.substringAfter("://").take(20)}... (${configuration.name})")
        
        val connection = RelayConnection(relayUrl, configuration.id)
        relayConnections[relayUrl] = connection
        
        val request = Request.Builder()
            .url(relayUrl)
            .build()
        
        connection.webSocket = okHttpClient?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendDebugLog("✅ Connected: ${relayUrl.substringAfter("://").take(20)}...")
                connection.reconnectAttempts = 0
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
                scheduleReconnect(connection, configuration)
            }
        })
    }
    
    private fun subscribeToEvents(connection: RelayConnection, configuration: Configuration) {
        if (configuration.filter.isEmpty()) {
            sendDebugLog("⚠️ No filter configured for ${configuration.name}, cannot subscribe")
            return
        }
        
        // Check if we should update the "since" timestamp for resubscription
        val existingSubscriptionId = connection.subscriptionId
        val filterToUse = if (existingSubscriptionId != null) {
            // This is a resubscription - use updated filter with latest timestamp
            subscriptionManager.createResubscriptionFilter(existingSubscriptionId) ?: configuration.filter
        } else {
            configuration.filter
        }
        
        // Create new subscription with the subscription manager
        val subscriptionId = subscriptionManager.createSubscription(
            configurationId = configuration.id,
            filter = filterToUse,
            relayUrl = connection.relayUrl
        )
        
        // Update connection with new subscription ID
        connection.subscriptionId = subscriptionId
        
        val subscriptionMessage = NostrMessage.createSubscription(subscriptionId, filterToUse)
        
        sendDebugLog("📋 Subscribing to ${connection.relayUrl} with filter: ${filterToUse.getSummary()}")
        sendDebugLog("🆔 Subscription ID: $subscriptionId")
        
        connection.webSocket?.send(subscriptionMessage)
    }
    
    private fun handleWebSocketMessage(messageText: String, configuration: Configuration) {
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
                        
                        // 2. Validate event structure
                        if (!event.isValid()) {
                            sendDebugLog("❌ Invalid event rejected: ${event.id.take(8)}...")
                            return@launch
                        }
                        
                        // 3. Check for duplicate events
                        if (eventCache.hasSeenEvent(event.id)) {
                            sendDebugLog("🔄 Ignoring duplicate event: ${event.id.take(8)}...")
                            return@launch
                        }
                        
                        // 4. Mark as seen and update timestamp tracking
                        eventCache.markEventSeen(event.id)
                        subscriptionManager.updateLastEventTimestamp(subscriptionId, event.createdAt)
                        
                        sendDebugLog("📨 Event: ${event.id.take(8)}... (${configuration.name}) [${NostrEvent.getKindName(event.kind)}]")
                        handleNostrEvent(event, configuration, subscriptionId)
                    }
                    is NostrMessage.ParsedMessage.EoseMessage -> {
                        sendDebugLog("✅ End of stored events for ${configuration.name}")
                    }
                    is NostrMessage.ParsedMessage.NoticeMessage -> {
                        sendDebugLog("📢 Relay notice for ${configuration.name}: ${parsedMessage.notice}")
                    }
                    is NostrMessage.ParsedMessage.OkMessage -> {
                        Log.d(TAG, "OK response for ${configuration.name}: ${parsedMessage.eventId} - ${parsedMessage.success}")
                    }
                    is NostrMessage.ParsedMessage.UnknownMessage -> {
                        sendDebugLog("⚠️ Unknown message type for ${configuration.name}: ${parsedMessage.type}")
                    }
                    null -> {
                        sendDebugLog("❌ Failed to parse message for ${configuration.name}: $messageText")
                    }
                }
            } catch (e: Exception) {
                sendDebugLog("❌ Error processing message for ${configuration.name}: ${e.message}")
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
        val currentTime = System.currentTimeMillis()
        
        // Reset notification count every hour
        if (currentTime - lastNotificationTime > 3600000) { // 1 hour
            notificationCount = 0
        }
        
        // Rate limiting: max notifications per hour and minimum time between notifications
        if (notificationCount >= maxNotificationsPerHour) {
            sendDebugLog("⏸️ Rate limit: ${configuration.name} (${notificationCount}/${maxNotificationsPerHour})")
            return
        }
        
        if (currentTime - lastNotificationTime < notificationRateLimit) {
            sendDebugLog("⏱️ Too frequent: ${configuration.name}")
            return
        }
        
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
        
        // Create a notification with explicit grouping
        val notificationId = subscriptionId.hashCode()
        
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
        
        sendDebugLog("🔔 Notification sent: ${configuration.name} [Sub: ${subscriptionId.take(8)}...] (#$notificationCount)")
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
            val delayMs = minOf(
                RECONNECT_DELAY_MS * (1 shl connection.reconnectAttempts),
                MAX_RECONNECT_DELAY_MS
            )
            
            sendDebugLog("🔄 Reconnecting to ${connection.relayUrl} in ${delayMs}ms (attempt ${connection.reconnectAttempts + 1})")
            delay(delayMs)
            
            connection.reconnectAttempts++
            connectToRelay(connection.relayUrl, configuration)
        }
    }
    
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
    }
    
    /**
     * Refresh all WebSocket connections - useful when app is reopened after reinstall/restart
     */
    private fun refreshConnections() {
        CoroutineScope(Dispatchers.IO).launch {
            sendDebugLog("🔄 Refreshing all WebSocket connections...")
            
            // First, check connection health
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
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}
