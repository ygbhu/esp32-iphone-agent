package com.esp32phone.agent.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regressions for the first checkpoint review (items 1-4 of docs/codex-checkpoint-review.md).
 *
 * Every test here fails on the previous implementation: the plaintext ACTION gate, the
 * zero-padded short fragment, the lenient `\u` escape parser, and the sequence counter that
 * advanced even when authentication failed.
 */
class ProtocolReviewRegressionTest {

    // --- item 1: plaintext window closes after app_auth; auth_ok must be protected -------

    @Test
    fun `plaintext action after the handshake is rejected`() {
        val gate = HandshakeGate()
        gate.onPlaintextEnvelope(byteArrayOf(2, 1))
        gate.onPlaintextEnvelope(byteArrayOf(2, 2))
        gate.onPlaintextEnvelope(byteArrayOf(2, 3))
        assertTrue(gate.plaintextClosed, "plaintext window must be closed after app_auth")

        // [2,1] [2,2] [2,3] [2,30] - the last one must be refused.
        try {
            gate.onPlaintextEnvelope(byteArrayOf(2, 30))
            fail("plaintext ACTION must be rejected after the handshake")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.NOT_AUTHENTICATED, e.code)
        }
    }

    @Test
    fun `auth_ok must be protected and is the first protected message`() {
        val gate = HandshakeGate()
        gate.onPlaintextEnvelope(byteArrayOf(2, 1))
        gate.onPlaintextEnvelope(byteArrayOf(2, 2))
        gate.onPlaintextEnvelope(byteArrayOf(2, 3))
        // plaintext auth_ok is not allowed
        try {
            gate.onPlaintextEnvelope(byteArrayOf(2, 4))
            fail("plaintext auth_ok must be rejected")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.NOT_AUTHENTICATED, e.code)
        }
        gate.onProtectedEnvelope(MessageType.AUTH_OK)
        assertTrue(gate.plaintextClosed)
        gate.onProtectedEnvelope(MessageType.ACTION)
    }

    @Test
    fun `protected messages before the handshake completes are rejected`() {
        val gate = HandshakeGate()
        try {
            gate.onProtectedEnvelope(MessageType.ACTION)
            fail("encrypted ACTION before authentication must be refused")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.NOT_AUTHENTICATED, e.code)
        }
        gate.onPlaintextEnvelope(byteArrayOf(2, 1))
        try {
            gate.onProtectedEnvelope(MessageType.AUTH_OK)
            fail("auth_ok before board_hello/app_auth must be refused")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.NOT_AUTHENTICATED, e.code)
        }
    }

    @Test
    fun `handshake order is enforced`() {
        val gate = HandshakeGate()
        try {
            gate.onPlaintextEnvelope(byteArrayOf(2, 3))
            fail("app_auth before client_hello must be refused")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.AUTH_FAILED, e.code)
        }
        gate.onPlaintextEnvelope(byteArrayOf(2, 1))
        try {
            gate.onPlaintextEnvelope(byteArrayOf(2, 1))
            fail("a repeated client_hello must be refused")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.AUTH_FAILED, e.code)
        }
    }

    // --- item 2: no zero padding, flags bound, timeout on arrival ----------------------

    private fun fragment(
        index: Int,
        count: Int,
        total: Int,
        content: ByteArray,
        flags: Int = WireFormat.FLAG_PROTECTED,
        transferId: Int = 5,
    ): ByteArray {
        val out = ByteArray(WireFormat.FRAGMENT_HEADER_LEN + content.size)
        out[0] = WireFormat.VERSION.toByte()
        out[1] = flags.toByte()
        putU16le(out, 2, transferId)
        out[4] = index.toByte()
        out[5] = count.toByte()
        putU16le(out, 6, total)
        content.copyInto(out, WireFormat.FRAGMENT_HEADER_LEN)
        return out
    }

    @Test
    fun `short middle fragment is rejected instead of zero padded`() {
        val reassembler = Reassembler(Channel.NORMAL, 23)
        // total 13 at MTU 23: fragment 0 must carry 12 bytes, fragment 1 carries 1.
        try {
            reassembler.onFragment(fragment(0, 2, 13, byteArrayOf(0x41)), nowMs = 0)
            fail("short first fragment must be rejected instead of padded with zeros")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.BAD_FRAME, e.code)
            assertTrue(e.message!!.contains("short fragments are not padded"), e.message!!)
        }
    }

    @Test
    fun `non canonical fragment count is rejected`() {
        val reassembler = Reassembler(Channel.NORMAL, 23)
        try {
            reassembler.onFragment(fragment(0, 3, 13, ByteArray(12) { 0x41 }), nowMs = 0)
            fail("count 3 for 13 bytes at MTU 23 must be rejected")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.BAD_FRAME, e.code)
            assertTrue(e.message!!.contains("canonical"))
        }
    }

    @Test
    fun `correct two fragment assembly still works`() {
        val reassembler = Reassembler(Channel.NORMAL, 23)
        val first = reassembler.onFragment(fragment(0, 2, 13, ByteArray(12) { 0x41 }), nowMs = 0)
        assertTrue(first is ReassemblyResult.Incomplete)
        val second = reassembler.onFragment(fragment(1, 2, 13, byteArrayOf(0x42)), nowMs = 10)
        assertTrue(second is ReassemblyResult.Complete)
        val payload = (second as ReassemblyResult.Complete).payload
        assertEquals(13, payload.size)
        assertEquals(0x42, payload[12].toInt() and 0xFF, "last byte must not be zero padding")
    }

    @Test
    fun `flags cannot change mid assembly`() {
        val reassembler = Reassembler(Channel.NORMAL, 23)
        reassembler.onFragment(fragment(0, 2, 13, ByteArray(12) { 1 }, flags = WireFormat.FLAG_PROTECTED), 0)
        try {
            reassembler.onFragment(fragment(1, 2, 13, byteArrayOf(2), flags = WireFormat.FLAG_PLAINTEXT), 5)
            fail("plaintext and protected fragments must not be mixed")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.BAD_FRAME, e.code)
            assertTrue(e.message!!.contains("flags changed"))
        }
    }

    @Test
    fun `timeout is enforced when the next fragment arrives`() {
        val reassembler = Reassembler(Channel.NORMAL, 23)
        reassembler.onFragment(fragment(0, 2, 13, ByteArray(12) { 1 }), nowMs = 0)
        try {
            reassembler.onFragment(fragment(1, 2, 13, byteArrayOf(2)), nowMs = Channel.NORMAL.fragmentTimeoutMs + 1)
            fail("a fragment arriving after the assembly timeout must be refused")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.FRAGMENT_TIMEOUT, e.code)
            assertTrue(!reassembler.isAssembling)
        }
    }

    @Test
    fun `control slot timeout on arrival is shorter`() {
        val reassembler = Reassembler(Channel.CONTROL, 23)
        reassembler.onFragment(fragment(0, 2, 13, ByteArray(12) { 1 }), nowMs = 0)
        try {
            reassembler.onFragment(fragment(1, 2, 13, byteArrayOf(2)), nowMs = 1200)
            fail("control assembly must time out after 1 s")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.FRAGMENT_TIMEOUT, e.code)
        }
    }

    @Test
    fun `completed assembly reports its flags`() {
        val reassembler = Reassembler(Channel.NORMAL, 23)
        val single = reassembler.onFragment(
            fragment(0, 1, 4, byteArrayOf(1, 2, 3, 4), flags = WireFormat.FLAG_PLAINTEXT),
            0,
        )
        assertTrue(single is ReassemblyResult.Complete)
        assertEquals(WireFormat.FLAG_PLAINTEXT, (single as ReassemblyResult.Complete).flags)
        assertEquals(4, single.payload.size)
    }

    // --- item 3: strict \u escapes and surrogates --------------------------------------

    @Test
    fun `signed hex escapes are rejected`() {
        for (bad in listOf("""{"a":"\u+041"}""", """{"a":"\u-001"}""", """{"a":"\u 041"}""", """{"a":"\u0g41"}""")) {
            try {
                StrictJson.parseObject(bad)
                fail("must reject $bad")
            } catch (e: ProtocolException) {
                assertEquals(ErrorCode.INVALID_JSON, e.code, bad)
            }
        }
    }

    @Test
    fun `surrogate pairs are combined and lone surrogates rejected`() {
        val parsed = StrictJson.parseObject("""{"a":"\ud83d\ude00"}""")
        assertEquals("\uD83D\uDE00", parsed.require("a").asString())

        for (bad in listOf("""{"a":"\ud800"}""", """{"a":"\udc00"}""", """{"a":"\ud800x"}""")) {
            try {
                StrictJson.parseObject(bad)
                fail("must reject $bad")
            } catch (e: ProtocolException) {
                assertEquals(ErrorCode.INVALID_JSON, e.code, bad)
            }
        }
    }

    @Test
    fun `raw lone surrogates in the String entry point are rejected`() {
        // The ByteArray entry decodes strict UTF-8; the String entry receives raw UTF-16 and
        // must apply the same rule, otherwise the Kotlin and C++ parsers disagree.
        for (bad in listOf("{\"a\":\"\uD800\"}", "{\"a\":\"\uDC00\"}", "{\"a\":\"\uD800x\"}")) {
            try {
                StrictJson.parseObject(bad)
                fail("raw lone surrogate must be rejected")
            } catch (e: ProtocolException) {
                assertEquals(ErrorCode.INVALID_JSON, e.code)
            }
        }
        // A well-formed pair is still accepted and preserved.
        val parsed = StrictJson.parseObject("{\"a\":\"\uD83D\uDE00\"}")
        assertEquals("\uD83D\uDE00", parsed.require("a").asString())
    }

    @Test
    fun `fragment timeout reports the true elapsed time`() {
        val reassembler = Reassembler(Channel.NORMAL, 23)
        reassembler.onFragment(fragment(0, 2, 13, ByteArray(12) { 1 }), nowMs = 10_000)
        try {
            reassembler.onFragment(fragment(1, 2, 13, byteArrayOf(2)), nowMs = 12_500)
            fail("timeout must be enforced")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.FRAGMENT_TIMEOUT, e.code)
            assertTrue(
                e.message!!.contains("after 2500 ms"),
                "elapsed must be measured before the slot is cleared: ${e.message}",
            )
        }
    }

    @Test
    fun `invalid utf8 and raw control characters are rejected`() {
        try {
            StrictJson.parseObject(byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D))
            fail("invalid UTF-8 must be rejected")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.INVALID_JSON, e.code)
        }
        try {
            StrictJson.parseObject("{\"a\":\"line\nbreak\"}")
            fail("raw control character in a string must be rejected")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.INVALID_JSON, e.code)
        }
    }

    // --- item 4: a failed open must not advance the receive sequence -------------------

    @Test
    fun `tampered high sequence does not advance the receiver`() {
        val keys = Handshake.deriveSession(ByteArray(32) { 7 }, ByteArray(32) { 9 })
        val sender = EnvelopeChannel(KeySlot.BOARD_NORMAL, keys)
        val payload = """{"t":"status"}""".toByteArray()
        val seq1 = sender.seal(MessageType.STATUS, 0, payload)
        val seq2 = sender.seal(MessageType.STATUS, 0, payload)
        val seq3 = sender.seal(MessageType.STATUS, 0, payload)

        val receiver = EnvelopeChannel(KeySlot.BOARD_NORMAL, keys)
        assertEquals(1L, receiver.open(seq1).sequence)

        // A tampered sequence-3 packet fails authentication and must not move the counter.
        val tampered = seq3.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        try {
            receiver.open(tampered)
            fail("tampered packet must be rejected")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.AUTH_FAILED, e.code)
        }
        assertEquals(1L, receiver.lastReceivedSequence, "failed open must not advance the sequence")

        // A genuine lower packet still works afterwards.
        assertEquals(2L, receiver.open(seq2).sequence)
        assertEquals(3L, receiver.open(seq3).sequence)
    }

    @Test
    fun `envelope from another role or channel is rejected even with a valid key`() {
        val keys = Handshake.deriveSession(ByteArray(32) { 3 }, ByteArray(32) { 4 })
        val appNormal = EnvelopeChannel(KeySlot.APP_NORMAL, keys)
        val sealed = appNormal.seal(MessageType.ACTION, 1, """{"op":"tap"}""".toByteArray())
        for (slot in listOf(KeySlot.BOARD_NORMAL, KeySlot.APP_CONTROL, KeySlot.BOARD_CONTROL)) {
            try {
                EnvelopeChannel(slot, keys).open(sealed)
                fail("a message sealed for APP_NORMAL must not open on $slot")
            } catch (e: ProtocolException) {
                assertTrue(
                    e.code in setOf(ErrorCode.AUTH_FAILED, ErrorCode.BAD_FRAME, ErrorCode.REPLAY_DETECTED),
                    "unexpected code ${e.code} for $slot",
                )
            }
        }
    }

    @Test
    fun `envelope from a different session is rejected`() {
        val keysA = Handshake.deriveSession(ByteArray(32) { 1 }, ByteArray(32) { 1 })
        val keysB = Handshake.deriveSession(ByteArray(32) { 2 }, ByteArray(32) { 2 })
        val sealed = EnvelopeChannel(KeySlot.APP_NORMAL, keysA).seal(MessageType.ACTION, 1, "{}".toByteArray())
        try {
            EnvelopeChannel(KeySlot.APP_NORMAL, keysB).open(sealed)
            fail("another session's envelope must be rejected")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.AUTH_FAILED, e.code)
        }
    }
}
