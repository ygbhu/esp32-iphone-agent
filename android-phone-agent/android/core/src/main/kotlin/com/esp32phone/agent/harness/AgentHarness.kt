package com.esp32phone.agent.harness

import com.esp32phone.agent.agent.ActionRequest
import com.esp32phone.agent.agent.Clock
import com.esp32phone.agent.agent.DeviceOutcome
import com.esp32phone.agent.agent.DeviceSink
import com.esp32phone.agent.agent.LiveObservation
import com.esp32phone.agent.agent.ModelClient
import com.esp32phone.agent.agent.ModelException
import com.esp32phone.agent.agent.ModelRequest
import com.esp32phone.agent.agent.ScreenSource
import com.esp32phone.agent.agent.StopController
import com.esp32phone.agent.control.HidAction
import com.esp32phone.agent.control.ModelDecision
import com.esp32phone.agent.protocol.Crypto
import com.esp32phone.agent.protocol.ErrorCode
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * Deterministic test harness.
 *
 * These fakes are used by the unit tests and by the app's 测试 tab (which is labelled
 * 假设备/假模型). They are NOT a phone, NOT a model and NOT the firmware: nothing here proves
 * hardware or gateway behaviour.
 */
public class ManualClock(public var now: Long = 0) : Clock {
    override fun nowMs(): Long = now

    public fun advance(deltaMs: Long) {
        now += deltaMs
    }
}

public enum class EventKind { OBSERVE, MODEL_REQUEST, MODEL_RESPONSE, VERIFY, EXECUTE_ENTRY, OUTPUT, ACK, STOP_REQUEST, REVOKE, AUTHORITY_GAINED }

public class TimelineEvent(
    public val seq: Long,
    public val kind: EventKind,
    public val generation: Long,
    public val commandId: Long,
    public val note: String,
) {
    override fun toString(): String = "#$seq $kind gen=$generation cmd=$commandId $note"
}

/**
 * Thread-safe, monotonically sequenced event log used to assert *ordering* (not just counts).
 * Ordering is what makes a "stop" test real: output that appears after a revoke of its own
 * generation is a violation regardless of which generation number it carries.
 */
public class EventLog {
    private val counter = AtomicLong()
    private val events = Collections.synchronizedList(ArrayList<TimelineEvent>())

    public fun record(kind: EventKind, generation: Long, commandId: Long = -1, note: String = ""): TimelineEvent {
        val event = TimelineEvent(counter.incrementAndGet(), kind, generation, commandId, note)
        events.add(event)
        return event
    }

    public fun snapshot(): List<TimelineEvent> = synchronized(events) { ArrayList(events) }

    public fun count(kind: EventKind): Int = snapshot().count { it.kind == kind }

    public fun kinds(): List<EventKind> = snapshot().map { it.kind }

    /** Outputs that were produced after the first stop, i.e. HID bytes sent post-revoke. */
    public fun outputsAfterFirstStop(): List<TimelineEvent> {
        val all = snapshot()
        val firstStop = all.firstOrNull { it.kind == EventKind.REVOKE } ?: return emptyList()
        return all.filter { it.kind == EventKind.OUTPUT && it.seq > firstStop.seq }
    }

    /**
     * Outputs emitted with a generation that was already revoked. A stop of generation G
     * creates generation G+1, so any later output labelled G (or lower) is a violation.
     */
    public fun outputsWithDeadGeneration(): List<TimelineEvent> {
        val all = snapshot()
        val revokedGens = ArrayList<Long>()
        val bad = ArrayList<TimelineEvent>()
        for (event in all) {
            when (event.kind) {
                EventKind.REVOKE -> revokedGens.add(event.generation - 1)
                EventKind.OUTPUT -> if (revokedGens.any { revoked -> event.generation <= revoked }) bad.add(event)
                else -> Unit
            }
        }
        return bad
    }

    public fun describe(): String = snapshot().joinToString("\n") { "    $it" }
}

public class FakeScreenSource(
    private val clock: Clock,
    private val log: EventLog? = null,
    public var projectionEpoch: Long = 1,
    public var rotation: Int = 0,
    public var width: Int = 1080,
    public var height: Int = 2400,
) : ScreenSource {

    /** When false, `currentObservation` returns null (simulates "no new frame"). */
    public var available: Boolean = true

    /** Backdates `receivedAtMs` to simulate a stale frame. */
    public var backdateMs: Long = 0

    /** Repeats the previous frame id (simulates "not updated"). */
    public var reuseFrame: Boolean = false

    /** Invoked before returning, so tests can inject a stop/rotation *during* observation. */
    public var onObserve: (() -> Unit)? = null

    private var frameCounter: Long = 0
    private var last: LiveObservation? = null

    override fun currentObservation(nowMs: Long): LiveObservation? {
        onObserve?.invoke()
        if (!available) return null
        val previous = last
        val id = if (reuseFrame && previous != null) {
            previous.frameId
        } else {
            frameCounter += 1
            frameCounter
        }
        val observation = LiveObservation(
            projectionEpoch = projectionEpoch,
            frameId = id,
            receivedAtMs = nowMs - backdateMs,
            captureTimeMs = null,
            width = width,
            height = height,
            rotation = rotation,
        )
        last = observation
        log?.record(EventKind.OBSERVE, -1, id, "epoch=$projectionEpoch rot=$rotation ${width}x$height")
        return observation
    }
}

public class FakeModel(public var decision: ModelDecision, private val log: EventLog? = null) : ModelClient {

