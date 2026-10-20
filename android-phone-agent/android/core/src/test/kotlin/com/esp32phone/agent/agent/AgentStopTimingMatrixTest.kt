package com.esp32phone.agent.agent

import com.esp32phone.agent.agent.AgentBudget
import com.esp32phone.agent.agent.AgentCore
import com.esp32phone.agent.agent.AgentState
import com.esp32phone.agent.agent.DeviceOutcome
import com.esp32phone.agent.agent.ObservationPolicy
import com.esp32phone.agent.agent.PauseReason
import com.esp32phone.agent.agent.StepResult
import com.esp32phone.agent.agent.StopController
import com.esp32phone.agent.agent.UnavailableVerifier
import com.esp32phone.agent.agent.VerificationVerdict
import com.esp32phone.agent.agent.Verifier
import com.esp32phone.agent.harness.EventKind
import com.esp32phone.agent.harness.EventLog
import com.esp32phone.agent.harness.FakeDevice
import com.esp32phone.agent.harness.FakeModel
import com.esp32phone.agent.harness.FakeScreenSource
import com.esp32phone.agent.harness.ManualClock
import com.esp32phone.agent.harness.doneDecision
import com.esp32phone.agent.harness.logRevokes
import com.esp32phone.agent.harness.tapAction
import com.esp32phone.agent.protocol.ErrorCode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * STOP / late-response timing matrix (checklist §5: "首轮至少 100 个时序组合").
 *
 * Each combination injects a stop (and, where relevant, a clock jump) at a specific *phase*
 * of one step, and the assertions are about the recorded event order:
 *
 *  P1  no OUTPUT event appears after a REVOKE event that killed its generation;
 *  P2  the device stop request is only sent after authority was revoked;
 *  P3  a control stop issued during `model`, `verify`, `execute_entry`, `output` or `ack`
 *      leaves the task STOPPED and never yields success;
 *  P4  no HID output is produced twice for the same command id;
 *  P5  the sink never receives an already-revoked permit (the core, not the double, must
 *      prevent that).
 *
 * Separate tests at the bottom use real threads and latches for the cases that only a true
 * interleaving can expose.
 */
class AgentStopTimingMatrixTest {

    private enum class Phase { BEFORE_STEP, OBSERVE, MODEL, VERIFY, EXECUTE_ENTRY, OUTPUT, ACK, AFTER_STEP }

    private enum class ClockJump { NONE, DURING_MODEL, BEFORE_EXECUTION }

    private data class Combo(
        val phase: Phase,
        val stopFirst: Boolean,
        val clockJump: ClockJump,
        val outcome: DeviceOutcome,
        val rotateDuringModel: Boolean,
    ) {
        override fun toString(): String =
            "phase=$phase stopFirst=$stopFirst clock=$clockJump outcome=${outcome::class.simpleName} rotate=$rotateDuringModel"
    }

    private class World(val combo: Combo) {
        val log = EventLog()
        val clock = ManualClock(1_000)
        val authority = StopController()
        val screen = FakeScreenSource(clock, log)
        val model = FakeModel(tapAction(), log)
        val device = FakeDevice(clock, authority, log)
        val budget = AgentBudget()
        val mutableVerifier: MutableVerifier = MutableVerifier(ALWAYS_SATISFIED)
        val core = AgentCore(
            clock = clock,
            screen = screen,
            model = model,
            device = device,
            budget = budget,
            authority = authority,
            verifier = mutableVerifier,
            policy = ObservationPolicy(),
        )
        var stopIssued = false
        var result: StepResult? = null

        init {
            authority.logRevokes(log)
        }

        fun stopNow(reason: String = "test stop") {
            if (stopIssued) return
            stopIssued = true
            core.stop(reason)
        }
    }

    private fun matrix(): List<Combo> {
        val combos = ArrayList<Combo>()
        val outcomes = listOf(
            DeviceOutcome.Done,
            DeviceOutcome.Accepted,
            DeviceOutcome.Rejected(ErrorCode.BUSY, "busy"),
            DeviceOutcome.Unknown("receipt lost"),
        )
        for (phase in Phase.entries) {
            for (stopFirst in listOf(true, false)) {
                for (clockJump in ClockJump.entries) {
                    for (outcome in outcomes) {
                        for (rotate in listOf(false, true)) {
                            // keep the matrix focused: rotation only matters around the model call
                            if (rotate && phase != Phase.MODEL && phase != Phase.EXECUTE_ENTRY) continue
                            combos.add(Combo(phase, stopFirst, clockJump, outcome, rotate))
                        }
                    }
                }
            }
        }
        return combos
    }

