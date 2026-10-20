package com.esp32phone.agent.agent

import com.esp32phone.agent.control.HidAction
import com.esp32phone.agent.harness.FakeDevice
import com.esp32phone.agent.harness.FakeModel
import com.esp32phone.agent.harness.FakeScreenSource
import com.esp32phone.agent.harness.ManualClock
import com.esp32phone.agent.harness.tapAction
import com.esp32phone.agent.protocol.Crypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinalBoundaryRegressionTest {
    private fun releaseStartsNewTask(budget: AgentBudget, throwOnExecute: Boolean) {
        val clock = ManualClock(5_000)
        val authority = StopController()
        lateinit var core: AgentCore
        var stops = 0
        var newGeneration = -1L
        val device = object : DeviceSink {
            override fun execute(request: ActionRequest): DeviceOutcome {
                if (throwOnExecute) error("disconnected")
                return DeviceOutcome.Done
            }

            override fun requestStop(reason: String): DeviceOutcome {
                stops++
                assertTrue(authority.isStopped)
                newGeneration = core.beginTask("replacement task")
                return DeviceOutcome.Done
            }
        }
        core = AgentCore(clock, FakeScreenSource(clock), FakeModel(tapAction()), device, budget, authority)
        core.beginTask("old task")

        assertTrue(core.step() is StepResult.Stale)
        assertEquals(1, stops)
        assertEquals(newGeneration, core.ownedGeneration)
        assertTrue(authority.isCurrent(newGeneration))
        assertEquals(AgentState.IDLE, core.state)
        assertNull(core.pauseReason)
        assertNull(core.lastError)
        assertNull(core.pendingCommandId)
    }

    @Test
    fun `device release returning to old exception cannot overwrite replacement task`() {
        releaseStartsNewTask(AgentBudget(), throwOnExecute = true)
    }

    @Test
    fun `budget release returning to old step cannot overwrite replacement task`() {
        releaseStartsNewTask(AgentBudget(maxActions = 0), throwOnExecute = false)
    }

    @Test
    fun `old step cannot obtain permit after stop and replacement task`() {
        val authority = StopController()
        val old = authority.beginNewTask()
        val digest = ByteArray(16)
        val oldPermit = authority.issuePermit(old, 1, digest)!!
        authority.stop()
        val replacement = authority.beginNewTask()

        assertNull(authority.issuePermit(old, 2, digest))
        assertTrue(!oldPermit.tryDispatch { error("old output must not run") })
        val fresh = authority.issuePermit(replacement, 2, digest)!!
        var writes = 0
        assertTrue(fresh.tryDispatch { writes++ })
        assertEquals(1, writes)
    }

    @Test
    fun `sink refuses old request carrying new task permit`() {
        val clock = ManualClock(5_000)
        val authority = StopController()
        val old = authority.beginNewTask()
        authority.stop()
        val replacement = authority.beginNewTask()
        val action = HidAction.Tap(500, 500, 80)
        val payload = action.toWireJson().toByteArray(Charsets.UTF_8)
        val digest = Crypto.sha256(payload).copyOf(16)
        val permit = authority.issuePermit(replacement, 1, digest)!!
        val device = FakeDevice(clock, authority)
        val request = ActionRequest(1, old, permit, action,
            FakeScreenSource(clock).currentObservation(clock.nowMs())!!, 1, payload, digest)

        assertTrue(device.execute(request) is DeviceOutcome.Rejected)
        assertEquals(1, device.permitMismatches)
        assertTrue(device.outputs.isEmpty())
        assertEquals(0, permit.dispatchCount)
    }
}
