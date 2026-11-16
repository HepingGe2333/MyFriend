package com.yap.agentd.search

import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonParseException
import com.yap.app.core.json.JsonValue

/**
 * provider 响应解析用的小工具（`JsonValue` 上的取值扩展 + 请求体拼接）。
 *
 * 只做两件事：
 * - 把「缺字段 / 类型不对」收敛成默认值（第三方返回里 `title: null`、`snippet: 42` 很常见）；
 * - 把「整个响应不是 JSON」收敛成 [SearchErrorKind.UNPARSABLE]（这个不能静默成空结果）。
 *
 * 所有 provider 共用同一套字段别名顺序，避免同一条结果在 A provider 有标题、B provider 没有。
 */
internal object JsonSupport {

    /** 解析响应根节点；不是合法 JSON 就是 [SearchErrorKind.UNPARSABLE]。 */
    fun parse(body: String, provider: String): JsonValue = try {
        Json.parse(body)
    } catch (invalid: JsonParseException) {
        throw SearchException(
            SearchError(
                SearchErrorKind.UNPARSABLE,
                provider,
                null,
                "$provider 返回的不是合法 JSON（${invalid.message.orEmpty().take(80)}）"
            )
        )
    }

    /** 拼一个请求体（字段顺序稳定，方便测试直接比对字符串）。 */
    fun body(vararg fields: Pair<String, JsonValue>): String {
        val map = LinkedHashMap<String, JsonValue>(fields.size)
        fields.forEach { (key, value) -> map[key] = value }
        return Json.stringify(JsonValue.Obj(map))
    }

    fun str(value: String): JsonValue = JsonValue.Str(value)

    fun num(value: Int): JsonValue = JsonValue.Num(value.toString())

    fun bool(value: Boolean): JsonValue = JsonValue.Bool(value)
}

/** 按顺序取第一个「非空白字符串」的字段；数字/布尔也接受（有些后端把 snippet 写成数字）。 */
internal fun JsonValue.Obj.string(vararg keys: String): String = stringOrNull(*keys).orEmpty()

internal fun JsonValue.Obj.stringOrNull(vararg keys: String): String? {
    keys.forEach { key ->
        val text = when (val value = fields[key]) {
            is JsonValue.Str -> value.value
            is JsonValue.Num -> value.raw
            is JsonValue.Bool -> value.value.toString()
            else -> null
        } ?: return@forEach
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) return trimmed
    }
    return null
}

internal fun JsonValue.Obj.objOrNull(key: String): JsonValue.Obj? = fields[key] as? JsonValue.Obj

/** 取对象数组：`[{...}, {...}]`；元素不是对象的条目直接跳过（不因为一条脏数据废掉整页结果）。 */
internal fun JsonValue.Obj.objects(key: String): List<JsonValue.Obj> {
    val array = fields[key] as? JsonValue.Arr ?: return emptyList()
    return array.items.mapNotNull { it as? JsonValue.Obj }
}

/** 根节点本身就是数组时（MESSAGEAPI 兼容接口有时直接回 `[...]`）。 */
internal fun JsonValue.arrayObjects(): List<JsonValue.Obj> {
    val array = this as? JsonValue.Arr ?: return emptyList()
    return array.items.mapNotNull { it as? JsonValue.Obj }
}
