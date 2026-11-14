package com.myfriend.app.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 「导出全部对话」的分批写入（审计 P1-4）。
 *
 * 纯逻辑测试：不碰 Room、不碰磁盘。构造出来的文本必须是**合法 JSON**、
 * 字段与 `ExportBundle` 一致、批次按阈值切开、顺序不变、写失败要抛出来
 * （不能出现「一半文件看着像成功」）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportStreamingTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `没有会话时也是合法 JSON`() = runTest {
        val chunks = mutableListOf<String>()
        val stream = ExportJsonStream(emit = { chunks += it })
        stream.start(schemaVersion = 1, exportedAt = 123L)
        stream.finish()

        val root = json.parseToJsonElement(chunks.joinToString("")).jsonObject
        assertEquals(1, root.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals(123L, root.getValue("exportedAt").jsonPrimitive.content.toLong())
        assertEquals(0, (root.getValue("sessions") as JsonArray).size)
    }

    @Test
    fun `多条会话按顺序拼成同一个数组`() = runTest {
        val chunks = mutableListOf<String>()
        val stream = ExportJsonStream(batchChars = 1, emit = { chunks += it })
        stream.start(schemaVersion = 1, exportedAt = 1L)
        listOf("""{"id":"a"}""", """{"id":"b"}""", """{"id":"c"}""").forEach {
            stream.writeSession(it)
        }
        stream.finish()

        val sessions = json.parseToJsonElement(chunks.joinToString(""))
            .jsonObject.getValue("sessions") as JsonArray
        assertEquals(
            listOf("a", "b", "c"),
            sessions.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        )
        assertTrue("批次应该真的被切开（batchChars=1）", chunks.size >= 3)
    }

    @Test
    fun `小批量时不主动切分 最后一次性写出`() = runTest {
        val chunks = mutableListOf<String>()
        val stream = ExportJsonStream(batchChars = 1024 * 1024, emit = { chunks += it })
        stream.start(schemaVersion = 1, exportedAt = 1L)
        stream.writeSession("""{"id":"a"}""")
        stream.writeSession("""{"id":"b"}""")
        stream.finish()

        assertEquals("没有超过阈值，只该有一次落盘", 1, chunks.size)
        val sessions = json.parseToJsonElement(chunks.single()).jsonObject.getValue("sessions") as JsonArray
        assertEquals(2, sessions.size)
    }

    @Test
    fun `单条会话比批大小还大时 仍然只写一次完整 JSON`() = runTest {
        val chunks = mutableListOf<String>()
        val stream = ExportJsonStream(batchChars = 16, emit = { chunks += it })
        stream.start(schemaVersion = 1, exportedAt = 1L)
        val big = """{"id":"${"x".repeat(500)}"}"""
        stream.writeSession(big)
        stream.finish()

        val sessions = json.parseToJsonElement(chunks.joinToString("")).jsonObject.getValue("sessions") as JsonArray
        assertEquals(1, sessions.size)
        assertTrue("大条目本身不能被截断", chunks.joinToString("").contains(big))
    }

    @Test
    fun `写文件失败会让整次导出失败`() = runTest {
        var calls = 0
        val stream = ExportJsonStream(batchChars = 1) {
            calls++
            if (calls == 2) throw IOException("disk full")
        }
        stream.start(schemaVersion = 1, exportedAt = 1L)
        stream.writeSession("""{"id":"a"}""")
        val error = runCatching {
            // 第二次 flush 会抛：调用方必须把整次导出当失败，而不是「已经写了一半」。
            stream.writeSession("""{"id":"b"}""")
            stream.finish()
        }.exceptionOrNull()
        assertTrue("失败必须向上传播", error is IOException)
    }

    @Test
    fun `输出字段顺序与 ExportBundle 一致`() = runTest {
        val chunks = mutableListOf<String>()
        val stream = ExportJsonStream(emit = { chunks += it })
        stream.start(schemaVersion = 1, exportedAt = 5L)
        stream.writeSession("""{"id":"a"}""")
        stream.finish()
        val text = chunks.joinToString("")
        // 兼容旧读法：顶层是 schemaVersion / exportedAt / sessions 三个字段。
        assertTrue(text.startsWith("""{"schemaVersion":1,"exportedAt":5,"sessions":["""))
        assertTrue(text.endsWith("]}"))
    }
}
