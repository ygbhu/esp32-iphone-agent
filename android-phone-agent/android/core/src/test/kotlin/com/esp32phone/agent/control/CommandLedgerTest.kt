package com.esp32phone.agent.control

import com.esp32phone.agent.Vectors
import com.esp32phone.agent.arr
import com.esp32phone.agent.integer
import com.esp32phone.agent.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import com.esp32phone.agent.protocol.ProtocolException

class CommandLedgerTest {

    private val doc = Vectors.load("command_ledger.json")

    @Test
    fun `shared ledger scenarios behave as specified`() {
        assertEquals(64, CommandLedger.DEFAULT_CACHE_SIZE)
        assertEquals(doc.integer("cache_size"), CommandLedger.DEFAULT_CACHE_SIZE)
        for (item in doc.arr("scenarios").items) {
            val scenario = item.asObject()
            val name = scenario.text("name")
            val ledger = CommandLedger()
            for (step in scenario.arr("steps").items) {
                val spec = step.asObject()
                val commandId = spec.integer("command_id").toLong()
                val payload = spec.text("payload").toByteArray()
                val expected = LedgerDecision.valueOf(spec.text("expect"))
                assertEquals(expected, ledger.admit(commandId, payload), "$name: command $commandId")
            }
        }
    }

    @Test
    fun `duplicate returns the stored state instead of re-executing`() {
        val ledger = CommandLedger()
        val payload = "tap:1,2".toByteArray()
        assertEquals(LedgerDecision.EXECUTE, ledger.admit(1, payload))
        ledger.recordState(1, "done")
        assertEquals(LedgerDecision.DUPLICATE, ledger.admit(1, payload))
        assertEquals("done", ledger.stateOf(1))
        assertEquals(1, ledger.cachedCount(), "a duplicate must not add a second entry")
    }

    @Test
    fun `conflicting payload for the same id is refused`() {
        val ledger = CommandLedger()
        assertEquals(LedgerDecision.EXECUTE, ledger.admit(3, "a".toByteArray()))
        assertEquals(LedgerDecision.CONFLICT, ledger.admit(3, "b".toByteArray()))
        assertEquals("ACCEPTED", ledger.stateOf(3), "the original command keeps its state")
    }

    @Test
    fun `watermark survives eviction`() {
        val ledger = CommandLedger(cacheSize = 4)
        for (id in 1L..10L) {
            assertEquals(LedgerDecision.EXECUTE, ledger.admit(id, "p$id".toByteArray()), "id $id")
        }
        assertEquals(4, ledger.cachedCount())
        assertEquals(10L, ledger.acceptedWatermark)
        assertEquals(LedgerDecision.DUPLICATE, ledger.admit(10, "p10".toByteArray()))
        assertEquals(LedgerDecision.STALE, ledger.admit(5, "p5".toByteArray()), "evicted id must never re-execute")
        assertEquals(LedgerDecision.STALE, ledger.admit(1, "p1".toByteArray()))
    }

    @Test
    fun `a fresh session clears the watermark`() {
        val ledger = CommandLedger(cacheSize = 2)
        ledger.admit(5, "a".toByteArray())
        ledger.admit(9, "b".toByteArray())
        ledger.reset()
        assertEquals(0L, ledger.acceptedWatermark)
        assertNull(ledger.stateOf(1), "a reset ledger holds no state")
        assertEquals(LedgerDecision.EXECUTE, ledger.admit(1, "a".toByteArray()))
        assertEquals("ACCEPTED", ledger.stateOf(1))
    }

    @Test
    fun `negative ids are a programming error`() {
        val ledger = CommandLedger()
        try {
            ledger.admit(-1, ByteArray(0))
            fail("negative command id must not be accepted")
        } catch (e: IllegalArgumentException) {
            assertEquals(true, e.message!!.contains("non-negative"))
        }
    }

    @Test
    fun `stale id rejection carries a protocol error when surfaced`() {
        // CommandLedger itself returns STALE; the device layer maps it to COMMAND_STALE.
        val ledger = CommandLedger(cacheSize = 1)
        ledger.admit(1, "a".toByteArray())
        ledger.admit(2, "b".toByteArray())
        val decision = ledger.admit(1, "a".toByteArray())
        assertEquals(LedgerDecision.STALE, decision)
        val mapped = ProtocolException(com.esp32phone.agent.protocol.ErrorCode.COMMAND_STALE, "stale command id")
        assertEquals(com.esp32phone.agent.protocol.ErrorCode.COMMAND_STALE, mapped.code)
    }
}
