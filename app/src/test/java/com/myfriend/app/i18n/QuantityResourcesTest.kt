package com.myfriend.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计数文案的 0 / 1 / 2 三种值。
 *
 * 0.10.2 把「1 个人格」这类以前说成「1 personas」的句子从 `<string>` 换成了 `<plurals>`：
 * 中文只有 `other`（中文没有单复数变形），英文必须有 `other`，需要单数时再补 `one`。
 *
 * 这里按 `getQuantityString` 的语义（0 → other、1 → one、2 → other）把三种值都拼出来逐字断言，
 * 只断言资源本身：不需要 `Context`，也不会因为真机语言设置不同而漂。
 */
class QuantityResourcesTest {

    private val zh = TestResources.plurals("values")
    private val en = TestResources.plurals("values-en")

    /** key → (英文 one, 英文 other 模板, 中文 other 模板)。参数只有计数（`%1$d`）。 */
    private val countOnlyCases: Map<String, Case> = linkedMapOf(
        "backup_preview_personas" to Case("%1\$d persona", "%1\$d personas", "%1\$d 个人格"),
        "backup_preview_memories" to Case("%1\$d memory", "%1\$d memories", "%1\$d 条记忆"),
        "backup_preview_sessions" to Case("%1\$d conversation", "%1\$d conversations", "%1\$d 段对话"),
        "backup_preview_messages" to Case("%1\$d message", "%1\$d messages", "%1\$d 句话"),
        "backup_preview_rules" to Case("%1\$d proactive rule", "%1\$d proactive rules", "%1\$d 条主动节奏"),
        "backup_preview_presets" to Case("%1\$d preset", "%1\$d presets", "%1\$d 个预设"),
        "backup_import_skipped" to Case(
            "; %1\$d thing didn't come back",
            "; %1\$d things didn't come back",
            "；有 %1\$d 条没接回来"
        ),
        "backup_warn_extra_sessions" to Case(
            "The backup had %1\$d extra conversation; only the newest one per persona was kept",
            "The backup had %1\$d extra conversations; only the newest one per persona was kept",
            "备份里有 %1\$d 段多余会话，已按「一人一段」保留最新那段"
        ),
        "backup_warn_attachments" to Case(
            "%1\$d message used to have a picture. The picture didn't travel with the backup, so only the text is back",
            "%1\$d messages used to have pictures. The pictures didn't travel with the backup, so only the text is back",
            "有 %1\$d 句话原本带着图片，图片没有跟着备份走，现在只剩文字"
        ),
        "backup_warn_configs_missing_key" to Case(
            "%1\$d API setup still needs a key (keys never travel in backups); fill it in under API settings",
            "%1\$d API setups still need a key (keys never travel in backups); fill them in under API settings",
            "有 %1\$d 个 API 配置还缺 Key（备份里从来不装密钥），去「API 配置」里补一次就好"
        ),
        "backup_warn_api_config_address_kept" to Case(
            "%1\$d API setup points somewhere else in the backup; this phone’s addresses were kept (edit and confirm manually to change one)",
            "%1\$d API setups point somewhere else in the backup; this phone’s addresses were kept (edit and confirm manually to change one)",
            "有 %1\$d 条 API 配置的地址与备份里不同，已保留这台手机上的地址（要改地址请手动编辑并重新确认）"
        ),
        "persona_import_result_ok" to Case("Imported %1\$d persona", "Imported %1\$d personas", "成功导入 %1\$d 个人格"),
        "preset_import_result_ok" to Case("Imported %1\$d preset", "Imported %1\$d presets", "已导入 %1\$d 个预设"),
        "memory_extract_result_interval" to Case(
            "Not at the memory interval yet (every %1\$d message)",
            "Not at the memory interval yet (every %1\$d messages)",
            "还没到记忆整理间隔（每 %1\$d 条）"
        ),
        "persona_editor_extraction_value" to Case(
            "Every %1\$d message",
            "Every %1\$d messages",
            "每 %1\$d 条消息抽一次"
        ),
        "api_connect_success_models" to Case(
            "Connected — found %1\$d model",
            "Connected — found %1\$d models",
            "连接成功，发现 %1\$d 个模型"
        ),
        "chat_profile_prompt_value" to Case(
            "%1\$d character · kept in full",
            "%1\$d characters · kept in full",
            "%1\$d 字符 · 完整保存"
        )
    )

