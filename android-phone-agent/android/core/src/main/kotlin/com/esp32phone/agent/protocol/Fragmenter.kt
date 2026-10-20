package com.esp32phone.agent.protocol

/**
 * Splits one logical message into BLE-sized fragments.
 *
 * See `protocol/schema/wire-format.md` §2. The encoder never trusts the peer's MTU: the
 * capacity comes from the locally negotiated MTU only.
 */
public object Fragmenter {

    /**
     * @param payload complete logical message (handshake plaintext or AEAD envelope)
     * @param flags bit0 = protected; all other bits must be zero
     * @param mtu negotiated ATT MTU (must be >= 12 to leave any payload room)
     */
    public fun encode(payload: ByteArray, transferId: Int, flags: Int, mtu: Int): List<ByteArray> {
        if (transferId < 0 || transferId > 0xFFFF) {
            throw ProtocolException(ErrorCode.BAD_FRAME, "transfer_id out of u16 range: $transferId")
        }
        if (flags != WireFormat.FLAG_PLAINTEXT && flags != WireFormat.FLAG_PROTECTED) {
            // only bit0 is defined; anything else is a protocol error, never silently masked
            throw ProtocolException(ErrorCode.BAD_FRAME, "reserved flag bits set: $flags")
        }
        WireFormat.assertValidMtu(mtu)

        val content = WireFormat.fragmentContentCapacity(mtu)
        val count = if (payload.isEmpty()) 1 else (payload.size + content - 1) / content
        if (count > WireFormat.MAX_FRAGMENT_COUNT) {
            throw ProtocolException(
                ErrorCode.TOO_LARGE,
                "payload of ${payload.size} bytes needs $count fragments at MTU $mtu",
            )
        }
        if (payload.size > 0xFFFF) {
            throw ProtocolException(ErrorCode.TOO_LARGE, "payload exceeds u16 total_bytes: ${payload.size}")
        }

        val out = ArrayList<ByteArray>(count)
        for (index in 0 until count) {
            val from = index * content
            val to = minOf(from + content, payload.size)
            val chunk = if (from >= payload.size) ByteArray(0) else payload.copyOfRange(from, to)
            val fragment = ByteArray(WireFormat.FRAGMENT_HEADER_LEN + chunk.size)
            fragment[0] = WireFormat.VERSION.toByte()
            fragment[1] = flags.toByte()
            putU16le(fragment, 2, transferId)
            fragment[4] = index.toByte()
            fragment[5] = count.toByte()
            putU16le(fragment, 6, payload.size)
            chunk.copyInto(fragment, WireFormat.FRAGMENT_HEADER_LEN)
            out.add(fragment)
        }
        return out
    }
}

/** Result of feeding one fragment into a [Reassembler]. */
public sealed class ReassemblyResult {
    public data object Incomplete : ReassemblyResult()
    public class Complete(public val payload: ByteArray, public val flags: Int) : ReassemblyResult()
}

/**
 * Reassembles fragments for exactly one channel (one slot per connection).
 *
 * Invariants (all silently-unsafe cases are hard errors, never "best effort"):
 *  - fragments must arrive in order starting at 0 and the declared count must be the
 *    canonical count for `total_bytes` at this MTU (a short middle fragment is rejected
 *    instead of being zero-padded into the payload);
 *  - the transfer id and the flags cannot change while a transfer is in flight, so
 *    plaintext and protected bytes can never be mixed inside one logical message;
 *  - the declared total must fit the channel limit;
 *  - an in-flight transfer is checked against the timeout on every incoming fragment, not
 *    only when the caller remembers to tick.
 */
