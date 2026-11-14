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
    /**
     * 显示用标题，**写入时**按当时的界面语言从 `R.string.tool_trace_*` 取
     *（`data/repo/ToolTraceText`）。老库里的值（中文原文）原样显示，不改写。
     */
    val title: String = "",
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

    /**
     * 气泡下面那一行：多个工具压成一句，如「联网搜索 2 次 · 想了想」。
     *
     * 「次」是**界面文案**，各语言说法不同（英文「2 times」/「once」），所以由调用方
     * 传入 [countText]（Android 侧是 `R.plurals.chat_trace_count`）；标题本身已经是
     * 写入时的显示文案，这里只负责分组与拼接。
     */
    fun summarize(items: List<ToolTraceItem>, countText: (Int) -> String): String {
        if (items.isEmpty()) return ""
        val counts = items.groupingBy { it.title.ifBlank { it.name } }.eachCount()
        return counts.entries.joinToString(" · ") { (title, count) ->
            if (count > 1) "$title ${countText(count)}" else title
        }
    }
}
