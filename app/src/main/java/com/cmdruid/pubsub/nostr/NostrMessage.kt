package com.cmdruid.pubsub.nostr

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser

/**
 * Utility class for handling Nostr WebSocket messages
 */
object NostrMessage {
    private val gson = GsonBuilder()
        .registerTypeAdapter(NostrFilter::class.java, NostrFilterSerializer())
        .create()
    
    /**
     * Message types as defined in NIP-01
     */
    object Type {
        const val EVENT = "EVENT"
        const val REQ = "REQ"
        const val CLOSE = "CLOSE"
        const val EOSE = "EOSE"  // End of stored events
        const val NOTICE = "NOTICE"
        const val OK = "OK"
    }
    
    /**
     * Create a REQ message to subscribe to events
     */
    fun createSubscription(subscriptionId: String, filter: NostrFilter): String {
        val message = JsonArray().apply {
            add(Type.REQ)
            add(subscriptionId)
            add(gson.toJsonTree(filter))
        }
        return gson.toJson(message)
    }
    
    /**
     * Create a CLOSE message to unsubscribe
     */
    fun createClose(subscriptionId: String): String {
        val message = JsonArray().apply {
            add(Type.CLOSE)
            add(subscriptionId)
        }
        return gson.toJson(message)
    }
    
    /**
     * Parse an incoming WebSocket message
     */
    fun parseMessage(messageText: String): ParsedMessage? {
        return try {
            val jsonArray = JsonParser.parseString(messageText).asJsonArray
            if (jsonArray.size() < 2) return null
            
            val messageType = jsonArray[0].asString
            
            when (messageType) {
                Type.EVENT -> {
                    if (jsonArray.size() >= 3) {
                        val subscriptionId = jsonArray[1].asString
                        val event = gson.fromJson(jsonArray[2], NostrEvent::class.java)
                        ParsedMessage.EventMessage(subscriptionId, event)
                    } else null
                }
                Type.EOSE -> {
                    val subscriptionId = jsonArray[1].asString
                    ParsedMessage.EoseMessage(subscriptionId)
                }
                Type.NOTICE -> {
                    val notice = jsonArray[1].asString
                    ParsedMessage.NoticeMessage(notice)
                }
                Type.OK -> {
                    if (jsonArray.size() >= 3) {
                        val eventId = jsonArray[1].asString
                        val success = jsonArray[2].asBoolean
                        val message = if (jsonArray.size() >= 4) jsonArray[3].asString else ""
                        ParsedMessage.OkMessage(eventId, success, message)
                    } else null
                }
                else -> ParsedMessage.UnknownMessage(messageType, messageText)
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Sealed class representing different types of parsed messages
     */
    sealed class ParsedMessage {
        data class EventMessage(val subscriptionId: String, val event: NostrEvent) : ParsedMessage()
        data class EoseMessage(val subscriptionId: String) : ParsedMessage()
        data class NoticeMessage(val notice: String) : ParsedMessage()
        data class OkMessage(val eventId: String, val success: Boolean, val message: String) : ParsedMessage()
        data class UnknownMessage(val type: String, val rawMessage: String) : ParsedMessage()
    }
}