    @Test
    fun `计数文案在 0 1 2 时选对单复数`() {
        countOnlyCases.forEach { (key, case) ->
            val zhItems = zh.getValue(key)
            val enItems = en.getValue(key)
            assertEquals("$key 中文 plurals 只该有 other", setOf("other"), zhItems.keys)
            assertTrue("$key 英文 plurals 缺少 other", "other" in enItems)
            assertTrue("$key 英文 plurals 缺少 one（1 时说成复数了）", "one" in enItems)

            assertEquals("$key 中文 1", case.zhOther.replace("%1\$d", "1"), fill(zhItems, 1))
            assertEquals("$key 中文 2", case.zhOther.replace("%1\$d", "2"), fill(zhItems, 2))

            assertEquals("$key 英文 0（按 other）", case.enOther.replace("%1\$d", "0"), fill(enItems, 0))
            assertEquals("$key 英文 1（按 one）", case.enOne.replace("%1\$d", "1"), fill(enItems, 1))
            assertEquals("$key 英文 2（按 other）", case.enOther.replace("%1\$d", "2"), fill(enItems, 2))
        }
    }

    /** 带额外参数（表格名 / token 数）的计数文案：只核对 one/other 都在、数量词都还在。 */
    @Test
    fun `复合计数文案也带 one 变体`() {
        listOf(
            "usage_kind_calls_tokens",
            "usage_detail_counts",
            "backup_warn_dropped_rows",
            "backup_warn_blank_ids",
            "tool_trace_recall_hits",
            "tool_trace_defer_detail",
            "tool_trace_defer_detail_reason",
            "tool_trace_when_years",
            "chat_trace_count",
            "proactive_default_note_interval"
        ).forEach { key ->
            assertTrue("$key 缺中文资源", key in zh)
            assertTrue("$key 缺英文资源", key in en)
            assertTrue("$key 英文 plurals 缺少 one", "one" in en.getValue(key))
            assertTrue("$key 英文 plurals 缺少 other", "other" in en.getValue(key))
            assertEquals("$key 中文 plurals 只该有 other", setOf("other"), zh.getValue(key).keys)
        }
    }

    /**
     * 英文 `one` 变体不许引入 `other` 里没有的占位符。
     *
     * `one` 可以**少**一个参数（「once」「Checks in every hour」正是为了让 1 读起来自然），
     * 但不能多：多出来的 `%2$s` 在 `getQuantityString` 里会把后面的参数顶错位。
     */
    @Test
    fun `英文 one 与 other 的占位符一致`() {
        val mismatched = buildList {
            en.forEach { (key, items) ->
                val one = items["one"] ?: return@forEach
                val other = items["other"] ?: return@forEach
                val extra = specifiers(one) - specifiers(other)
                if (extra.isNotEmpty()) add("$key one 多出占位符 $extra（other=${specifiers(other)}）")
            }
        }
        assertEquals("英文 plurals 的 one/other 占位符对不上：$mismatched", emptyList<String>(), mismatched)
    }

    private fun specifiers(text: String): Set<String> =
        Regex("""%(\d+\$)?[-#+ 0,]*\d*(?:\.\d+)?([a-zA-Z%])""")
            .findAll(text)
            .map { it.value }
            .toSet()

    private data class Case(val enOne: String, val enOther: String, val zhOther: String)

    /** 模拟 `getQuantityString`：n == 1 选 one（没有就 other），其它选 other，再按位置填 %1$d。 */
    private fun fill(items: Map<String, String>, count: Int): String {
        val template = if (count == 1) items["one"] ?: items.getValue("other") else items.getValue("other")
        return template.replace("%1\$d", count.toString())
    }
}
