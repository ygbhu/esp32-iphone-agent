package com.esp32phone.agent.agent

import com.esp32phone.agent.agent.AgentBudget
import com.esp32phone.agent.agent.AgentCore
import com.esp32phone.agent.agent.AgentState
import com.esp32phone.agent.agent.DeviceOutcome
import com.esp32phone.agent.agent.LiveObservation
import com.esp32phone.agent.agent.ModelException
import com.esp32phone.agent.agent.ModelFailure
import com.esp32phone.agent.agent.ObservationPolicy
import com.esp32phone.agent.agent.PauseReason
import com.esp32phone.agent.agent.StepResult
import com.esp32phone.agent.agent.StopController
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
import com.esp32phone.agent.protocol.Crypto
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regressions for `docs/codex-checkpoint-review-2.md` items 1-3.
 *
 * Every test here must fail on the previous implementation:
 *  1. a callback belonging to a dead generation wrote state/`lastError`/pending/policy of the
 *     *new* task, and an old device exception could stop the new generation;
 *  2. `revalidate` judged freshness with a clock read taken *before* the capture, so a slow
 *     capture could authorise input on an aged observation;
 *  3. the permit was check-then-act, leaving a window between the check and the HID report.
 */
class AgentGenerationIsolationTest {

    private class Rig(
        decision: com.esp32phone.agent.control.ModelDecision = tapAction(),
        verifier: Verifier = Verifier { _, _ -> VerificationVerdict.Unavailable },
    ) {
        val log = EventLog()
        val clock = ManualClock(5_000)
        val authority = StopController()
        val screen = FakeScreenSource(clock, log)
        val model = FakeModel(decision, log)
        val device = FakeDevice(clock, authority, log)
        val core = AgentCore(
            clock = clock,
            screen = screen,
            model = model,
            device = device,
            budget = AgentBudget(),
            authority = authority,
            verifier = verifier,
            policy = ObservationPolicy(),
        )

        init {
            authority.logRevokes(log)
        }

        fun start(instruction: String = "tap the test button"): Rig {
            core.beginTask(instruction, clock.nowMs())
            return this
        }

        fun step(): StepResult {
            clock.advance(5)
            result = core.step(clock.nowMs())
            return result!!
        }

        var result: StepResult? = null
    }

    // --- item 1: dead-generation callbacks must not write the new task -------------------

    private fun assertNewTaskUntouched(rig: Rig, expectedGeneration: Long) {
        assertEquals(AgentState.IDLE, rig.core.state, "a dead callback must not change the new task's state")
        assertNull(rig.core.lastError, "a dead callback must not write lastError")
        assertNull(rig.core.pendingCommandId, "a dead callback must not create a pending command")
        assertTrue(!rig.core.hasPendingAction)
        assertTrue(
            rig.authority.isCurrent(rig.core.ownedGeneration),
            "the new task's authority must still be live",
        )
        assertEquals(expectedGeneration, rig.core.ownedGeneration)
    }

    @Test
    fun `old device Done after stop and new task writes nothing into the new task`() {
        val rig = Rig().start()
        var newGeneration = -1L
        var fired = false
        rig.device.onExecute = {
            if (!fired) {
                fired = true
                rig.core.stop("stop while writing")
                newGeneration = rig.core.beginTask("task two", rig.clock.nowMs())
            }
        }

        val result = rig.step()

        assertTrue(result is StepResult.Stale, "expected Stale, got $result")
        assertEquals(1L, (result as StepResult.Stale).abandonedCommandId, "the unknown command must be reported")
        assertNewTaskUntouched(rig, newGeneration)
        assertEquals(0, rig.device.outputs.size, "the commit must have been refused")
        assertEquals(1, rig.device.stalePermitAttempts)
        assertEquals(1, rig.core.orphaned.size)
        assertEquals(1L, rig.core.orphaned.single().commandId)

        // The new task still works and keeps increasing command ids.
        val second = rig.step()
        assertTrue(second is StepResult.Executed, "task two must be able to act, got $second")
        assertEquals(2L, (second as StepResult.Executed).commandId)
        assertEquals(1, rig.device.outputs.size)
    }

    @Test
    fun `old device exception after stop and new task does not stop the new generation`() {
        val rig = Rig().start()
        var newGeneration = -1L
        var fired = false
        rig.device.onExecute = {
            if (!fired) {
                fired = true
                rig.core.stop("stop while writing")
                newGeneration = rig.core.beginTask("task two", rig.clock.nowMs())
                throw IllegalStateException("usb detached")
            }
        }

        val result = rig.step()

        assertTrue(result is StepResult.Stale, "expected Stale, got $result")
        assertNewTaskUntouched(rig, newGeneration)
        assertEquals(
            1,
            rig.device.stopCalls,
            "only the explicit stop may reach the device; the old exception must not stop the new task",
        )
    }

