package com.yap.app.i18n

import com.yap.app.data.repo.ToolTraceTitle
import com.yap.app.domain.ToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「工具痕迹的显示文案」跨语言契约。
 *
 * 背景：同一次工具调用会产生两份文字，规则完全不同——
 * 回灌给模型的文本（`ToolProtocol.renderResultsForModel`）保持中文，由 `ToolProtocolTest` 钉住；
 * **给用户看的**标题/副标题/展开正文走 `R.string.tool_trace_*`（`data/repo/ToolTraceText`），
 * 这个文件负责钉它的中英文案。
 *
 * 这里同时是**跨端契约**：iOS 侧的 `ToolTraceTitleLocalizationTests` 里钉着同一张表
 *（位于 `ios/` 下）。
 * 谁改了一端的措辞，那端的测试先红；两边都改才算同步。
 */
class ToolTraceTextTest {

    /** 工具痕迹的最终口径：中文 / English 逐字一致，两端共用。 */
    private val canonicalTitles: Map<ToolTraceTitle, Pair<String, String>> = linkedMapOf(
        ToolTraceTitle.THINK to ("想了想" to "Thought it over"),
        ToolTraceTitle.WEB_SEARCH to ("联网搜索" to "Web search"),
        ToolTraceTitle.WEB_FETCH to ("读了网页" to "Read a page"),
        ToolTraceTitle.REMEMBER to ("记住了一件事" to "Remembered something"),
        ToolTraceTitle.REMEMBER_PENDING to ("记下了（还在确认）" to "Noted (still confirming)"),
        ToolTraceTitle.REMEMBER_CONFIRMED to ("记住了" to "Remembered"),
        ToolTraceTitle.REMEMBER_REINFORCED to ("又确认了一次" to "Confirmed again"),
        ToolTraceTitle.REMEMBER_SKIPPED to ("这次先不记" to "Not saving this one"),
        ToolTraceTitle.REMEMBER_CONSIDERING to ("想了想要不要记" to "Thought about whether to remember it"),
        ToolTraceTitle.UPDATE_PERSONA to ("调整了自己的人设" to "Adjusted his profile"),
        ToolTraceTitle.PERSONA_UNCHANGED to ("想了想，没有改" to "Thought it over, left it unchanged"),
        ToolTraceTitle.SET_QUIET_HOURS to ("安排了自己的作息" to "Set his sleep hours"),
        ToolTraceTitle.SET_ACTIVITY to ("在忙一件事" to "Busy with something"),
        ToolTraceTitle.SET_MOOD to ("心情变了" to "Mood changed"),
        ToolTraceTitle.RECALL to ("回想了一下" to "Recalled"),
        ToolTraceTitle.DEFER_REPLY to ("决定先不回" to "Decided not to reply yet"),
        ToolTraceTitle.DIARY to ("日记" to "Diary"),
        ToolTraceTitle.DIARY_ADD to ("写日记" to "Wrote a note"),
        ToolTraceTitle.DIARY_LIST to ("翻日记" to "Flipped through the diary"),
        ToolTraceTitle.DIARY_REMOVE to ("撕掉一页" to "Tore out a page"),
        // 0.13：表情包（spec/stickers）。send/make/save 三句分开，兜底那一条只写「表情」。
        ToolTraceTitle.STICKER to ("表情" to "Sticker"),
        ToolTraceTitle.STICKER_SEND to ("发了一张表情" to "Sent a sticker"),
        ToolTraceTitle.STICKER_MAKE to ("做了一张表情" to "Made a sticker"),
        ToolTraceTitle.STICKER_SAVE to ("收藏了一张表情" to "Saved a sticker"),
        // 0.13：语音/视频（spec/audio-video）。工具名就叫 listen / watch，标题写成「听语音/看视频」。
        ToolTraceTitle.LISTEN to ("听语音" to "Listened"),
        ToolTraceTitle.WATCH to ("看视频" to "Watched"),
        ToolTraceTitle.UNKNOWN to ("动作" to "Action")
    )

    @Test
    fun `每个工具都有标题，认不出的工具落到「动作」`() {
        ToolCatalog.all.forEach { spec ->
            assertNotEquals(
                "工具 ${spec.name} 没有自己的痕迹标题，落到了兜底",
                ToolTraceTitle.UNKNOWN,
                ToolTraceTitle.forTool(spec.name)
            )
            assertEquals("工具名要去空格后匹配", ToolTraceTitle.forTool(spec.name), ToolTraceTitle.forTool(" ${spec.name} "))
        }
        assertEquals(ToolTraceTitle.UNKNOWN, ToolTraceTitle.forTool("no_such_tool"))
        assertEquals(ToolTraceTitle.UNKNOWN, ToolTraceTitle.forTool(""))
        // 枚举里每一条都要在表里：加了新标题却忘了写中英文，这里先红。
        assertEquals(ToolTraceTitle.entries.toSet(), canonicalTitles.keys)
    }

    @Test
    fun `痕迹标题在中英文下逐字一致`() {
        val zh = TestResources.strings("values")
        val en = TestResources.strings("values-en")
        canonicalTitles.forEach { (title, expected) ->
            val key = stringKeyName(title.res)
            assertEquals("找不到 $title 对应的 string 资源（key=$key）", true, zh.containsKey(key))
            assertEquals("${key} 的中文不对", expected.first, zh[key])
            assertEquals("${key} 的英文不对", expected.second, en[key])
        }
    }

