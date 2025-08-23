package com.pubsub.service

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
import com.pubsub.R
import com.pubsub.data.Configuration
import com.pubsub.data.ConfigurationManager
import com.pubsub.nostr.NostrEvent
import com.pubsub.nostr.NostrMessage
import com.pubsub.ui.MainActivity
import com.pubsub.utils.UriBuilder
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
        private const val CHANNEL_ID = "pubsub_service_channel"
        private const val EVENT_CHANNEL_ID = "pubsub_event_channel"
        
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
    
    // Rate limiting for notifications
    private var lastNotificationTime = 0L
    private var notificationCount = 0
    private val notificationRateLimit = 5000L // 5 seconds between notifications
    private val maxNotificationsPerHour = 20
    

    
    override fun onCreate() {
        super.onCreate()
        configurationManager = ConfigurationManager(this)
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        
        createNotificationChannels()
        setupOkHttpClient()
        
        sendDebugLog("⚡ Service created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sendDebugLog("🚀 Service started")
        
        startForeground(NOTIFICATION_ID, createForegroundNotification())
        

        
        serviceJob = CoroutineScope(Dispatchers.IO).launch {
            connectToAllRelays()
        }
        
        return START_STICKY // Restart if killed
    }
    
    override fun onDestroy() {
        sendDebugLog("🛑 Service destroyed")
        
        serviceJob?.cancel()
        disconnectFromAllRelays()
        okHttpClient = null
        configurationManager.isServiceRunning = false
        

        
        // Clear any remaining notifications to prevent accumulation
        try {
            notificationManager.cancelAll()
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
        
        connection.subscriptionId = UUID.randomUUID().toString()
        val subscriptionMessage = NostrMessage.createSubscription(connection.subscriptionId!!, configuration.filter)
        
        sendDebugLog("📋 Subscribing to ${connection.relayUrl} with filter: ${configuration.filter.getSummary()}")
        connection.webSocket?.send(subscriptionMessage)
    }
    
    private fun handleWebSocketMessage(messageText: String, configuration: Configuration) {
        // Process messages on a background thread to prevent ANRs
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val parsedMessage = NostrMessage.parseMessage(messageText)
                
                when (parsedMessage) {
                    is NostrMessage.ParsedMessage.EventMessage -> {
                        sendDebugLog("📨 Event: ${parsedMessage.event.id.take(8)}... (${configuration.name})")
                        handleNostrEvent(parsedMessage.event, configuration)
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
    
    private fun handleNostrEvent(event: NostrEvent, configuration: Configuration) {
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
        
        showEventNotification(event, eventUri, configuration)
    }
    

    
    private fun showEventNotification(event: NostrEvent, uri: Uri, configuration: Configuration) {
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
        
        // Create a consolidated notification that replaces previous ones
        val notification = NotificationCompat.Builder(this, EVENT_CHANNEL_ID)
            .setContentTitle("New Event (${configuration.name})")
            .setContentText(event.getContentPreview())
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFEA8F70.toInt()) // Coral background color
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true) // Only alert once to reduce noise
            .build()
        
        // Use a fixed notification ID per configuration to replace previous notifications
        val notificationId = configuration.id.hashCode()
        notificationManager.notify(notificationId, notification)
        
        lastNotificationTime = currentTime
        notificationCount++
        
        sendDebugLog("🔔 Notification sent: ${configuration.name} (#$notificationCount)")
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
