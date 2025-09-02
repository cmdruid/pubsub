package com.cmdruid.pubsub.nostr

import org.junit.Test
import org.junit.Assert.*

/**
 * Test class for NostrEvent functionality
 * Tests validation logic, tag operations, and event type helpers
 */
class NostrEventTest {

    // === VALIDATION LOGIC TESTS ===

    @Test
    fun `isValid should accept valid events`() {
        // Given: Valid event
        val validEvent = createValidEvent()
        
        // When: Checking validity
        val isValid = validEvent.isValid()
        
        // Then: Should be valid
        assertTrue(isValid)
    }

    @Test
    fun `isValid should reject events with blank required fields`() {
        // Given: Events with blank required fields
        val eventWithBlankId = createValidEvent().copy(id = "")
        val eventWithBlankPubkey = createValidEvent().copy(pubkey = "")
        val eventWithBlankSignature = createValidEvent().copy(signature = "")
        
        // When & Then: Should be invalid
        assertFalse(eventWithBlankId.isValid())
        assertFalse(eventWithBlankPubkey.isValid())
        assertFalse(eventWithBlankSignature.isValid())
    }

    @Test
    fun `isValid should reject events with invalid hex fields`() {
        // Given: Events with invalid hex fields
        val eventWithInvalidIdLength = createValidEvent().copy(id = "abc123") // Too short
        val eventWithInvalidIdChars = createValidEvent().copy(id = "xyz123" + "0".repeat(58)) // Invalid chars
        val eventWithInvalidPubkeyLength = createValidEvent().copy(pubkey = "abc123") // Too short
        val eventWithInvalidSignatureLength = createValidEvent().copy(signature = "abc123") // Too short
        
        // When & Then: Should be invalid
        assertFalse(eventWithInvalidIdLength.isValid())
        assertFalse(eventWithInvalidIdChars.isValid())
        assertFalse(eventWithInvalidPubkeyLength.isValid())
        assertFalse(eventWithInvalidSignatureLength.isValid())
    }

    @Test
    fun `isValid should reject events with invalid timestamps`() {
        // Given: Events with invalid timestamps
        val eventWithZeroTimestamp = createValidEvent().copy(createdAt = 0)
        val eventWithNegativeTimestamp = createValidEvent().copy(createdAt = -1)
        val eventWithFutureTimestamp = createValidEvent().copy(
            createdAt = System.currentTimeMillis() / 1000 + 7200 // 2 hours in future
        )
        
        // When & Then: Should be invalid
        assertFalse(eventWithZeroTimestamp.isValid())
        assertFalse(eventWithNegativeTimestamp.isValid())
        assertFalse(eventWithFutureTimestamp.isValid())
    }

    @Test
    fun `isValid should reject events with negative kinds`() {
        // Given: Event with negative kind
        val eventWithNegativeKind = createValidEvent().copy(kind = -1)
        
        // When: Checking validity
        val isValid = eventWithNegativeKind.isValid()
        
        // Then: Should be invalid
        assertFalse(isValid)
    }

    // === TAG OPERATIONS TESTS ===

    @Test
    fun `getTag should return first tag of specified type`() {
        // Given: Event with multiple tags
        val event = createValidEvent().copy(
            tags = listOf(
                listOf("p", "pubkey1", "relay1"),
                listOf("e", "eventid1", "relay1"),
                listOf("p", "pubkey2", "relay2")
            )
        )
        
        // When: Getting first p tag
        val firstPTag = event.getTag("p")
        
        // Then: Should return first p tag
        assertNotNull(firstPTag)
        assertEquals(listOf("p", "pubkey1", "relay1"), firstPTag)
    }

    @Test
    fun `getAllTags should return all tags of specified type`() {
        // Given: Event with multiple tags
        val event = createValidEvent().copy(
            tags = listOf(
                listOf("p", "pubkey1"),
                listOf("e", "eventid1"),
                listOf("p", "pubkey2"),
                listOf("t", "hashtag1")
            )
        )
        
        // When: Getting all p tags
        val allPTags = event.getAllTags("p")
        
        // Then: Should return both p tags
        assertEquals(2, allPTags.size)
        assertEquals(listOf("p", "pubkey1"), allPTags[0])
        assertEquals(listOf("p", "pubkey2"), allPTags[1])
    }

