package com.esp32phone.agent.agent

import com.esp32phone.agent.control.HidAction
import com.esp32phone.agent.control.ModelDecision
import com.esp32phone.agent.protocol.Crypto
import com.esp32phone.agent.protocol.ErrorCode

/** Allocates command ids for one device session; never resets on a new task. */
public class CommandIdAllocator(initial: Long = 0) {
    private var last: Long = initial

    public val lastIssued: Long get() = last

    public fun next(): Long {
        last += 1
        return last
    }

    public fun reset() {
        last = 0
    }
}

/** Diagnostic record for a command whose outcome died with its generation. Never task state. */
public class OrphanedCommand(
    public val generation: Long,
    public val commandId: Long,
    public val detail: String,
)

public sealed class StepResult {
    public data object NoWork : StepResult()

    /**
     * The step was abandoned because its generation is no longer current. When a callback for
     * a dead generation returns late, this is the *only* result it produces: it writes no
     * state, no `lastError`, no pending command and no policy change for the newer task.
     */
    public class Stale(
        public val detail: String,
        /** Command whose outcome is now unknown because authority died mid-flight. */
        public val abandonedCommandId: Long? = null,
    ) : StepResult()

    public class Executed(
        public val commandId: Long,
        public val action: HidAction,
        public val outcome: DeviceOutcome,
    ) : StepResult()

    /**
     * The device accepted the command but has not confirmed the final result yet. The task
     * stays with a single action in flight; no further input may be sent.
     */
    public class Pending(
        public val commandId: Long,
        public val outcome: DeviceOutcome,
    ) : StepResult()

    public class Completed(public val detail: String) : StepResult()

    public class Paused(public val reason: PauseReason, public val detail: String) : StepResult()

    public class Rejected(public val code: ErrorCode, public val detail: String) : StepResult()

    public class BudgetExceeded(
        public val reason: BudgetReason,
        public val stop: StopResult,
    ) : StepResult()
}

public class StopResult(
    public val generation: Long,
    public val deviceOutcome: DeviceOutcome?,
    public val deviceStopError: String?,
)

/**
 * Single-task, single-action agent loop (baseline §5).
 *
 * Ordering guarantees:
 *  1. stop revokes the generation and only then asks the device to stop; budget exhaustion
 *     and device exceptions do the same;
 *  2. every external call (screen, model, verifier, device) is followed by a fresh clock
 *     read, an authority check and a freshness re-check of *both* the bound frame and the
 *     newly captured frame — a slow capture cannot authorise input on an aged observation;
 *  3. HID output only happens through [InputPermit.tryDispatch], which shares a monitor with
 *     [StopController.stop] (no check-then-act window). This is a local App guarantee; it
 *     does not claim the board already stopped before it received `stop`;
 *  4. callbacks belonging to a dead generation write nothing — not state, not `lastError`,
 *     not the pending command, not the observation policy — and never stop a newer task;
 *  5. the model's own "done" never becomes COMPLETED without an independent verifier, and a
 *     stop that lands during verification still wins;
 *  6. command ids increase for the whole device session: a new task never restarts at 1.
 */