    @Test
    fun `old screen exception after stop and new task writes nothing`() {
        val rig = Rig().start()
        var newGeneration = -1L
        var calls = 0
        rig.screen.onObserve = {
            if (calls++ == 0) {
                rig.core.stop("stop during capture")
                newGeneration = rig.core.beginTask("task two", rig.clock.nowMs())
                throw IllegalStateException("capture died")
            }
        }

        val result = rig.step()

        assertTrue(result is StepResult.Stale, "expected Stale, got $result")
        assertNewTaskUntouched(rig, newGeneration)
    }

    @Test
    fun `old screen null after stop and new task writes nothing`() {
        val rig = Rig().start()
        var newGeneration = -1L
        var calls = 0
        rig.screen.onObserve = {
            if (calls++ == 0) {
                rig.core.stop("stop during capture")
                newGeneration = rig.core.beginTask("task two", rig.clock.nowMs())
            }
        }
        rig.screen.available = false

        val result = rig.step()

        assertTrue(result is StepResult.Stale, "expected Stale, got $result")
        assertNewTaskUntouched(rig, newGeneration)
    }

    @Test
    fun `old model error after stop and new task writes nothing`() {
        val rig = Rig().start()
        var newGeneration = -1L
        var fired = false
        rig.model.onDecide = {
            if (!fired) {
                fired = true
                rig.core.stop("stop while thinking")
                newGeneration = rig.core.beginTask("task two", rig.clock.nowMs())
                throw ModelException(ModelFailure.TIMEOUT, "late timeout")
            }
        }

        val result = rig.step()

        assertTrue(result is StepResult.Stale, "expected Stale, got $result")
        assertNewTaskUntouched(rig, newGeneration)
        assertEquals(0, rig.device.attempts.size)
    }

    @Test
    fun `old verifier result after stop and new task writes nothing`() {
        lateinit var rig: Rig
        var newGeneration = -1L
        rig = Rig(
            decision = doneDecision(),
            verifier = Verifier { _, _ ->
                rig.core.stop("stop during verification")
                newGeneration = rig.core.beginTask("task two", rig.clock.nowMs())
                VerificationVerdict.Satisfied
            },
        ).start()

        val result = rig.step()

        assertTrue(result is StepResult.Stale, "a dead verifier callback must not complete, got $result")
        assertNewTaskUntouched(rig, newGeneration)
        assertEquals(0, rig.device.outputs.size)
    }

    @Test
    fun `old callbacks never invalidate the new task's observation policy`() {
        val rig = Rig().start()
        // First task: stop inside the model call, then start task two before the decision
        // returns. The stale callback runs revalidation bookkeeping on a dead generation.
        var newGeneration = -1L
        var fired = false
        rig.model.onDecide = {
            if (!fired) {
                fired = true
                rig.core.stop("stop while thinking")
                newGeneration = rig.core.beginTask("task two", rig.clock.nowMs())
            }
        }
        rig.step()

        // Task two must be able to observe and act on the very next frame.
        val second = rig.step()
        assertTrue(
            second is StepResult.Executed,
            "a stale callback must not invalidate the new task's policy, got $second",
        )
        assertEquals(newGeneration, rig.core.ownedGeneration)
    }

    // --- item 2: freshness must be judged with a clock read after the capture ----------

    @Test
    fun `a slow capture cannot authorise input on an aged observation`() {
        val rig = Rig().start()
        var calls = 0
        // The *final* capture before execution takes longer than the freshness budget. This is
        // the only place a post-capture clock read can catch it: the earlier capture would be
        // covered by the next revalidation's entry read.
        rig.screen.onObserve = {
            if (calls++ == 2) rig.clock.advance(ObservationPolicy.DEFAULT_MAX_FRAME_AGE_MS + 500)
        }

        val result = rig.step()

        assertTrue(result is StepResult.Paused, "a slow capture must pause, got $result")
        assertEquals(PauseReason.NO_FRESH_FRAME, rig.core.pauseReason)
        assertEquals(0, rig.device.outputs.size, "no HID output from an aged observation")
        assertEquals(0, rig.device.attempts.size)
        assertTrue(rig.core.lastError!!.contains("aged out"), rig.core.lastError!!)
    }