    @Test
    fun `副标题与展开正文的中英文案`() {
        val zh = TestResources.strings("values")
        val en = TestResources.strings("values-en")
        val pairs = mapOf(
            "tool_trace_recall_empty" to ("翻了翻，没想起什么" to "Looked back — nothing came to mind"),
            "tool_trace_search_no_result" to ("没有搜到结果" to "No results found"),
            "tool_trace_remember_skipped_detail" to ("这次先不记：%1\$s" to "Not saving this one: %1\$s"),
            "tool_trace_persona_applied" to ("已生效，改动进了版本历史。" to "Applied — the change is in his version history."),
            "tool_trace_persona_reason" to ("原因：%1\$s" to "Reason: %1\$s"),
            "tool_trace_quiet_hours" to ("%1\$s 睡 · %2\$s 起" to "Sleep %1\$s · wake %2\$s"),
            "tool_trace_mood" to ("%1\$s（%2\$d/5）" to "%1\$s (%2\$d/5)"),
            "tool_trace_mood_reason" to ("%1\$s（%2\$d/5）· %3\$s" to "%1\$s (%2\$d/5) · %3\$s"),
            "tool_trace_recall_hit_line" to ("（%1\$s）%2\$s" to "(%1\$s) %2\$s"),
            "tool_trace_source_memory" to ("记忆" to "Memories"),
            "tool_trace_source_chat" to ("聊天" to "Chats"),
            "tool_trace_recall_depth_normal" to ("认真回忆" to "a careful recall")
        )
        pairs.forEach { (key, expected) ->
            assertEquals("$key 的中文不对", expected.first, zh[key])
            assertEquals("$key 的英文不对", expected.second, en[key])
        }
    }

    @Test
    fun `落库的会话标题与消息预览在中英文下都有文案`() {
        val zh = TestResources.strings("values")
        val en = TestResources.strings("values-en")
        assertEquals("与 %1\$s 的对话", zh["chat_session_default_title"])
        assertEquals("Chat with %1\$s", en["chat_session_default_title"])
        assertEquals("图片", zh["chat_preview_image"])
        assertEquals("Photo", en["chat_preview_image"])
        assertEquals("主动提议", zh["chat_preview_proactive"])
        assertEquals("Reached out", en["chat_preview_proactive"])
        assertEquals("回复失败", zh["chat_preview_failed"])
        assertEquals("Reply failed", en["chat_preview_failed"])
    }

    /**
     * 计数文案的 0 / 1 / 2 三种值。
     *
     * Android 的中文 plurals 只有 `other`，英文必须有 `other`、需要单数时再带 `one`；
     * 这里按 `getQuantityString` 的语义模拟挑选（0 与 2 走 other，1 走 one），
     * 逐字断言用户最终看到的句子。
     */
    @Test
    fun `数量文案在 0 1 2 时选对变体`() {
        assertPlural(
            "tool_trace_recall_hits",
            zhOne = "翻了翻记忆 · 1 条", zhOther = "翻了翻记忆 · 2 条",
            enOne = "Looked back · 1 memory", enOther = "Looked back · 2 memories"
        )
        assertPlural(
            "tool_trace_defer_detail",
            zhOne = "过 1 分钟再想这件事", zhOther = "过 2 分钟再想这件事",
            enOne = "Will think about it again in 1 minute",
            enOther = "Will think about it again in 2 minutes"
        )
        assertPlural(
            "tool_trace_when_years",
            zhOne = "1 年前", zhOther = "2 年前",
            enOne = "1 year ago", enOther = "2 years ago"
        )
        assertPlural(
            "proactive_default_note_interval",
            zhOne = "每 1 小时主动关心一次", zhOther = "每 2 小时主动关心一次",
            enOne = "Checks in every hour", enOther = "Checks in every 2 hours"
        )
        assertPlural(
            "chat_trace_count",
            zhOne = "1 次", zhOther = "2 次",
            enOne = "once", enOther = "2 times"
        )
    }

    // ---------------------------------------------------------------- 辅助

    private fun assertPlural(
        key: String,
        zhOne: String,
        zhOther: String,
        enOne: String,
        enOther: String
    ) {
        val zh = TestResources.plurals("values").getValue(key)
        val en = TestResources.plurals("values-en").getValue(key)

        assertEquals("$key 中文 plurals 只该有 other", setOf("other"), zh.keys)
        assertTrue("$key 英文 plurals 缺少 other", "other" in en.keys)
        assertTrue("$key 英文 plurals 缺少 one（1 时说成复数了）", "one" in en.keys)

        // 0：英语按 other（"0 memories"）
        assertEquals("$key 在 0 时的英文", enOther.replace("2", "0"), format(en, 0))
        // 1：英语按 one，中文仍是 other（中文没有单数变形）
        assertEquals("$key 在 1 时的英文", enOne, format(en, 1))
        assertEquals("$key 在 1 时的中文", zhOne, format(zh, 1))
        // 2：other
        assertEquals("$key 在 2 时的英文", enOther, format(en, 2))
        assertEquals("$key 在 2 时的中文", zhOther, format(zh, 2))
    }

    /** 模拟 `getQuantityString`：中文没有 one；英文 n==1 选 one，其它选 other。 */
    private fun format(items: Map<String, String>, count: Int): String {
        val template = if (count == 1) items["one"] ?: items.getValue("other") else items.getValue("other")
        return if ("%1\$d" in template) {
            template.replace("%1\$d", count.toString())
        } else {
            template
        }
    }

    /** 资源 id → 字段名（`R.string` 的字段名就是 XML 里的 key）。 */
    private fun stringKeyName(id: Int): String {
        val cached = keyCache[id]
        if (cached != null) return cached
        val fields = Class.forName("com.yap.app.R\$string").declaredFields
        val name = fields.firstOrNull { field ->
            runCatching { field.getInt(null) }.getOrNull() == id
        }?.name ?: error("R.string 里找不到 id=$id 的资源")
        keyCache[id] = name
        return name
    }

    private companion object {
        val keyCache = mutableMapOf<Int, String>()
    }
}
