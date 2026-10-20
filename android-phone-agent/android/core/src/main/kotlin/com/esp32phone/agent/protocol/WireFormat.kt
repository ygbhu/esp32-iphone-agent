package com.esp32phone.agent.protocol

/**
 * Byte-level constants for the v2 wire format.
 *
 * The normative description lives in `protocol/schema/wire-format.md`; the machine-readable
 * copy used by the shared vector tests is `protocol/vectors/schema.json`.
 */
public object WireFormat {
    public const val VERSION: Int = 2

    public const val FLAG_PLAINTEXT: Int = 0x00
    public const val FLAG_PROTECTED: Int = 0x01

    public const val FRAGMENT_HEADER_LEN: Int = 8
    public const val ENVELOPE_HEADER_LEN: Int = 20
    public const val GCM_TAG_LEN: Int = 16

    public const val LIMIT_NORMAL: Int = 1024
    public const val LIMIT_HANDSHAKE: Int = 512
    public const val LIMIT_CONTROL: Int = 128

    /** ATT overhead: 3 byte ATT write header. */
    public const val ATT_OVERHEAD: Int = 3

    public const val NORMAL_FRAGMENT_TIMEOUT_MS: Long = 2000
    public const val CONTROL_FRAGMENT_TIMEOUT_MS: Long = 1000

    public const val MAX_FRAGMENT_COUNT: Int = 255

    public const val SERVICE_UUID: String = "50e00000-7cc6-47b9-8e6f-6f59d90e0000"
    public const val INFO_CHARACTERISTIC_UUID: String = "50e00000-7cc6-47b9-8e6f-6f59d90e0001"
    public const val COMMAND_CHARACTERISTIC_UUID: String = "50e00000-7cc6-47b9-8e6f-6f59d90e0002"
    public const val EVENT_CHARACTERISTIC_UUID: String = "50e00000-7cc6-47b9-8e6f-6f59d90e0003"
    public const val CONTROL_CHARACTERISTIC_UUID: String = "50e00000-7cc6-47b9-8e6f-6f59d90e0004"

    /** Usable payload bytes inside one BLE write at a given ATT MTU. */
    public fun fragmentContentCapacity(mtu: Int): Int = mtu - ATT_OVERHEAD - FRAGMENT_HEADER_LEN

    /** Upper bound for a logical message on [channel]. */
    public fun messageLimit(channel: Channel): Int = when (channel) {
        Channel.NORMAL -> LIMIT_NORMAL
        Channel.CONTROL -> LIMIT_CONTROL
        Channel.HANDSHAKE -> LIMIT_HANDSHAKE
    }

    public fun assertValidMtu(mtu: Int) {
        val capacity = fragmentContentCapacity(mtu)
        if (capacity <= 0) {
            throw ProtocolException(ErrorCode.BAD_FRAME, "MTU $mtu leaves no fragment payload")
        }
    }
}

public enum class Channel(public val fragmentTimeoutMs: Long) {
    HANDSHAKE(WireFormat.NORMAL_FRAGMENT_TIMEOUT_MS),
    NORMAL(WireFormat.NORMAL_FRAGMENT_TIMEOUT_MS),
    CONTROL(WireFormat.CONTROL_FRAGMENT_TIMEOUT_MS),
}

/**
 * Direction/channel key slots. Each slot has its own AES-GCM key, nonce prefix and
 * sequence counter so that a control stop never blocks a normal message sequence.
 */
public enum class KeySlot(public val info: String, public val nonceInfo: String) {
    APP_NORMAL("esp32phone/v2/key/app-normal", "esp32phone/v2/nonce/app-normal"),
    BOARD_NORMAL("esp32phone/v2/key/board-normal", "esp32phone/v2/nonce/board-normal"),
    APP_CONTROL("esp32phone/v2/key/app-control", "esp32phone/v2/nonce/app-control"),
    BOARD_CONTROL("esp32phone/v2/key/board-control", "esp32phone/v2/nonce/board-control"),
    ;

    public val isAppToBoard: Boolean
        get() = this == APP_NORMAL || this == APP_CONTROL
}

