package com.esp32phone.agent.control

import com.esp32phone.agent.protocol.Crypto
import java.util.LinkedHashMap

public enum class LedgerDecision {
    /** New command id: the caller may execute it exactly once. */
    EXECUTE,

    /** Same id and same payload as before: return the previously stored state, do not re-execute. */
    DUPLICATE,

    /** Same id but a different payload: hard rejection. */
    CONFLICT,

    /** Id at or below the accepted watermark but no longer cached: never treat as new. */
    STALE,
}

/**
 * Per-session command de-duplication (protocol baseline §5, wire-format §7).
 *
 * The watermark survives cache eviction so an evicted id can never be replayed as a fresh
 * action. It is reset only when a new session is authenticated; the app must then still
 * refuse to replay its old queue (baseline §7).
 */
public class CommandLedger(public val cacheSize: Int = DEFAULT_CACHE_SIZE) {

    public companion object {
        public const val DEFAULT_CACHE_SIZE: Int = 64
        private const val DIGEST_LEN: Int = 16
    }

    private class Entry(val digest: ByteArray) {
        var state: String = "ACCEPTED"
    }

    private val cache = LinkedHashMap<Long, Entry>()
    private var watermark: Long = 0

    public val acceptedWatermark: Long get() = watermark

    public fun admit(commandId: Long, payload: ByteArray): LedgerDecision {
        require(commandId >= 0) { "command id must be non-negative" }
        val digest = Crypto.sha256(payload).copyOf(DIGEST_LEN)
        val existing = cache[commandId]
        if (existing != null) {
            return if (Crypto.constantTimeEquals(existing.digest, digest)) {
                LedgerDecision.DUPLICATE
            } else {
                LedgerDecision.CONFLICT
            }
        }
        if (commandId <= watermark) {
            return LedgerDecision.STALE
        }
        cache[commandId] = Entry(digest)
        watermark = maxOf(watermark, commandId)
        evictOldest()
        return LedgerDecision.EXECUTE
    }

    public fun recordState(commandId: Long, state: String) {
        cache[commandId]?.state = state
    }

    public fun stateOf(commandId: Long): String? = cache[commandId]?.state

    public fun cachedCount(): Int = cache.size

    public fun reset() {
        cache.clear()
        watermark = 0
    }

    private fun evictOldest() {
        while (cache.size > cacheSize) {
            val oldest = cache.keys.firstOrNull() ?: return
            cache.remove(oldest)
        }
    }
}
