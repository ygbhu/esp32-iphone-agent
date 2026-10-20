package com.esp32phone.agent.agent

import com.esp32phone.agent.agent.AgentBudget
import com.esp32phone.agent.agent.AgentCore
import com.esp32phone.agent.agent.AgentState
import com.esp32phone.agent.agent.DeviceOutcome
import com.esp32phone.agent.agent.LiveObservation
import com.esp32phone.agent.agent.ObservationPolicy
import com.esp32phone.agent.agent.PauseReason
import com.esp32phone.agent.agent.StepResult
import com.esp32phone.agent.agent.StopController
import com.esp32phone.agent.agent.VerificationVerdict
import com.esp32phone.agent.agent.Verifier
import com.esp32phone.agent.control.ModelDecision
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
 * STOP / late-response timing matrix (checklist §5 "首轮至少 100 个时序组合").
 *
 * Each combination injects a stop at a specific *phase* of one step and varies the clock
 * behaviour and the device outcome. The assertions are about the recorded event order and
 * about the phases actually being reached — not about the number of green tests:
 *
 *  P1  no OUTPUT event ever appears after the REVOKE of its generation;
 *  P2  the device stop request is only sent after authority was revoked;
 *  P3  every device call either commits exactly one report or is refused (no third path);
 *  P4  command ids are never reused;
 *  P5  after a stop the task is never COMPLETED/EXECUTING;
 *  P6  the VERIFY phase really calls the verifier (a stop there still wins);
 *  P7  Accepted/Unknown are never reported as Executed/Completed.
 *
 * The phase coverage is asserted explicitly, so a combination that silently fails to reach
 * its target phase fails the test instead of passing vacuously.
 */
class AgentStopTimingMatrixTest {

    private enum class Phase { BEFORE_STEP, OBSERVE, MODEL, VERIFY, EXECUTE_ENTRY, OUTPUT, ACK, AFTER_STEP }

    private enum class ClockJump { NONE, DURING_MODEL, DURING_SCREEN, BEFORE_EXECUTION }

    private data class Combo(
        val phase: Phase,
        val injectStop: Boolean,
        val clockJump: ClockJump,
        val outcome: DeviceOutcome,
    ) {
        val rotate: Boolean get() = phase == Phase.MODEL

        override fun toString(): String =
            "phase=$phase stop=$injectStop clock=$clockJump outcome=${outcome::class.simpleName}"
    }

    private class MutableVerifier(initial: Verifier) : Verifier {
        private var delegate: Verifier = initial

        @Volatile
        var calls: Int = 0
            private set

        fun set(verifier: Verifier) {
            delegate = verifier
        }

        override fun verify(observation: LiveObservation, expected: String): VerificationVerdict {
            calls += 1
            return delegate.verify(observation, expected)
        }
    }

    private class World(val combo: Combo) {
        val log = EventLog()
        val clock = ManualClock(1_000)
        val authority = StopController()
        val screen = FakeScreenSource(clock, log)
        val model = FakeModel(
            if (combo.phase == Phase.VERIFY) doneDecision() else tapAction(),
            log,
        )
        val device = FakeDevice(clock, authority, log)
        val budget = AgentBudget()
        val verifier = MutableVerifier { _, _ -> VerificationVerdict.Satisfied }
        val core = AgentCore(
            clock = clock,
            screen = screen,
            model = model,
            device = device,
            budget = budget,
            authority = authority,
            verifier = verifier,
            policy = ObservationPolicy(),
        )

        var stopIssued = false
        var result: StepResult? = null

        /** Phases actually executed during the run (including the final step). */
        val reached = HashSet<Phase>()

        /** Every phase the injection hook was invoked from. */
        val phasesObserved = LinkedHashSet<Phase>()

        init {
            authority.logRevokes(log)
        }

        fun stopNow() {
            if (stopIssued) return
            stopIssued = true
            core.stop("test stop")
        }