    @Test
    fun `a capture returning a fresh frame with identical geometry is still rejected when the bound frame aged`() {
        val rig = Rig().start()
        var calls = 0
        rig.screen.onObserve = {
            // Return a brand-new frame (new id, timestamp taken at call entry) but take 2 s.
            if (calls++ == 2) rig.clock.advance(ObservationPolicy.DEFAULT_MAX_FRAME_AGE_MS + 500)
        }

        val result = rig.step()

        assertTrue(result is StepResult.Paused, "got $result")
        assertEquals(0, rig.device.outputs.size)
    }

    @Test
    fun `a future dated observation is never authorised`() {
        val rig = Rig().start()
        var calls = 0
        rig.screen.onObserve = {
            // Simulate a mixed clock domain: the final capture claims to be from the future.
            if (calls++ == 2) rig.screen.backdateMs = -5_000
        }

        val result = rig.step()

        assertTrue(result is StepResult.Paused, "a future observation must pause, got $result")
        assertEquals(PauseReason.NO_FRESH_FRAME, rig.core.pauseReason)
        assertEquals(0, rig.device.outputs.size)
        assertTrue(rig.core.lastError!!.contains("future"), rig.core.lastError!!)
    }

    @Test
    fun `a stale current frame during revalidation is rejected`() {
        val rig = Rig().start()
        var calls = 0
        rig.screen.onObserve = {
            // Advance the clock and let the source back-date the frame it returns.
            if (calls++ == 2) {
                rig.clock.advance(ObservationPolicy.DEFAULT_MAX_FRAME_AGE_MS + 500)
                rig.screen.backdateMs = ObservationPolicy.DEFAULT_MAX_FRAME_AGE_MS + 100
            }
        }

        val result = rig.step()

        assertTrue(result is StepResult.Paused, "got $result")
        assertEquals(0, rig.device.outputs.size)
    }

    // --- item 3: the commit boundary is shared with stop() -----------------------------

    @Test
    fun `a stop that lands before the commit refuses the report`() {
        val rig = Rig().start()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        rig.device.onBeforeOutput = {
            entered.countDown()
            proceed.await(5, TimeUnit.SECONDS)
        }
        val stepper = Thread { rig.result = rig.step() }
        stepper.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the device entry hook was never reached")

        val stopper = Thread { rig.core.stop("stop before the report") }
        stopper.start()
        stopper.join(5_000)
        proceed.countDown()
        stepper.join(10_000)

        assertEquals(0, rig.device.outputs.size, "the report must never be written after the revoke")
        assertEquals(1, rig.device.stalePermitAttempts, "the refused commit must be recorded")
        assertTrue(rig.result is StepResult.Stale, "got ${rig.result}")
        assertEquals(AgentState.STOPPED, rig.core.state)
    }

    @Test
    fun `a report that commits before the stop is not retroactively revoked but reports unknown`() {
        val rig = Rig().start()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        rig.device.onAfterOutput = {
            entered.countDown()
            proceed.await(5, TimeUnit.SECONDS)
        }
        val stepper = Thread { rig.result = rig.step() }
        stepper.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val stopper = Thread { rig.core.stop("stop after the report") }
        stopper.start()
        stopper.join(5_000)
        proceed.countDown()
        stepper.join(10_000)

        assertEquals(1, rig.device.outputs.size, "the report was already committed before the stop")
        assertTrue(rig.result is StepResult.Stale, "an outcome after the stop is unknown, got ${rig.result}")
        assertEquals(1L, (rig.result as StepResult.Stale).abandonedCommandId)
        assertEquals(AgentState.STOPPED, rig.core.state)
    }

    @Test
    fun `a permit only authorises its own command and action`() {
        val authority = StopController()
        val digestA = Crypto.sha256("""{"op":"tap","x_counts":2,"y_counts":2,"duration_ms":80}""".toByteArray()).copyOf(16)
        val digestB = Crypto.sha256("""{"op":"tap","x_counts":1,"y_counts":1,"duration_ms":80}""".toByteArray()).copyOf(16)
        val permit = authority.issuePermit(authority.current, 7, digestA)!!
        val device = FakeDevice(ManualClock(1_000), authority, EventLog())
        val observation = LiveObservation(1, 1, 1_000, null, 1080, 2400, 0)

        val outcome = device.execute(
            ActionRequest(
                commandId = 7,
                generation = authority.current,
                permit = permit,
                action = com.esp32phone.agent.control.HidAction.Tap(1, 1, 80),
                observation = observation,
                decisionFrameId = 1,
                payload = """{"op":"tap","x_counts":1,"y_counts":1,"duration_ms":80}""".toByteArray(),
                actionDigest = digestB,
            ),
        )

        assertTrue(outcome is DeviceOutcome.Rejected, "expected rejection, got $outcome")
        assertEquals(0, device.outputs.size)
        assertEquals(1, device.permitMismatches, "the sink must reject a permit for another action")
        assertEquals(0, device.digestViolations)
        assertEquals(0, permit.dispatchCount, "a mismatched permit must not commit anything")
    }

