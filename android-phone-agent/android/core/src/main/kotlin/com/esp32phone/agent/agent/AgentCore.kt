package com.esp32phone.agent.agent

import com.esp32phone.agent.control.HidAction
import com.esp32phone.agent.control.ModelDecision
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

public sealed class StepResult {
    public data object NoWork : StepResult()

    /** The step was abandoned because the generation changed (stop/pause/new task). */
    public class Stale(public val detail: String) : StepResult()

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
 * Ordering guarantees this class is responsible for:
 *  1. authority (generation) is revoked *before* the device stop request is sent, and every
 *     stop path (user stop, budget exhaustion, device exception) does both;
 *  2. every external call (screen, model, verifier, device) is followed by a fresh
 *     authorisation + freshness check using a fresh clock read, so a slow model response
 *     cannot act on an observation that aged out or rotated while it was thinking;
 *  3. a device action only starts with a valid [InputPermit] that the core itself re-checks
 *     immediately before and after the call — not only inside the device double;
 *  4. the model's own "done" never becomes COMPLETED without an independent verifier, and a
 *     stop that lands during verification still wins;
 *  5. command ids increase for the whole device session: a new task never restarts at 1, so
 *     an id can never be replayed into a second execution.
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

    public val commandsIssued: Long get() = commandIds.lastIssued
    public val steps: Int get() = stepIndex
    public val currentGeneration: Long get() = authority.current
    public val hasPendingAction: Boolean get() = pendingCommand != null
    public val pendingCommandId: Long? get() = pendingCommand

    /**
     * Starts a new task. Keeps the command-id watermark because the device session is the
     * same; resets the budget because this is a new task, not a resume.
     */
    public fun beginTask(instruction: String, nowMs: Long = clock.nowMs()): Long {
        check(pendingCommand == null) {
            "cannot start a new task while command $pendingCommand has an unknown/pending result"
        }
        val generation = authority.beginNewTask()
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
        state = AgentState.STOPPED
        policy.invalidate()
        return requestDeviceStop(generation, reason)
    }

    public fun step(nowMs: Long = clock.nowMs()): StepResult {
        if (task.isEmpty()) return StepResult.NoWork
        if (authority.isStopped) return StepResult.Stale("task authority revoked")
        when (state) {
            AgentState.PAUSED, AgentState.COMPLETED, AgentState.FAILED, AgentState.STOPPED -> return StepResult.NoWork
            else -> Unit
        }

        val generation = authority.current
        stepIndex += 1

        state = AgentState.OBSERVING
        var now = maxOf(nowMs, clock.nowMs())
        val observation = try {
            screen.currentObservation(now)
        } catch (e: Exception) {
            pause(PauseReason.NO_FRESH_FRAME, e.message ?: "screen source failed")
            return StepResult.Paused(PauseReason.NO_FRESH_FRAME, lastError ?: "screen source failed")
        }
        // Re-read the clock: capture may have taken time, so an old nowMs must not be reused.
        now = clock.nowMs()
        if (!authority.isCurrent(generation)) {
            return staleWhileCurrent(generation, "stop arrived during observation")
        }
        when (val verdict = policy.check(observation, now)) {
            is FreshnessVerdict.Rejected -> {
                pause(PauseReason.NO_FRESH_FRAME, "observation rejected: ${verdict.failure.name}")
                return StepResult.Paused(PauseReason.NO_FRESH_FRAME, lastError ?: verdict.failure.name)
            }
            FreshnessVerdict.Fresh -> Unit
        }
        val boundObservation = observation
            ?: return StepResult.Paused(PauseReason.NO_FRESH_FRAME, "no observation")
        policy.bind(boundObservation)

        state = AgentState.DECIDING
        when (val budgetResult = budget.tryConsumeModelCall(clock.nowMs())) {
            is BudgetOutcome.Exceeded -> return budgetStop(budgetResult.reason)
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
            if (!authority.isCurrent(generation)) {
                return staleWhileCurrent(generation, "model failed after stop")
            }
            val reason = when (e.kind) {
                ModelFailure.TIMEOUT -> PauseReason.MODEL_TIMEOUT
                ModelFailure.BUDGET -> PauseReason.BUDGET_EXCEEDED
                ModelFailure.AUTHORITY_LOST -> PauseReason.ACTION_UNKNOWN
                else -> PauseReason.MODEL_ERROR
            }
            pause(reason, e.message ?: e.kind.name)
            return StepResult.Paused(reason, lastError ?: e.kind.name)
        }

        // A late response dies here: authority and freshness are re-checked with a fresh clock.
        if (!authority.isCurrent(generation)) {
            return staleWhileCurrent(generation, "model response arrived after the task was stopped")
        }
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
        state = AgentState.VERIFYING
        if (!authority.isCurrent(generation)) {
            return staleWhileCurrent(generation, "stop arrived before verification")
        }
        val verdict = try {
            verifier.verify(observation, decision.expected)
        } catch (e: Exception) {
            if (!authority.isCurrent(generation)) {
                return staleWhileCurrent(generation, "stop arrived during verification")
            }
            pause(PauseReason.VERIFICATION_FAILED, e.message ?: "verifier failed")
            return StepResult.Paused(PauseReason.VERIFICATION_FAILED, lastError ?: "verifier failed")
        }
        // The verifier may be slow: a stop issued while it ran must still win.
        if (!authority.isCurrent(generation)) {
            return staleWhileCurrent(generation, "stop arrived during verification")
        }
        when (val r = revalidate(generation, observation, "verification")) {
            is Revalidation.Failed -> return r.result
            is Revalidation.Ok -> Unit
        }

        return when (verdict) {
            VerificationVerdict.Satisfied -> {
                state = AgentState.COMPLETED
                StepResult.Completed(decision.expected)
            }
            VerificationVerdict.Unavailable -> {
                pause(PauseReason.MODEL_DONE_UNVERIFIED, "model claimed done; independent verification is not implemented")
                StepResult.Paused(PauseReason.MODEL_DONE_UNVERIFIED, decision.reason)
            }
            is VerificationVerdict.NotSatisfied -> {
                pause(PauseReason.VERIFICATION_FAILED, verdict.detail)
                StepResult.Paused(PauseReason.VERIFICATION_FAILED, verdict.detail)
            }
        }
    }

