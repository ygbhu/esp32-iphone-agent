package com.esp32phone.agent.protocol

import com.esp32phone.agent.Vectors
import com.esp32phone.agent.integer
import com.esp32phone.agent.obj
import com.esp32phone.agent.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins the Kotlin constants to the shared schema so the ESP32 side and the app cannot drift.
 */
class SchemaVectorsTest {

    private val schema = Vectors.load("schema.json")

    @Test
    fun `wire version and lengths match`() {
        assertEquals(schema.integer("wire_version"), WireFormat.VERSION)
        assertEquals(schema.integer("fragment_header_len"), WireFormat.FRAGMENT_HEADER_LEN)
        assertEquals(schema.integer("envelope_header_len"), WireFormat.ENVELOPE_HEADER_LEN)
        assertEquals(schema.integer("gcm_tag_len"), WireFormat.GCM_TAG_LEN)
    }

    @Test
    fun `message limits match`() {
        val limits = schema.obj("limits")
        assertEquals(limits.integer("normal"), WireFormat.LIMIT_NORMAL)
        assertEquals(limits.integer("handshake"), WireFormat.LIMIT_HANDSHAKE)
        assertEquals(limits.integer("control"), WireFormat.LIMIT_CONTROL)
    }

    @Test
    fun `uuid allocation matches`() {
        val uuid = schema.obj("uuid")
        assertEquals(uuid.text("service"), WireFormat.SERVICE_UUID)
        assertEquals(uuid.text("info"), WireFormat.INFO_CHARACTERISTIC_UUID)
        assertEquals(uuid.text("command"), WireFormat.COMMAND_CHARACTERISTIC_UUID)
        assertEquals(uuid.text("event"), WireFormat.EVENT_CHARACTERISTIC_UUID)
        assertEquals(uuid.text("control"), WireFormat.CONTROL_CHARACTERISTIC_UUID)
        // The characteristic UUIDs must differ from the service only in the last four hex digits.
        val suffix = WireFormat.SERVICE_UUID.take(32)
        for (characteristic in listOf(
            WireFormat.INFO_CHARACTERISTIC_UUID,
            WireFormat.COMMAND_CHARACTERISTIC_UUID,
            WireFormat.EVENT_CHARACTERISTIC_UUID,
            WireFormat.CONTROL_CHARACTERISTIC_UUID,
        )) {
            assertEquals(suffix, characteristic.take(32), "$characteristic must share the service base")
        }
    }

    @Test
    fun `message type ids match`() {
        val types = schema.obj("message_types")
        for (key in types.keys) {
            val expected = types.require(key).asLong().toInt()
            val actual = MessageType.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
            assertNotNull(actual, "Kotlin is missing message type \"$key\"")
            assertEquals(expected, actual.id, "message type $key")
            assertEquals(actual, MessageType.fromId(expected), "reverse lookup for $key")
        }
        assertEquals(types.keys.size, MessageType.entries.size, "no extra Kotlin message types")
    }

    @Test
    fun `error codes match`() {
        val codes = schema.obj("error_codes")
        for (key in codes.keys) {
            val expected = codes.require(key).asLong().toInt()
            val actual = ErrorCode.entries.firstOrNull { it.name == key }
            assertNotNull(actual, "Kotlin is missing error code \"$key\"")
            assertEquals(expected, actual.id, "error code $key")
            assertEquals(actual, ErrorCode.fromId(expected), "reverse lookup for $key")
        }
        assertEquals(codes.keys.size, ErrorCode.entries.size, "no extra Kotlin error codes")
    }

    @Test
    fun `hkdf info strings match`() {
        val hkdf = schema.obj("hkdf")
        assertEquals(hkdf.text("session_info"), Handshake.INFO_SESSION)
        val keyInfo = hkdf.obj("key_info")
        val nonceInfo = hkdf.obj("nonce_info")
        for (slot in KeySlot.entries) {
            val name = slot.name.lowercase()
            assertEquals(keyInfo.text(name), slot.info, "key info for $slot")
            assertEquals(nonceInfo.text(name), slot.nonceInfo, "nonce info for $slot")
        }
        val labels = schema.obj("proof_labels")
        assertEquals(labels.text("board"), Handshake.LABEL_BOARD)
        assertEquals(labels.text("app"), Handshake.LABEL_APP)
        assertEquals(schema.text("transcript_magic_hex"), Handshake.TRANSCRIPT_MAGIC.toByteArray().hex())
    }
}