public enum class MessageType(public val id: Int) {
    CLIENT_HELLO(1),
    BOARD_HELLO(2),
    APP_AUTH(3),
    AUTH_OK(4),
    CAPABILITIES_REQUEST(10),
    CAPABILITIES(11),
    HEARTBEAT(20),
    HEARTBEAT_ACK(21),
    ACTION(30),
    ACCEPTED(31),
    STARTED(32),
    DONE(33),
    REJECTED(34),
    CANCELLED(35),
    ERROR(36),
    STOP(40),
    STOPPED(41),
    QUERY_RESULT_REQUEST(50),
    QUERY_RESULT(51),
    STATUS(60),
    ;

    public companion object {
        private val byId: Map<Int, MessageType> = entries.associateBy { it.id }

        public fun fromId(id: Int): MessageType? = byId[id]
    }
}

public enum class ErrorCode(public val id: Int) {
    OK(0),
    BAD_VERSION(1),
    BAD_FRAME(2),
    FRAGMENT_TIMEOUT(3),
    AUTH_FAILED(4),
    NOT_AUTHENTICATED(5),
    REPLAY_DETECTED(6),
    UNKNOWN_TYPE(7),
    INVALID_JSON(8),
    INVALID_ACTION(9),
    ARG_OUT_OF_RANGE(10),
    BUSY(11),
    COMMAND_CONFLICT(12),
    COMMAND_STALE(13),
    USB_UNAVAILABLE(14),
    CALIBRATION_REQUIRED(15),
    RELEASE_FAILED(16),
    BUDGET_EXCEEDED(17),
    NOT_CONTROLLER(18),
    RATE_LIMITED(19),
    TOO_LARGE(20),
    ;

    public companion object {
        private val byId: Map<Int, ErrorCode> = entries.associateBy { it.id }

        public fun fromId(id: Int): ErrorCode? = byId[id]
    }
}

/** Thrown by the codecs. Every rejection carries a machine-readable [ErrorCode]. */
public class ProtocolException(
    public val code: ErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception("${code.name}: $message", cause)

// --- little-endian helpers -------------------------------------------------------------

public fun ByteArray.u8(offset: Int): Int {
    requireIndex(offset, 1)
    return this[offset].toInt() and 0xFF
}

public fun ByteArray.u16le(offset: Int): Int {
    requireIndex(offset, 2)
    return (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
}

public fun ByteArray.u32le(offset: Int): Long {
    requireIndex(offset, 4)
    var value = 0L
    for (i in 3 downTo 0) {
        value = (value shl 8) or (this[offset + i].toLong() and 0xFF)
    }
    return value
}

public fun ByteArray.u64le(offset: Int): Long {
    requireIndex(offset, 8)
    var value = 0L
    for (i in 7 downTo 0) {
        value = (value shl 8) or (this[offset + i].toLong() and 0xFF)
    }
    return value
}

public fun putU16le(target: ByteArray, offset: Int, value: Int) {
    target.requireIndex(offset, 2)
    target[offset] = (value and 0xFF).toByte()
    target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

public fun putU32le(target: ByteArray, offset: Int, value: Long) {
    target.requireIndex(offset, 4)
    for (i in 0..3) {
        target[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }
}

public fun putU64le(target: ByteArray, offset: Int, value: Long) {
    target.requireIndex(offset, 8)
    for (i in 0..7) {
        target[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }
}

public fun ByteArray.hex(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(digits[value ushr 4]).append(digits[value and 0x0F])
    }
    return out.toString()
}

public fun String.hexToBytes(): ByteArray {
    val clean = trim()
    require(clean.length % 2 == 0) { "hex length must be even" }
    val out = ByteArray(clean.length / 2)
    for (i in out.indices) {
        out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return out
}

private fun ByteArray.requireIndex(offset: Int, length: Int) {
    if (offset < 0 || length < 0 || offset + length > size) {
        throw ProtocolException(
            ErrorCode.BAD_FRAME,
            "read of $length byte(s) at $offset exceeds ${size} byte buffer",
        )
    }
}