    private fun onModelAction(
        generation: Long,
        observation: LiveObservation,
        currentObservation: LiveObservation,
        decision: ModelDecision.Act,
    ): StepResult {
        // Final pre-execution checks with a fresh clock and a fresh observation.
        if (!authority.isCurrent(generation)) {
            return staleWhileCurrent(generation, "stop arrived before execution")
        }
        val authorisedFrame = when (val r = revalidate(generation, observation, "pre-execution")) {
            is Revalidation.Failed -> return r.result
            is Revalidation.Ok -> r.observation
        }
        when (val budgetResult = budget.tryConsumeAction(clock.nowMs())) {
            is BudgetOutcome.Exceeded -> return budgetStop(budgetResult.reason)
            BudgetOutcome.Ok -> Unit
        }

        val permit = authority.issuePermit()
            ?: return staleWhileCurrent(generation, "no input permit: authority already revoked")
        if (!permit.isCurrent()) {
            return staleWhileCurrent(generation, "input permit invalidated before execution")
        }

        val commandId = commandIds.next()
        val request = ActionRequest(
            commandId = commandId,
            generation = generation,
            permit = permit,
            action = decision.action,
            observation = authorisedFrame,
            decisionFrameId = maxOf(observation.frameId, currentObservation.frameId),
            payload = decision.action.toWireJson().toByteArray(Charsets.UTF_8),
        )

        state = AgentState.EXECUTING
        val outcome = try {
            device.execute(request)
        } catch (e: Exception) {
            // The action state is unknown and authority may already be gone: revoke, release,
            // and never retry.
            val stopResult = authority.stop().let { revoked ->
                policy.invalidate()
                requestDeviceStop(revoked, "device exception during action")
            }
            pendingCommand = commandId
            state = AgentState.PAUSED
            pauseReason = PauseReason.ACTION_UNKNOWN
            lastError = e.message
            return StepResult.Paused(PauseReason.ACTION_UNKNOWN, e.message ?: "device error (${stopResult.generation})")
        }

        // Re-check authority *and* the permit after the call: a stop that landed while the
        // device was writing makes the outcome unknown, and success must not be reported.
        if (!authority.isCurrent(generation) || !permit.isCurrent()) {
            pendingCommand = commandId
            state = AgentState.STOPPED
            policy.invalidate()
            lastError = "stop arrived during action execution; outcome unknown"
            return StepResult.Stale("stop arrived during action execution; outcome unknown")
        }
        // The device call may also have been slow enough to blow the task deadline.
        if (budget.checkDuration(clock.nowMs()) is BudgetOutcome.Exceeded) {
            return budgetStop(BudgetReason.DURATION)
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
                    is BudgetOutcome.Exceeded -> budgetStop(budgetResult.reason)
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
        if (!authority.isCurrent(generation)) {
            return StepResult.Stale("confirmation for generation $generation is stale")
        }
        if (pendingCommand != commandId) {
            return StepResult.Rejected(ErrorCode.COMMAND_STALE, "no pending command $commandId")
        }
        if (outcome !is DeviceOutcome.Done) {
            return StepResult.Paused(PauseReason.ACTION_UNKNOWN, "pending command $commandId did not complete")
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
     * external call. Uses a fresh clock read, so a slow model/verifier/device cannot act on an
     * observation or budget that expired while it was working.
     */
    private fun revalidate(generation: Long, bound: LiveObservation, stage: String): Revalidation {
        if (!authority.isCurrent(generation)) {
            return Revalidation.Failed(staleWhileCurrent(generation, "stop arrived during $stage"))
        }
        val now = clock.nowMs()
        if (budget.checkDuration(now) is BudgetOutcome.Exceeded) {
            return Revalidation.Failed(budgetStop(BudgetReason.DURATION))
        }
        if (now - bound.receivedAtMs > policy.maxFrameAgeMs) {
            pause(PauseReason.NO_FRESH_FRAME, "frame aged out during $stage")
            return Revalidation.Failed(StepResult.Paused(PauseReason.NO_FRESH_FRAME, lastError ?: "stale"))
        }
        val current = try {
            screen.currentObservation(now)
        } catch (e: Exception) {
            pause(PauseReason.NO_FRESH_FRAME, "screen unavailable during $stage")
            return Revalidation.Failed(StepResult.Paused(PauseReason.NO_FRESH_FRAME, lastError ?: "screen unavailable"))
        }
        if (current == null) {
            pause(PauseReason.NO_FRESH_FRAME, "no current observation during $stage")
            return Revalidation.Failed(StepResult.Paused(PauseReason.NO_FRESH_FRAME, lastError ?: "no observation"))
        }
        if (!authority.isCurrent(generation)) {
            return Revalidation.Failed(staleWhileCurrent(generation, "stop arrived during $stage"))
        }
        if (current.projectionEpoch != bound.projectionEpoch) {
            pause(PauseReason.NO_FRESH_FRAME, "projection epoch changed during $stage")
            return Revalidation.Failed(StepResult.Paused(PauseReason.NO_FRESH_FRAME, lastError ?: "projection changed"))
        }
        if (current.rotation != bound.rotation) {
            pause(PauseReason.NO_FRESH_FRAME, "rotation changed during $stage")
            return Revalidation.Failed(StepResult.Paused(PauseReason.NO_FRESH_FRAME, lastError ?: "rotation changed"))
        }
        if (current.width != bound.width || current.height != bound.height) {
            pause(PauseReason.NO_FRESH_FRAME, "size changed during $stage")
            return Revalidation.Failed(StepResult.Paused(PauseReason.NO_FRESH_FRAME, lastError ?: "size changed"))
        }
        return Revalidation.Ok(current)
    }

    private fun budgetStop(reason: BudgetReason): StepResult {
        val stopResult = revokeAndRelease("budget exceeded: $reason")
        state = AgentState.PAUSED
        pauseReason = PauseReason.BUDGET_EXCEEDED
        lastError = "budget exceeded: $reason"
        return StepResult.BudgetExceeded(reason, stopResult)
    }

    /** Revokes authority first, then releases the device. Used by every abnormal exit path. */
    private fun revokeAndRelease(reason: String): StopResult {
        val generation = authority.stop()
        policy.invalidate()
        state = AgentState.PAUSED
        return requestDeviceStop(generation, reason)
    }

    private fun requestDeviceStop(generation: Long, reason: String): StopResult =
        try {
            StopResult(generation, device.requestStop(reason), null)
        } catch (e: Exception) {
            StopResult(generation, null, e.message ?: e::class.java.simpleName)
        }

    private fun pause(reason: PauseReason, detail: String) {
        if (authority.isStopped) return
        state = AgentState.PAUSED
        pauseReason = reason
        lastError = detail
    }

    /**
     * A stale generation must not touch the state of a newer task. Only the policy of the
     * *current* task is invalidated (and only when this call still owns the generation).
     */
    private fun staleWhileCurrent(generation: Long, detail: String): StepResult {
        if (authority.isCurrent(generation)) {
            policy.invalidate()
        }
        lastError = detail
        return StepResult.Stale("generation $generation is stale: $detail")
    }
}
