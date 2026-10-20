package com.esp32phone.agent.agent

import java.util.concurrent.atomic.AtomicLong

public fun interface Clock {
    public fun nowMs(): Long

    public companion object {
        public val SYSTEM: Clock = Clock { System.nanoTime() / 1_000_000 }
    }
}

/**
 * Task-level execution authority.
 *
 * Stopping increments the generation *first*, so every in-flight decision, device command
 * or result callback carrying an older generation is dead on arrival. A late model response
 * or a late device callback can therefore never revive input (baseline §7).
 *
 * Local commit boundary: [stop] and [InputPermit.tryDispatch] share one short monitor, so a
 * HID report can never be committed after the authority that authorised it was revoked.
 * The monitor is only ever held for the generation counter and a short local write — never
 * around screen capture, model calls, verification or a device acknowledgement.
 *
 * Scope warning: this is a **local App guarantee**. It cannot stop reports that the board has
 * already been sent before it receives `stop`; the 300 ms / 1.5 s targets in the baseline are
 * engineering goals that need hardware measurement, not something this lock proves.
 */
public class StopController(initialGeneration: Long = 1) {

    /** Observability hook used by tests to record revoke/issue ordering. */
    public fun interface Listener {
        public fun onAuthorityChanged(oldGeneration: Long, newGeneration: Long, kind: ChangeKind)
    }

    public enum class ChangeKind { STOP, NEW_TASK }

    public var listener: Listener? = null

    private val generation = AtomicLong(initialGeneration)

    /** Guards the generation/revoked flags and the (short) local dispatch commit. */
    private val commitLock = Any()

    @Volatile
    private var stopped: Boolean = false

    public val current: Long get() = generation.get()

    public val isStopped: Boolean get() = stopped

    /** Revokes all current authority and returns the new (dead) generation. */
    public fun stop(): Long {
        val next = synchronized(commitLock) {
            stopped = true
            generation.incrementAndGet()
        }
        listener?.onAuthorityChanged(next - 1, next, ChangeKind.STOP)
        return next
    }

    /** Begins a new task: new generation, authority available again. */
    public fun beginNewTask(): Long {
        val next = synchronized(commitLock) {
            stopped = false
            generation.incrementAndGet()
        }
        listener?.onAuthorityChanged(next - 1, next, ChangeKind.NEW_TASK)
        return next
    }

    public fun isCurrent(candidate: Long): Boolean = candidate == generation.get() && !stopped

    /**
     * The only way to produce output for [expectedGeneration].
     *
     * [emit] runs inside the same monitor as [stop], so either the report is written while
     * the generation is still valid, or it is never written at all — there is no window
     * between "check" and "act".
     *
     * [emit] MUST be short and must not block (no I/O, no waiting on BLE callbacks): a slow
     * block would delay a user stop.
     *
     * @return true when [emit] ran and the generation was current
     */
    internal fun dispatchIfCurrent(expectedGeneration: Long, emit: () -> Unit): Boolean =
        synchronized(commitLock) {
            if (stopped || generation.get() != expectedGeneration) {
                return false
            }
            emit()
            true
        }

    /**
     * Issues a dispatch permit bound to one generation and one command/action.
     *
     * A permit is **revocable, not single-use**: one action legitimately needs several short
     * HID reports (move, press, release), so the sink may call [InputPermit.tryDispatch]
     * repeatedly — but every single report must go through it, and once authority is revoked
     * every further dispatch fails.
     *
     * @return null when authority is already revoked
     */
    public fun issuePermit(expectedGeneration: Long, commandId: Long, actionDigest: ByteArray): InputPermit? {
        synchronized(commitLock) {
            if (stopped || generation.get() != expectedGeneration) return null
            return InputPermit(this, expectedGeneration, commandId, actionDigest.copyOf())
        }
    }
}

/**
 * Generation- and action-bound capability to emit HID reports.
 *
 * All output MUST go through [tryDispatch]; a bare [isCurrent] check followed by a write is a
 * check-then-act race and is not a valid way to produce input.
 */
