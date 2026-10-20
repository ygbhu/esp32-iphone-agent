package com.esp32phone.agent.protocol

/**
 * Enforces the plaintext window of the handshake (wire-format §3).
 *
 * Rules:
 *  - only `client_hello`, `board_hello` and `app_auth` may travel unencrypted, strictly in
 *    that order and each at most once;
 *  - `auth_ok` and everything after it must be protected (AEAD); a plaintext fragment in
 *    those phases is a hard `NOT_AUTHENTICATED` rejection;
 *  - a protected message before the handshake reaches its point is rejected as well, so an
 *    early encrypted ACTION cannot skip authentication.
 *
 * This gate only tracks *phase*. It never authenticates anything: the proofs and the AEAD
 * tag must still be verified by the cryptography layer. Passing this gate is not "authenticated".
 */
public class HandshakeGate {

    public enum class Phase {
        AWAITING_CLIENT_HELLO,
        AWAITING_BOARD_HELLO,
        AWAITING_APP_AUTH,
        AWAITING_AUTH_OK,
        PROTECTED_ONLY,
    }

    public var phase: Phase = Phase.AWAITING_CLIENT_HELLO
        private set

    /** True when the next message must already be AEAD protected. */
    public val plaintextClosed: Boolean
        get() = phase == Phase.AWAITING_AUTH_OK || phase == Phase.PROTECTED_ONLY

    /**
     * Feed a fragment whose fragment header says `flags = 0` (plaintext).
     *
     * @param sealedEnvelope handshake plaintext envelope (unencrypted, at least 2 bytes)
     * @return the message type carried by it
     */
    public fun onPlaintextEnvelope(sealedEnvelope: ByteArray): MessageType {
        if (phase == Phase.AWAITING_AUTH_OK || phase == Phase.PROTECTED_ONLY) {
            throw ProtocolException(
                ErrorCode.NOT_AUTHENTICATED,
                "plaintext envelope in phase $phase; the plaintext window is closed",
            )
        }
        if (sealedEnvelope.size < 2) {
            throw ProtocolException(ErrorCode.BAD_FRAME, "plaintext envelope shorter than 2 bytes")
        }
        if (sealedEnvelope.u8(0) != WireFormat.VERSION) {
            throw ProtocolException(ErrorCode.BAD_VERSION, "plaintext envelope version ${sealedEnvelope.u8(0)}")
        }
        val typeId = sealedEnvelope.u8(1)
        val type = MessageType.fromId(typeId)
            ?: throw ProtocolException(ErrorCode.UNKNOWN_TYPE, "unknown message_type $typeId")

        when (phase) {
            Phase.AWAITING_CLIENT_HELLO -> {
                expect(type, MessageType.CLIENT_HELLO)
                phase = Phase.AWAITING_BOARD_HELLO
            }
            Phase.AWAITING_BOARD_HELLO -> {
                expect(type, MessageType.BOARD_HELLO)
                phase = Phase.AWAITING_APP_AUTH
            }
            Phase.AWAITING_APP_AUTH -> {
                expect(type, MessageType.APP_AUTH)
                phase = Phase.AWAITING_AUTH_OK
            }
            else -> throw ProtocolException(ErrorCode.NOT_AUTHENTICATED, "unreachable phase $phase")
        }
        return type
    }

    /**
     * Feed a fragment whose fragment header says `flags = 1` (protected).
     *
     * @param messageType type recovered *after* the AEAD tag verified
     */
    public fun onProtectedEnvelope(messageType: MessageType) {
        when (phase) {
            Phase.AWAITING_AUTH_OK -> {
                expect(messageType, MessageType.AUTH_OK)
                phase = Phase.PROTECTED_ONLY
            }
            Phase.PROTECTED_ONLY -> {
                if (messageType.isHandshakeMessage()) {
                    throw ProtocolException(
                        ErrorCode.AUTH_FAILED,
                        "handshake message $messageType repeated after authentication",
                    )
                }
            }
            else -> throw ProtocolException(
                ErrorCode.NOT_AUTHENTICATED,
                "protected message $messageType before the handshake completed (phase $phase)",
            )
        }
    }

    private fun expect(actual: MessageType, expected: MessageType) {
        if (actual != expected) {
            throw ProtocolException(
                ErrorCode.AUTH_FAILED,
                "expected $expected but received $actual (handshake order violated in $phase)",
            )
        }
    }

    public fun reset() {
        phase = Phase.AWAITING_CLIENT_HELLO
    }
}

internal fun MessageType.isHandshakeMessage(): Boolean = when (this) {
    MessageType.CLIENT_HELLO, MessageType.BOARD_HELLO, MessageType.APP_AUTH, MessageType.AUTH_OK -> true
    else -> false
}
