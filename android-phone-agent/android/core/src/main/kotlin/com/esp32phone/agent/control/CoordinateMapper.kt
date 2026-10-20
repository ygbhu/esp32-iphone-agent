package com.esp32phone.agent.control

import com.esp32phone.agent.protocol.ErrorCode
import com.esp32phone.agent.protocol.ProtocolException

public enum class Axis { X, Y }

/**
 * Maps normalised screenshot coordinates (model output, `0..1000`) to HID counts.
 *
 * A real implementation only exists after the in-app calibration flow has produced a fit for
 * the current device + display size/orientation (M2, not implemented in this checkpoint).
 * Until then [UncalibratedMapper] throws — it never invents a mapping, and callers must show
 * "未校准" instead of sending coordinates.
 */
public fun interface CoordinateMapper {
    public fun toCounts(axis: Axis, modelValue: Int): Int
}

public object UncalibratedMapper : CoordinateMapper {
    override fun toCounts(axis: Axis, modelValue: Int): Int = throw ProtocolException(
        ErrorCode.CALIBRATION_REQUIRED,
        "no calibration for axis $axis; refusing to map model coordinate $modelValue",
    )
}

/**
 * Deterministic linear mapper used by unit tests and by the debug harness only.
 *
 * It is deliberately *not* derived from any real device: it exists so that parsing and
 * range-checking can be tested without pretending calibration has happened.
 */
public class LinearTestMapper(
    private val countsPerAxis: Int = 4096,
    private val valid: Boolean = true,
) : CoordinateMapper {
    override fun toCounts(axis: Axis, modelValue: Int): Int {
        if (!valid) throw ProtocolException(ErrorCode.CALIBRATION_REQUIRED, "test mapper marked invalid")
        if (modelValue < 0 || modelValue > 1000) {
            throw ProtocolException(ErrorCode.ARG_OUT_OF_RANGE, "model coordinate $modelValue outside 0..1000")
        }
        val counts = ((modelValue.toLong() * countsPerAxis) + 500) / 1000
        return counts.coerceIn(0, countsPerAxis.toLong()).toInt()
    }
}
