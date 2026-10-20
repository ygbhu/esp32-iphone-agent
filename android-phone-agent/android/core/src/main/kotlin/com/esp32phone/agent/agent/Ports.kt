package com.esp32phone.agent.agent

import com.esp32phone.agent.control.HidAction
import com.esp32phone.agent.control.ModelDecision
import com.esp32phone.agent.protocol.ErrorCode

/** Supplies the newest captured frame, or null when there is no usable observation. */
public fun interface ScreenSource {
    public fun currentObservation(nowMs: Long): LiveObservation?
}

public class ModelRequest(
    public val generation: Long,
    public val stepIndex: Int,
    public val observation: LiveObservation,
    public val declaredCapabilities: Set<String>,
    public val taskInstruction: String,
)

/** Thrown by [ModelClient] implementations; the runner never executes on an error. */
public class ModelException(
    public val kind: ModelFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

public enum class ModelFailure {
    TIMEOUT,
    CANCELLED,
    REFUSED,
    TRUNCATED,
    INVALID_JSON,
    INVALID_ACTION,
    RATE_LIMITED,
    AUTH,
    NETWORK,
    BUDGET,

    /** The authority for this request was revoked before the answer could be used. */
    AUTHORITY_LOST,
}

public fun interface ModelClient {
    public fun decide(request: ModelRequest): ModelDecision
}

public class ActionRequest(
    public val commandId: Long,
    public val generation: Long,
    /** Revocable, generation- and action-bound dispatch permit. */
    public val permit: InputPermit,
    public val action: HidAction,
    /** Frame that authorises this action: verified again immediately before the call. */
    public val observation: LiveObservation,
    /** Frame the model actually looked at (may be older than [observation]). */
    public val decisionFrameId: Long,
    public val payload: ByteArray,
    /** SHA-256(payload)[0..16); the permit only authorises this exact action. */
    public val actionDigest: ByteArray,
)

public sealed class DeviceOutcome {
    public data object Accepted : DeviceOutcome()
    public data object Started : DeviceOutcome()
    public data object Done : DeviceOutcome()
    public class Rejected(public val code: ErrorCode, public val detail: String) : DeviceOutcome()
    public class Unknown(public val detail: String) : DeviceOutcome()
}

public interface DeviceSink {
    /**
     * Contract: every HID report this sink emits MUST be committed through
     * [InputPermit.tryDispatch] (which shares a monitor with [StopController.stop]).
     *
     * A sink must NOT do `isCurrent()` and then write: that is the check-then-act race the
     * permit exists to remove. It must also verify
     * [InputPermit.matchesAction] for the request and require request.generation == permit.generation, and keep each dispatch short and
     * non-blocking so a user stop is never delayed.
     */
    public fun execute(request: ActionRequest): DeviceOutcome

    /** Must be idempotent: cancels the running action, clears the queue and releases HID. */
    public fun requestStop(reason: String): DeviceOutcome
}

public sealed class VerificationVerdict {
    public data object Satisfied : VerificationVerdict()
    public class NotSatisfied(public val detail: String) : VerificationVerdict()
    public data object Unavailable : VerificationVerdict()
}

/**
 * Independent completion check. The model saying "done" is never sufficient (baseline §5).
 * No implementation exists yet (M3), so [UnavailableVerifier] is the only shipped one and
 * the runner refuses to report COMPLETED while it is in use.
 */
public fun interface Verifier {
    public fun verify(observation: LiveObservation, expected: String): VerificationVerdict
}

public object UnavailableVerifier : Verifier {
    override fun verify(observation: LiveObservation, expected: String): VerificationVerdict =
        VerificationVerdict.Unavailable
}
