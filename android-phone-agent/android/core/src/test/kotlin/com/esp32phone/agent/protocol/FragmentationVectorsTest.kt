package com.esp32phone.agent.protocol

import com.esp32phone.agent.arr
import com.esp32phone.agent.integer
import com.esp32phone.agent.obj
import com.esp32phone.agent.text
import com.esp32phone.agent.Vectors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Byte-for-byte agreement with the shared vectors (also consumed by the ESP32 host tests).
 */
class FragmentationVectorsTest {

    private val doc = Vectors.load("fragmentation.json")

    @Test
    fun `encoder reproduces every shared vector`() {
        val cases = doc.arr("cases")
        assertTrue(cases.items.isNotEmpty(), "vector file must contain cases")
        for (item in cases.items) {
            val case = item.asObject()
            val name = case.text("name")
            val payload = case.text("payload_hex").hexToBytes()
            val fragments = Fragmenter.encode(
                payload = payload,
                transferId = case.integer("transfer_id"),
                flags = case.integer("flags"),
                mtu = case.integer("mtu"),
            )
            val expected = case.arr("expected_fragments_hex").items.map { it.asString().hexToBytes() }
            assertEquals(expected.size, fragments.size, "$name: fragment count")
            expected.indices.forEach { index ->
                assertEquals(
                    expected[index].hex(),
                    fragments[index].hex(),
                    "$name: fragment $index",
                )
            }
        }
    }

    @Test
    fun `reassembler rebuilds each vector from its own fragments`() {
        for (item in doc.arr("cases").items) {
            val case = item.asObject()
            val name = case.text("name")
            val mtu = case.integer("mtu")
            val payload = case.text("payload_hex").hexToBytes()
            val reassembler = Reassembler(Channel.NORMAL, mtu)
            var result: ReassemblyResult = ReassemblyResult.Incomplete
            for (fragmentHex in case.arr("expected_fragments_hex").items) {
                result = reassembler.onFragment(fragmentHex.asString().hexToBytes(), nowMs = 0)
            }
            when (result) {
                is ReassemblyResult.Complete ->
                    assertEquals(payload.hex(), result.payload.hex(), "$name: reassembled payload")
                ReassemblyResult.Incomplete -> fail("$name: reassembly did not complete")
            }
            assertTrue(!reassembler.isAssembling, "$name: slot must be free after completion")
        }
    }

    @Test
    fun `malformed fragments are rejected with the expected code`() {
        for (item in doc.arr("negative_fragments").items) {
            val case = item.asObject()
            val name = case.text("name")
            val expected = ErrorCode.valueOf(case.text("reason"))
            val reassembler = Reassembler(Channel.NORMAL, 23)
            try {
                reassembler.onFragment(case.text("fragment_hex").hexToBytes(), nowMs = 0)
                fail("$name: expected ${expected.name} but fragment was accepted")
            } catch (e: ProtocolException) {
                assertEquals(expected, e.code, "$name: rejection code")
            }
        }
    }

    @Test
    fun `assembler state machine follows the shared sequences`() {
        for (item in doc.arr("assembler_sequences").items) {
            val scenario = item.asObject()
            val name = scenario.text("name")
            val mtu = scenario.integer("mtu")
            val transferId = scenario.integer("transfer_id")
            val payload = scenario.text("payload_hex").hexToBytes()
            val fragments = Fragmenter.encode(payload, transferId, WireFormat.FLAG_PROTECTED, mtu)
            val reassembler = Reassembler(Channel.NORMAL, mtu)
            for (step in scenario.arr("steps").items) {
                val spec = step.asObject()
                val stepTransferId = spec.get("transfer_id")?.asLong()?.toInt() ?: transferId
                val index = spec.integer("fragment_index")
                val expected = spec.text("expect")
                var fragment = fragments[index].copyOf()
                putU16le(fragment, 2, stepTransferId)
                val truncate = spec.get("truncate_content_to")?.asLong()?.toInt()
                if (truncate != null) {
                    fragment = fragment.copyOf(WireFormat.FRAGMENT_HEADER_LEN + truncate)
                }
                if (expected == "BAD_FRAME") {
                    try {
                        reassembler.onFragment(fragment, nowMs = 0)
                        fail("$name: step $index should have been rejected")
                    } catch (e: ProtocolException) {
                        assertEquals(ErrorCode.BAD_FRAME, e.code, "$name: step $index")
                    }
                } else {
                    val result = reassembler.onFragment(fragment, nowMs = 0)
                    if (expected == "COMPLETE") {
                        assertTrue(result is ReassemblyResult.Complete, "$name: step $index should complete")
                    } else {
                        assertTrue(result is ReassemblyResult.Incomplete, "$name: step $index should be incomplete")
                    }
                }
            }
        }
    }

    @Test
    fun `assembly slot times out and is cleared`() {
        val payload = ByteArray(30) { it.toByte() }
        val fragments = Fragmenter.encode(payload, 7, WireFormat.FLAG_PROTECTED, 23)
        val reassembler = Reassembler(Channel.NORMAL, 23)
        reassembler.onFragment(fragments[0], nowMs = 1000)
        assertEquals(false, reassembler.onTick(1000 + 1999))
        assertEquals(true, reassembler.onTick(1000 + 2000), "normal slot times out after 2 s")
        assertEquals(false, reassembler.isAssembling)
        // A fresh transfer may start after the timeout.
        val first = reassembler.onFragment(fragments[0], nowMs = 5000)
        assertTrue(first is ReassemblyResult.Incomplete)
    }

    @Test
    fun `control slot uses the shorter timeout`() {
        val payload = ByteArray(20) { it.toByte() }
        val fragments = Fragmenter.encode(payload, 3, WireFormat.FLAG_PROTECTED, 23)
        val reassembler = Reassembler(Channel.CONTROL, 23)
        reassembler.onFragment(fragments[0], 0)
        assertEquals(false, reassembler.onTick(999))
        assertEquals(true, reassembler.onTick(1000))
    }

    @Test
    fun `mtu 23 fits exactly twelve bytes per fragment`() {
        assertEquals(12, WireFormat.fragmentContentCapacity(23))
        val fragments = Fragmenter.encode(ByteArray(12) { 0x41 }, 1, 1, 23)
        assertEquals(1, fragments.size)
        assertEquals(20, fragments[0].size)
        val two = Fragmenter.encode(ByteArray(13) { 0x41 }, 1, 1, 23)
        assertEquals(2, two.size)
        assertEquals(20, two[0].size)
        assertEquals(9, two[1].size)
    }

    @Test
    fun `too many fragments is a hard error rather than silent truncation`() {
        val capacity = WireFormat.fragmentContentCapacity(23)
        try {
            Fragmenter.encode(ByteArray(capacity * WireFormat.MAX_FRAGMENT_COUNT + 1), 1, 1, 23)
            fail("expected TOO_LARGE")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.TOO_LARGE, e.code)
        }
    }

    @Test
    fun `mtu below the header is refused`() {
        for (mtu in 0..10) {
            try {
                Fragmenter.encode(ByteArray(1), 1, 1, mtu)
                fail("mtu $mtu must be rejected")
            } catch (e: ProtocolException) {
                assertEquals(ErrorCode.BAD_FRAME, e.code)
            }
        }
    }
}
