package com.yap.agentd.llm

import com.yap.agentd.mood.MoodRules
import com.yap.agentd.persona.PersonaFields
import com.yap.agentd.proactive.ActivityState
import com.yap.agentd.proactive.ActivityAvailability
import com.yap.agentd.store.Message
import com.yap.agentd.store.Mood
import com.yap.agentd.store.Persona
import com.yap.app.domain.PersonaPromptScaffold
import com.yap.app.domain.PromptAvailability
import com.yap.app.domain.PromptScaffoldInput
import com.yap.app.domain.PromptState
import com.yap.app.domain.PromptVoice
import com.yap.app.domain.PromptWorld
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 服务端系统提示词拼装（`spec/persona-guidance/README.md` §1 + `spec/mood/README.md` §3）。
 *
 * **规范中文只来自 `:agentcore` 的 `PersonaPromptScaffold`**：段标题、行模板、边界四条、
 * 常用语与世界的渲染规则都在那里（Android/iOS 调的是同一份）。这里只做两件事：
 *
 * 1. **喂数据**：人格（名字/年龄/职业/世界/话风/关系/四组值）、此刻（时间/占用时间）、
 *    多维心情——全部来自服务端自己的存储（`personas.json` / `moods.json`）；
 * 2. **按规范顺序插入规范之外的段**：
 *    `身份 → 世界 → 此刻 → 心情 → 话风 → 关系 → 你能做什么 → 边界`。
 *    「你能做什么」的位置由 §1.3 钉死在关系之后、边界之前；心情段来自 `spec/mood` §3。
 *
 * 为什么不自己拼中文：规范要求两端逐字一致，多写一份就一定漂。段落拼接用
 * `"\n\n"` 切分（脚手架保证段内没有空行），插入后按原顺序 join 回去。
 *
 * 「你能做什么」**由 `tools/ServerToolCatalog.capabilityPrompt()` 生成**（§1.3）：
 * 工具清单只有一处真源，所以「提示词说有」与「服务端真能执行」不会漂。
 */
