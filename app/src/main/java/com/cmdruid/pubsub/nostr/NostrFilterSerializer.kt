package com.cmdruid.pubsub.nostr

import com.google.gson.*
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

/**
 * Custom serializer for NostrFilter that handles dynamic hashtag fields according to NIP-01
 * This ensures hashtag entries are serialized as individual "#a", "#b", "#t" etc. fields
 */
class NostrFilterSerializer : JsonSerializer<NostrFilter>, JsonDeserializer<NostrFilter> {
    
    override fun serialize(src: NostrFilter, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val jsonObject = JsonObject()
        
        // Serialize standard fields
        src.ids?.let { if (it.isNotEmpty()) jsonObject.add("ids", context.serialize(it)) }
        src.authors?.let { if (it.isNotEmpty()) jsonObject.add("authors", context.serialize(it)) }
        src.kinds?.let { if (it.isNotEmpty()) jsonObject.add("kinds", context.serialize(it)) }
        src.eventRefs?.let { if (it.isNotEmpty()) jsonObject.add("#e", context.serialize(it)) }
        src.pubkeyRefs?.let { if (it.isNotEmpty()) jsonObject.add("#p", context.serialize(it)) }
        src.dTags?.let { if (it.isNotEmpty()) jsonObject.add("#d", context.serialize(it)) }
        src.rTags?.let { if (it.isNotEmpty()) jsonObject.add("#r", context.serialize(it)) }
        src.aTags?.let { if (it.isNotEmpty()) jsonObject.add("#a", context.serialize(it)) }
        src.since?.let { jsonObject.addProperty("since", it) }
        src.until?.let { jsonObject.addProperty("until", it) }
        src.limit?.let { jsonObject.addProperty("limit", it) }
        src.search?.let { if (it.isNotBlank()) jsonObject.addProperty("search", it) }
        
        // Serialize hashtag entries as dynamic fields
        src.hashtagEntries?.let { entries ->
            val groupedByTag = entries.groupBy { it.tag }
            for ((tag, tagEntries) in groupedByTag) {
                val values = tagEntries.map { it.value }
                if (values.isNotEmpty()) {
                    jsonObject.add("#$tag", context.serialize(values))
                }
            }
        }
        
        return jsonObject
    }
    
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): NostrFilter {
        val jsonObject = json.asJsonObject
        
        // Deserialize standard fields
        val ids = jsonObject.get("ids")?.let { 
            context.deserialize<List<String>>(it, object : TypeToken<List<String>>() {}.type)
        }
        val authors = jsonObject.get("authors")?.let { 
            context.deserialize<List<String>>(it, object : TypeToken<List<String>>() {}.type)
        }
        val kinds = jsonObject.get("kinds")?.let { 
            context.deserialize<List<Int>>(it, object : TypeToken<List<Int>>() {}.type)
        }
        val eventRefs = jsonObject.get("#e")?.let { 
            context.deserialize<List<String>>(it, object : TypeToken<List<String>>() {}.type)
        }
        val pubkeyRefs = jsonObject.get("#p")?.let { 
            context.deserialize<List<String>>(it, object : TypeToken<List<String>>() {}.type)
        }
        val dTags = jsonObject.get("#d")?.let { 
            context.deserialize<List<String>>(it, object : TypeToken<List<String>>() {}.type)
        }
        val rTags = jsonObject.get("#r")?.let { 
            context.deserialize<List<String>>(it, object : TypeToken<List<String>>() {}.type)
        }
        val aTags = jsonObject.get("#a")?.let { 
            context.deserialize<List<String>>(it, object : TypeToken<List<String>>() {}.type)
        }
        val since = jsonObject.get("since")?.asLong
        val until = jsonObject.get("until")?.asLong
        val limit = jsonObject.get("limit")?.asInt
        val search = jsonObject.get("search")?.asString
        
        // Deserialize hashtag entries from dynamic fields
        // Skip standard NIP-01 fields that are handled by dedicated properties
        val hashtagEntries = mutableListOf<com.cmdruid.pubsub.data.HashtagEntry>()
        for ((key, value) in jsonObject.entrySet()) {
            if (key.startsWith("#") && key.length == 2 && key[1].isLetter()) {
                val tag = key.substring(1)
                // Skip standard NIP-01 fields that have dedicated properties
                if (tag !in listOf("e", "p", "d", "r", "a")) {
                    // For hashtag entries, we still want to validate against UI restrictions
                    // but allow any single letter tag in the filter
                    if (tag.matches(Regex("[a-zA-Z]"))) {
                        val values = context.deserialize<List<String>>(value, object : TypeToken<List<String>>() {}.type)
                        values?.forEach { entryValue ->
                            hashtagEntries.add(com.cmdruid.pubsub.data.HashtagEntry(tag, entryValue))
                        }
                    }
                }
            }
        }
        
        return NostrFilter(
            ids = ids,
            authors = authors,
            kinds = kinds,
            eventRefs = eventRefs,
            pubkeyRefs = pubkeyRefs,
            hashtagEntries = hashtagEntries.takeIf { it.isNotEmpty() },
            dTags = dTags,
            rTags = rTags,
            aTags = aTags,
            since = since,
            until = until,
            limit = limit,
            search = search
        )
    }
}