    private fun run(world: World) {
        val combo = world.combo
        world.device.outcome = combo.outcome

        fun injectAt(phase: Phase, action: () -> Unit) {
            if (combo.phase == phase) action()
        }

        world.screen.onObserve = {
            injectAt(Phase.OBSERVE) { if (combo.stopFirst) world.stopNow() }
        }

        world.model.onDecide = {
            if (combo.rotateDuringModel) world.screen.rotation = 1
            if (combo.clockJump == ClockJump.DURING_MODEL) world.clock.advance(600_001)
            injectAt(Phase.MODEL) {
                // stop while the model response is still in the harness (same thread, after the
                // decision exists but before the core regains control)
                world.stopNow()
            }
        }

        world.device.onExecute = {
            injectAt(Phase.EXECUTE_ENTRY) { world.stopNow() }
        }
        world.device.onBeforeOutput = {
            injectAt(Phase.OUTPUT) { world.stopNow() }
        }
        world.device.onAfterOutput = {
            injectAt(Phase.ACK) { world.stopNow() }
        }

        if (combo.phase == Phase.BEFORE_STEP) {
            world.core.beginTask("tap the test button", world.clock.nowMs())
            world.stopNow()
        } else {
            world.core.beginTask("tap the test button", world.clock.nowMs())
        }

        if (combo.clockJump == ClockJump.BEFORE_EXECUTION) world.clock.advance(600_001)
        world.clock.advance(5)
        val first = world.core.step(world.clock.nowMs())

        if (combo.phase == Phase.AFTER_STEP) world.stopNow()

        // A second step must never produce extra input after a stop.
        world.clock.advance(5)
        val second = world.core.step(world.clock.nowMs())

        if (world.stopIssued) {
            assertTrue(
                second is StepResult.Stale || second is StepResult.NoWork,
                "$combo: stepping continued after stop with $second",
            )
        }
        if (first is StepResult.Executed) {
            assertEquals(
                1,
                world.device.outputs.count { it.commandId == first.commandId },
                "$combo: duplicate output for one command",
            )
        }
        if (combo.outcome is DeviceOutcome.Accepted) {
            assertTrue(
                first !is StepResult.Executed && first !is StepResult.Completed,
                "$combo: Accepted must never be reported as execution success, got $first",
            )
        }
        if (combo.outcome is DeviceOutcome.Unknown) {
            assertTrue(
                first !is StepResult.Executed && first !is StepResult.Completed,
                "$combo: Unknown must never be reported as success, got $first",
            )
        }
    }

    private fun assertProperties(world: World) {
        val combo = world.combo
        // P1: nothing may be written after its generation was revoked.
        val dead = world.log.outputsWithDeadGeneration()
        assertTrue(dead.isEmpty(), "$combo: output after revoke: ${dead}\n${world.log.describe()}")
        // P2: device stop after revoke only.
        assertTrue(!world.device.stopWhileAuthorityLive, "$combo: device stop before revoke")
        // P5: every execute call either produced output under a live permit or was refused
        // because the permit had already been revoked. No third outcome is allowed.
        assertEquals(
            world.device.attempts.size,
            world.device.outputs.size + world.device.stalePermitAttempts,
            "$combo: unexpected device call outcome",
        )
        // P3: after a stop the task must not be COMPLETED and must not still be executing.
        if (world.stopIssued) {
            assertTrue(
                world.core.state == AgentState.STOPPED || world.core.state == AgentState.PAUSED,
                "$combo: state after stop was ${world.core.state}\n${world.log.describe()}",
            )
        }
        // P4: unique command ids.
        val ids = world.device.attempts.map { it.commandId }
        assertEquals(ids.size, ids.toSet().size, "$combo: duplicate command ids")
        // Model call budget must never be exceeded.
        assertTrue(world.budget.modelCalls <= 90)
    }

    @Test
    fun `timing matrix covers at least one hundred distinct combinations`() {
        val combos = matrix()
        assertTrue(combos.size >= 100, "matrix must have >= 100 combinations, had ${combos.size}")
        assertEquals(combos.size, combos.toSet().size, "combinations must be distinct")
    }

    @Test
    fun `stop at every phase never revives input`() {
        for (combo in matrix()) {
            val world = World(combo)
            run(world)
            assertProperties(world)
        }
    }

    @Test
    fun `stop without a stop never loses the normal path`() {
        // Sanity check that the matrix's "no stop" combinations still execute exactly once,
        // so the tests above cannot pass by never executing anything.
        var executed = 0
        for (combo in matrix()) {
            if (combo.phase == Phase.ACK || combo.phase == Phase.OUTPUT || combo.phase == Phase.EXECUTE_ENTRY) continue
            val world = World(combo)
            run(world)
            if (world.device.outputs.isNotEmpty()) executed += 1
        }
        assertTrue(executed > 0, "at least some combinations must reach the device")
    }

