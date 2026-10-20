package com.esp32phone.agent.agent

import com.esp32phone.agent.agent.AgentBudget
import com.esp32phone.agent.agent.AgentCore
import com.esp32phone.agent.agent.AgentState
import com.esp32phone.agent.agent.BudgetReason
import com.esp32phone.agent.agent.DeviceOutcome
import com.esp32phone.agent.agent.ModelException
import com.esp32phone.agent.agent.ModelFailure
import com.esp32phone.agent.agent.ObservationPolicy
import com.esp32phone.agent.agent.PauseReason
import com.esp32phone.agent.agent.StepResult
import com.esp32phone.agent.agent.StopController
import com.esp32phone.agent.agent.UnavailableVerifier
import com.esp32phone.agent.agent.VerificationVerdict
import com.esp32phone.agent.agent.Verifier
import com.esp32phone.agent.control.HidAction
import com.esp32phone.agent.control.KeyName
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentCoreTest {

    private class Rig(
        val budget: AgentBudget = AgentBudget(),
        modelDecision: ModelDecision = tapAction(),
        val verifier: Verifier = UnavailableVerifier,
        val policy: ObservationPolicy = ObservationPolicy(),
    ) {
        val log = EventLog()
        val clock = ManualClock(5_000)
        val authority = StopController()
        val screen = FakeScreenSource(clock, log)
        val model = FakeModel(modelDecision, log)
        val device = FakeDevice(clock, authority, log)
        val core = AgentCore(clock, screen, model, device, budget, authority, verifier, policy)

        init {
            authority.logRevokes(log)
        }

        fun start(instruction: String = "tap the test button"): Rig {
            core.beginTask(instruction, clock.nowMs())
            return this
        }

        fun step(): StepResult = core.step(clock.nowMs())
    }

    @Test
    fun `a normal step observes decides and executes exactly once`() {
        val rig = Rig().start()
        rig.clock.advance(5)
        val result = rig.step()
        assertTrue(result is StepResult.Executed, "expected Executed, got $result")
        assertEquals(1, rig.device.attempts.size)
        assertEquals(1, rig.device.outputs.size)
        assertEquals(AgentState.VERIFYING, rig.core.state)
        assertEquals(1, rig.budget.modelCalls)
        assertEquals(1, rig.budget.actions)
        assertEquals(
            listOf(
                EventKind.AUTHORITY_GAINED, // beginTask
                EventKind.OBSERVE,
                EventKind.MODEL_REQUEST,
                EventKind.MODEL_RESPONSE,
                EventKind.OBSERVE, // revalidation after the model call
                EventKind.OBSERVE, // pre-execution revalidation
                EventKind.EXECUTE_ENTRY,
                EventKind.OUTPUT,
                EventKind.ACK,
            ),
            rig.log.kinds(),
        )
    }

    @Test
    fun `the same frame cannot authorise two actions`() {
        val rig = Rig().start()
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Executed)
        rig.screen.reuseFrame = true
        rig.clock.advance(5)
        val second = rig.step()
        assertTrue(second is StepResult.Paused, "re-used frame must pause, got $second")
        assertEquals(PauseReason.NO_FRESH_FRAME, rig.core.pauseReason)
        assertEquals(1, rig.device.outputs.size)
    }

    @Test
    fun `missing frame pauses without inventing an observation`() {
        val rig = Rig().start()
        rig.screen.available = false
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Paused)
        assertEquals(PauseReason.NO_FRESH_FRAME, rig.core.pauseReason)
        assertEquals(0, rig.device.attempts.size)
        assertEquals(0, rig.budget.modelCalls)
    }

    @Test
    fun `stale frame pauses`() {
        val rig = Rig().start()
        rig.screen.backdateMs = 5_000
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Paused)
        assertEquals(PauseReason.NO_FRESH_FRAME, rig.core.pauseReason)
        assertTrue(rig.core.lastError!!.contains("STALE_FRAME"))
    }

    @Test
    fun `rotation and size changes invalidate the previous observation`() {
        val rig = Rig().start()
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Executed)
        rig.screen.rotation = 1
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Paused, "rotation change must pause")
        rig.core.resumeAfterPause()
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Executed)
        rig.screen.width = 720
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Paused, "size change must pause")
    }

    @Test
    fun `new projection epoch invalidates old coordinates`() {
        val rig = Rig().start()
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Executed)
        rig.screen.projectionEpoch = 2
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Paused)
        assertTrue(rig.core.lastError!!.contains("OLD_PROJECTION_EPOCH"))
    }

    // --- fix 5: the clock must be re-read after every external call --------------------

    @Test
    fun `a slow model response cannot use an observation that aged out`() {
        val rig = Rig().start()
        rig.model.onDecide = { rig.clock.advance(ObservationPolicy.DEFAULT_MAX_FRAME_AGE_MS + 1) }
        rig.clock.advance(5)
        val result = rig.step()
        assertTrue(result is StepResult.Paused, "aged observation must pause, got $result")
        assertEquals(PauseReason.NO_FRESH_FRAME, rig.core.pauseReason)
        assertEquals(0, rig.device.outputs.size, "no HID output from an aged observation")
    }

    @Test
    fun `a slow model response cannot outlive the task duration budget`() {
        val rig = Rig(budget = AgentBudget(maxDurationMs = 1_000)).start()
        rig.model.onDecide = { rig.clock.advance(600_001) }
        rig.clock.advance(5)
        val result = rig.step()
        assertTrue(result is StepResult.BudgetExceeded, "duration budget must be re-checked, got $result")
        assertEquals(BudgetReason.DURATION, (result as StepResult.BudgetExceeded).reason)
        assertEquals(0, rig.device.outputs.size)
    }

    @Test
    fun `rotation during the model call pauses before any input`() {
        val rig = Rig().start()
        rig.model.onDecide = { rig.screen.rotation = 1 }
        rig.clock.advance(5)
        val result = rig.step()
        assertTrue(result is StepResult.Paused, "rotation during model call must pause, got $result")
        assertEquals(0, rig.device.outputs.size)
        assertTrue(rig.core.lastError!!.contains("rotation changed"))
    }

    @Test
    fun `projection epoch change during the model call pauses`() {
        val rig = Rig().start()
        rig.model.onDecide = { rig.screen.projectionEpoch = 7 }
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Paused)
        assertTrue(rig.core.lastError!!.contains("projection epoch changed"))
    }

    // --- fix 6: resume must not reset the budget --------------------------------------

    @Test
    fun `resume keeps the model call budget`() {
        val rig = Rig(budget = AgentBudget(maxModelCalls = 2, maxConsecutiveFailures = 99)).start()
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Executed)
        rig.core.resumeAfterPause()
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Executed)
        rig.core.resumeAfterPause()
        rig.clock.advance(5)
        val third = rig.step()
        assertTrue(third is StepResult.BudgetExceeded, "resume must not reset model calls, got $third")
        assertEquals(BudgetReason.MODEL_CALLS, (third as StepResult.BudgetExceeded).reason)
    }

    @Test
    fun `resume keeps the task deadline`() {
        val rig = Rig(budget = AgentBudget(maxDurationMs = 1_000, maxConsecutiveFailures = 99)).start()
        rig.core.resumeAfterPause()
        rig.clock.advance(1_500)
        val result = rig.step()
        assertTrue(result is StepResult.BudgetExceeded, "resume must not restart the deadline, got $result")
        assertEquals(BudgetReason.DURATION, (result as StepResult.BudgetExceeded).reason)
    }

    @Test
    fun `resume keeps the action count`() {
        val rig = Rig(budget = AgentBudget(maxActions = 1, maxConsecutiveFailures = 99)).start()
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Executed)
        rig.core.resumeAfterPause()
        rig.clock.advance(5)
        val second = rig.step()
        assertTrue(second is StepResult.BudgetExceeded, "resume must not reset the action budget, got $second")
        assertEquals(BudgetReason.ACTIONS, (second as StepResult.BudgetExceeded).reason)
    }

    // --- fixes 7 and 8: verifier / accepted handling ----------------------------------

    @Test
    fun `model claiming done is never success without an independent verifier`() {
        val rig = Rig(modelDecision = doneDecision()).start()
        rig.clock.advance(5)
        val result = rig.step()
        assertTrue(result is StepResult.Paused, "unverified done must pause, got $result")
        assertEquals(PauseReason.MODEL_DONE_UNVERIFIED, rig.core.pauseReason)
        assertEquals(0, rig.device.outputs.size)
    }

    @Test
    fun `done with a satisfying verifier completes`() {
        val rig = Rig(
            modelDecision = doneDecision(),
            verifier = Verifier { _, _ -> VerificationVerdict.Satisfied },
        ).start()
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Completed)
        assertEquals(AgentState.COMPLETED, rig.core.state)
    }

    @Test
    fun `a stop during verification wins over a satisfying verdict`() {
        val log = EventLog()
        val authority = StopController()
        authority.logRevokes(log)
        val clock = ManualClock(5_000)
        val screen = FakeScreenSource(clock, log)
        val device = FakeDevice(clock, authority, log)
        lateinit var core: AgentCore
        val verifier = Verifier { _, _ ->
            core.stop("user stopped during verification")
            VerificationVerdict.Satisfied
        }
        core = AgentCore(clock, screen, FakeModel(doneDecision()), device, AgentBudget(), authority, verifier)
        core.beginTask("finish", clock.nowMs())
        clock.advance(5)
        val result = core.step(clock.nowMs())
        assertTrue(result is StepResult.Stale, "a stop during verification must not complete, got $result")
        assertTrue(core.state != AgentState.COMPLETED, "state must not be COMPLETED")
        assertEquals(AgentState.STOPPED, core.state)
    }

    @Test
    fun `done with a failing verifier does not complete`() {
        val rig = Rig(
            modelDecision = doneDecision(),
            verifier = Verifier { _, _ -> VerificationVerdict.NotSatisfied("screen unchanged") },
        ).start()
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Paused)
        assertEquals(PauseReason.VERIFICATION_FAILED, rig.core.pauseReason)
    }

    @Test
    fun `accepted is not done and blocks further input`() {
        val rig = Rig().start()
        rig.device.outcome = DeviceOutcome.Accepted
        rig.clock.advance(5)
        val first = rig.step()
        assertTrue(first is StepResult.Pending, "Accepted must produce Pending, got $first")
        assertEquals(PauseReason.ACTION_PENDING, rig.core.pauseReason)
        assertTrue(rig.core.hasPendingAction)
        assertEquals(1, rig.device.outputs.size)

        rig.clock.advance(5)
        val second = rig.step()
        assertTrue(second is StepResult.NoWork, "no second input while a command is pending, got $second")
        assertEquals(1, rig.device.outputs.size)

        // A new task is refused while the result is unknown.
        try {
            rig.core.beginTask("another task", rig.clock.nowMs())
            throw AssertionError("beginTask must be refused while a command is pending")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("pending"))
        }
    }

    @Test
    fun `started is not done either`() {
        val rig = Rig().start()
        rig.device.outcome = DeviceOutcome.Started
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Pending)
        assertTrue(rig.core.hasPendingAction)
    }

    @Test
    fun `pending command can be confirmed or explicitly abandoned`() {
        val rig = Rig().start()
        rig.device.outcome = DeviceOutcome.Accepted
        rig.clock.advance(5)
        rig.step()
        val commandId = rig.core.pendingCommandId!!
        val generation = rig.core.currentGeneration
        assertTrue(rig.core.confirmPendingResult(commandId, generation, DeviceOutcome.Done) is StepResult.Completed)
        assertTrue(!rig.core.hasPendingAction)

        rig.device.outcome = DeviceOutcome.Accepted
        rig.core.beginTask("next", rig.clock.nowMs())
        rig.clock.advance(5)
        rig.step()
        assertTrue(rig.core.hasPendingAction)
        assertTrue(rig.core.abandonPendingAction())
        assertTrue(!rig.core.hasPendingAction)
    }

    @Test
    fun `unknown action outcome pauses and is never retried`() {
        val rig = Rig().start()
        rig.device.outcome = DeviceOutcome.Unknown("done receipt lost")
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Paused)
        assertEquals(PauseReason.ACTION_UNKNOWN, rig.core.pauseReason)
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.NoWork)
        assertEquals(1, rig.device.attempts.size, "no automatic retry of an unknown action")
    }

    // --- fixes 9 and 10: command ids, revoke+release -----------------------------------

    @Test
    fun `command ids keep increasing across tasks in the same device session`() {
        val rig = Rig().start()
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Executed)
        rig.core.beginTask("second task", rig.clock.nowMs())
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Executed)
        val ids = rig.device.attempts.map { it.commandId }
        assertEquals(listOf(1L, 2L), ids, "a new task must not restart command ids")
    }

    @Test
    fun `only a new device session resets the command id watermark`() {
        val rig = Rig().start()
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Executed)
        rig.core.stop("user")
        assertTrue(rig.core.onNewDeviceSession(), "idle core may accept a new device session")
        rig.core.beginTask("after rebind", rig.clock.nowMs())
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Executed)
        assertEquals(listOf(1L, 1L), rig.device.attempts.map { it.commandId })
    }

    @Test
    fun `budget exhaustion revokes authority and releases the device`() {
        val rig = Rig(budget = AgentBudget(maxActions = 0)).start()
        rig.clock.advance(5)
        val result = rig.step()
        assertTrue(result is StepResult.BudgetExceeded, "expected budget stop, got $result")
        val stop = (result as StepResult.BudgetExceeded).stop
        assertTrue(rig.authority.isStopped, "budget exhaustion must revoke authority")
        assertEquals(1, rig.device.stopCalls, "budget exhaustion must release the device")
        assertTrue(stop.generation == rig.authority.current)
        assertEquals(0, rig.device.outputs.size)
        assertTrue(rig.log.kinds().contains(EventKind.REVOKE))
        assertTrue(rig.log.kinds().indexOf(EventKind.REVOKE) < rig.log.kinds().indexOf(EventKind.STOP_REQUEST))
    }

    @Test
    fun `device exception revokes releases and reports unknown`() {
        val clock = ManualClock(0)
        val authority = StopController()
        val log = EventLog()
        authority.logRevokes(log)
        val throwing = object : DeviceSink {
            override fun execute(request: ActionRequest): DeviceOutcome = throw IllegalStateException("usb detached")
            override fun requestStop(reason: String): DeviceOutcome {
                log.record(EventKind.STOP_REQUEST, authority.current, -1, reason)
                return DeviceOutcome.Done
            }
        }
        val core = AgentCore(clock, FakeScreenSource(clock), FakeModel(tapAction()), throwing, AgentBudget(), authority)
        core.beginTask("tap", clock.nowMs())
        clock.advance(5)
        val result = core.step()
        assertTrue(result is StepResult.Paused, "expected pause, got $result")
        assertEquals(PauseReason.ACTION_UNKNOWN, core.pauseReason)
        assertTrue(authority.isStopped, "a device exception must revoke authority")
        assertTrue(core.hasPendingAction, "the unknown command must stay pending")
        assertEquals(1, log.count(EventKind.STOP_REQUEST))
    }

    @Test
    fun `rejected action raises the consecutive failure budget`() {
        val rig = Rig(budget = AgentBudget(maxConsecutiveFailures = 3)).start()
        rig.device.outcome = DeviceOutcome.Rejected(ErrorCode.BUSY, "busy")
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Rejected)
        rig.core.resumeAfterPause()
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Rejected)
        rig.core.resumeAfterPause()
        rig.clock.advance(5)
        val third = rig.step()
        assertTrue(third is StepResult.BudgetExceeded, "third consecutive failure must stop the task, got $third")
        assertEquals(BudgetReason.CONSECUTIVE_FAILURES, (third as StepResult.BudgetExceeded).reason)
        assertTrue(rig.authority.isStopped)
        assertEquals(1, rig.device.stopCalls)
    }

    @Test
    fun `action budget caps the number of hid commands`() {
        val rig = Rig(budget = AgentBudget(maxActions = 2, maxConsecutiveFailures = 99)).start()
        var executed = 0
        repeat(5) {
            rig.clock.advance(5)
            when (rig.step()) {
                is StepResult.Executed -> executed += 1
                is StepResult.Paused -> rig.core.resumeAfterPause()
                else -> Unit
            }
        }
        assertEquals(2, executed, "budget of 2 actions must cap execution")
        assertEquals(2, rig.device.outputs.size)
    }

    @Test
    fun `model call budget is enforced before the next call`() {
        val rig = Rig(budget = AgentBudget(maxModelCalls = 1, maxConsecutiveFailures = 99)).start()
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Executed)
        rig.clock.advance(5)
        val result = rig.step()
        assertTrue(result is StepResult.BudgetExceeded, "expected model-call budget, got $result")
        assertEquals(BudgetReason.MODEL_CALLS, (result as StepResult.BudgetExceeded).reason)
        assertEquals(1, rig.model.calls)
    }

    @Test
    fun `model failures pause instead of executing`() {
        for ((failure, reason) in listOf(
            ModelFailure.TIMEOUT to PauseReason.MODEL_TIMEOUT,
            ModelFailure.INVALID_JSON to PauseReason.MODEL_ERROR,
            ModelFailure.REFUSED to PauseReason.MODEL_ERROR,
        )) {
            val rig = Rig().start()
            rig.model.error = ModelException(failure, "injected $failure")
            rig.clock.advance(5)
            val result = rig.step()
            assertTrue(result is StepResult.Paused, "$failure must pause")
            assertEquals(reason, rig.core.pauseReason, "$failure pause reason")
            assertEquals(0, rig.device.attempts.size, "$failure must not reach the device")
        }
    }

    @Test
    fun `explicit stop requests device release and reports the raw outcome`() {
        val rig = Rig().start()
        rig.device.stopOutcome = DeviceOutcome.Unknown("BLE write not acknowledged")
        val stop = rig.core.stop("user")
        assertEquals(1, rig.device.stopCalls)
        assertTrue(stop.deviceOutcome is DeviceOutcome.Unknown, "a failed release must not be reported as success")
        assertEquals(AgentState.STOPPED, rig.core.state)
        assertTrue(stop.generation > 0)
    }

    @Test
    fun `stop swallows a device exception but surfaces it`() {
        val clock = ManualClock(0)
        val authority = StopController()
        val throwingDevice = object : DeviceSink {
            override fun execute(request: ActionRequest): DeviceOutcome = DeviceOutcome.Done
            override fun requestStop(reason: String): DeviceOutcome = throw IllegalStateException("ble down")
        }
        val core = AgentCore(clock, FakeScreenSource(clock), FakeModel(tapAction()), throwingDevice, AgentBudget(), authority)
        val result = core.stop("user")
        assertEquals(null, result.deviceOutcome)
        assertEquals("ble down", result.deviceStopError)
        assertEquals(AgentState.STOPPED, core.state)
    }

    @Test
    fun `no task means no work`() {
        val rig = Rig()
        assertTrue(rig.step() is StepResult.NoWork)
        assertEquals(0, rig.model.calls)
    }

    @Test
    fun `type and key actions carry a canonical wire payload`() {
        val rig = Rig(modelDecision = ModelDecision.Act(HidAction.TypeAscii("hello"), "type", "text present")).start()
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Executed)
        assertEquals("""{"op":"type_ascii","text":"hello"}""", rig.device.attempts[0].action.toWireJson())

        val keyRig = Rig(modelDecision = ModelDecision.Act(HidAction.Key(KeyName.ENTER), "press enter", "sent")).start()
        keyRig.clock.advance(5)
        assertTrue(keyRig.step() is StepResult.Executed)
        assertEquals("""{"op":"key","key":"enter"}""", keyRig.device.attempts[0].action.toWireJson())
    }

    @Test
    fun `a late stop cannot invalidate the next task's observation policy`() {
        val rig = Rig().start()
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Executed)
        // Old generation is revoked by a resume; a stale callback for the old generation must
        // not clear the new task's bound epoch.
        val oldGeneration = rig.core.currentGeneration
        rig.core.resumeAfterPause()
        rig.clock.advance(5)
        assertTrue(rig.step() is StepResult.Executed)
        assertTrue(!rig.authority.isCurrent(oldGeneration))
        assertEquals(2, rig.device.outputs.size, "new task must still be able to act")
    }
}
