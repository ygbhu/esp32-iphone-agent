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
 */
public class StopController(initialGeneration: Long = 1) {

    /** Observability hook used by tests to record revoke/issue ordering. */
    public fun interface Listener {
        public fun onAuthorityChanged(oldGeneration: Long, newGeneration: Long, kind: ChangeKind)
    }

    public enum class ChangeKind { STOP, NEW_TASK }

    public var listener: Listener? = null

    private val generation = AtomicLong(initialGeneration)

    @Volatile
    private var stopped: Boolean = false

    public val current: Long get() = generation.get()

    public val isStopped: Boolean get() = stopped

    /** Revokes all current authority and returns the new (dead) generation. */
    public fun stop(): Long {
        stopped = true
        val previous = generation.get()
        val next = generation.incrementAndGet()
        listener?.onAuthorityChanged(previous, next, ChangeKind.STOP)
        return next
    }

    /** Begins a new task: new generation, authority available again. */
    public fun beginNewTask(): Long {
        stopped = false
        val previous = generation.get()
        val next = generation.incrementAndGet()
        listener?.onAuthorityChanged(previous, next, ChangeKind.NEW_TASK)
        return next
    }

    public fun isCurrent(candidate: Long): Boolean = candidate == generation.get() && !stopped

    /**
     * Issues a single-use token for one device write. The device sink must re-check
     * [InputPermit.isCurrent] immediately before generating HID reports; the core checks it
     * immediately before and after the call. Neither side alone is the contract.
     *
     * @return null when authority is already revoked
     */
    public fun issuePermit(): InputPermit? =
        if (stopped) null else InputPermit(this, generation.get())
}

/** Generation-bound capability to produce HID output once. */
public class InputPermit internal constructor(
    private val authority: StopController,
    public val generation: Long,
) {
    public fun isCurrent(): Boolean = authority.isCurrent(generation)
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