        /** Advances the clock by more than the freshness budget. */
        fun jumpClock() {
            clock.advance(ObservationPolicy.DEFAULT_MAX_FRAME_AGE_MS + 500)
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
            for (injectStop in listOf(true, false)) {
                for (clockJump in ClockJump.entries) {
                    for (outcome in outcomes) {
                        combos.add(Combo(phase, injectStop, clockJump, outcome))
                    }
                }
            }
        }
        return combos
    }

    private fun run(world: World) {
        val combo = world.combo
        world.device.outcome = combo.outcome
        world.verifier.set { _, _ ->
            world.reached += Phase.VERIFY
            injectAt(world, Phase.VERIFY) { world.stopNow() }
            VerificationVerdict.Satisfied
        }

        world.screen.onObserve = {
            world.reached += Phase.OBSERVE
            world.phasesObserved += Phase.OBSERVE
            if (combo.clockJump == ClockJump.DURING_SCREEN) world.jumpClock()
            injectAt(world, Phase.OBSERVE) { world.stopNow() }
        }

        world.model.onDecide = {
            world.reached += Phase.MODEL
            if (combo.rotate) world.screen.rotation = 1
            if (combo.clockJump == ClockJump.DURING_MODEL) world.jumpClock()
            injectAt(world, Phase.MODEL) { world.stopNow() }
        }

        world.device.onExecute = {
            world.reached += Phase.EXECUTE_ENTRY
            injectAt(world, Phase.EXECUTE_ENTRY) { world.stopNow() }
        }
        world.device.onBeforeOutput = {
            world.reached += Phase.OUTPUT
            injectAt(world, Phase.OUTPUT) { world.stopNow() }
        }
        world.device.onAfterOutput = {
            world.reached += Phase.ACK
            injectAt(world, Phase.ACK) { world.stopNow() }
        }

        if (combo.phase == Phase.BEFORE_STEP) {
            world.core.beginTask("tap the test button", world.clock.nowMs())
            world.reached += Phase.BEFORE_STEP
            if (combo.injectStop) world.stopNow()
        } else {
            world.core.beginTask("tap the test button", world.clock.nowMs())
        }

        if (combo.clockJump == ClockJump.BEFORE_EXECUTION) world.jumpClock()
        world.clock.advance(5)
        world.result = world.core.step(world.clock.nowMs())

        if (combo.phase == Phase.AFTER_STEP) {
            world.reached += Phase.AFTER_STEP
            if (combo.injectStop) world.stopNow()
        }

        // A second step must never produce extra input after a stop.
        if (world.stopIssued) {
            world.clock.advance(5)
            val after = world.core.step(world.clock.nowMs())
            assertTrue(
                after is StepResult.Stale || after is StepResult.NoWork,
                "$combo: stepping continued after stop with $after",
            )
        }
    }

    private fun injectAt(world: World, phase: Phase, action: () -> Unit) {
        world.phasesObserved += phase
        if (world.combo.phase == phase && world.combo.injectStop) action()
    }

