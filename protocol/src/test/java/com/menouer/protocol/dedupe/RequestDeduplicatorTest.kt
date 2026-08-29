package com.menouer.protocol.dedupe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestDeduplicatorTest {

    @Test
    fun `first time seeing a messageId is not a duplicate`() {
        val dedup = RequestDeduplicator()
        assertFalse(dedup.isDuplicate("player-1", "msg-1"))
    }

    @Test
    fun `repeating the same sender and messageId is a duplicate`() {
        val dedup = RequestDeduplicator()
        assertFalse(dedup.isDuplicate("player-1", "msg-1"))
        assertTrue(dedup.isDuplicate("player-1", "msg-1"))
    }

    @Test
    fun `repeated duplicate checks keep reporting duplicate`() {
        val dedup = RequestDeduplicator()
        dedup.isDuplicate("player-1", "msg-1")
        assertTrue(dedup.isDuplicate("player-1", "msg-1"))
        assertTrue(dedup.isDuplicate("player-1", "msg-1"))
    }

    @Test
    fun `same messageId from a different sender is not a duplicate`() {
        val dedup = RequestDeduplicator()
        assertFalse(dedup.isDuplicate("player-1", "msg-1"))
        assertFalse(dedup.isDuplicate("player-2", "msg-1"))
    }

    @Test
    fun `distinct messageIds from the same sender are each recorded independently`() {
        val dedup = RequestDeduplicator()
        assertFalse(dedup.isDuplicate("player-1", "msg-1"))
        assertFalse(dedup.isDuplicate("player-1", "msg-2"))
        assertTrue(dedup.isDuplicate("player-1", "msg-1"))
        assertTrue(dedup.isDuplicate("player-1", "msg-2"))
    }

    @Test
    fun `history per sender is bounded and evicts oldest first`() {
        val dedup = RequestDeduplicator(maxHistoryPerSender = 3)

        dedup.isDuplicate("player-1", "msg-1")
        dedup.isDuplicate("player-1", "msg-2")
        dedup.isDuplicate("player-1", "msg-3")
        assertEquals(3, dedup.historySizeFor("player-1"))

        // Pushes history over the cap; "msg-1" (oldest) should be evicted.
        dedup.isDuplicate("player-1", "msg-4")
        assertEquals(3, dedup.historySizeFor("player-1"))

        // "msg-1" was evicted, so re-checking it looks like a brand-new
        // message rather than a duplicate. Note this check itself mutates
        // history (re-inserting "msg-1"), which in turn evicts "msg-2" —
        // the new oldest entry — to stay within the cap.
        assertFalse(dedup.isDuplicate("player-1", "msg-1"))

        // "msg-3" and "msg-4" are still within the retained window.
        assertTrue(dedup.isDuplicate("player-1", "msg-3"))
        assertTrue(dedup.isDuplicate("player-1", "msg-4"))
    }

    @Test
    fun `unknown sender reports zero history size`() {
        val dedup = RequestDeduplicator()
        assertEquals(0, dedup.historySizeFor("nobody"))
    }
}