package com.esp32phone.agent.protocol

/**
 * Mutual authentication and session-key derivation (protocol baseline §4).
 *
 * The transcript is a fixed binary layout so it never depends on JSON field order; the
 * exact bytes are pinned by `protocol/vectors/handshake.json`.
 */
public object Handshake {

    public const val TRANSCRIPT_MAGIC: String = "E32PHN2H"
    public const val LABEL_BOARD: String = "esp32phone/v2/proof/board"
    public const val LABEL_APP: String = "esp32phone/v2/proof/app"
    public const val INFO_SESSION: String = "esp32phone/v2/session"
    public const val NONCE_LEN: Int = 32
    public const val SECRET_LEN: Int = 32
    public const val MAX_BOARD_ID_LEN: Int = 32
    public const val SESSION_ID_LEN: Int = 8

    public fun computeTranscript(
        boardId: String,
        clientNonce: ByteArray,
        boardNonce: ByteArray,
        features: Long,
    ): ByteArray {
        require(clientNonce.size == NONCE_LEN) { "client nonce must be $NONCE_LEN bytes" }
        require(boardNonce.size == NONCE_LEN) { "board nonce must be $NONCE_LEN bytes" }
        val ident = boardId.toByteArray(Charsets.UTF_8)
        if (ident.isEmpty() || ident.size > MAX_BOARD_ID_LEN) {
            throw ProtocolException(ErrorCode.AUTH_FAILED, "board_id must be 1..$MAX_BOARD_ID_LEN UTF-8 bytes")
        }
        if (features < 0 || features > 0xFFFFFFFFL) {
            throw ProtocolException(ErrorCode.AUTH_FAILED, "features must fit u32")
        }
        val magic = TRANSCRIPT_MAGIC.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(magic.size + 2 + ident.size + NONCE_LEN * 2 + 4)
        var offset = 0
        magic.copyInto(out, offset); offset += magic.size
        out[offset] = WireFormat.VERSION.toByte(); offset += 1
        out[offset] = ident.size.toByte(); offset += 1
        ident.copyInto(out, offset); offset += ident.size
        clientNonce.copyInto(out, offset); offset += NONCE_LEN
        boardNonce.copyInto(out, offset); offset += NONCE_LEN
        putU32le(out, offset, features)
        return out
    }

    public fun boardProof(secret: ByteArray, transcript: ByteArray): ByteArray =
        Crypto.hmacSha256(secret, LABEL_BOARD.toByteArray(Charsets.US_ASCII) + transcript)

    public fun appProof(secret: ByteArray, transcript: ByteArray, boardProof: ByteArray): ByteArray =
        Crypto.hmacSha256(secret, LABEL_APP.toByteArray(Charsets.US_ASCII) + transcript + boardProof)

    /**
     * Verifies the board proof and produces the app proof in one step so a caller cannot
     * accidentally answer a bad board proof.
     */
    public fun verifyBoardAndProve(
        secret: ByteArray,
        transcript: ByteArray,
        receivedBoardProof: ByteArray,
    ): ByteArray {
        val expected = boardProof(secret, transcript)
        if (!Crypto.constantTimeEquals(expected, receivedBoardProof)) {
            throw ProtocolException(ErrorCode.AUTH_FAILED, "board proof mismatch")
        }
        return appProof(secret, transcript, expected)
    }

    public fun deriveSession(secret: ByteArray, transcript: ByteArray): SessionKeys {
        val salt = Crypto.sha256(transcript)
        val sessionId = Crypto.hkdfSha256(secret, salt, INFO_SESSION.toByteArray(Charsets.US_ASCII), SESSION_ID_LEN)
        val slots = KeySlot.entries.associateWith { slot ->
            SlotKey(
                key = Crypto.hkdfSha256(secret, salt, slot.info.toByteArray(Charsets.US_ASCII), 32),
                noncePrefix = Crypto.hkdfSha256(secret, salt, slot.nonceInfo.toByteArray(Charsets.US_ASCII), 8),
            )
        }
        return SessionKeys(sessionId, slots, transcript)
    }
}

public class SlotKey(public val key: ByteArray, public val noncePrefix: ByteArray)

public class SessionKeys(
    public val sessionId: ByteArray,
    slots: Map<KeySlot, SlotKey>,
    public val transcript: ByteArray,
) {
    private val slots: Map<KeySlot, SlotKey> = slots.toMap()

    init {
        require(sessionId.size == Handshake.SESSION_ID_LEN) { "session id must be 8 bytes" }
        for (slot in KeySlot.entries) {
            val entry = requireNotNull(this.slots[slot]) { "missing key material for $slot" }
            require(entry.key.size == 32) { "slot $slot key must be 32 bytes" }
            require(entry.noncePrefix.size == 8) { "slot $slot nonce prefix must be 8 bytes" }
        }
    }

    public fun slot(slot: KeySlot): SlotKey =
        slots[slot] ?: throw ProtocolException(ErrorCode.AUTH_FAILED, "no key material for $slot")
}