    private fun assertProperties(world: World) {
        val combo = world.combo

        // P1: no report may be committed after its generation was revoked.
        val dead = world.log.outputsWithDeadGeneration()
        assertTrue(dead.isEmpty(), "$combo: output after revoke: $dead\n${world.log.describe()}")

        // P2: the device stop request is only sent after the revoke.
        assertTrue(!world.device.stopWhileAuthorityLive, "$combo: device stop before revoke")

        // P3: every device call either committed one report or was refused.
        assertEquals(
            world.device.attempts.size,
            world.device.outputs.size + world.device.stalePermitAttempts,
            "$combo: unexpected device call outcome",
        )
        assertEquals(0, world.device.digestViolations, "$combo: digest mismatch")
        assertEquals(0, world.device.permitMismatches, "$combo: permit/action mismatch")

        // P4: unique command ids.
        val ids = world.device.attempts.map { it.commandId }
        assertEquals(ids.size, ids.toSet().size, "$combo: duplicate command ids")

        // P5: after a stop the task must never look successful or still running.
        if (world.stopIssued) {
            assertTrue(
                world.core.state == AgentState.STOPPED || world.core.state == AgentState.PAUSED,
                "$combo: state after stop was ${world.core.state}\n${world.log.describe()}",
            )
            val result = world.result
            assertTrue(
                result !is StepResult.Completed,
                "$combo: a stopped task must not report Completed, got $result",
            )
        }

        // P6: the VERIFY phase must really invoke the verifier. Clock jumps that happen
        // *before* verification legitimately abort earlier (they age the bound frame during
        // revalidation), so precision requires naming those combinations explicitly.
        val verifyPhaseShouldReachVerifier = combo.phase == Phase.VERIFY &&
            combo.clockJump != ClockJump.DURING_MODEL &&
            combo.clockJump != ClockJump.DURING_SCREEN
        if (verifyPhaseShouldReachVerifier) {
            assertTrue(world.verifier.calls >= 1, "$combo: verifier was not reached")
        }
        if (combo.phase == Phase.VERIFY && combo.injectStop && verifyPhaseShouldReachVerifier) {
            assertEquals(1, world.verifier.calls, "$combo: verifier must run exactly once")
            assertTrue(
                world.result is StepResult.Stale,
                "$combo: a stop inside the verifier must not complete, got ${world.result}",
            )
            assertEquals(AgentState.STOPPED, world.core.state)
        }
        if (combo.phase != Phase.VERIFY) {
            assertEquals(0, world.verifier.calls, "$combo: verifier called without a done decision")
        }

        // P7: Accepted/Unknown are never execution success. Only meaningful when the combo
        // actually issued a device command (VERIFY combos end with a done decision instead).
        val deviceWasCalled = world.device.attempts.isNotEmpty()
        if (deviceWasCalled && (combo.outcome is DeviceOutcome.Accepted || combo.outcome is DeviceOutcome.Unknown)) {
            assertTrue(
                world.result !is StepResult.Executed && world.result !is StepResult.Completed,
                "$combo: ${combo.outcome::class.simpleName} reported as success: ${world.result}",
            )
        }
    }

