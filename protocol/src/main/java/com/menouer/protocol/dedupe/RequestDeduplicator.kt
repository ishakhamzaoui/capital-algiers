package com.menouer.protocol.dedupe

/**
 * Tracks recently-seen client `messageId`s to satisfy
 * MultiplayerProtocol.md §9: "the host shall keep sufficient recent request
 * history to detect duplicates and avoid applying the same transaction
 * twice." Session 2's validation pipeline calls [isDuplicate] as one of its
 * checks, before a request is allowed to reach the rules engine.
 *
 * Keyed by (senderId, messageId) rather than messageId alone: §9 only
 * requires uniqueness "per client request", so two different clients
 * minting the same ID independently isn't actually a collision.
 *
 * History is bounded per sender via [maxHistoryPerSender] rather than
 * growing unboundedly for the life of a match — eviction is oldest-first
 * (insertion order). This is safe because the longest timeout in
 * MultiplayerProtocol.md §13 that could plausibly motivate a legitimate
 * retry is `reconnectionGracePeriodSeconds` (120s); a client has no reason
 * to be usefully re-sending a request from hundreds of messages back, so
 * capping history trades an unrealistic edge case for guaranteed-bounded
 * memory over a long match.
 */
class RequestDeduplicator(private val maxHistoryPerSender: Int = 200) {

    private val seenBySender: MutableMap<String, LinkedHashSet<String>> = mutableMapOf()

    /**
     * Records (senderId, messageId) if it hasn't been seen before.
     * Returns `true` if this exact request was already recorded (a
     * duplicate that must NOT be re-applied), `false` if this is the first
     * time we've seen it.
     */
    fun isDuplicate(senderId: String, messageId: String): Boolean {
        val history = seenBySender.getOrPut(senderId) { LinkedHashSet() }
        val isNew = history.add(messageId)
        if (!isNew) {
            return true
        }
        if (history.size > maxHistoryPerSender) {
            history.remove(history.first())
        }
        return false
    }

    /** For tests/diagnostics only. */
    fun historySizeFor(senderId: String): Int = seenBySender[senderId]?.size ?: 0
}