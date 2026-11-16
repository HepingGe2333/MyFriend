package com.myfriend.agentd.store

import com.myfriend.app.core.json.Json
import com.myfriend.app.core.json.JsonParseException
import com.myfriend.app.core.json.JsonValue

/**
 * `:agentd` 存储层用的 JSON 小工具。
 *
 * 为什么要单独一层：`JsonValue`（`:agentcore` 的零依赖解析器）是**通用**的，没有「取字段、缺了就报
 * `缺字段 x`」这类带上下文的便利方法；HTTP 层（`Api.kt`）里那套 `stringField/intField` 是私有的，而且
 * 错误语义是 HTTP 400，不适合直接用在「磁盘上的数据坏了」这种场景。
 *
 * 这里只做两件事：
 * 1. 写：`obj/arr/str/num/nullable*`，把 Kotlin 值装箱成 `JsonValue`；
 * 2. 读：带 `where` 上下文的取值器，缺字段/类型不对一律抛 [StoreCorruptionException]（启动或读盘时
 *    的错误要**明确指向哪个文件哪一行**，而不是静默当成默认值）。
 */
internal object StoreJson {

    fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue.Obj =
        JsonValue.Obj(linkedMapOf(*pairs))

    fun obj(pairs: LinkedHashMap<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(pairs)

    fun str(value: String): JsonValue.Str = JsonValue.Str(value)

    fun num(value: Long): JsonValue.Num = JsonValue.Num(value.toString())

    fun num(value: Int): JsonValue.Num = JsonValue.Num(value.toString())

    /** 概率/随机数这类小数（只用于诊断字段；`NaN`/`Inf` 由调用方保证不会出现）。 */
    fun num(value: Double): JsonValue.Num = JsonValue.Num(value.toString())

    fun bool(value: Boolean): JsonValue.Bool = JsonValue.Bool(value)

    fun nullableStr(value: String?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Str(value)

    fun nullableNum(value: Long?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Num(value.toString())

    fun nullableInt(value: Int?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Num(value.toString())

    fun strArr(items: List<String>): JsonValue.Arr =
        JsonValue.Arr(items.mapTo(mutableListOf()) { JsonValue.Str(it) })

    fun arr(items: List<JsonValue>): JsonValue.Arr = JsonValue.Arr(items.toMutableList())

    fun parse(text: String, where: String): JsonValue = try {
        Json.parse(text)
    } catch (failure: JsonParseException) {
        throw StoreCorruptionException("$where 不是合法 JSON：${failure.message}")
    }

    fun stringify(value: JsonValue): String = Json.stringify(value)

    // ------------------------------------------------------------------ 读

    fun JsonValue.Obj.reqString(key: String, where: String): String {
        val value = fields[key] ?: throw StoreCorruptionException("$where 缺少字段 $key")
        return (value as? JsonValue.Str)?.value
            ?: throw StoreCorruptionException("$where 的字段 $key 不是字符串")
    }

    fun JsonValue.Obj.optString(key: String, where: String): String? {
        val value = fields[key] ?: return null
        if (value is JsonValue.Null) return null
        return (value as? JsonValue.Str)?.value
            ?: throw StoreCorruptionException("$where 的字段 $key 不是字符串")
    }

    fun JsonValue.Obj.reqLong(key: String, where: String): Long {
        val value = fields[key] ?: throw StoreCorruptionException("$where 缺少字段 $key")
        return (value as? JsonValue.Num)?.asLong
            ?: throw StoreCorruptionException("$where 的字段 $key 不是整数")
    }

    fun JsonValue.Obj.optLong(key: String, where: String): Long? {
        val value = fields[key] ?: return null
        if (value is JsonValue.Null) return null
        return (value as? JsonValue.Num)?.asLong
            ?: throw StoreCorruptionException("$where 的字段 $key 不是整数")
    }

    fun JsonValue.Obj.optInt(key: String, where: String): Int? {
        val value = fields[key] ?: return null
        if (value is JsonValue.Null) return null
        return (value as? JsonValue.Num)?.asInt
            ?: throw StoreCorruptionException("$where 的字段 $key 不是整数")
    }

    fun JsonValue.Obj.reqBool(key: String, where: String): Boolean {
        val value = fields[key] ?: throw StoreCorruptionException("$where 缺少字段 $key")
        return (value as? JsonValue.Bool)?.value
            ?: throw StoreCorruptionException("$where 的字段 $key 不是布尔")
    }

    fun JsonValue.Obj.reqObj(key: String, where: String): JsonValue.Obj {
        val value = fields[key] ?: throw StoreCorruptionException("$where 缺少字段 $key")
        return value as? JsonValue.Obj
            ?: throw StoreCorruptionException("$where 的字段 $key 不是对象")
    }

    fun JsonValue.Obj.optObj(key: String, where: String): JsonValue.Obj? {
        val value = fields[key] ?: return null
        if (value is JsonValue.Null) return null
        return value as? JsonValue.Obj
            ?: throw StoreCorruptionException("$where 的字段 $key 不是对象")
    }

    fun JsonValue.Obj.optArr(key: String, where: String): List<JsonValue> {
        val value = fields[key] ?: return emptyList()
        if (value is JsonValue.Null) return emptyList()
        return (value as? JsonValue.Arr)?.items
            ?: throw StoreCorruptionException("$where 的字段 $key 不是数组")
    }

    fun JsonValue.Obj.stringList(key: String, where: String): List<String> =
        optArr(key, where).map { item ->
            (item as? JsonValue.Str)?.value
                ?: throw StoreCorruptionException("$where 的字段 $key 里有非字符串元素")
        }
}

/** 磁盘数据与期望的结构不一致（缺文件、坏 JSON、版本不对）。启动时直接失败，不静默重建。 */
class StoreCorruptionException(message: String) : IllegalStateException(message)