public class InputPermit internal constructor(
    private val authority: StopController,
    public val generation: Long,
    public val commandId: Long,
    private val actionDigest: ByteArray,
) {
    @Volatile
    private var dispatched: Int = 0

    /** Number of committed reports (diagnostics/tests only). */
    public val dispatchCount: Int get() = dispatched

    /** Hint only; never use as a substitute for [tryDispatch]. */
    public fun isCurrent(): Boolean = authority.isCurrent(generation)

    public fun matchesAction(commandId: Long, digest: ByteArray): Boolean =
        commandId == this.commandId && java.security.MessageDigest.isEqual(actionDigest, digest)

    /**
     * Commits one short local HID report inside the authority's commit boundary.
     *
     * @return false when the generation was already revoked; [emit] did not run
     */
    public fun tryDispatch(emit: () -> Unit): Boolean {
        val ran = authority.dispatchIfCurrent(generation) {
            emit()
            dispatched += 1
        }
        return ran
    }
}

public enum class AgentState {
    IDLE,
    OBSERVING,
    DECIDING,
    EXECUTING,
    VERIFYING,
    PAUSED,
    COMPLETED,
    FAILED,
    STOPPED,
}

public enum class PauseReason {
    NO_FRESH_FRAME,
    ACTION_UNKNOWN,
    MODEL_ERROR,
    MODEL_TIMEOUT,
    MODEL_DONE_UNVERIFIED,
    ACTION_PENDING,
    BUDGET_EXCEEDED,
    CALIBRATION_REQUIRED,
    DEVICE_REJECTED,
    VERIFICATION_FAILED,
    USER_PAUSE,
}

public enum class FreshnessFailure {
    NO_OBSERVATION,
    NO_FRESH_FRAME,
    STALE_FRAME,
    OLD_PROJECTION_EPOCH,
    ROTATION_CHANGED,
    SIZE_CHANGED,
    FRAME_ALREADY_USED,
}

/**
 * One captured screen, with the metadata the protocol requires (baseline §13).
 *
 * @param captureTimeMs platform timestamp only when its time base has been verified; it is
 *   never used as a substitute for [receivedAtMs].
 */
public class LiveObservation(
    public val projectionEpoch: Long,
    public val frameId: Long,
    public val receivedAtMs: Long,
    public val captureTimeMs: Long?,
    public val width: Int,
    public val height: Int,
    public val rotation: Int,
)

public sealed class FreshnessVerdict {
    public data object Fresh : FreshnessVerdict()
    public class Rejected(public val failure: FreshnessFailure) : FreshnessVerdict()
}

/**
 * Decides whether an observation may authorise input.
 *
 * Rules (baseline §13 / A04): a new projection epoch, a rotation or size change invalidates
 * old coordinates; a frame older than [maxFrameAgeMs] is unusable; the same frame may not be
 * reused for a second action; "no new frame" is never treated as "screen unchanged".
 */
public class ObservationPolicy(
    public val maxFrameAgeMs: Long = DEFAULT_MAX_FRAME_AGE_MS,
) {
    public companion object {
        public const val DEFAULT_MAX_FRAME_AGE_MS: Long = 1500
    }

    private var epoch: Long = -1
    private var rotation: Int = -1
    private var width: Int = -1
    private var height: Int = -1
    private var lastUsedFrameId: Long = -1

    public fun check(observation: LiveObservation?, nowMs: Long): FreshnessVerdict {
        if (observation == null) return FreshnessVerdict.Rejected(FreshnessFailure.NO_OBSERVATION)
        if (epoch >= 0 && observation.projectionEpoch != epoch) {
            return FreshnessVerdict.Rejected(FreshnessFailure.OLD_PROJECTION_EPOCH)
        }
        if (rotation >= 0 && observation.rotation != rotation) {
            return FreshnessVerdict.Rejected(FreshnessFailure.ROTATION_CHANGED)
        }
        if (width >= 0 && (observation.width != width || observation.height != height)) {
            return FreshnessVerdict.Rejected(FreshnessFailure.SIZE_CHANGED)
        }
        if (observation.frameId <= lastUsedFrameId) {
            return FreshnessVerdict.Rejected(FreshnessFailure.FRAME_ALREADY_USED)
        }
        if (nowMs - observation.receivedAtMs > maxFrameAgeMs) {
            return FreshnessVerdict.Rejected(FreshnessFailure.STALE_FRAME)
        }
        if (observation.receivedAtMs > nowMs) {
            // A frame "from the future" means the clock domains were mixed up.
            return FreshnessVerdict.Rejected(FreshnessFailure.STALE_FRAME)
        }
        return FreshnessVerdict.Fresh
    }

    /** Records the observed geometry; called when an observation is bound to an action. */
    public fun bind(observation: LiveObservation) {        epoch = observation.projectionEpoch
        rotation = observation.rotation
        width = observation.width
        height = observation.height
    }

    public fun markUsed(observation: LiveObservation) {
        lastUsedFrameId = observation.frameId
    }

    /**
     * Re-check immediately before the device is asked to act. Differs from [check] only in
     * that the frame bound to this action is allowed (it has not been marked used yet) but
     * must still be the same frame, the same epoch, the same geometry, and not stale.
     */
    public fun checkForAction(observation: LiveObservation, nowMs: Long): FreshnessVerdict {
        if (observation.projectionEpoch != epoch) {
            return FreshnessVerdict.Rejected(FreshnessFailure.OLD_PROJECTION_EPOCH)
        }
        if (observation.rotation != rotation) {
            return FreshnessVerdict.Rejected(FreshnessFailure.ROTATION_CHANGED)
        }
        if (observation.width != width || observation.height != height) {
            return FreshnessVerdict.Rejected(FreshnessFailure.SIZE_CHANGED)
        }
        if (nowMs - observation.receivedAtMs > maxFrameAgeMs) {
            return FreshnessVerdict.Rejected(FreshnessFailure.STALE_FRAME)
        }
        return FreshnessVerdict.Fresh
    }

    public fun invalidate() {
        epoch = -1
        rotation = -1
        width = -1
        height = -1
        lastUsedFrameId = -1
    }

    public val lastUsed: Long get() = lastUsedFrameId
}

