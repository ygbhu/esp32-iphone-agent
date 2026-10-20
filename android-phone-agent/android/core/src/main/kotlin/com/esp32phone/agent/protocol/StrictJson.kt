package com.esp32phone.agent.protocol

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Parser strict enough to be used on protocol payloads.
 *
 * Rejects: invalid UTF-8, trailing characters, duplicate object keys, non-object roots at
 * the API level, nesting deeper than [MAX_DEPTH], `NaN`/`Infinity`, leading `+`, leading
 * zeros, and raw control characters inside strings. Anything the parser refuses is
 * [ErrorCode.INVALID_JSON] and must never be executed.
 */
public object StrictJson {

    public const val MAX_DEPTH: Int = 8

    public fun parseObject(bytes: ByteArray): JsonObject {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw ProtocolException(ErrorCode.INVALID_JSON, "payload is not valid UTF-8", e)
        }
        return parseObject(text)
    }

    public fun parseObject(text: String): JsonObject {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.parseValue(0)
        parser.skipWhitespace()
        if (!parser.atEnd) {
            throw ProtocolException(ErrorCode.INVALID_JSON, "trailing data at offset ${parser.position}")
        }
        return value as? JsonObject
            ?: throw ProtocolException(ErrorCode.INVALID_JSON, "root value must be a JSON object")
    }

    private class Parser(private val text: String) {
        var position: Int = 0
            private set

        val atEnd: Boolean get() = position >= text.length

        fun skipWhitespace() {
            while (position < text.length) {
                when (text[position]) {
                    ' ', '\t', '\n', '\r' -> position += 1
                    else -> return
                }
            }
        }

        private fun fail(message: String): Nothing =
            throw ProtocolException(ErrorCode.INVALID_JSON, "$message at offset $position")

        fun parseValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("nesting exceeds $MAX_DEPTH")
            if (atEnd) fail("unexpected end of input")
            return when (val c = text[position]) {
                '{' -> parseObjectValue(depth)
                '[' -> parseArray(depth)
                '"' -> JsonString(parseString())
                't' -> literal("true", JsonBool(true))
                'f' -> literal("false", JsonBool(false))
                'n' -> literal("null", JsonNull)
                else -> if (c == '-' || c in '0'..'9') parseNumber() else fail("unexpected character '$c'")
            }
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, position)) fail("expected '$word'")
            position += word.length
            return value
        }

        private fun parseObjectValue(depth: Int): JsonObject {
            position += 1 // '{'
            val members = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (!atEnd && text[position] == '}') {
                position += 1
                return JsonObject(members)
            }
            while (true) {
                skipWhitespace()
                if (atEnd || text[position] != '"') fail("expected object key")
                val key = parseString()
                if (members.containsKey(key)) fail("duplicate object key \"$key\"")
                skipWhitespace()
                if (atEnd || text[position] != ':') fail("expected ':' after key")
                position += 1
                skipWhitespace()
                members[key] = parseValue(depth + 1)
                skipWhitespace()
                if (atEnd) fail("unterminated object")
                when (text[position]) {
                    ',' -> position += 1
                    '}' -> { position += 1; return JsonObject(members) }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(depth: Int): JsonArray {
            position += 1 // '['
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (!atEnd && text[position] == ']') {
                position += 1
                return JsonArray(items)
            }
            while (true) {
                skipWhitespace()
                items.add(parseValue(depth + 1))
                skipWhitespace()
                if (atEnd) fail("unterminated array")
                when (text[position]) {
                    ',' -> position += 1
                    ']' -> { position += 1; return JsonArray(items) }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            position += 1 // opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd) fail("unterminated string")
                val c = text[position]
                when {
                    c == '"' -> { position += 1; return out.toString() }
                    c == '\\' -> {
                        position += 1
                        if (atEnd) fail("unterminated escape")
                        when (val esc = text[position]) {
                            '"' -> { out.append('"'); position += 1 }
                            '\\' -> { out.append('\\'); position += 1 }
                            '/' -> { out.append('/'); position += 1 }
                            'b' -> { out.append('\b'); position += 1 }
                            'f' -> { out.append('\u000C'); position += 1 }
                            'n' -> { out.append('\n'); position += 1 }
                            'r' -> { out.append('\r'); position += 1 }
                            't' -> { out.append('\t'); position += 1 }
                            'u' -> {
                                position += 1
                                if (position + 4 > text.length) fail("truncated \\u escape")
                                val code = readHex4()
                                if (code in 0xD800..0xDBFF) {
                                    // high surrogate: must be followed by a low surrogate escape
                                    if (position + 1 >= text.length || text[position] != '\\' ||
                                        text[position + 1] != 'u'
                                    ) {
                                        fail("unpaired high surrogate escape (no low surrogate follows)")
                                    }
                                    position += 2
                                    if (position + 4 > text.length) fail("truncated low surrogate escape")
                                    val low = readHex4()
                                    if (low !in 0xDC00..0xDFFF) {
                                        fail("high surrogate not followed by a low surrogate")
                                    }
                                    val codePoint = 0x10000 + ((code - 0xD800) shl 10) + (low - 0xDC00)
                                    out.appendCodePoint(codePoint)
                                } else if (code in 0xDC00..0xDFFF) {
                                    fail("lone low surrogate escape")
                                } else {
                                    out.append(code.toChar())
                                }
                            }
                            else -> fail("bad escape '\\$esc'")
                        }
                    }
                    c.code < 0x20 -> fail("raw control character 0x${c.code.toString(16)} in string")
                    //
                    // Raw UTF-16 surrogates in the *String* entry point must follow the same
                    // rule as UTF-8 bytes: a high surrogate needs its low partner, a lone low
                    // surrogate is invalid. Without this the JVM and the C++ parser could
                    // disagree about the same logical text.
                    c.code in 0xD800..0xDBFF -> {
                        val lowIndex = position + 1
                        if (lowIndex >= text.length) fail("unpaired high surrogate at offset $position")
                        val low = text[lowIndex]
                        if (low.code !in 0xDC00..0xDFFF) fail("high surrogate not followed by a low surrogate")
                        out.append(c)
                        out.append(low)
                        position += 2
                    }
                    c.code in 0xDC00..0xDFFF -> fail("lone low surrogate at offset $position")
                    else -> { out.append(c); position += 1 }
                }
            }
        }

        /** Strictly reads 4 hex digits. Rejects signs, whitespace and any non-hex character. */
        private fun readHex4(): Int {
            if (position + 4 > text.length) fail("truncated \\u escape")
            var value = 0
            for (i in 0 until 4) {
                val c = text[position + i]
                val digit = when (c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> c - 'a' + 10
                    in 'A'..'F' -> c - 'A' + 10
                    else -> fail("non-hex character '$c' in \\u escape")
                }
                value = (value shl 4) or digit
            }
            position += 4
            return value
        }

        private fun parseNumber(): JsonNumber {
            val start = position
            if (!atEnd && text[position] == '-') position += 1
            if (atEnd) fail("truncated number")
            if (text[position] == '0') {
                position += 1
                if (!atEnd && text[position] in '0'..'9') fail("leading zero in number")
            } else if (text[position] in '1'..'9') {
                while (!atEnd && text[position] in '0'..'9') position += 1
            } else {
                fail("expected digit")
            }
            if (!atEnd && text[position] == '.') {
                position += 1
                if (atEnd || text[position] !in '0'..'9') fail("fraction requires at least one digit")
                while (!atEnd && text[position] in '0'..'9') position += 1
            }
            if (!atEnd && (text[position] == 'e' || text[position] == 'E')) {
                position += 1
                if (!atEnd && (text[position] == '+' || text[position] == '-')) position += 1
                if (atEnd || text[position] !in '0'..'9') fail("exponent requires at least one digit")
                while (!atEnd && text[position] in '0'..'9') position += 1
            }
            return JsonNumber(text.substring(start, position))
        }
    }
}

public sealed class JsonValue {
    public fun asObject(): JsonObject =
        this as? JsonObject ?: throw ProtocolException(ErrorCode.INVALID_JSON, "expected object")

    public fun asString(): String =
        (this as? JsonString)?.value ?: throw ProtocolException(ErrorCode.INVALID_JSON, "expected string")

    public fun asBoolean(): Boolean =
        (this as? JsonBool)?.value ?: throw ProtocolException(ErrorCode.INVALID_JSON, "expected boolean")

    public fun asLong(min: Long = Long.MIN_VALUE, max: Long = Long.MAX_VALUE): Long {
        val number = this as? JsonNumber ?: throw ProtocolException(ErrorCode.INVALID_JSON, "expected number")
        val value = number.raw.toLongOrNull()
            ?: throw ProtocolException(ErrorCode.INVALID_JSON, "number \"${number.raw}\" is not an integer")
        if (value < min || value > max) {
            throw ProtocolException(
                ErrorCode.ARG_OUT_OF_RANGE,
                "number $value outside $min..$max",
            )
        }
        return value
    }
}

public data object JsonNull : JsonValue()
public class JsonBool(public val value: Boolean) : JsonValue()
public class JsonNumber(public val raw: String) : JsonValue()
public class JsonString(public val value: String) : JsonValue()
public class JsonArray(public val items: List<JsonValue>) : JsonValue()

public class JsonObject(private val members: Map<String, JsonValue>) : JsonValue() {
    public val keys: Set<String> get() = members.keys

    public fun get(key: String): JsonValue? = members[key]

    public fun require(key: String): JsonValue =
        members[key] ?: throw ProtocolException(ErrorCode.INVALID_JSON, "missing required field \"$key\"")

    public fun optString(key: String): String? = members[key]?.asString()

    public fun rejectUnknown(allowed: Set<String>) {
        val extra = members.keys - allowed
        if (extra.isNotEmpty()) {
            throw ProtocolException(ErrorCode.INVALID_ACTION, "unexpected field(s): ${extra.sorted()}")
        }
    }
}
