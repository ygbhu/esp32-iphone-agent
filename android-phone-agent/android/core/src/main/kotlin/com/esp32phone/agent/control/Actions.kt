package com.esp32phone.agent.control

import com.esp32phone.agent.protocol.ErrorCode
import com.esp32phone.agent.protocol.JsonObject
import com.esp32phone.agent.protocol.ProtocolException
import com.esp32phone.agent.protocol.StrictJson

/**
 * Action schema shared by the model boundary and the firmware boundary.
 *
 * The model only ever sees normalised screenshot coordinates in `0..1000`. The firmware
 * only ever receives HID counts in `0..4096`. The app performs the mapping; the firmware
 * must never re-map `0..1000`. See `protocol/schema/wire-format.md` §6.
 */
public enum class KeyName {
    ENTER, BACKSPACE, ESC, TAB, ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT;

    public companion object {
        public fun fromWire(name: String): KeyName? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** A validated action expressed in HID counts, ready for the firmware. */
public sealed class HidAction {
    public data class Tap(val xCounts: Int, val yCounts: Int, val durationMs: Int) : HidAction()
    public data class Swipe(
        val x0Counts: Int,
        val y0Counts: Int,
        val x1Counts: Int,
        val y1Counts: Int,
        val durationMs: Int,
    ) : HidAction()
    public data class LongPress(val xCounts: Int, val yCounts: Int, val durationMs: Int) : HidAction()
    public data class Scroll(val amount: Int) : HidAction()
    public data class TypeAscii(val text: String) : HidAction()
    public data class Key(val key: KeyName) : HidAction()

    public fun operationName(): String = when (this) {
        is Tap -> "tap"
        is Swipe -> "swipe"
        is LongPress -> "long_press"
        is Scroll -> "scroll"
        is TypeAscii -> "type_ascii"
        is Key -> "key"
    }

    /** Canonical JSON body used for the wire payload and for command de-duplication. */
    public fun toWireJson(): String = when (this) {
        is Tap -> """{"op":"tap","x_counts":$xCounts,"y_counts":$yCounts,"duration_ms":$durationMs}"""
        is Swipe ->
            """{"op":"swipe","x0_counts":$x0Counts,"y0_counts":$y0Counts,""" +
                """"x1_counts":$x1Counts,"y1_counts":$y1Counts,"duration_ms":$durationMs}"""
        is LongPress ->
            """{"op":"long_press","x_counts":$xCounts,"y_counts":$yCounts,"duration_ms":$durationMs}"""
        is Scroll -> """{"op":"scroll","amount":$amount}"""
        is TypeAscii -> """{"op":"type_ascii","text":${Actions.quoteAscii(text)}}"""
        is Key -> """{"op":"key","key":"${key.name.lowercase()}"}"""
    }
}

public object Actions {

    public const val MAX_COUNT: Int = 4096
    public const val MIN_MODEL_COORD: Int = 0
    public const val MAX_MODEL_COORD: Int = 1000
    public const val MAX_ASCII_LEN: Int = 128

    public const val TAP_MIN_MS: Int = 30
    public const val TAP_MAX_MS: Int = 1000
    public const val SWIPE_MIN_MS: Int = 50
    public const val SWIPE_MAX_MS: Int = 3000
    public const val LONG_PRESS_MIN_MS: Int = 400
    public const val LONG_PRESS_MAX_MS: Int = 3000
    public const val SCROLL_MIN: Int = -10
    public const val SCROLL_MAX: Int = 10

    /** Hard ceiling for any single firmware action, including homing and release. */
    public const val ACTION_TOTAL_LIMIT_MS: Int = 4000

    public fun quoteAscii(text: String): String {
        val out = StringBuilder(text.length + 2)
        out.append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                else -> out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    public fun validate(action: HidAction) {
        when (action) {
            is HidAction.Tap -> {
                requireCount("x_counts", action.xCounts)
                requireCount("y_counts", action.yCounts)
                requireRange("duration_ms", action.durationMs, TAP_MIN_MS, TAP_MAX_MS)
            }
            is HidAction.Swipe -> {
                requireCount("x0_counts", action.x0Counts)
                requireCount("y0_counts", action.y0Counts)
                requireCount("x1_counts", action.x1Counts)
                requireCount("y1_counts", action.y1Counts)
                requireRange("duration_ms", action.durationMs, SWIPE_MIN_MS, SWIPE_MAX_MS)
                if (action.x0Counts == action.x1Counts && action.y0Counts == action.y1Counts) {
                    throw ProtocolException(ErrorCode.ARG_OUT_OF_RANGE, "swipe start equals end")
                }
            }
            is HidAction.LongPress -> {
                requireCount("x_counts", action.xCounts)
                requireCount("y_counts", action.yCounts)
                requireRange("duration_ms", action.durationMs, LONG_PRESS_MIN_MS, LONG_PRESS_MAX_MS)
            }
            is HidAction.Scroll -> {
                requireRange("amount", action.amount, SCROLL_MIN, SCROLL_MAX)
                if (action.amount == 0) {
                    throw ProtocolException(ErrorCode.ARG_OUT_OF_RANGE, "scroll amount must be non-zero")
                }
            }
            is HidAction.TypeAscii -> {
                if (action.text.isEmpty()) {
                    throw ProtocolException(ErrorCode.ARG_OUT_OF_RANGE, "type_ascii text is empty")
                }
                if (action.text.length > MAX_ASCII_LEN) {
                    throw ProtocolException(
                        ErrorCode.ARG_OUT_OF_RANGE,
                        "type_ascii text length ${action.text.length} > $MAX_ASCII_LEN",
                    )
                }
                for (c in action.text) {
                    if (c.code < 0x20 || c.code > 0x7E) {
                        throw ProtocolException(
                            ErrorCode.ARG_OUT_OF_RANGE,
                            "type_ascii contains non-printable/non-ASCII char U+${c.code.toString(16)}",
                        )
                    }
                }
            }
            is HidAction.Key -> Unit // enum is already closed
        }
        if (durationOf(action) > ACTION_TOTAL_LIMIT_MS) {
            throw ProtocolException(
                ErrorCode.ARG_OUT_OF_RANGE,
                "action duration ${durationOf(action)} exceeds total limit $ACTION_TOTAL_LIMIT_MS",
            )
        }
    }

    private fun durationOf(action: HidAction): Int = when (action) {
        is HidAction.Tap -> action.durationMs
        is HidAction.Swipe -> action.durationMs
        is HidAction.LongPress -> action.durationMs
        else -> 0
    }

    private fun requireCount(name: String, value: Int) {
        if (value < 0 || value > MAX_COUNT) {
            throw ProtocolException(ErrorCode.ARG_OUT_OF_RANGE, "$name $value outside 0..$MAX_COUNT")
        }
    }

    private fun requireRange(name: String, value: Int, min: Int, max: Int) {
        if (value < min || value > max) {
            throw ProtocolException(ErrorCode.ARG_OUT_OF_RANGE, "$name $value outside $min..$max")
        }
    }
}

/**
 * A model decision. Parsing is strict: unknown actions, unknown fields, out-of-range
 * arguments and any attempt to name a session/observation/device are rejected outright
 * (baseline §8: the model cannot address the device).
 */
public sealed class ModelDecision {

    public class Act(
        public val action: HidAction,
        public val reason: String,
        public val expected: String,
    ) : ModelDecision()

    public class Done(
        public val reason: String,
        public val expected: String,
    ) : ModelDecision()

    public companion object {
        /**
         * @param countsPerUnit calibration factor: HID counts for one model unit (0..1000).
         *   Supplied by the app after calibration; a missing calibration must be handled by
         *   the caller (it must not fabricate one).
         */
        public fun parse(json: String, calibration: CoordinateMapper): ModelDecision {
            val root = StrictJson.parseObject(json)
            return parse(root, calibration)
        }

        public fun parse(root: JsonObject, calibration: CoordinateMapper): ModelDecision {
            val isDone = root.get("done") != null
            val allowed = if (isDone) {
                setOf("done", "reason", "expected")
            } else {
                setOf("action", "reason", "expected")
            }
            root.rejectUnknown(allowed)

            val reason = root.requireString("reason")
            val expected = root.requireString("expected")

            if (isDone) {
                if (!root.require("done").asBoolean()) {
                    throw ProtocolException(ErrorCode.INVALID_ACTION, "done must be true when present")
                }
                return Done(reason, expected)
            }

            val action = root.require("action").asObject()
            val op = action.requireString("op")
            val hid = when (op) {
                "tap" -> {
                    action.rejectUnknown(setOf("op", "x", "y", "duration_ms"))
                    HidAction.Tap(
                        calibration.toCounts(Axis.X, action.require("x").asLong(0, 1000).toInt()),
                        calibration.toCounts(Axis.Y, action.require("y").asLong(0, 1000).toInt()),
                        action.require("duration_ms").asLong(0, Int.MAX_VALUE.toLong()).toInt(),
                    )
                }
                "swipe" -> {
                    action.rejectUnknown(setOf("op", "x0", "y0", "x1", "y1", "duration_ms"))
                    HidAction.Swipe(
                        calibration.toCounts(Axis.X, action.require("x0").asLong(0, 1000).toInt()),
                        calibration.toCounts(Axis.Y, action.require("y0").asLong(0, 1000).toInt()),
                        calibration.toCounts(Axis.X, action.require("x1").asLong(0, 1000).toInt()),
                        calibration.toCounts(Axis.Y, action.require("y1").asLong(0, 1000).toInt()),
                        action.require("duration_ms").asLong(0, Int.MAX_VALUE.toLong()).toInt(),
                    )
                }
                "long_press" -> {
                    action.rejectUnknown(setOf("op", "x", "y", "duration_ms"))
                    HidAction.LongPress(
                        calibration.toCounts(Axis.X, action.require("x").asLong(0, 1000).toInt()),
                        calibration.toCounts(Axis.Y, action.require("y").asLong(0, 1000).toInt()),
                        action.require("duration_ms").asLong(0, Int.MAX_VALUE.toLong()).toInt(),
                    )
                }
                "scroll" -> {
                    action.rejectUnknown(setOf("op", "amount"))
                    HidAction.Scroll(action.require("amount").asLong(-10, 10).toInt())
                }
                "type_ascii" -> {
                    action.rejectUnknown(setOf("op", "text"))
                    HidAction.TypeAscii(action.require("text").asString())
                }
                "key" -> {
                    action.rejectUnknown(setOf("op", "key"))
                    val name = action.require("key").asString()
                    val key = KeyName.fromWire(name)
                        ?: throw ProtocolException(ErrorCode.INVALID_ACTION, "unsupported key \"$name\"")
                    HidAction.Key(key)
                }
                else -> throw ProtocolException(ErrorCode.INVALID_ACTION, "unknown action \"$op\"")
            }
            Actions.validate(hid)
            return Act(hid, reason, expected)
        }

        private fun JsonObject.requireString(key: String): String {
            val value = require(key).asString()
            if (value.length > 512) {
                throw ProtocolException(ErrorCode.ARG_OUT_OF_RANGE, "$key longer than 512 chars")
            }
            return value
        }
    }
}