class ChatPromptBuilder(
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** 「你能做什么」段；测试可以传一段固定文本，生产永远来自工具注册表。 */
    private val capabilities: String = com.yap.agentd.tools.ServerToolCatalog.capabilityPrompt()
) {

    /**
     * 完整系统提示词（聊天用）。
     *
     * @param activity 「他此刻在做什么」（`personas.activityJson`）；过期/没有时写「没在忙什么」
     * @param mood 多维心情；`null` = 没有记录，整段省略
     */
    fun systemPrompt(
        persona: Persona,
        mood: Mood?,
        activity: ActivityState?,
        now: Long
    ): String {
        val card = PersonaFields.card(persona)
        val world = PersonaFields.world(persona)
        val speech = PersonaFields.speech(persona)
        val profile = PersonaFields.current(persona)
        val clock = clockParts(now)

        val input = PromptScaffoldInput(
            name = persona.name.ifBlank { "他" },
            meta = listOf(card.age, card.role).filter { it.isNotBlank() }.joinToString("，").ifBlank { null },
            world = PromptWorld(
                worldName = world.worldName,
                era = world.era,
                description = world.description,
                notes = world.notes
            ),
            state = PromptState(
                weekday = clock?.first.orEmpty(),
                time = clock?.second.orEmpty(),
                activity = activity?.takeIf { it.isActive(now) }?.activity,
                until = activity?.takeIf { it.isActive(now) }?.let { formatClock(it.until) },
                availability = availabilityOf(activity, now)
            ),
            voice = PromptVoice(
                tone = speech.tone,
                phrases = if (speech.phrases.isNotEmpty()) speech.phrases else card.catchphrases,
                habits = speech.habits,
                avoid = speech.avoid
            ),
            relationship = relationshipOf(card)
        )

        val sections = PersonaPromptScaffold.build(input)
            .split("\n\n")
            .toMutableList()
        // 身份段之后补人格数据（性格/喜欢/习惯/设定/描述/作息），它们是数据不是规范文案。
        val identityExtras = buildList {
            val description = persona.description.trim().take(300)
            if (description.isNotBlank()) add("- 他是这样的人：$description")
            if (profile.traits.isNotEmpty()) add("- 性格：" + profile.traits.joinToString("、"))
            if (profile.likes.isNotEmpty()) add("- 喜欢的事：" + profile.likes.joinToString("、"))
            if (profile.behaviors.isNotEmpty()) add("- 习惯：" + profile.behaviors.joinToString("、"))
            if (profile.quirks.isNotBlank()) add("- 设定：" + profile.quirks.replace('\n', '；').take(300))
            if (card.schedule.isNotBlank()) add("- 作息：" + card.schedule.take(120))
            if (card.doing.isNotBlank()) add("- 平时这个时候：" + card.doing.take(120))
            if (card.caresAbout.isNotBlank()) add("- 最近在意：" + card.caresAbout.take(120))
        }
        if (identityExtras.isNotEmpty()) {
            appendToSection(sections, PersonaPromptScaffold.Section.IDENTITY, identityExtras.joinToString("\n"))
        }

        // 话风段之后补「习惯 / 不要」（数据行；脚手架没有对应模板，见 PromptVoice 注释）。
        val voiceExtras = buildList {
            speech.habits.take(6).forEach { add("- 习惯：$it") }
            speech.avoid.take(6).forEach { add("- 不要：$it") }
        }
        if (voiceExtras.isNotEmpty()) {
            appendToSection(sections, PersonaPromptScaffold.Section.VOICE, voiceExtras.joinToString("\n"))
        }

        // 心情段：插在「此刻」之后、「话风」之前（spec/mood §3）。
        if (mood != null && mood.label.isNotBlank()) {
            insertAfterSection(
                sections,
                PersonaPromptScaffold.Section.STATE,
                MoodRules.promptSection(MoodRules.Values.from(mood))
            )
        }

        // 「你能做什么」：规范 §1.3 固定在关系之后、边界之前；内容来自工具注册表。
        insertAfterSection(sections, PersonaPromptScaffold.Section.RELATIONSHIP, capabilities)

        return sections.joinToString("\n\n")
    }

    /** 聊天请求的消息列表：`system` + 最近历史（升序，含本轮用户消息）。 */
    fun chatMessages(
        persona: Persona,
        mood: Mood?,
        activity: ActivityState?,
        history: List<Message>,
        now: Long,
        historyLimit: Int = LlmDefaults.MAX_HISTORY_MESSAGES
    ): List<LlmMessage> {
        val system = LlmMessage.system(systemPrompt(persona, mood, activity, now))
        val selected = history.takeLast(historyLimit.coerceAtLeast(1))
        // 上下文窗口（1M）只是**上限**：真的超了就从最老的历史开始丢，保证一次请求不会无限大。
        val budget = LlmDefaults.CONTEXT_WINDOW_TOKENS - LlmUsage.estimate(system.content)
        val kept = ArrayDeque<LlmMessage>()
        var used = 0
        for (message in selected.asReversed()) {
            val content = withAttachmentNote(message)
            if (content.isBlank()) continue
            val cost = LlmUsage.estimate(content)
            if (used + cost > budget && kept.isNotEmpty()) break
            used += cost
            kept.addFirst(LlmMessage.fromStoreRole(message.role, content))
        }
        return listOf(system) + kept
    }

    /**
     * 主动消息的消息列表：同一份人设提示词 + 一段「现在是你想主动找他」的处境说明。
     *
     * 只要求模型写**一句**：主动消息不是文章，太长的开场会像客服。
     */
    fun proactiveMessages(
        persona: Persona,
        mood: Mood?,
        activity: ActivityState?,
        history: List<Message>,
        now: Long,
        historyLimit: Int = 20
    ): List<LlmMessage> {
        val system = buildString {
            append(systemPrompt(persona, mood, activity, now))
            append("\n\n")
            append(PROACTIVE_INSTRUCTION)
        }
        val messages = mutableListOf<LlmMessage>(LlmMessage.system(system))
        history.takeLast(historyLimit.coerceAtLeast(0)).forEach { message ->
            val content = withAttachmentNote(message)
            if (content.isNotBlank()) messages += LlmMessage.fromStoreRole(message.role, content)
        }
        messages += LlmMessage.user("现在你想主动找他说一句。只输出那一句本身。")
        return messages
    }

    // ------------------------------------------------------------------ 工具

    /** 「YYYY-MM-DD 周X」+「HH:mm（UTC±HH:mm）」；`now <= 0` 时返回 null（整行省略）。 */
    private fun clockParts(now: Long): Pair<String, String>? {
        if (now <= 0L) return null
        val zoned = runCatching { Instant.ofEpochMilli(now).atZone(zone) }.getOrNull() ?: return null
        val weekday = WEEKDAYS[zoned.dayOfWeek] ?: zoned.dayOfWeek.name
        val day = zoned.format(YYYY_MM_DD)
        val offset = zoneOffsetLabel(zoned.offset.totalSeconds / 60)
        return "$day $weekday" to "${zoned.format(HH_MM)}（$offset）"
    }

    /** 主动消息/日志共用的一行时间：`2026-11-18 周三 19:40（UTC+08:00）`。 */
    fun clockText(now: Long): String? = clockParts(now)?.let { (day, time) -> "$day $time" }

    private fun formatClock(epochMillis: Long): String =
        runCatching { Instant.ofEpochMilli(epochMillis).atZone(zone).format(HH_MM) }.getOrDefault("")

    private fun availabilityOf(activity: ActivityState?, now: Long): PromptAvailability =
        when (activity?.availabilityAt(now)) {
            ActivityAvailability.BUSY -> PromptAvailability.BUSY
            ActivityAvailability.LIMITED -> PromptAvailability.LIMITED
            else -> PromptAvailability.FREE
        }

    /** 关系段：人设卡里的关系/称呼/你们之间；都没有时交给脚手架默认句。 */
    private fun relationshipOf(card: PersonaFields.Card): String? {
        val lines = buildList {
            if (card.relation.isNotBlank()) add("- 你是对方的${card.relation.take(40)}。")
            if (card.address.isNotBlank()) add("- 你称呼对方「${card.address.take(20)}」。")
            if (card.bond.isNotBlank()) add(card.bond.take(600))
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** 附件说明：模型看不到图片内容，但至少要「知道对方发了东西」（不能让模型假装看见）。 */
    private fun withAttachmentNote(message: Message): String {
        val content = message.content.trim()
        if (message.attachments.isEmpty()) return content
        val note = if (message.role == "USER") {
            "（对方附了 ${message.attachments.size} 个文件/图片，你暂时看不到内容）"
        } else {
            "（这条消息附了 ${message.attachments.size} 个文件/图片）"
        }
        return if (content.isEmpty()) note else "$content\n$note"
    }

    private fun indexOfSection(sections: List<String>, title: String): Int {
        val index = sections.indexOfFirst { it.startsWith(title) }
        // 脚手架保证六段都在；万一将来改版少了某段，退化成「追加到最后」而不是崩。
        return if (index >= 0) index else sections.size - 1
    }

    /**
     * 段标题的**最后一个片段**下标：世界 notes / bond 这类自由文本里可能有空行，
     * 用 `split("\n\n")` 拆完会留下没有标题的续行；追加数据行时要落在它们之后。
     */
    private fun lastFragmentOf(sections: List<String>, title: String): Int {
        var index = indexOfSection(sections, title)
        while (index + 1 < sections.size && !sections[index + 1].startsWith("# ")) index++
        return index
    }

    private fun appendToSection(sections: MutableList<String>, title: String, block: String) {
        if (block.isBlank()) return
        val index = lastFragmentOf(sections, title)
        sections[index] = sections[index] + "\n" + block.trim()
    }

    /** 在某一整段（含续行）之后插入一个独立段。 */
    private fun insertAfterSection(sections: MutableList<String>, title: String, block: String) {
        if (block.isBlank()) return
        sections.add(lastFragmentOf(sections, title) + 1, block.trim())
    }

    private fun zoneOffsetLabel(totalMinutes: Int): String {
        if (totalMinutes == 0) return "UTC"
        val sign = if (totalMinutes < 0) "-" else "+"
        val abs = kotlin.math.abs(totalMinutes)
        return "UTC%s%02d:%02d".format(Locale.ROOT, sign, abs / 60, abs % 60)
    }

    companion object {
        /** 主动消息的额外指令（只写一句、不空泛）。 */
        val PROACTIVE_INSTRUCTION: String = """
            # 现在的情况
            现在不是对方先开口，是你想主动找他说一句。
            - 写一句自然的开场：具体、像日常消息，不要「在吗」「在干嘛」这种空话，不要连着追问。
            - 可以提你正在做的事、刚想到的一个念头，或一句关心。
            - 只输出这一句话本身：不要引号、不要旁白、不要解释、不要换行。
            - 20–60 字，短一点也行。
        """.trimIndent()

        private val HH_MM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
        private val YYYY_MM_DD = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

        private val WEEKDAYS = mapOf(
            DayOfWeek.MONDAY to "周一",
            DayOfWeek.TUESDAY to "周二",
            DayOfWeek.WEDNESDAY to "周三",
            DayOfWeek.THURSDAY to "周四",
            DayOfWeek.FRIDAY to "周五",
            DayOfWeek.SATURDAY to "周六",
            DayOfWeek.SUNDAY to "周日"
        )
    }
}
