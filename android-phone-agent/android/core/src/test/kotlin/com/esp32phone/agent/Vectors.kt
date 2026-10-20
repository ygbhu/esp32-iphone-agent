package com.esp32phone.agent

import com.esp32phone.agent.protocol.JsonArray
import com.esp32phone.agent.protocol.JsonObject
import com.esp32phone.agent.protocol.JsonValue
import com.esp32phone.agent.protocol.StrictJson
import java.io.File

/** Loads the shared, cross-language wire-format vectors written by protocol/tools/gen_vectors.py. */
object Vectors {
    private val dir: File by lazy {
        val configured = System.getProperty("vectors.dir")
            ?: error("vectors.dir system property is not set")
        File(configured).also {
            check(it.isDirectory) { "vector directory not found: $it" }
        }
    }

    fun load(name: String): JsonObject = StrictJson.parseObject(File(dir, name).readText(Charsets.UTF_8))
}

fun JsonObject.obj(key: String): JsonObject = require(key).asObject()
fun JsonObject.arr(key: String): JsonArray = require(key) as? JsonArray
    ?: error("field \"$key\" is not an array")
fun JsonObject.text(key: String): String = require(key).asString()
fun JsonObject.integer(key: String): Int = require(key).asLong().toInt()
fun JsonObject.long(key: String): Long = require(key).asLong()
fun JsonValue.intRange(min: Long, max: Long): Int = asLong(min, max).toInt()