    @Test
    fun `dispatch after a revoke is refused every time`() {
        val authority = StopController()
        val digest = Crypto.sha256("payload".toByteArray()).copyOf(16)
        val permit = authority.issuePermit(authority.current, 3, digest)!!

        var wrote = 0
        assertTrue(permit.tryDispatch { wrote += 1 }, "the first dispatch must succeed")
        assertEquals(1, wrote)

        authority.stop()

        repeat(5) {
            assertTrue(!permit.tryDispatch { wrote += 1 }, "every dispatch after the revoke must be refused")
        }
        assertEquals(1, wrote, "no report may be written after the revoke")
        assertEquals(1, permit.dispatchCount)
        assertTrue(!permit.isCurrent())
    }

    @Test
    fun `a permit may commit several short reports while authority is live`() {
        // A swipe legitimately needs move/press/move/release as separate short reports.
        val authority = StopController()
        val digest = Crypto.sha256("swipe".toByteArray()).copyOf(16)
        val permit = authority.issuePermit(authority.current, 9, digest)!!

        var reports = 0
        assertTrue(permit.tryDispatch { reports += 1 })
        assertTrue(permit.tryDispatch { reports += 1 })
        assertTrue(permit.tryDispatch { reports += 1 })
        assertEquals(3, reports)
        assertEquals(3, permit.dispatchCount)
        assertTrue(permit.matchesAction(9, digest))
        assertTrue(!permit.matchesAction(10, digest))
    }

    @Test
    fun `stop waits for an in-flight short commit instead of interleaving with it`() {
        // Mutually exclusive critical section: while a commit is running, stop() cannot
        // complete. A check-then-act permit (no shared lock) would let stop() finish
        // immediately and the report would land afterwards.
        val authority = StopController()
        val digest = Crypto.sha256("report".toByteArray()).copyOf(16)
        val permit = authority.issuePermit(authority.current, 11, digest)!!
        val inCommit = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopDone = CountDownLatch(1)

        val dispatcher = Thread {
            permit.tryDispatch {
                inCommit.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        dispatcher.start()
        assertTrue(inCommit.await(5, TimeUnit.SECONDS), "the commit never started")

        val stopper = Thread {
            authority.stop()
            stopDone.countDown()
        }
        stopper.start()

        assertTrue(
            !stopDone.await(150, TimeUnit.MILLISECONDS),
            "stop() completed while a report was inside the commit boundary",
        )
        release.countDown()
        assertTrue(stopDone.await(5, TimeUnit.SECONDS), "stop() did not complete after the commit finished")
        dispatcher.join(5_000)
        stopper.join(5_000)

        assertEquals(1, permit.dispatchCount)
        assertTrue(!permit.isCurrent(), "the commit finished before the revoke, so it stays valid for nothing more")
        var after = 0
        assertTrue(!permit.tryDispatch { after += 1 }, "no report may be committed after the stop")
        assertEquals(0, after)
    }

    @Test
    fun `concurrent stop and dispatch never commit a report after the revoke`() {
        // Property test: whatever the interleaving, no output may be committed for a
        // generation that has been revoked. The event log records the order.
        repeat(120) { iteration ->
            val rig = Rig().start()
            val start = CountDownLatch(1)
            val stepper = Thread {
                start.await(5, TimeUnit.SECONDS)
                rig.result = rig.step()
            }
            val stopper = Thread {
                start.await(5, TimeUnit.SECONDS)
                // Deterministically vary how far the stepper gets before the stop.
                repeat(iteration % 7) { Thread.yield() }
                rig.core.stop("concurrent stop #$iteration")
            }
            stepper.start()
            stopper.start()
            start.countDown()
            stepper.join(10_000)
            stopper.join(10_000)

            val dead = rig.log.outputsWithDeadGeneration()
            assertTrue(
                dead.isEmpty(),
                "iteration $iteration committed output after the revoke: $dead\n${rig.log.describe()}",
            )
            assertEquals(
                rig.device.attempts.size,
                rig.device.outputs.size + rig.device.stalePermitAttempts,
                "iteration $iteration: every attempt must either commit or be refused",
            )
            assertEquals(0, rig.device.digestViolations)
            assertEquals(0, rig.device.permitMismatches)
            assertTrue(rig.log.kinds().contains(EventKind.REVOKE) || rig.device.outputs.isNotEmpty())
        }
    }
}