public class Reassembler(
    private val channel: Channel,
    private val mtu: Int,
) {
    private var transferId: Int = -1
    private var nextIndex: Int = 0
    private var fragmentCount: Int = 0
    private var totalBytes: Int = 0
    private var receivedBytes: Int = 0
    private var flags: Int = -1
    private var buffer: ByteArray? = null
    private var startedAtMs: Long = 0
    private val limit: Int = WireFormat.messageLimit(channel)
    private val capacity: Int = WireFormat.fragmentContentCapacity(mtu)

    public val isAssembling: Boolean
        get() = buffer != null

    public val assemblyFlags: Int
        get() = flags

    public fun reset() {
        transferId = -1
        nextIndex = 0
        fragmentCount = 0
        totalBytes = 0
        receivedBytes = 0
        flags = -1
        buffer = null
        startedAtMs = 0
    }

    /** @return true when a half-open assembly was discarded because of the timeout. */
    public fun onTick(nowMs: Long): Boolean {
        if (buffer == null) return false
        if (nowMs - startedAtMs < channel.fragmentTimeoutMs) return false
        reset()
        return true
    }

    public fun onFragment(fragment: ByteArray, nowMs: Long): ReassemblyResult {
        if (fragment.size < WireFormat.FRAGMENT_HEADER_LEN) {
            reset()
            throw ProtocolException(ErrorCode.BAD_FRAME, "fragment shorter than header: ${fragment.size}")
        }
        if (fragment.u8(0) != WireFormat.VERSION) {
            reset()
            throw ProtocolException(ErrorCode.BAD_VERSION, "wire_version ${fragment.u8(0)} != ${WireFormat.VERSION}")
        }
        val incomingFlags = fragment.u8(1)
        if (incomingFlags != WireFormat.FLAG_PLAINTEXT && incomingFlags != WireFormat.FLAG_PROTECTED) {
            reset()
            throw ProtocolException(ErrorCode.BAD_FRAME, "reserved flag bits set: $incomingFlags")
        }
        val inTransfer = fragment.u16le(2)
        val index = fragment.u8(4)
        val count = fragment.u8(5)
        val declaredTotal = fragment.u16le(6)
        val contentLen = fragment.size - WireFormat.FRAGMENT_HEADER_LEN

        if (count == 0) {
            reset()
            throw ProtocolException(ErrorCode.BAD_FRAME, "fragment_count must be >= 1")
        }
        if (declaredTotal > limit) {
            reset()
            throw ProtocolException(
                ErrorCode.TOO_LARGE,
                "declared total $declaredTotal exceeds ${channel.name} limit $limit",
            )
        }
        if (contentLen > capacity) {
            reset()
            throw ProtocolException(
                ErrorCode.BAD_FRAME,
                "fragment content $contentLen exceeds MTU $mtu capacity $capacity",
            )
        }

        // Timeout is enforced on every fragment, not only via an external tick.
        if (buffer != null && nowMs - startedAtMs >= channel.fragmentTimeoutMs) {
            reset()
            throw ProtocolException(
                ErrorCode.FRAGMENT_TIMEOUT,
                "assembly timed out after ${nowMs - startedAtMs} ms",
            )
        }

        if (buffer == null) {
            if (index != 0) {
                throw ProtocolException(ErrorCode.BAD_FRAME, "first fragment index must be 0, got $index")
            }
            // The count must be the canonical count for this total: this is what forbids
            // "short" fragments from being padded with zeros.
            val canonicalCount = if (declaredTotal == 0) 1 else (declaredTotal + capacity - 1) / capacity
            if (count != canonicalCount) {
                reset()
                throw ProtocolException(
                    ErrorCode.BAD_FRAME,
                    "fragment_count $count is not canonical for total_bytes $declaredTotal " +
                        "at MTU $mtu (expected $canonicalCount)",
                )
            }
            // Intermediate fragments must be full.
            val expectedContent = expectedContentLength(index, declaredTotal)
            if (contentLen != expectedContent) {
                reset()
                throw ProtocolException(
                    ErrorCode.BAD_FRAME,
                    "fragment $index carries $contentLen bytes, expected $expectedContent " +
                        "(short fragments are not padded)",
                )
            }
            transferId = inTransfer
            fragmentCount = count
            totalBytes = declaredTotal
            receivedBytes = 0
            flags = incomingFlags
            nextIndex = 0
            startedAtMs = nowMs
            buffer = ByteArray(declaredTotal)
        } else {
            if (inTransfer != transferId) {
                reset()
                throw ProtocolException(
                    ErrorCode.BAD_FRAME,
                    "transfer_id changed mid-assembly: $transferId -> $inTransfer",
                )
            }
            if (incomingFlags != flags) {
                reset()
                throw ProtocolException(
                    ErrorCode.BAD_FRAME,
                    "flags changed mid-assembly: $flags -> $incomingFlags",
                )
            }
            if (count != fragmentCount || declaredTotal != totalBytes) {
                reset()
                throw ProtocolException(
                    ErrorCode.BAD_FRAME,
                    "fragment header disagrees with open transfer (count $fragmentCount/$count, total $totalBytes/$declaredTotal)",
                )
            }
        }

        if (index != nextIndex) {
            reset()
            throw ProtocolException(ErrorCode.BAD_FRAME, "expected fragment index $nextIndex, got $index")
        }

        val expectedContent = expectedContentLength(index, totalBytes)
        if (contentLen != expectedContent) {
            reset()
            throw ProtocolException(
                ErrorCode.BAD_FRAME,
                "fragment $index carries $contentLen bytes, expected $expectedContent " +
                    "(short fragments are not padded)",
            )
        }

        val target = buffer ?: throw ProtocolException(ErrorCode.BAD_FRAME, "no assembly buffer")
        fragment.copyInto(target, receivedBytes, WireFormat.FRAGMENT_HEADER_LEN, fragment.size)
        receivedBytes += contentLen
        nextIndex += 1

        if (nextIndex < fragmentCount) {
            return ReassemblyResult.Incomplete
        }
        if (receivedBytes != totalBytes) {
            reset()
            throw ProtocolException(
                ErrorCode.BAD_FRAME,
                "assembly ended at $receivedBytes of $totalBytes bytes",
            )
        }
        val payload = target
        val completedFlags = flags
        reset()
        return ReassemblyResult.Complete(payload, completedFlags)
    }

    /** Exact number of content bytes fragment [index] must carry. */
    private fun expectedContentLength(index: Int, total: Int): Int {
        val from = index * capacity
        if (from >= total) return 0
        return minOf(capacity, total - from)
    }
}
