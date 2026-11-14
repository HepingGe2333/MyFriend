package com.myfriend.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 他这一轮「做过什么」的痕迹：查了什么、记住了什么、想了什么。
 *
 * 存进 messages.toolTraceJson，聊天里在气泡下方折成一行小字，
 * 点开能看到搜索词、命中的网页标题、失败原因——不是黑箱。
 */
@Serializable
data class ToolTraceItem(
    val name: String = "",
    val title: String = "",          // 「联网搜索」
    val subtitle: String? = null,    // 「DeepSeek V4 发布」
    val detail: String = "",         // 展开后的正文（查询词 + 结果摘要，或错误信息）
    val ok: Boolean = true
)

object ToolTraceCodec {
    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun encode(items: List<ToolTraceItem>): String? = if (items.isEmpty()) {
        null
    } else {
        runCatching { json.encodeToString(ListSerializer(ToolTraceItem.serializer()), items) }.getOrNull()
    }

    fun decode(raw: String?): List<ToolTraceItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<ToolTraceItem>>(raw) }.getOrDefault(emptyList())
    }

    /** 气泡下面那一行：多个工具压成一句，如「联网搜索 2 次 · 想了想」。 */
    fun summarize(items: List<ToolTraceItem>): String {
        if (items.isEmpty()) return ""
        val counts = items.groupingBy { it.title.ifBlank { it.name } }.eachCount()
        return counts.entries.joinToString(" · ") { (title, count) ->
            if (count > 1) "$title $count 次" else title
        }
    }
}
