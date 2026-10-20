package com.esp32phone.agent.control

import com.esp32phone.agent.protocol.ErrorCode
import com.esp32phone.agent.protocol.ProtocolException
import com.esp32phone.agent.protocol.StrictJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ActionsTest {

    private val mapper = LinearTestMapper()

    private fun parse(json: String): ModelDecision = ModelDecision.parse(json, mapper)

    @Test
    fun `tap is mapped from model coordinates to hid counts`() {
        val decision = parse("""{"action":{"op":"tap","x":0,"y":1000,"duration_ms":80},"reason":"r","expected":"e"}""")
        val act = decision as ModelDecision.Act
        val tap = act.action as HidAction.Tap
        assertEquals(0, tap.xCounts)
        assertEquals(4096, tap.yCounts)
        assertEquals(80, tap.durationMs)
    }

    @Test
    fun `done decision requires explicit true`() {
        assertTrue(parse("""{"done":true,"reason":"r","expected":"e"}""") is ModelDecision.Done)
        try {
            parse("""{"done":false,"reason":"r","expected":"e"}""")
            fail("done:false must be rejected")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.INVALID_ACTION, e.code)
        }
    }

    @Test
    fun `unknown actions are rejected`() {
        for (op in listOf("swipe_path", "circle", "press", "shell", "adb", "home")) {
            try {
                parse("""{"action":{"op":"$op"},"reason":"r","expected":"e"}""")
                fail("action $op must be rejected")
            } catch (e: ProtocolException) {
                assertEquals(ErrorCode.INVALID_ACTION, e.code, "action $op")
            }
        }
    }

    @Test
    fun `extra fields cannot smuggle device or session control`() {
        for (json in listOf(
            """{"action":{"op":"tap","x":1,"y":2,"duration_ms":80,"session_id":9},"reason":"r","expected":"e"}""",
            """{"action":{"op":"tap","x":1,"y":2,"duration_ms":80},"reason":"r","expected":"e","command_id":3}""",
            """{"action":{"op":"tap","x":1,"y":2,"duration_ms":80,"counts":9999},"reason":"r","expected":"e"}""",
        )) {
            try {
                parse(json)
                fail("must reject: $json")
            } catch (e: ProtocolException) {
                assertTrue(
                    e.code == ErrorCode.INVALID_ACTION || e.code == ErrorCode.INVALID_JSON,
                    "unexpected code ${e.code}",
                )
            }
        }
    }

    @Test
    fun `coordinates outside 0 to 1000 are rejected`() {
        for (value in listOf(-1, 1001, 5000)) {
            try {
                parse("""{"action":{"op":"tap","x":$value,"y":0,"duration_ms":80},"reason":"r","expected":"e"}""")
                fail("x=$value must be rejected")
            } catch (e: ProtocolException) {
                assertTrue(e.code == ErrorCode.ARG_OUT_OF_RANGE || e.code == ErrorCode.INVALID_JSON)
            }
        }
    }

    @Test
    fun `duration bounds are enforced per action`() {
        val cases = listOf(
            """{"action":{"op":"tap","x":1,"y":1,"duration_ms":29},"reason":"r","expected":"e"}""" to false,
            """{"action":{"op":"tap","x":1,"y":1,"duration_ms":30},"reason":"r","expected":"e"}""" to true,
            """{"action":{"op":"tap","x":1,"y":1,"duration_ms":1001},"reason":"r","expected":"e"}""" to false,
            """{"action":{"op":"swipe","x0":0,"y0":0,"x1":100,"y1":100,"duration_ms":49},"reason":"r","expected":"e"}""" to false,
            """{"action":{"op":"swipe","x0":0,"y0":0,"x1":100,"y1":100,"duration_ms":50},"reason":"r","expected":"e"}""" to true,
            """{"action":{"op":"long_press","x":1,"y":1,"duration_ms":399},"reason":"r","expected":"e"}""" to false,
            """{"action":{"op":"long_press","x":1,"y":1,"duration_ms":400},"reason":"r","expected":"e"}""" to true,
            """{"action":{"op":"long_press","x":1,"y":1,"duration_ms":3001},"reason":"r","expected":"e"}""" to false,
        )
        for ((json, shouldPass) in cases) {
            try {
                parse(json)
                if (!shouldPass) fail("should have been rejected: $json")
            } catch (e: ProtocolException) {
                if (shouldPass) fail("should have been accepted: $json (${e.message})")
                assertEquals(ErrorCode.ARG_OUT_OF_RANGE, e.code, json)
            }
        }
    }

    @Test
    fun `swipe with identical endpoints is rejected`() {
        try {
            parse("""{"action":{"op":"swipe","x0":10,"y0":10,"x1":10,"y1":10,"duration_ms":100},"reason":"r","expected":"e"}""")
            fail("degenerate swipe must be rejected")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.ARG_OUT_OF_RANGE, e.code)
        }
    }

    @Test
    fun `scroll range and non-zero are enforced`() {
        assertTrue(parse("""{"action":{"op":"scroll","amount":-10},"reason":"r","expected":"e"}""") is ModelDecision.Act)
        assertTrue(parse("""{"action":{"op":"scroll","amount":10},"reason":"r","expected":"e"}""") is ModelDecision.Act)
        for (amount in listOf(0, 11, -11)) {
            try {
                parse("""{"action":{"op":"scroll","amount":$amount},"reason":"r","expected":"e"}""")
                fail("scroll $amount must be rejected")
            } catch (e: ProtocolException) {
                assertEquals(ErrorCode.ARG_OUT_OF_RANGE, e.code)
            }
        }
    }

    @Test
    fun `type_ascii only accepts short printable ascii`() {
        assertTrue(parse("""{"action":{"op":"type_ascii","text":"Hello, world!"},"reason":"r","expected":"e"}""") is ModelDecision.Act)
        val tooLong = "a".repeat(129)
        try {
            parse("""{"action":{"op":"type_ascii","text":"$tooLong"},"reason":"r","expected":"e"}""")
            fail("129 chars must be rejected")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.ARG_OUT_OF_RANGE, e.code)
        }
        for (text in listOf("中文", "tab\\tinside", "line\\nbreak", "\\u0000nul")) {
            try {
                parse("""{"action":{"op":"type_ascii","text":"$text"},"reason":"r","expected":"e"}""")
                fail("text \"$text\" must be rejected")
            } catch (e: ProtocolException) {
                assertEquals(ErrorCode.ARG_OUT_OF_RANGE, e.code, "text \"$text\"")
            }
        }
    }

    @Test
    fun `key names are a closed set and case insensitive`() {
        assertTrue(parse("""{"action":{"op":"key","key":"ENTER"},"reason":"r","expected":"e"}""") is ModelDecision.Act)
        assertTrue(parse("""{"action":{"op":"key","key":"arrow_left"},"reason":"r","expected":"e"}""") is ModelDecision.Act)
        for (key in listOf("home", "recents", "volume_up", "power", "f13")) {
            try {
                parse("""{"action":{"op":"key","key":"$key"},"reason":"r","expected":"e"}""")
                fail("key $key must be rejected")
            } catch (e: ProtocolException) {
                assertEquals(ErrorCode.INVALID_ACTION, e.code)
            }
        }
    }

    @Test
    fun `uncalibrated mapper refuses to produce counts`() {
        try {
            ModelDecision.parse(
                """{"action":{"op":"tap","x":500,"y":500,"duration_ms":80},"reason":"r","expected":"e"}""",
                UncalibratedMapper,
            )
            fail("uncalibrated mapping must be refused")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.CALIBRATION_REQUIRED, e.code)
        }
    }

    @Test
    fun `wire json is canonical and stable`() {
        val action = HidAction.Tap(2048, 1024, 80)
        assertEquals("""{"op":"tap","x_counts":2048,"y_counts":1024,"duration_ms":80}""", action.toWireJson())
        assertEquals(
            """{"op":"type_ascii","text":"a\"b\\c"}""",
            HidAction.TypeAscii("a\"b\\c").toWireJson(),
        )
        // Canonical form is used for de-duplication digests, so it must be byte-stable.
        assertEquals(action.toWireJson(), HidAction.Tap(2048, 1024, 80).toWireJson())
    }

    @Test
    fun `strict json rejects duplicate keys and trailing data`() {
        for (bad in listOf(
            """{"a":1,"a":2}""",
            """{"a":1} extra""",
            """{"a":NaN}""",
            """{a:1}""",
            """[]""",
            """{"a":01}""",
        )) {
            try {
                StrictJson.parseObject(bad)
                fail("must reject: $bad")
            } catch (e: ProtocolException) {
                assertTrue(
                    e.code == ErrorCode.INVALID_JSON || e.code == ErrorCode.ARG_OUT_OF_RANGE,
                    "unexpected code ${e.code} for $bad",
                )
            }
        }
        StrictJson.parseObject("""{"a":[1,2,{"b":null}],"c":true}""")
    }

    @Test
    fun `invalid utf8 payload is rejected`() {
        try {
            StrictJson.parseObject(byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D))
            fail("invalid UTF-8 must be rejected")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.INVALID_JSON, e.code)
        }
    }
}