public enum class BudgetReason { ACTIONS, DURATION, MODEL_CALLS, CONSECUTIVE_FAILURES }

public sealed class BudgetOutcome {
    public data object Ok : BudgetOutcome()
    public class Exceeded(public val reason: BudgetReason) : BudgetOutcome()
}

/** Per-task limits. These are app limits, not gateway balance guarantees (baseline §5). */
public class AgentBudget(
    public val maxActions: Int = 40,
    public val maxDurationMs: Long = 600_000,
    public val maxModelCalls: Int = 90,
    public val maxConsecutiveFailures: Int = 3,
) {
    private var actionsUsed: Int = 0
    private var modelCallsUsed: Int = 0
    private var consecutiveFailures: Int = 0
    public var startedAtMs: Long = -1
        private set

    public val actions: Int get() = actionsUsed
    public val modelCalls: Int get() = modelCallsUsed
    public val failuresInARow: Int get() = consecutiveFailures

    public fun start(nowMs: Long) {
        startedAtMs = nowMs
        actionsUsed = 0
        modelCallsUsed = 0
        consecutiveFailures = 0
    }

    private fun deadlineExceeded(nowMs: Long): Boolean =
        startedAtMs >= 0 && nowMs - startedAtMs > maxDurationMs

    /** Pure deadline check, used to re-verify the task duration after long external calls. */
    public fun checkDuration(nowMs: Long): BudgetOutcome =
        if (deadlineExceeded(nowMs)) BudgetOutcome.Exceeded(BudgetReason.DURATION) else BudgetOutcome.Ok

    public fun tryConsumeModelCall(nowMs: Long): BudgetOutcome {
        if (deadlineExceeded(nowMs)) return BudgetOutcome.Exceeded(BudgetReason.DURATION)
        if (modelCallsUsed >= maxModelCalls) return BudgetOutcome.Exceeded(BudgetReason.MODEL_CALLS)
        modelCallsUsed += 1
        return BudgetOutcome.Ok
    }

    public fun tryConsumeAction(nowMs: Long): BudgetOutcome {
        if (deadlineExceeded(nowMs)) return BudgetOutcome.Exceeded(BudgetReason.DURATION)
        if (actionsUsed >= maxActions) return BudgetOutcome.Exceeded(BudgetReason.ACTIONS)
        actionsUsed += 1
        return BudgetOutcome.Ok
    }

    public fun recordSuccess() {
        consecutiveFailures = 0
    }

    public fun recordFailure(nowMs: Long): BudgetOutcome {
        if (deadlineExceeded(nowMs)) return BudgetOutcome.Exceeded(BudgetReason.DURATION)
        consecutiveFailures += 1
        if (consecutiveFailures >= maxConsecutiveFailures) {
            return BudgetOutcome.Exceeded(BudgetReason.CONSECUTIVE_FAILURES)
        }
        return BudgetOutcome.Ok
    }
}
