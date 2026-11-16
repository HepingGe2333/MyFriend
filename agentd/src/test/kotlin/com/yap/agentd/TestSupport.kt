package com.yap.agentd

import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/**
 * 测试共用的小工具（只在 `src/test` 里；不进发行包）。
 *
 * - 临时数据目录与递归删除：存储测试每个用例都从空目录开始；
 * - [FakeClock]：存储层的时间戳是注入的，测试不需要 `Thread.sleep` 就能造出「不同时刻」；
 * - JSON 取值器：断言时不用手写一堆 `as? JsonValue.Str`。
 */
object TestSupport {

    fun tempDir(prefix: String): Path = Files.createTempDirectory(prefix)

    fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { candidate ->
                runCatching { Files.deleteIfExists(candidate) }
            }
        }
    }

    /**
     * 假时钟。存储层保证 `createdAt` 严格递增（同毫秒也不会并列），所以固定 now 也能得到
     * 单调的时间线；要造「一段时间之后」只需 [advance]。
     */
    class FakeClock(var now: Long = 1_000L) {
        val reader: () -> Long = { now }

        fun advance(millis: Long) {
            now += millis
        }
    }

    fun parse(text: String): JsonValue.Obj = Json.parse(text) as JsonValue.Obj
}

fun JsonValue.Obj.str(key: String): String = (fields[key] as? JsonValue.Str)?.value
    ?: throw AssertionError("字段 $key 不是字符串：$fields")

fun JsonValue.Obj.long(key: String): Long = (fields[key] as? JsonValue.Num)?.asLong
    ?: throw AssertionError("字段 $key 不是整数：$fields")

fun JsonValue.Obj.int(key: String): Int = long(key).toInt()

fun JsonValue.Obj.optLong(key: String): Long? = when (val value = fields[key]) {
    null, JsonValue.Null -> null
    is JsonValue.Num -> value.asLong
    else -> throw AssertionError("字段 $key 不是整数：$fields")
}

fun JsonValue.Obj.bool(key: String): Boolean = (fields[key] as? JsonValue.Bool)?.value
    ?: throw AssertionError("字段 $key 不是布尔：$fields")

fun JsonValue.Obj.obj(key: String): JsonValue.Obj = fields[key] as? JsonValue.Obj
    ?: throw AssertionError("字段 $key 不是对象：$fields")

fun JsonValue.Obj.optObj(key: String): JsonValue.Obj? = when (val value = fields[key]) {
    null, JsonValue.Null -> null
    is JsonValue.Obj -> value
    else -> throw AssertionError("字段 $key 不是对象：$fields")
}

fun JsonValue.Obj.arr(key: String): List<JsonValue> = (fields[key] as? JsonValue.Arr)?.items
    ?: throw AssertionError("字段 $key 不是数组：$fields")

fun JsonValue.Obj.objects(key: String): List<JsonValue.Obj> = arr(key).map { item ->
    item as? JsonValue.Obj ?: throw AssertionError("字段 $key 里有非对象元素")
}

fun JsonValue.Obj.strings(key: String): List<String> = arr(key).map { item ->
    (item as? JsonValue.Str)?.value ?: throw AssertionError("字段 $key 里有非字符串元素")
}

fun JsonValue.Obj.stringAt(key: String): String? = (fields[key] as? JsonValue.Str)?.value
