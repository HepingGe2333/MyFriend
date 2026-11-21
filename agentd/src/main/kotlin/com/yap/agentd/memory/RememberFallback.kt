package com.yap.agentd.memory

import com.yap.agentd.store.FileStore
import com.yap.agentd.store.Memory

/**
 * 「模型说记住了、其实没调 remember」的服务端兜底（0.15 可靠性修复）。
 *
 * 评测里反复出现同一个短板：用户说「我对花生过敏」「我生日是 3 月」，模型回一句
 * 「好，我记住了」，但**没有调用 `remember`**，于是下一轮 `recall` 什么也翻不到。
 * 提示词里已经写了「必须调用 remember」，但提示词不是保证；这里在**回复落库之后**
 * 再做一道确定性的兜底：
 *
 * - 用户原句命中「过敏 / 忌口 / 不能吃 / 生日 / 纪念日 / 重要约定…」；
 * - 这一轮的工具痕迹里**没有** `remember`（模型已经记过就不重复写）；
 * - 记忆库里也没有同一条（按内容归一化去重，重复说同一件事不会越记越多）。
 *
 * 内容取**原句里命中的那个分句**（去掉「对了 / 记住」这类口头前缀）：
 * 不改写、不推断、不补全日期，宁可留着原话的口气。
 *
 * 这个对象是**纯 Kotlin**：不读时钟、不发请求、不碰 HTTP；唯一的副作用在
 * [saveIfMissing]（写 [FileStore]），便于单测直接验证命中/不命中/去重。
 */
object RememberFallback {

    /** 记忆标签里的来源标记：这条是「关于对方（USER）」的自动兜底记忆。 */
    const val USER_TAG = "USER"

    /** 一条兜底记忆正文的上限（多分句拼起来也不会刷屏）。 */
    const val MAX_CONTENT_CHARS = 160

    /** 单个分句最多保留多少字；超长时围绕关键词截一段。 */
    private const val MAX_FRAGMENT_CHARS = 80

    /** 一条记忆最多几个标签（与 `MemoryTags.MAX_TAGS` 同口径）。 */
    private const val MAX_TAGS = 5

    /** 去重时参与「包含」比较的最短长度：太短的正文（例如「生日」）包含关系没有意义。 */
    private const val MIN_DEDUPE_CHARS = 4

    /**
     * 一条待写入的兜底记忆。
     *
     * [content] 是落库正文（原句关键片段）；[fragment] 与它相同，单独留一个字段是为了
     * 把「片段」和「将来可能加的前缀/格式」分开；去重只比片段正文。
     */
    data class Draft(
        val content: String,
        val importance: Int,
        val tags: List<String>,
        val fragment: String
    )

    private data class Rule(val keyword: String, val tag: String, val importance: Int)

    /**
     * 值得兜底的信息类型。importance 一律 4–5：
     * 过敏/忌口是健康级（5），生日/纪念日是长期事实（4），约定按「重要约定」抬到 5。
     */
    private val RULES: List<Rule> = listOf(
        Rule("过敏", "过敏", 5),
        Rule("忌口", "忌口", 5),
        Rule("不能吃", "忌口", 5),
        Rule("不能喝", "忌口", 5),
        Rule("吃不了", "忌口", 4),
        Rule("喝不了", "忌口", 4),
        Rule("生日", "生日", 4),
        Rule("纪念日", "纪念日", 4),
        Rule("重要约定", "约定", 5),
        Rule("约定", "约定", 4),
        Rule("说好", "约定", 4),
        Rule("约好", "约定", 4),
        Rule("答应", "约定", 4),
        Rule("承诺", "约定", 4)
    )

    /** 分句：中文与英文标点都当边界（「对了，我对花生过敏。」→「对了」「我对花生过敏」）。 */
    private val CLAUSE_SEPARATOR = Regex("[，。！？；、,.!?;\\n\\r]+")

    /** 分句开头的情境/口头前缀：去掉之后才是「要记的那件事」。 */
    private val LEADING_FILLERS: List<String> = listOf(
        "一定要记住", "帮我记住", "你记住", "别忘了", "别忘记", "记住",
        "我告诉你", "我跟你说", "告诉你", "跟你说", "和你说",
        "对了", "那个", "其实", "然后", "就是", "所以", "另外", "还有"
    )

    /** 关键词前面紧跟这些词时是否定/假设（「我不过敏」「我没有自杀」），不该当成事实。 */
    private val NEGATION_SUFFIX = Regex("(?:没有|没|不|别|不是|不会|并不|没想|没打算|从未|从没|不想)(?:任何|什么)?$")