    @Test
    fun `should extract hashtags from tags and content`() {
        // Given: Event with hashtags in both tags and content
        val event = createValidEvent().copy(
            tags = listOf(
                listOf("t", "bitcoin"),
                listOf("t", "nostr")
            ),
            content = "This is about #lightning and #bitcoin technology"
        )
        
        // When: Getting hashtags
        val hashtags = event.getHashtags()
        
        // Then: Should extract from both sources
        assertTrue(hashtags.contains("bitcoin"))
        assertTrue(hashtags.contains("nostr"))
        assertTrue(hashtags.contains("lightning"))
        assertEquals(3, hashtags.distinct().size) // bitcoin should not be duplicated
    }

    @Test
    fun `should extract URLs from content`() {
        // Given: Event with URLs in content
        val event = createValidEvent().copy(
            content = "Check out https://example.com and http://test.org for more info"
        )
        
        // When: Getting URLs
        val urls = event.getUrls()
        
        // Then: Should extract both URLs
        assertEquals(2, urls.size)
        assertTrue(urls.contains("https://example.com"))
        assertTrue(urls.contains("http://test.org"))
    }

    // === EVENT TYPE HELPERS TESTS ===

    @Test
    fun `should identify text notes correctly`() {
        // Given: Text note event
        val textNoteEvent = createValidEvent().copy(kind = NostrEvent.KIND_TEXT_NOTE)
        val otherEvent = createValidEvent().copy(kind = NostrEvent.KIND_METADATA)
        
        // When & Then: Should identify correctly
        assertTrue(textNoteEvent.isTextNote())
        assertFalse(otherEvent.isTextNote())
    }

    @Test
    fun `should identify reposts correctly`() {
        // Given: Repost event
        val repostEvent = createValidEvent().copy(kind = NostrEvent.KIND_REPOST)
        val otherEvent = createValidEvent().copy(kind = NostrEvent.KIND_TEXT_NOTE)
        
        // When & Then: Should identify correctly
        assertTrue(repostEvent.isRepost())
        assertFalse(otherEvent.isRepost())
    }

    @Test
    fun `should identify delete events correctly`() {
        // Given: Delete event
        val deleteEvent = createValidEvent().copy(kind = NostrEvent.KIND_DELETE)
        val otherEvent = createValidEvent().copy(kind = NostrEvent.KIND_TEXT_NOTE)
        
        // When & Then: Should identify correctly
        assertTrue(deleteEvent.isDelete())
        assertFalse(otherEvent.isDelete())
    }

    @Test
    fun `should check mentions correctly`() {
        // Given: Event that mentions a specific pubkey
        val mentionedPubkey = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef12"
        val event = createValidEvent().copy(
            tags = listOf(
                listOf("p", mentionedPubkey),
                listOf("p", "other_pubkey_here_1234567890abcdef1234567890abcdef1234567890ab")
            )
        )
        
        // When: Checking mentions
        val mentionsTarget = event.mentionsPubkey(mentionedPubkey)
        val mentionsOther = event.mentionsPubkey("non_existent_pubkey_1234567890abcdef1234567890abcdef12345678")
        
        // Then: Should identify mentions correctly
        assertTrue(mentionsTarget)
        assertFalse(mentionsOther)
    }

    // === CONTENT PREVIEW TESTS ===

    @Test
    fun `should create content preview with default length`() {
        // Given: Event with long content
        val longContent = "This is a very long content that should be truncated when creating preview"
        val event = createValidEvent().copy(content = longContent)
        
        // When: Getting preview
        val preview = event.getContentPreview()
        
        // Then: Should be truncated with ellipsis
        assertEquals(50, preview.length - 3) // 50 chars + "..."
        assertTrue(preview.endsWith("..."))
    }

    @Test
    fun `should create content preview with custom length`() {
        // Given: Event with content
        val content = "This is test content"
        val event = createValidEvent().copy(content = content)
        
        // When: Getting preview with custom length
        val preview = event.getContentPreview(10)
        
        // Then: Should be truncated to custom length
        assertEquals("This is te...", preview)
    }

    @Test
    fun `should not truncate short content`() {
        // Given: Event with short content
        val shortContent = "Short"
        val event = createValidEvent().copy(content = shortContent)
        
        // When: Getting preview
        val preview = event.getContentPreview()
        
        // Then: Should return full content
        assertEquals(shortContent, preview)
    }

    // === MENTIONED PUBKEYS TESTS ===