    @Test
    fun `timing matrix covers at least one hundred distinct combinations`() {
        val combos = matrix()
        assertTrue(combos.size >= 100, "matrix must have >= 100 combinations, had ${combos.size}")
        assertEquals(combos.size, combos.toSet().size, "combinations must be distinct")
        // Every phase must actually be represented.
        assertEquals(Phase.entries.toSet(), combos.map { it.phase }.toSet())
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
    fun `each injected phase is really reached`() {
        // Coverage guard: if a hook stopped firing, the matrix above would silently pass.
        val targets = listOf(Phase.OBSERVE, Phase.MODEL, Phase.VERIFY, Phase.EXECUTE_ENTRY, Phase.OUTPUT, Phase.ACK)
        for (target in targets) {
            val combo = Combo(target, injectStop = true, clockJump = ClockJump.NONE, outcome = DeviceOutcome.Done)
            val world = World(combo)
            run(world)
            assertTrue(
                world.reached.contains(target),
                "phase $target was never reached; hooks may be dead\n${world.log.describe()}",
            )
        }
    }

    @Test
    fun `the no stop control group still executes`() {
        var executed = 0
        for (combo in matrix()) {
            if (combo.injectStop) continue
            if (combo.clockJump != ClockJump.NONE) continue
            if (combo.outcome !is DeviceOutcome.Done) continue
            if (combo.phase == Phase.VERIFY) continue
            // These combos rotate the screen by design, which correctly pauses the task.
            if (combo.rotate) continue
            val world = World(combo)
            run(world)
            if (world.result is StepResult.Executed) executed += 1
            assertEquals(1, world.device.outputs.size, "$combo: the clean path must write exactly one report")
        }
        assertTrue(executed >= 5, "the clean path must be exercised, only $executed combos executed")
    }

    @Test
    fun `the no stop control group completes a verified done`() {
        val combo = Combo(Phase.VERIFY, injectStop = false, clockJump = ClockJump.NONE, outcome = DeviceOutcome.Done)
        val world = World(combo)
        run(world)
        assertEquals(1, world.verifier.calls, "the verifier must run for a done decision")
        assertTrue(world.result is StepResult.Completed, "got ${world.result}")
        assertEquals(AgentState.COMPLETED, world.core.state)
        assertEquals(0, world.device.outputs.size, "a done decision writes no HID report")
    }

    @Test
    fun `a clock jump during capture is never authorised`() {
        for (phase in Phase.entries) {
            val combo = Combo(phase, injectStop = false, clockJump = ClockJump.DURING_SCREEN, outcome = DeviceOutcome.Done)
            val world = World(combo)
            run(world)
            if (world.result is StepResult.Executed || world.result is StepResult.Completed) {
                throw AssertionError("$combo: an aged observation was authorised: ${world.result}")
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Genuine concurrency: a second thread issues the stop while the first is blocked in
    // the injected phase. A sequential clock advance cannot produce these interleavings.
    // ---------------------------------------------------------------------------------

    private fun raceTest(
        phase: Phase,
        outcome: DeviceOutcome,
        block: (World, CountDownLatch, CountDownLatch) -> Unit,
    ): World {
        val combo = Combo(phase, injectStop = false, clockJump = ClockJump.NONE, outcome = outcome)
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
        world.stopNow()
        proceed.countDown()
        stepper.join(10_000)
        assertTrue(!stepper.isAlive, "step did not return after the stop")
        assertProperties(world)
        return world
    }

    @Test
    fun `concurrent stop while blocked inside the model call`() {
        val world = raceTest(Phase.MODEL, DeviceOutcome.Done) { w, entered, proceed ->
            w.model.onDecide = {
                w.reached += Phase.MODEL
                entered.countDown()
                proceed.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(world.result is StepResult.Stale, "got ${world.result}")
        assertEquals(0, world.device.outputs.size)
        assertEquals(1, world.device.stopCalls)
        assertEquals(AgentState.STOPPED, world.core.state)
    }

    @Test
    fun `concurrent stop while blocked inside verification`() {
        val world = raceTest(Phase.VERIFY, DeviceOutcome.Done) { w, entered, proceed ->
            w.model.decision = doneDecision()
            w.verifier.set { _, _ ->
                w.reached += Phase.VERIFY
                entered.countDown()
                proceed.await(5, TimeUnit.SECONDS)
                VerificationVerdict.Satisfied
            }
        }
        assertEquals(1, world.verifier.calls, "the verifier must actually have run")
        assertTrue(world.result is StepResult.Stale, "got ${world.result}")
        assertEquals(AgentState.STOPPED, world.core.state)
    }

    @Test
    fun `concurrent stop while blocked at the device entry`() {
        val world = raceTest(Phase.EXECUTE_ENTRY, DeviceOutcome.Done) { w, entered, proceed ->
            w.device.onExecute = {
                w.reached += Phase.EXECUTE_ENTRY
                entered.countDown()
                proceed.await(5, TimeUnit.SECONDS)
            }
        }
        assertEquals(0, world.device.outputs.size, "no HID report may follow the stop")
        assertEquals(1, world.device.stalePermitAttempts)
        assertTrue(world.result is StepResult.Stale, "got ${world.result}")
    }

    @Test
    fun `concurrent stop between the hid report and the acknowledgement`() {
        val world = raceTest(Phase.ACK, DeviceOutcome.Done) { w, entered, proceed ->
            w.device.onAfterOutput = {
                w.reached += Phase.ACK
                entered.countDown()
                proceed.await(5, TimeUnit.SECONDS)
            }
        }
        assertEquals(1, world.device.outputs.size, "the report was already committed")
        assertTrue(world.result is StepResult.Stale, "got ${world.result}")
        assertEquals(AgentState.STOPPED, world.core.state)
        assertTrue(world.core.lastError!!.contains("outcome unknown"), world.core.lastError!!)
    }

    @Test
    fun `concurrency results are reproducible`() {
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
