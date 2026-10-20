package com.esp32phone.agent.protocol

/** A successfully opened (authenticated, decrypted) envelope. */
public class OpenedEnvelope(
    public val messageType: MessageType,
    public val sessionId: Long,
    public val sequence: Long,
    public val commandId: Long,
    public val payload: ByteArray,
)

/**
 * Authenticated envelope codec for one direction/channel slot.
 *
 * Header layout (20 bytes, also the AAD) is documented in
 * `protocol/schema/wire-format.md` §4 and pinned by `protocol/vectors/envelope.json`.
 */
public class EnvelopeChannel(
    private val slot: KeySlot,
    private val sessionKeys: SessionKeys,
    private val maxSequence: Long = 0xFFFFFFFFL,
) {
    private val material: SlotKey = sessionKeys.slot(slot)
    private val expectedSessionId: Long = sessionKeys.sessionId.u64le(0)
    private var sendSequence: Long = 0
    private var receiveSequence: Long = 0

    public val lastSentSequence: Long get() = sendSequence
    public val lastReceivedSequence: Long get() = receiveSequence

    public fun encodeHeader(
        messageType: MessageType,
        sequence: Long,
        commandId: Long,
        payloadLength: Int,
    ): ByteArray {
        if (sequence <= 0 || sequence > maxSequence) {
            throw ProtocolException(ErrorCode.BAD_FRAME, "sequence $sequence outside 1..$maxSequence")
        }
        if (commandId < 0 || commandId > 0xFFFFFFFFL) {
            throw ProtocolException(ErrorCode.BAD_FRAME, "command_id must fit u32")
        }
        if (payloadLength < 0 || payloadLength > 0xFFFF) {
            throw ProtocolException(ErrorCode.TOO_LARGE, "payload_length must fit u16")
        }
        val header = ByteArray(WireFormat.ENVELOPE_HEADER_LEN)
        header[0] = WireFormat.VERSION.toByte()
        header[1] = messageType.id.toByte()
        putU64le(header, 2, expectedSessionId)
        putU32le(header, 10, sequence)
        putU32le(header, 14, commandId)
        putU16le(header, 18, payloadLength)
        return header
    }

    public fun seal(messageType: MessageType, commandId: Long, payload: ByteArray): ByteArray {
        if (sendSequence >= maxSequence) {
            throw ProtocolException(ErrorCode.REPLAY_DETECTED, "send sequence exhausted; re-handshake required")
        }
        val sequence = sendSequence + 1
        val header = encodeHeader(messageType, sequence, commandId, payload.size)
        val nonce = nonce(sequence)
        val sealed = Crypto.seal(material.key, nonce, header, payload)
        sendSequence = sequence
        return header + sealed
    }

    public fun open(sealedEnvelope: ByteArray): OpenedEnvelope {
        if (sealedEnvelope.size < WireFormat.ENVELOPE_HEADER_LEN + WireFormat.GCM_TAG_LEN) {
            throw ProtocolException(ErrorCode.BAD_FRAME, "envelope shorter than header+tag: ${sealedEnvelope.size}")
        }
        if (sealedEnvelope.u8(0) != WireFormat.VERSION) {
            throw ProtocolException(ErrorCode.BAD_VERSION, "envelope version ${sealedEnvelope.u8(0)}")
        }
        val typeId = sealedEnvelope.u8(1)
        val messageType = MessageType.fromId(typeId)
            ?: throw ProtocolException(ErrorCode.UNKNOWN_TYPE, "unknown message_type $typeId")
        val sessionId = sealedEnvelope.u64le(2)
        if (sessionId != expectedSessionId) {
            throw ProtocolException(ErrorCode.AUTH_FAILED, "session_id mismatch")
        }
        val sequence = sealedEnvelope.u32le(10)
        val commandId = sealedEnvelope.u32le(14)
        val payloadLength = sealedEnvelope.u16le(18)
        val actualCipherLen = sealedEnvelope.size - WireFormat.ENVELOPE_HEADER_LEN - WireFormat.GCM_TAG_LEN
        if (payloadLength != actualCipherLen) {
            throw ProtocolException(
                ErrorCode.BAD_FRAME,
                "payload_length $payloadLength disagrees with ciphertext length $actualCipherLen",
            )
        }
        if (sequence <= 0 || sequence <= receiveSequence) {
            throw ProtocolException(
                ErrorCode.REPLAY_DETECTED,
                "sequence $sequence not greater than last accepted $receiveSequence",
            )
        }
        if (sequence > maxSequence) {
            throw ProtocolException(ErrorCode.BAD_FRAME, "sequence $sequence exceeds $maxSequence")
        }

        val header = sealedEnvelope.copyOfRange(0, WireFormat.ENVELOPE_HEADER_LEN)
        val ciphertext = sealedEnvelope.copyOfRange(WireFormat.ENVELOPE_HEADER_LEN, sealedEnvelope.size)
        val plaintext = Crypto.open(material.key, nonce(sequence), header, ciphertext)
            ?: throw ProtocolException(ErrorCode.AUTH_FAILED, "AEAD authentication failed on $slot")
        receiveSequence = sequence
        return OpenedEnvelope(messageType, sessionId, sequence, commandId, plaintext)
    }

    private fun nonce(sequence: Long): ByteArray {
        val nonce = ByteArray(12)
        material.noncePrefix.copyInto(nonce, 0)
        putU32le(nonce, 8, sequence)
        return nonce
    }
}