    public var error: ModelException? = null
    public var onDecide: ((ModelRequest) -> Unit)? = null
    public var calls: Int = 0
        private set

    override fun decide(request: ModelRequest): ModelDecision {
        calls += 1
        log?.record(EventKind.MODEL_REQUEST, request.generation, -1, "step=${request.stepIndex}")
        onDecide?.invoke(request)
        error?.let {
            log?.record(EventKind.MODEL_RESPONSE, request.generation, -1, "error=${it.kind}")
            throw it
        }
        log?.record(EventKind.MODEL_RESPONSE, request.generation, -1, "decision=${decision::class.simpleName}")
        return decision
    }
}

public class RecordedAction(
    public val commandId: Long,
    public val generation: Long,
    public val action: HidAction,
    public val atMs: Long,
    public val frameId: Long,
)

/**
 * Device double that follows the real [DeviceSink] contract: every HID report is committed
 * through [com.esp32phone.agent.agent.InputPermit.tryDispatch], so the check and the local
 * write share one critical section with `stop`. There is deliberately no "check then write"
 * gap to exploit.
 */
public class FakeDevice(
    private val clock: Clock,
    private val authority: StopController? = null,
    private val log: EventLog? = null,
) : DeviceSink {

    public val attempts: MutableList<RecordedAction> = Collections.synchronizedList(ArrayList())
    public val outputs: MutableList<RecordedAction> = Collections.synchronizedList(ArrayList())

    public var outcome: DeviceOutcome = DeviceOutcome.Done
    public var stopOutcome: DeviceOutcome = DeviceOutcome.Done

    public var onExecute: ((ActionRequest) -> Unit)? = null

    /** Runs immediately before the commit boundary (deterministic interleaving point). */
    public var onBeforeOutput: ((ActionRequest) -> Unit)? = null

    /** Runs after the report was committed (outside the critical section). */
    public var onAfterOutput: ((ActionRequest) -> Unit)? = null

    public var stopCalls: Int = 0
        private set

    /** Commits refused because authority was revoked before the report was written. */
    public var stalePermitAttempts: Int = 0
        private set

    /** Permits used for a different command/action than they were issued for. Must stay 0. */
    public var permitMismatches: Int = 0
        private set

    /** Requests whose digest did not match their payload. Must stay 0. */
    public var digestViolations: Int = 0
        private set

    /** True if a stop request was issued while authority was still live (must stay false). */
    public var stopWhileAuthorityLive: Boolean = false
        private set

    override fun execute(request: ActionRequest): DeviceOutcome {
        log?.record(EventKind.EXECUTE_ENTRY, request.generation, request.commandId, "frame=${request.observation.frameId}")
        attempts.add(RecordedAction(request.commandId, request.generation, request.action, clock.nowMs(), request.observation.frameId))
        onExecute?.invoke(request)

        val recomputed = Crypto.sha256(request.payload).copyOf(16)
        if (!Crypto.constantTimeEquals(recomputed, request.actionDigest)) {
            digestViolations += 1
            log?.record(EventKind.ACK, request.generation, request.commandId, "rejected: digest mismatch")
            return DeviceOutcome.Rejected(ErrorCode.INVALID_ACTION, "permit digest does not match payload")
        }
        if (request.generation != request.permit.generation ||
            !request.permit.matchesAction(request.commandId, request.actionDigest)
        ) {
            permitMismatches += 1
            log?.record(EventKind.ACK, request.generation, request.commandId, "rejected: permit/action mismatch")
            return DeviceOutcome.Rejected(ErrorCode.NOT_CONTROLLER, "permit does not authorise this action")
        }

        onBeforeOutput?.invoke(request)

        val committed = request.permit.tryDispatch {
            // Short, non-blocking local write only: this runs inside the authority's commit lock.
            outputs.add(RecordedAction(request.commandId, request.generation, request.action, clock.nowMs(), request.observation.frameId))
            log?.record(EventKind.OUTPUT, request.generation, request.commandId, request.action.operationName())
        }
        if (!committed) {
            stalePermitAttempts += 1
            log?.record(EventKind.ACK, request.generation, request.commandId, "rejected: commit refused (revoked)")
            return DeviceOutcome.Rejected(
                ErrorCode.NOT_CONTROLLER,
                "permit for generation ${request.generation} was revoked before the HID report",
            )
        }

        onAfterOutput?.invoke(request)
        log?.record(EventKind.ACK, request.generation, request.commandId, "outcome=${outcome::class.simpleName}")
        return outcome
    }

    override fun requestStop(reason: String): DeviceOutcome {
        stopCalls += 1
        if (authority != null && !authority.isStopped) stopWhileAuthorityLive = true
        log?.record(EventKind.STOP_REQUEST, authority?.current ?: -1, -1, reason)
        return stopOutcome
    }
}

/** Wires an [EventLog] to the authority so revokes are sequenced against output. */
public fun StopController.logRevokes(log: EventLog) {
    listener = StopController.Listener { _, next, kind ->
        when (kind) {
            StopController.ChangeKind.STOP -> log.record(EventKind.REVOKE, next, -1, "stop")
            StopController.ChangeKind.NEW_TASK -> log.record(EventKind.AUTHORITY_GAINED, next, -1, "new task")
        }
    }
}

public fun tapAction(x: Int = 500, y: Int = 500, durationMs: Int = 80): ModelDecision =
    ModelDecision.Act(HidAction.Tap(x, y, durationMs), "tap target", "target pressed")

public fun doneDecision(): ModelDecision = ModelDecision.Done("looks done", "task complete")