public class AgentCore(
    private val clock: Clock,
    private val screen: ScreenSource,
    private val model: ModelClient,
    private val device: DeviceSink,
    private val budget: AgentBudget,
    private val authority: StopController,
    private val verifier: Verifier = UnavailableVerifier,
    private val policy: ObservationPolicy = ObservationPolicy(),
    private val declaredCapabilities: Set<String> = setOf(
        "tap", "swipe", "long_press", "scroll", "type_ascii", "key",
    ),
) {
    public var state: AgentState = AgentState.IDLE
        private set

    public var pauseReason: PauseReason? = null
        private set

    public var lastError: String? = null
        private set

    private val commandIds = CommandIdAllocator()
    private var stepIndex: Int = 0
    private var task: String = ""
    private var pendingCommand: Long? = null

    /** Generation of the task currently owned by this core; callbacks for older ones are dead. */
    private var taskGeneration: Long = -1

    private val orphanedCommands = ArrayList<OrphanedCommand>()

    public val commandsIssued: Long get() = commandIds.lastIssued
    public val steps: Int get() = stepIndex
    public val currentGeneration: Long get() = authority.current
    public val hasPendingAction: Boolean get() = pendingCommand != null
    public val pendingCommandId: Long? get() = pendingCommand
    public val ownedGeneration: Long get() = taskGeneration

    /**
     * Commands whose outcome died with their generation. Diagnostic only: never consulted by
     * the state machine, never used to block a newer task, bounded to the last 32 entries.
     */
    public val orphaned: List<OrphanedCommand> get() = orphanedCommands.toList()

    /**
     * Starts a new task. Keeps the command-id watermark because the device session is the
     * same; resets the budget because this is a new task, not a resume.
     */
    public fun beginTask(instruction: String, nowMs: Long = clock.nowMs()): Long {
        check(pendingCommand == null) {
            "cannot start a new task while command $pendingCommand has an unknown/pending result"
        }
        val generation = authority.beginNewTask()
        taskGeneration = generation
        task = instruction
        state = AgentState.IDLE
        pauseReason = null
        lastError = null
        stepIndex = 0
        policy.invalidate()
        budget.start(nowMs)
        return generation
    }

    /**
     * Resumes after a pause. Revokes the previous generation (so late responses die) but
     * deliberately keeps the budget consumed so far: pausing and resuming cannot be used to
     * reset the action/time/model-call/consecutive-failure limits.
     */
    public fun resumeAfterPause(): Long {
        check(pendingCommand == null) {
            "cannot resume while command $pendingCommand has an unknown/pending result"
        }
        val generation = authority.beginNewTask()
        taskGeneration = generation
        state = AgentState.IDLE
        pauseReason = null
        lastError = null
        policy.invalidate()
        return generation
    }

    /**
     * Must be called when a brand-new device session is authenticated. Only then may command
     * ids restart; the app must still not replay its old queue.
     */
    public fun onNewDeviceSession(): Boolean {
        if (pendingCommand != null) return false
        if (state == AgentState.EXECUTING || state == AgentState.DECIDING || state == AgentState.OBSERVING) {
            return false
        }
        commandIds.reset()
        policy.invalidate()
        return true
    }

    /** Explicit user decision to drop an unresolved action. Never re-sends anything. */
    public fun abandonPendingAction(): Boolean {
        if (pendingCommand == null) return false
        pendingCommand = null
        state = AgentState.PAUSED
        pauseReason = PauseReason.USER_PAUSE
        return true
    }

    /**
     * Revokes authority first, then asks the device to stop. The device result is returned
     * as-is: a failed release must be shown, never reported as "confirmed released".
     */
    public fun stop(reason: String): StopResult {
        val generation = authority.stop()
        if (state != AgentState.STOPPED) {
            state = AgentState.STOPPED
            policy.invalidate()
        }
        return requestDeviceStop(generation, reason)
    }

    public fun step(nowMs: Long = clock.nowMs()): StepResult {
        if (task.isEmpty()) return StepResult.NoWork
        val generation = authority.current
        if (!owned(generation)) return StepResult.Stale("task authority revoked")
        when (state) {
            AgentState.PAUSED, AgentState.COMPLETED, AgentState.FAILED, AgentState.STOPPED -> return StepResult.NoWork
            else -> Unit
        }

        stepIndex += 1
        state = AgentState.OBSERVING
        var now = maxOf(nowMs, clock.nowMs())
        val observation = try {
            screen.currentObservation(now)
        } catch (e: Exception) {
            if (!owned(generation)) return stale(generation, "stop arrived during observation")
            return pauseAndReport(generation, PauseReason.NO_FRESH_FRAME, e.message ?: "screen source failed")
        }
        // Re-read the clock: capture may have taken time, so an old nowMs must not be reused.
        now = clock.nowMs()
        if (!owned(generation)) return stale(generation, "stop arrived during observation")
        when (val verdict = policy.check(observation, now)) {
            is FreshnessVerdict.Rejected ->
                return pauseAndReport(generation, PauseReason.NO_FRESH_FRAME, "observation rejected: ${verdict.failure.name}")
            FreshnessVerdict.Fresh -> Unit
        }
        val boundObservation = observation
            ?: return pauseAndReport(generation, PauseReason.NO_FRESH_FRAME, "no observation")
        policy.bind(boundObservation)

        state = AgentState.DECIDING
        when (val budgetResult = budget.tryConsumeModelCall(clock.nowMs())) {
            is BudgetOutcome.Exceeded -> return budgetStop(generation, budgetResult.reason)
            BudgetOutcome.Ok -> Unit
        }

        val decision = try {
            model.decide(
                ModelRequest(
                    generation = generation,
                    stepIndex = stepIndex,
                    observation = boundObservation,
                    declaredCapabilities = declaredCapabilities,
                    taskInstruction = task,
                ),
            )
        } catch (e: ModelException) {
            if (!owned(generation)) return stale(generation, "model failed after the task lost authority")
            val reason = when (e.kind) {
                ModelFailure.TIMEOUT -> PauseReason.MODEL_TIMEOUT
                ModelFailure.BUDGET -> PauseReason.BUDGET_EXCEEDED
                ModelFailure.AUTHORITY_LOST -> PauseReason.ACTION_UNKNOWN
                else -> PauseReason.MODEL_ERROR
            }
            return pauseAndReport(generation, reason, e.message ?: e.kind.name)
        }

        // A late response dies here: authority and freshness are re-checked with a fresh clock.
        if (!owned(generation)) return stale(generation, "model response arrived after the task was stopped")
        val currentAfterModel = when (val r = revalidate(generation, boundObservation, "model call")) {
            is Revalidation.Failed -> return r.result
            is Revalidation.Ok -> r.observation
        }

        return when (decision) {
            is ModelDecision.Done -> onModelDone(generation, boundObservation, decision)
            is ModelDecision.Act -> onModelAction(generation, boundObservation, currentAfterModel, decision)
        }
    }

    private fun onModelDone(
        generation: Long,
        observation: LiveObservation,
        decision: ModelDecision.Done,
    ): StepResult {
        if (!owned(generation)) return stale(generation, "stop arrived before verification")
        state = AgentState.VERIFYING

        val verdict = try {
            verifier.verify(observation, decision.expected)
        } catch (e: Exception) {
            if (!owned(generation)) return stale(generation, "stop arrived during verification")
            return pauseAndReport(generation, PauseReason.VERIFICATION_FAILED, e.message ?: "verifier failed")
        }
        // The verifier may be slow: a stop issued while it ran must still win, and a callback
        // for a dead task must not touch the new one.
        if (!owned(generation)) return stale(generation, "stop arrived during verification")
        when (val r = revalidate(generation, observation, "verification")) {
            is Revalidation.Failed -> return r.result
            is Revalidation.Ok -> Unit
        }

        return when (verdict) {
            VerificationVerdict.Satisfied -> {
                state = AgentState.COMPLETED
                StepResult.Completed(decision.expected)
            }
            VerificationVerdict.Unavailable -> pauseAndReport(
                generation,
                PauseReason.MODEL_DONE_UNVERIFIED,
                "model claimed done; independent verification is not implemented",
            )
            is VerificationVerdict.NotSatisfied ->
                pauseAndReport(generation, PauseReason.VERIFICATION_FAILED, verdict.detail)
        }
    }

    private fun onModelAction(
        generation: Long,
        observation: LiveObservation,
        currentObservation: LiveObservation,
        decision: ModelDecision.Act,
    ): StepResult {
        if (!owned(generation)) return stale(generation, "stop arrived before execution")
        val authorisedFrame = when (val r = revalidate(generation, observation, "pre-execution")) {
            is Revalidation.Failed -> return r.result
            is Revalidation.Ok -> r.observation
        }

        val payload = decision.action.toWireJson().toByteArray(Charsets.UTF_8)
        val digest = Crypto.sha256(payload).copyOf(16)
        val commandId = commandIds.next()
        val permit = authority.issuePermit(generation, commandId, digest)
            ?: return stale(generation, "no input permit: authority already revoked")
        when (val budgetResult = budget.tryConsumeAction(clock.nowMs())) {
            is BudgetOutcome.Exceeded -> return budgetStop(generation, budgetResult.reason)
            BudgetOutcome.Ok -> Unit
        }

        val request = ActionRequest(
            commandId = commandId,
            generation = generation,
            permit = permit,
            action = decision.action,
            observation = authorisedFrame,
            decisionFrameId = maxOf(observation.frameId, currentObservation.frameId),
            payload = payload,
            actionDigest = digest,
        )

        state = AgentState.EXECUTING
        val outcome = try {
            device.execute(request)
        } catch (e: Exception) {
            // A callback from a dead generation must never revoke the newer task's authority.
            if (!owned(generation)) {
                return stale(
                    generation,
                    "device threw after the task lost authority: ${e.message}",
                    commandId,
                )
            }
            revokeAndRelease("device exception during action")
            if (generation != taskGeneration) {
                return stale(generation, "task replaced during device release", commandId)
            }
            pendingCommand = commandId
            state = AgentState.PAUSED
            pauseReason = PauseReason.ACTION_UNKNOWN
            lastError = e.message
            return StepResult.Paused(PauseReason.ACTION_UNKNOWN, e.message ?: "device error")
        }

        if (!owned(generation) || !permit.isCurrent()) {
            return stale(generation, "stop arrived during action execution; outcome unknown", commandId)
        }
        if (budget.checkDuration(clock.nowMs()) is BudgetOutcome.Exceeded) {
            return budgetStop(generation, BudgetReason.DURATION)
        }

        return when (outcome) {
            DeviceOutcome.Done -> {
                policy.markUsed(authorisedFrame)
                budget.recordSuccess()
                state = AgentState.VERIFYING
                StepResult.Executed(commandId, decision.action, outcome)
            }
            DeviceOutcome.Accepted, DeviceOutcome.Started -> {
                // Accepted/Started is explicitly NOT done: keep the command in flight and stop
                // issuing input until the final result is known.
                policy.markUsed(authorisedFrame)
                pendingCommand = commandId
                state = AgentState.PAUSED
                pauseReason = PauseReason.ACTION_PENDING
                lastError = "device accepted command $commandId but has not confirmed completion"
                StepResult.Pending(commandId, outcome)
            }
            is DeviceOutcome.Rejected -> {
                policy.markUsed(authorisedFrame)
                lastError = outcome.detail
                when (val budgetResult = budget.recordFailure(clock.nowMs())) {
                    is BudgetOutcome.Exceeded -> budgetStop(generation, budgetResult.reason)
                    BudgetOutcome.Ok -> {
                        state = AgentState.PAUSED
                        pauseReason = PauseReason.DEVICE_REJECTED
                        StepResult.Rejected(outcome.code, outcome.detail)
                    }
                }
            }
            is DeviceOutcome.Unknown -> {
                // ACTION_UNKNOWN: no automatic retry of a click/type/send (baseline §7).
                policy.markUsed(authorisedFrame)
                pendingCommand = commandId
                state = AgentState.PAUSED
                pauseReason = PauseReason.ACTION_UNKNOWN
                lastError = outcome.detail
                StepResult.Paused(PauseReason.ACTION_UNKNOWN, outcome.detail)
            }
        }
    }

    /**
     * Confirms a previously pending command. Late confirmations for a dead generation are
     * ignored and never revive the task.
     */
    public fun confirmPendingResult(commandId: Long, generation: Long, outcome: DeviceOutcome): StepResult {
        if (!owned(generation)) {
            return stale(generation, "confirmation for generation $generation is dead")
        }
        if (pendingCommand != commandId) {
            return StepResult.Rejected(ErrorCode.COMMAND_STALE, "no pending command $commandId")
        }
        if (outcome !is DeviceOutcome.Done) {
            return pauseAndReport(generation, PauseReason.ACTION_UNKNOWN, "pending command $commandId did not complete")
        }
        pendingCommand = null
        budget.recordSuccess()
        state = AgentState.VERIFYING
        return StepResult.Completed("command $commandId confirmed done")
    }

    /** Result of a post-external-call re-check. */
    private sealed class Revalidation {
        class Ok(val observation: LiveObservation) : Revalidation()
        class Failed(val result: StepResult) : Revalidation()
    }

    /**
     * Re-checks authority, the task deadline, the clock and the current observation after an
     * external call.
     *
     * The clock is read again *after* the capture returns, and both the bound frame and the
     * returned frame must still be fresh at that moment. A capture that blocks for longer
     * than the freshness budget therefore cannot authorise input, even when the geometry is
     * identical and the fake/hook returned a "new" frame.
     */
    private fun revalidate(generation: Long, bound: LiveObservation, stage: String): Revalidation {
        if (!owned(generation)) return Revalidation.Failed(stale(generation, "stop arrived during $stage"))

        var now = clock.nowMs()
        if (budget.checkDuration(now) is BudgetOutcome.Exceeded) {
            return Revalidation.Failed(budgetStop(generation, BudgetReason.DURATION))
        }
        if (now - bound.receivedAtMs > policy.maxFrameAgeMs) {
            return Revalidation.Failed(
                pauseAndReport(generation, PauseReason.NO_FRESH_FRAME, "frame aged out during $stage"),
            )
        }

        val current = try {
            screen.currentObservation(now)
        } catch (e: Exception) {
            if (!owned(generation)) return Revalidation.Failed(stale(generation, "stop arrived during $stage"))
            return Revalidation.Failed(
                pauseAndReport(generation, PauseReason.NO_FRESH_FRAME, "screen unavailable during $stage"),
            )
        }

        // Fresh clock *after* the capture: the decision is authorised now, not before the call.
        now = clock.nowMs()
        if (!owned(generation)) return Revalidation.Failed(stale(generation, "stop arrived during $stage"))
        if (budget.checkDuration(now) is BudgetOutcome.Exceeded) {
            return Revalidation.Failed(budgetStop(generation, BudgetReason.DURATION))
        }
        if (now - bound.receivedAtMs > policy.maxFrameAgeMs) {
            return Revalidation.Failed(
                pauseAndReport(generation, PauseReason.NO_FRESH_FRAME, "frame aged out during $stage"),
            )
        }
        if (current == null) {
            return Revalidation.Failed(
                pauseAndReport(generation, PauseReason.NO_FRESH_FRAME, "no current observation during $stage"),
            )
        }
        if (current.receivedAtMs > now) {
            return Revalidation.Failed(
                pauseAndReport(generation, PauseReason.NO_FRESH_FRAME, "current observation is dated in the future"),
            )
        }
        if (now - current.receivedAtMs > policy.maxFrameAgeMs) {
            return Revalidation.Failed(
                pauseAndReport(generation, PauseReason.NO_FRESH_FRAME, "current observation is stale during $stage"),
            )
        }
        if (current.projectionEpoch != bound.projectionEpoch) {
            return Revalidation.Failed(
                pauseAndReport(generation, PauseReason.NO_FRESH_FRAME, "projection epoch changed during $stage"),
            )
        }
        if (current.rotation != bound.rotation) {
            return Revalidation.Failed(
                pauseAndReport(generation, PauseReason.NO_FRESH_FRAME, "rotation changed during $stage"),
            )
        }
        if (current.width != bound.width || current.height != bound.height) {
            return Revalidation.Failed(
                pauseAndReport(generation, PauseReason.NO_FRESH_FRAME, "size changed during $stage"),
            )
        }
        return Revalidation.Ok(current)
    }

    private fun budgetStop(generation: Long, reason: BudgetReason): StepResult {
        if (!owned(generation)) return stale(generation, "budget check for a dead generation")
        val stopResult = revokeAndRelease("budget exceeded: $reason")
        if (generation != taskGeneration) return stale(generation, "task replaced during budget release")
        state = AgentState.PAUSED
        pauseReason = PauseReason.BUDGET_EXCEEDED
        lastError = "budget exceeded: $reason"
        return StepResult.BudgetExceeded(reason, stopResult)
    }

    /** Revokes authority first, then releases the device. Used by every abnormal exit path. */
    private fun revokeAndRelease(reason: String): StopResult {
        val next = authority.stop()
        policy.invalidate()
        return requestDeviceStop(next, reason)
    }

    private fun requestDeviceStop(generation: Long, reason: String): StopResult =
        try {
            StopResult(generation, device.requestStop(reason), null)
        } catch (e: Exception) {
            StopResult(generation, null, e.message ?: e::class.java.simpleName)
        }

    /** True only for the task this core currently owns and only while authority is live. */
    private fun owned(generation: Long): Boolean =
        generation == taskGeneration && authority.isCurrent(generation)

    /**
     * A callback for a dead generation must not write any state of the current task. Only the
     * generation that still owns the core may annotate `lastError`.
     */
    private fun stale(generation: Long, detail: String, abandonedCommandId: Long? = null): StepResult.Stale {
        if (generation == taskGeneration) {
            lastError = detail
        }
        if (abandonedCommandId != null) {
            orphanedCommands.add(OrphanedCommand(generation, abandonedCommandId, detail))
            while (orphanedCommands.size > MAX_ORPHANED) orphanedCommands.removeAt(0)
        }
        return StepResult.Stale(detail, abandonedCommandId)
    }

    private fun pauseAndReport(generation: Long, reason: PauseReason, detail: String): StepResult {
        pause(generation, reason, detail)
        return StepResult.Paused(reason, detail)
    }

    private fun pause(generation: Long, reason: PauseReason, detail: String) {
        if (!owned(generation)) return
        state = AgentState.PAUSED
        pauseReason = reason
        lastError = detail
    }

    private companion object {
        const val MAX_ORPHANED = 32
    }
}