    @Test
    fun `should get all mentioned pubkeys`() {
        // Given: Event with multiple p tags
        val pubkey1 = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef12"
        val pubkey2 = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890ab"
        val event = createValidEvent().copy(
            tags = listOf(
                listOf("p", pubkey1),
                listOf("e", "some_event_id"),
                listOf("p", pubkey2),
                listOf("t", "hashtag")
            )
        )
        
        // When: Getting mentioned pubkeys
        val mentionedPubkeys = event.getMentionedPubkeys()
        
        // Then: Should return all p tag values
        assertEquals(2, mentionedPubkeys.size)
        assertTrue(mentionedPubkeys.contains(pubkey1))
        assertTrue(mentionedPubkeys.contains(pubkey2))
    }

    // === REFERENCED EVENTS TESTS ===

    @Test
    fun `should get referenced event IDs`() {
        // Given: Event with e tags
        val eventId1 = "event1234567890abcdef1234567890abcdef1234567890abcdef1234567890ab"
        val eventId2 = "eventabcdef1234567890abcdef1234567890abcdef1234567890abcdef123456"
        val event = createValidEvent().copy(
            tags = listOf(
                listOf("e", eventId1),
                listOf("p", "some_pubkey"),
                listOf("e", eventId2),
                listOf("t", "hashtag")
            )
        )
        
        // When: Getting referenced events
        val referencedEvents = event.getReferencedEvents()
        
        // Then: Should return all e tag values
        assertEquals(2, referencedEvents.size)
        assertTrue(referencedEvents.contains(eventId1))
        assertTrue(referencedEvents.contains(eventId2))
    }

    @Test
    fun `should get root event ID with root marker`() {
        // Given: Event with root marker
        val rootEventId = "root1234567890abcdef1234567890abcdef1234567890abcdef1234567890ab"
        val event = createValidEvent().copy(
            tags = listOf(
                listOf("e", "other_event_id", "relay", "mention"),
                listOf("e", rootEventId, "relay", "root")
            )
        )
        
        // When: Getting root event ID
        val rootId = event.getRootEventId()
        
        // Then: Should return the root marked event
        assertEquals(rootEventId, rootId)
    }

    @Test
    fun `should get root event ID as first e tag when no root marker`() {
        // Given: Event with e tags but no root marker
        val firstEventId = "first1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
        val event = createValidEvent().copy(
            tags = listOf(
                listOf("e", firstEventId),
                listOf("e", "second_event_id")
            )
        )
        
        // When: Getting root event ID
        val rootId = event.getRootEventId()
        
        // Then: Should return first e tag
        assertEquals(firstEventId, rootId)
    }

    @Test
    fun `should get reply event ID with reply marker`() {
        // Given: Event with reply marker
        val replyEventId = "reply1234567890abcdef1234567890abcdef1234567890abcdef123456789"
        val event = createValidEvent().copy(
            tags = listOf(
                listOf("e", "root_event_id", "relay", "root"),
                listOf("e", replyEventId, "relay", "reply")
            )
        )
        
        // When: Getting reply event ID
        val replyId = event.getReplyEventId()
        
        // Then: Should return the reply marked event
        assertEquals(replyEventId, replyId)
    }

    // === KIND NAME TESTS ===

    @Test
    fun `should return correct kind names`() {
        assertEquals("Text Note", NostrEvent.getKindName(NostrEvent.KIND_TEXT_NOTE))
        assertEquals("Metadata", NostrEvent.getKindName(NostrEvent.KIND_METADATA))
        assertEquals("Delete", NostrEvent.getKindName(NostrEvent.KIND_DELETE))
        assertEquals("Repost", NostrEvent.getKindName(NostrEvent.KIND_REPOST))
        assertEquals("Reaction", NostrEvent.getKindName(NostrEvent.KIND_REACTION))
        assertEquals("Unknown (999)", NostrEvent.getKindName(999))
    }

    // === HELPER METHODS ===

    private fun createValidEvent(): NostrEvent {
        return NostrEvent(
            id = "abcd1234567890abcdef1234567890abcdef1234567890abcdef1234567890ab",
            pubkey = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
            createdAt = System.currentTimeMillis() / 1000 - 3600, // 1 hour ago
            kind = 1,
            tags = emptyList(),
            content = "This is a test event",
            signature = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
        )
    }
}
