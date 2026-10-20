package com.esp32phone.agent.protocol

import com.esp32phone.agent.Vectors
import com.esp32phone.agent.arr
import com.esp32phone.agent.integer
import com.esp32phone.agent.obj
import com.esp32phone.agent.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class EnvelopeVectorsTest {

    private val doc = Vectors.load("envelope.json")
    private val handshakeCase = Vectors.load("handshake.json")
        .arr("cases").items
        .map { it.asObject() }
        .first { it.text("name") == doc.text("handshake_case") }

    private fun sessionKeys(): SessionKeys = Handshake.deriveSession(
        handshakeCase.text("secret_hex").hexToBytes(),
        handshakeCase.text("transcript_hex").hexToBytes(),
    )

    private fun slotFor(channel: String): KeySlot = when (channel) {
        "app_normal" -> KeySlot.APP_NORMAL
        "board_normal" -> KeySlot.BOARD_NORMAL
        "app_control" -> KeySlot.APP_CONTROL
        "board_control" -> KeySlot.BOARD_CONTROL
        else -> error("unknown channel $channel")
    }

    @Test
    fun `header nonce and sealed bytes match the shared vectors`() {
        val keys = sessionKeys()
        for (item in doc.arr("cases").items) {
            val case = item.asObject()
            val name = case.text("name")
            val slot = slotFor(case.text("channel"))
            val channel = EnvelopeChannel(slot, keys)
            val payload = case.text("payload_utf8").toByteArray(Charsets.UTF_8)
            val messageType = MessageType.fromId(case.integer("message_type"))
                ?: error("unknown type in vector $name")
            val sequence = case.integer("sequence").toLong()
            val commandId = case.integer("command_id").toLong()

            val header = channel.encodeHeader(messageType, sequence, commandId, payload.size)
            assertEquals(case.text("expected_aad_hex"), header.hex(), "$name: AAD/header")

            val material = keys.slot(slot)
            val nonce = material.noncePrefix + ByteArray(4).also { putU32le(it, 0, sequence) }
            assertEquals(case.text("expected_nonce_hex"), nonce.hex(), "$name: nonce")

            val sealed = Crypto.seal(material.key, nonce, header, payload)
            assertEquals(
                case.text("expected_sealed_hex"),
                (header + sealed).hex(),
                "$name: sealed envelope",
            )
        }
    }

    @Test
    fun `channel seal reproduces the vector at the same sequence`() {
        val keys = sessionKeys()
        for (item in doc.arr("cases").items) {
            val case = item.asObject()
            val name = case.text("name")
            val slot = slotFor(case.text("channel"))
            val channel = EnvelopeChannel(slot, keys)
            val payload = case.text("payload_utf8").toByteArray(Charsets.UTF_8)
            val messageType = MessageType.fromId(case.integer("message_type")) ?: error("bad type")
            val sequence = case.integer("sequence")
            val commandId = case.integer("command_id").toLong()

            var produced: ByteArray? = null
            for (step in 1..sequence) {
                produced = if (step == sequence) {
                    channel.seal(messageType, commandId, payload)
                } else {
                    channel.seal(MessageType.HEARTBEAT, 0, ByteArray(0))
                }
            }
            assertEquals(case.text("expected_sealed_hex"), produced!!.hex(), "$name: seal at sequence $sequence")
        }
    }

    @Test
    fun `authenticated envelope opens back to the original payload`() {
        val keys = sessionKeys()
        for (item in doc.arr("cases").items) {
            val case = item.asObject()
            val name = case.text("name")
            val slot = slotFor(case.text("channel"))
            val sealed = case.text("expected_sealed_hex").hexToBytes()
            val opened = EnvelopeChannel(slot, keys).open(sealed)
            assertEquals(case.text("payload_utf8"), String(opened.payload, Charsets.UTF_8), "$name: payload")
            assertEquals(case.integer("sequence").toLong(), opened.sequence, "$name: sequence")
            assertEquals(case.integer("command_id").toLong(), opened.commandId, "$name: command id")
            assertEquals(case.integer("message_type"), opened.messageType.id, "$name: type")
        }
    }

    @Test
    fun `tampered tag or aad is rejected`() {
        val keys = sessionKeys()
        for (item in doc.arr("negative_sealed").items) {
            val case = item.asObject()
            val name = case.text("name")
            val slot = slotFor(case.text("channel"))
            val expected = ErrorCode.valueOf(case.text("expect"))
            try {
                EnvelopeChannel(slot, keys).open(case.text("expected_sealed_hex").hexToBytes())
                fail("$name: must be rejected")
            } catch (e: ProtocolException) {
                assertEquals(expected, e.code, "$name")
            }
        }
    }

    @Test
    fun `cross channel and cross direction replay is rejected`() {
        val keys = sessionKeys()
        val case = doc.arr("cases").items.map { it.asObject() }.first { it.text("channel") == "app_normal" }
        val sealed = case.text("expected_sealed_hex").hexToBytes()
        for (other in listOf(KeySlot.BOARD_NORMAL, KeySlot.APP_CONTROL, KeySlot.BOARD_CONTROL)) {
            try {
                EnvelopeChannel(other, keys).open(sealed)
                fail("envelope sealed for app_normal must not open on $other")
            } catch (e: ProtocolException) {
                // AUTH_FAILED for a different key; BAD_FRAME/REPLAY for header mismatch is also acceptable.
                if (e.code !in setOf(ErrorCode.AUTH_FAILED, ErrorCode.BAD_FRAME, ErrorCode.REPLAY_DETECTED)) {
                    fail("unexpected rejection code ${e.code} on $other")
                }
            }
        }
    }

    @Test
    fun `sequence guard rejects duplicates and old packets`() {
        val keys = sessionKeys()
        val channel = EnvelopeChannel(KeySlot.APP_NORMAL, keys)
        val first = channel.seal(MessageType.HEARTBEAT, 0, ByteArray(0))
        val second = channel.seal(MessageType.HEARTBEAT, 0, ByteArray(0))

        val receiver = EnvelopeChannel(KeySlot.APP_NORMAL, keys)
        assertEquals(1L, receiver.open(first).sequence)
        assertEquals(2L, receiver.open(second).sequence)

        for (replay in listOf(first, second)) {
            try {
                receiver.open(replay)
                fail("replay must be rejected")
            } catch (e: ProtocolException) {
                assertEquals(ErrorCode.REPLAY_DETECTED, e.code)
            }
        }
        // And out of order: sequence 1 after 2 is also a replay.
        try {
            receiver.open(first)
            fail("sequence regression must be rejected")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.REPLAY_DETECTED, e.code)
        }
    }

    @Test
    fun `wrong session id is rejected before decryption`() {
        val keys = sessionKeys()
        val channel = EnvelopeChannel(KeySlot.BOARD_NORMAL, keys)
        val sealed = channel.seal(MessageType.AUTH_OK, 0, "{}".toByteArray())
        val tampered = sealed.copyOf()
        tampered[2] = (tampered[2].toInt() xor 0xFF).toByte()
        try {
            EnvelopeChannel(KeySlot.BOARD_NORMAL, keys).open(tampered)
            fail("session mismatch must be rejected")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.AUTH_FAILED, e.code)
        }
    }

    @Test
    fun `sequence exhaustion demands a new handshake`() {
        val keys = sessionKeys()
        val channel = EnvelopeChannel(KeySlot.APP_CONTROL, keys, maxSequence = 2)
        channel.seal(MessageType.HEARTBEAT, 0, ByteArray(0))
        channel.seal(MessageType.HEARTBEAT, 0, ByteArray(0))
        try {
            channel.seal(MessageType.HEARTBEAT, 0, ByteArray(0))
            fail("sequence exhaustion must be refused")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.REPLAY_DETECTED, e.code)
        }
    }
}