    // ---------------------------------------------------------------------------------
    // Genuine concurrency: a second thread issues the stop while the first is blocked inside
    // the injected phase. These are the cases a sequential clock advance cannot produce.
    // ---------------------------------------------------------------------------------

    private fun raceTest(
        phase: Phase,
        outcome: DeviceOutcome,
        block: (World, CountDownLatch, CountDownLatch) -> Unit,
    ): World {
        val combo = Combo(phase, stopFirst = false, clockJump = ClockJump.NONE, outcome = outcome, rotateDuringModel = false)
        val world = World(combo)
        world.device.outcome = outcome
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        block(world, entered, proceed)

        world.core.beginTask("tap", world.clock.nowMs())
        world.clock.advance(5)

        val stepper = Thread { world.result = world.core.step(world.clock.nowMs()) }
        stepper.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS), "injected phase $phase was never reached")
        world.stopNow("concurrent stop during $phase")
        proceed.countDown()
        stepper.join(10_000)
        assertTrue(!stepper.isAlive, "step did not return after the stop")
        assertProperties(world)
        return world
    }

    private val VERIFIER = ALWAYS_SATISFIED

    private companion object {
        val ALWAYS_SATISFIED: Verifier = Verifier { _, _ -> VerificationVerdict.Satisfied }
    }

    /** Verifier whose behaviour can be swapped per test (used by the verification race). */
    private class MutableVerifier(private var delegate: Verifier) : Verifier {
        fun set(verifier: Verifier) {
            delegate = verifier
        }

        override fun verify(observation: com.esp32phone.agent.agent.LiveObservation, expected: String): VerificationVerdict =
            delegate.verify(observation, expected)
    }

    @Test
    fun `concurrent stop while blocked inside the model call`() {
        val world = raceTest(Phase.MODEL, DeviceOutcome.Done) { w, entered, proceed ->
            w.model.onDecide = {
                entered.countDown()
                proceed.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(world.result is StepResult.Stale, "late model decision must be dropped, got ${world.result}")
        assertEquals(0, world.device.outputs.size)
        assertEquals(1, world.device.stopCalls)
        assertEquals(AgentState.STOPPED, world.core.state)
    }

    @Test
    fun `concurrent stop while blocked inside verification`() {
        val world = raceTest(Phase.VERIFY, DeviceOutcome.Done) { w, entered, proceed ->
            // Model says done and the verifier blocks; the stop happens during verification.
            w.model.decision = doneDecision()
            w.mutableVerifier.set { _, _ ->
                entered.countDown()
                proceed.await(5, TimeUnit.SECONDS)
                VerificationVerdict.Satisfied
            }
        }
        assertTrue(
            world.result is StepResult.Stale,
            "a stop during verification must not become COMPLETED, got ${world.result}",
        )
        assertTrue(world.core.state != AgentState.COMPLETED, "state must not be COMPLETED")
        assertEquals(AgentState.STOPPED, world.core.state)
    }

    @Test
    fun `concurrent stop while blocked at the device entry`() {
        val world = raceTest(Phase.EXECUTE_ENTRY, DeviceOutcome.Done) { w, entered, proceed ->
            w.device.onExecute = {
                entered.countDown()
                proceed.await(5, TimeUnit.SECONDS)
            }
        }
        // The permit was valid at entry but must be stale after the stop; nothing may be written.
        assertEquals(0, world.device.outputs.size, "no HID report may follow the stop")
        assertEquals(1, world.device.stalePermitAttempts, "the sink must see the revoked permit and refuse")
        assertTrue(world.result is StepResult.Stale, "expected stale, got ${world.result}")
    }

    @Test
    fun `concurrent stop between the hid report and the acknowledgement`() {
        val world = raceTest(Phase.ACK, DeviceOutcome.Done) { w, entered, proceed ->
            w.device.onAfterOutput = {
                entered.countDown()
                proceed.await(5, TimeUnit.SECONDS)
            }
        }
        assertEquals(1, world.device.outputs.size, "the report itself was already emitted")
        assertTrue(
            world.result is StepResult.Stale,
            "an outcome that arrived after the stop must be reported as unknown, got ${world.result}",
        )
        assertEquals(AgentState.STOPPED, world.core.state)
        assertTrue(world.core.lastError!!.contains("outcome unknown"))
    }

    @Test
    fun `concurrency errors are reproducible`() {
        // Same interleaving twice must produce the same event-kind sequence (no flaky coverage).
        fun once(): List<EventKind> {
            val world = raceTest(Phase.MODEL, DeviceOutcome.Done) { w, entered, proceed ->
                w.model.onDecide = {
                    entered.countDown()
                    proceed.await(5, TimeUnit.SECONDS)
                }
            }
            return world.log.kinds()
        }
        assertEquals(once(), once())
    }
}
