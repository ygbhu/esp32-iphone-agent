package com.esp32phone.agent.protocol

import com.esp32phone.agent.Vectors
import com.esp32phone.agent.arr
import com.esp32phone.agent.integer
import com.esp32phone.agent.long
import com.esp32phone.agent.obj
import com.esp32phone.agent.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class HandshakeVectorsTest {

    private val doc = Vectors.load("handshake.json")

    @Test
    fun `transcript and proofs match the shared vectors`() {
        assertTrue(doc.arr("cases").items.isNotEmpty())
        for (item in doc.arr("cases").items) {
            val case = item.asObject()
            val name = case.text("name")
            val secret = case.text("secret_hex").hexToBytes()
            val clientNonce = case.text("client_nonce_hex").hexToBytes()
            val boardNonce = case.text("board_nonce_hex").hexToBytes()
            val features = case.long("features")

            val transcript = Handshake.computeTranscript(case.text("board_id"), clientNonce, boardNonce, features)
            assertEquals(case.text("transcript_hex"), transcript.hex(), "$name: transcript")
            assertEquals(
                case.text("transcript_sha256_hex"),
                Crypto.sha256(transcript).hex(),
                "$name: transcript digest",
            )

            val boardProof = Handshake.boardProof(secret, transcript)
            assertEquals(case.text("board_proof_hex"), boardProof.hex(), "$name: board proof")

            val appProof = Handshake.appProof(secret, transcript, boardProof)
            assertEquals(case.text("app_proof_hex"), appProof.hex(), "$name: app proof")

            // The app-side helper must produce exactly the same app proof for a valid board proof.
            assertEquals(
                appProof.hex(),
                Handshake.verifyBoardAndProve(secret, transcript, boardProof).hex(),
                "$name: verify+prove",
            )
        }
    }

    @Test
    fun `session derivation matches the shared vectors`() {
        for (item in doc.arr("cases").items) {
            val case = item.asObject()
            val name = case.text("name")
            val secret = case.text("secret_hex").hexToBytes()
            val transcript = case.text("transcript_hex").hexToBytes()
            val keys = Handshake.deriveSession(secret, transcript)
            assertEquals(case.text("session_id_hex"), keys.sessionId.hex(), "$name: session id")

            val expectedKeys = case.obj("keys")
            val expectedPrefixes = case.obj("nonce_prefixes")
            for (slot in KeySlot.entries) {
                val vectorName = slot.name.lowercase()
                assertEquals(
                    expectedKeys.text(vectorName),
                    keys.slot(slot).key.hex(),
                    "$name: key for $slot",
                )
                assertEquals(
                    expectedPrefixes.text(vectorName),
                    keys.slot(slot).noncePrefix.hex(),
                    "$name: nonce prefix for $slot",
                )
            }
        }
    }

    @Test
    fun `all four channels derive distinct key material`() {
        val case = doc.arr("cases").items.first().asObject()
        val keys = Handshake.deriveSession(
            case.text("secret_hex").hexToBytes(),
            case.text("transcript_hex").hexToBytes(),
        )
        val hexes = KeySlot.entries.map { keys.slot(it).key.hex() }
        assertEquals(hexes.size, hexes.toSet().size, "every slot must have its own key")
    }

    @Test
    fun `negative proofs are rejected`() {
        val case = doc.arr("cases").items.first().asObject()
        val secret = case.text("secret_hex").hexToBytes()
        val transcript = case.text("transcript_hex").hexToBytes()

        for (item in doc.arr("negative_proofs").items) {
            val negative = item.asObject()
            val name = negative.text("name")
            val expectation = negative.text("expect")
            assertEquals("AUTH_FAILED", expectation)
            when (name) {
                "board_proof_used_as_app_proof" -> {
                    val expectedAppProof = Handshake.appProof(secret, transcript, Handshake.boardProof(secret, transcript))
                    assertNotEquals(
                        negative.text("app_proof_hex"),
                        expectedAppProof.hex(),
                        "a board proof must not be accepted as an app proof",
                    )
                }
                else -> {
                    try {
                        Handshake.verifyBoardAndProve(secret, transcript, negative.text("board_proof_hex").hexToBytes())
                        fail("$name: must be rejected")
                    } catch (e: ProtocolException) {
                        assertEquals(ErrorCode.AUTH_FAILED, e.code, "$name")
                    }
                }
            }
        }
    }

    @Test
    fun `app proof is bound to the board proof`() {
        val case = doc.arr("cases").items.first().asObject()
        val secret = case.text("secret_hex").hexToBytes()
        val transcript = case.text("transcript_hex").hexToBytes()
        val genuine = Handshake.boardProof(secret, transcript)
        val forged = genuine.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNotEquals(
            Handshake.appProof(secret, transcript, genuine).hex(),
            Handshake.appProof(secret, transcript, forged).hex(),
            "changing the board proof must change the app proof",
        )
    }

    @Test
    fun `board id bounds are enforced`() {
        val secret = ByteArray(32)
        val nonceA = ByteArray(32) { 1 }
        val nonceB = ByteArray(32) { 2 }
        try {
            Handshake.computeTranscript("", nonceA, nonceB, 0L)
            fail("empty board id must be rejected")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.AUTH_FAILED, e.code)
        }
        try {
            Handshake.computeTranscript("x".repeat(33), nonceA, nonceB, 0L)
            fail("oversized board id must be rejected")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.AUTH_FAILED, e.code)
        }
        Handshake.computeTranscript("x".repeat(32), nonceA, nonceB, 0L)
        Handshake.computeTranscript("esp32phone-1bc5", nonceA, nonceB, 0L)
        // keep the secret referenced so the test documents that it is unused for bounds checks
        assertEquals(32, secret.size)
    }

    @Test
    fun `transcript is order independent only through its fixed layout`() {
        val case = doc.arr("cases").items.first().asObject()
        val clientNonce = case.text("client_nonce_hex").hexToBytes()
        val boardNonce = case.text("board_nonce_hex").hexToBytes()
        val a = Handshake.computeTranscript("esp32phone-1bc5", clientNonce, boardNonce, 15L)
        val swapped = Handshake.computeTranscript("esp32phone-1bc5", boardNonce, clientNonce, 15L)
        assertNotEquals(a.hex(), swapped.hex(), "swapping the nonces must change the transcript")
        val otherFeatures = Handshake.computeTranscript("esp32phone-1bc5", clientNonce, boardNonce, 14L)
        assertNotEquals(a.hex(), otherFeatures.hex(), "features are part of the transcript")
    }
}