    // ------------------------------------------------------------------ 纯函数：命中与片段

    /**
     * 用户原句里有没有「值得兜底」的信息。没有命中返回 `null`。
     *
     * 同一个分句命中多条规则只算一个片段（标签合并、importance 取最大）；
     * 不同分句的多个事实会拼成**一条**记忆（「我对花生过敏；我生日是 3 月」）。
     */
    fun detect(message: String): Draft? {
        val text = message.trim()
        if (text.isEmpty()) return null
        val fragments = LinkedHashSet<String>()
        val tags = LinkedHashSet<String>()
        var importance = 0
        for (clause in text.split(CLAUSE_SEPARATOR)) {
            val trimmed = clause.trim()
            if (trimmed.isEmpty()) continue
            val matched = RULES.filter { rule ->
                trimmed.contains(rule.keyword) && !isNegated(trimmed, rule.keyword)
            }
            if (matched.isEmpty()) continue
            val fragment = fragmentOf(trimmed, matched.first().keyword)
            if (fragment.isEmpty()) continue
            fragments += fragment
            for (rule in matched) {
                tags += rule.tag
                importance = maxOf(importance, rule.importance)
            }
        }
        if (fragments.isEmpty()) return null
        val body = fragments.joinToString("；")
            .take(MAX_CONTENT_CHARS)
            .trimEnd('；', '，', ',')
        if (body.isEmpty()) return null
        return Draft(
            content = body,
            importance = importance.coerceIn(4, 5),
            tags = (listOf(USER_TAG) + tags).distinct().take(MAX_TAGS),
            fragment = body
        )
    }

    /** 关键词在分句里是不是被否定了（只看紧邻的前缀，避免「我不想自杀」被当成求救）。 */
    private fun isNegated(clause: String, keyword: String): Boolean {
        val index = clause.indexOf(keyword)
        if (index <= 0) return false
        return NEGATION_SUFFIX.containsMatchIn(clause.substring(0, index).trimEnd())
    }

    /** 取分句里的关键片段：剥掉口头前缀，超长时围绕关键词截一段。 */
    private fun fragmentOf(clause: String, keyword: String): String {
        var text = clause.trim()
        var changed = true
        while (changed) {
            changed = false
            for (filler in LEADING_FILLERS) {
                if (text.startsWith(filler) && text.length > filler.length) {
                    text = text.removePrefix(filler).trimStart(' ', '，', ',', '：', ':', '、')
                    changed = true
                    break
                }
            }
        }
        if (text.length <= MAX_FRAGMENT_CHARS) return text
        val index = text.indexOf(keyword).coerceAtLeast(0)
        val start = (index - 20).coerceAtLeast(0)
        val end = (start + MAX_FRAGMENT_CHARS).coerceAtMost(text.length)
        val head = if (start > 0) "…" else ""
        val tail = if (end < text.length) "…" else ""
        return head + text.substring(start, end).trim() + tail
    }

    // ------------------------------------------------------------------ 副作用：写库（带去重）

    /**
     * 写一条兜底记忆；**同一条事实已经存在时返回 `null`**（不重复记）。
     *
     * 去重口径：归一化（去掉空白/标点、转小写）后比较正文，另外允许「现有记忆包含这次片段」
     * 或反过来——模型自己调 `remember` 时可能写成更完整/更短的一句，不能因此记两遍。
     */
    fun saveIfMissing(store: FileStore, personaId: String, draft: Draft): Memory? {
        val newKey = normalize(draft.content)
        val fragmentKey = normalize(draft.fragment)
        val existing = store.memories(personaId, limit = Int.MAX_VALUE)
        for (memory in existing) {
            val oldKey = normalize(memory.content)
            if (oldKey == newKey) return null
            if (fragmentKey.length >= MIN_DEDUPE_CHARS && oldKey.contains(fragmentKey)) return null
            if (oldKey.length >= MIN_DEDUPE_CHARS && fragmentKey.contains(oldKey)) return null
        }
        return store.saveMemory(personaId, memoryId = null) { _, _ ->
            Memory(
                id = "",
                personaId = personaId,
                content = draft.content,
                // 记忆表的 importance 是 0–100；工具参数与兜底规则都是 1–5，这里统一换算。
                importance = (draft.importance * 20).coerceIn(0, 100),
                tags = draft.tags,
                createdAt = 0,
                updatedAt = 0
            )
        }
    }

    /** 归一化：只留字母/数字/汉字，去掉空白与中英文标点。 */
    private fun normalize(text: String): String = buildString(text.length) {
        for (ch in text.lowercase()) {
            if (ch.isLetterOrDigit()) append(ch)
        }
    }
}
