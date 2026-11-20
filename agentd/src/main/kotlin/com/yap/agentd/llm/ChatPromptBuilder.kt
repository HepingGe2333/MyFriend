package com.yap.agentd.llm

import com.yap.agentd.context.ContextAssembler
import com.yap.agentd.mood.MoodRules
import com.yap.agentd.persona.PersonaFields
import com.yap.agentd.proactive.ActivityState
import com.yap.agentd.proactive.ActivityAvailability
import com.yap.agentd.store.MentalActivity
import com.yap.agentd.store.Message
import com.yap.agentd.store.Mood
import com.yap.agentd.store.Persona
import com.yap.agentd.store.SessionMemory
import com.yap.app.domain.PersonaPromptScaffold
import com.yap.app.domain.PromptAvailability
import com.yap.app.domain.PromptContract
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
 * 服务端系统提示词拼装 v2（0.15 上下文/记忆 v2，`docs/design/context-memory-v2.md`）。
 *
 * **规范中文只来自 `:agentcore`**：段标题、行模板、边界四条、常用语与世界的渲染规则都在
 * 共享脚手架/契约里（Android / iOS / 服务端调的是同一份）。这里只做两件事：
 *
 * 1. **喂数据**：人格（名字/年龄/职业/世界/话风/关系/四组值）、此刻（时间/占用时间/睡眠）、
 *    多维心情、会话记忆（长期/短期/主题）、最近一次心理活动——全部来自服务端自己的存储；
 * 2. **按 v2 的固定顺序装配**：
 *    `完整设定+工具（身份 → 世界 → 话风 → 关系 → 你能做什么 → 边界）→ 长期记忆 → 短期记忆
 *     → 当前状态（此刻 + 心情）→ 对话主题 → 他刚才心里想 → 剩余预算的最近历史`。
 *
 * 预算与裁剪**全部交给 [ContextAssembler]**（`ExperienceContract.practicalPlan()`）：
 * 本文件不再自己算「历史条数/字符」，也不再把记忆拼成 0.14 的「最近你记得：…」——
 * 段标题统一成 `# 你的备忘录` / `## 长期记忆` / `## 短期记忆` / `## 对话主题`。
 *
 * 为什么「此刻/心情」要从脚手架里拆出来再插回去：脚手架按「他是谁」的顺序排六段，
 * 而 v2 要求**当前状态排在记忆之后**（先看长期/短期记忆，再看此刻，最后看主题）。
 * 硬改脚手架会同时影响 Android/iOS 的提示词，所以这里只做段重组，文案一字不改。
 */
class ChatPromptBuilder(
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** 「你能做什么」段；测试可以传一段固定文本，生产永远来自工具注册表。 */
    private val capabilities: String = com.yap.agentd.tools.ServerToolCatalog.capabilityPrompt(),
    /** v2 上下文装配器（预算/顺序/裁剪的唯一实现）。 */
    private val assembler: ContextAssembler = ContextAssembler()
) {

    /**
     * 完整系统提示词（聊天用）。
     *
     * @param activity 「他此刻在做什么」（`personas.activityJson`）；过期/没有时写「没在忙什么」
     * @param mood 多维心情；`null` = 没有记录，整段省略
     * @param sessionMemory 会话记忆（长期/短期/主题）；`null`/空整段省略
     * @param mentalActivity 最近一次心理活动（「你刚才心里想：…」）；过旧时调用方不传
     */
    fun systemPrompt(
        persona: Persona,
        mood: Mood?,
        activity: ActivityState?,
        now: Long,
        sessionMemory: SessionMemory? = null,
        mentalActivity: MentalActivity? = null
    ): String = build(
        persona = persona,
        mood = mood,
        activity = activity,
        now = now,
        sessionMemory = sessionMemory,
        mentalActivity = mentalActivity,
        history = emptyList()
    ).system

    /** 聊天请求的消息列表：`system` + 预算内的最近历史（升序，含本轮用户消息）。 */
    fun chatMessages(
        persona: Persona,
        mood: Mood?,
        activity: ActivityState?,
        history: List<Message>,
        now: Long,
        /**
         * 历史条数的**上限**（默认不限）：v2 的「剩余预算全部给最近聊天原文」由
         * [ContextAssembler] 按字符预算决定，不再用一个固定的 40 条把长上下文截短。
         * 传更小的值仍然有效（主动消息/测试想只带几条时用）。
         */
        historyLimit: Int = Int.MAX_VALUE,
        sessionMemory: SessionMemory? = null,
        mentalActivity: MentalActivity? = null
    ): List<LlmMessage> {
        val selected = history.takeLast(historyLimit.coerceAtLeast(1))
        val context = build(
            persona = persona,
            mood = mood,
            activity = activity,
            now = now,
            sessionMemory = sessionMemory,
            mentalActivity = mentalActivity,
            history = selected.map { message ->
                ContextAssembler.HistoryMessage(
                    role = message.role,
                    content = withAttachmentNote(message)
                )
            }
        )
        return listOf(LlmMessage.system(context.system)) +
            context.history.map { LlmMessage.fromStoreRole(it.role, it.content) }
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
        historyLimit: Int = 20,
        sessionMemory: SessionMemory? = null,
        mentalActivity: MentalActivity? = null
    ): List<LlmMessage> {
        val context = build(
            persona = persona,
            mood = mood,
            activity = activity,
            now = now,
            sessionMemory = sessionMemory,
            mentalActivity = mentalActivity,
            history = history.takeLast(historyLimit.coerceAtLeast(0)).map { message ->
                ContextAssembler.HistoryMessage(
                    role = message.role,
                    content = withAttachmentNote(message)
                )
            }
        )
        val system = context.system + "\n\n" + PROACTIVE_INSTRUCTION
        val messages = mutableListOf<LlmMessage>(LlmMessage.system(system))
        context.history.forEach { messages += LlmMessage.fromStoreRole(it.role, it.content) }
        messages += LlmMessage.user("现在你想主动找他说一句。只输出那一句本身。")
        return messages
    }

    // ------------------------------------------------------------------ 装配

    /** 装配一次上下文（前五段 + 历史选择都在 [ContextAssembler] 里）。 */
    private fun build(
        persona: Persona,
        mood: Mood?,
        activity: ActivityState?,
        now: Long,
        sessionMemory: SessionMemory?,
        mentalActivity: MentalActivity?,
        history: List<ContextAssembler.HistoryMessage>
    ): ContextAssembler.Result {
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
        // 「此刻的状态」要挪到记忆之后（v2 固定顺序）：先原样摘出来，最后再放回去。
        val stateSection = removeSection(sections, PersonaPromptScaffold.Section.STATE)

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

        // 「你能做什么」：规范 §1.3 固定在关系之后、边界之前；内容来自工具注册表。
        insertAfterSection(sections, PersonaPromptScaffold.Section.RELATIONSHIP, capabilities)

        // 「别像 AI」：七条共享规则 + 0.15 的服务端补充（评测里反复出现的旁白/动作描写）。
        insertAfterSection(
            sections,
            PersonaPromptScaffold.Section.BOUNDARIES,
            PromptContract.AntiAi.section() + "\n" + SERVER_ANTI_NARRATION_RULES
        )

        // ---- 第 4 段：当前状态（此刻 + 睡眠 + 心情直觉标签与三维）
        val currentState = buildString {
            if (stateSection.isNotBlank()) append(stateSection)
            // 睡眠/安静时段：`set_quiet_hours` 写下的作息；跨夜判断由 QuietHours 负责，这里只展示。
            PersonaFields.quietHours(persona)?.let { quiet ->
                if (isNotEmpty()) append("\n")
                append("- 你的作息：${quiet.text()}（这段时间想安静，除非被叫醒）")
            }
            if (mood != null && mood.label.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(MoodRules.promptSection(MoodRules.Values.from(mood)))
            }
        }

        val personaAndTools = sections
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")

        return assembler.assemble(
            ContextAssembler.Input(
                personaAndTools = personaAndTools,
                longTerm = sessionMemory?.longSummary,
                shortTerm = sessionMemory?.shortSummary,
                currentState = currentState,
                topics = sessionMemory?.topics.orEmpty(),
                mentalActivity = mentalActivity?.thought?.takeIf { it.isNotBlank() },
                history = history
            )
        )
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

    /** 把某一整段（含续行）从列表里摘出来，返回它的原文（不存在时返回空串）。 */
    private fun removeSection(sections: MutableList<String>, title: String): String {
        val start = sections.indexOfFirst { it.startsWith(title) }
        if (start < 0) return ""
        var end = start
        while (end + 1 < sections.size && !sections[end + 1].startsWith("# ")) end++
        val text = sections.subList(start, end + 1).joinToString("\n\n")
        repeat(end - start + 1) { sections.removeAt(start) }
        return text.trim()
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

        /** 心情段的标题（共享契约 `PromptContract.Section.MOOD`）。 */
        const val MOOD_SECTION = PromptContract.Section.MOOD

        /** 心理活动段的标题（0.15 起与共享契约一致）。 */
        const val MENTAL_SECTION = PromptContract.Section.MENTAL_ACTIVITY

        /** 会话备忘录段的标题（共享契约 `MemoryContract.Injection.TITLE`）。 */
        const val MEMO_SECTION = PromptContract.Section.MEMO

        /**
         * 0.15 评测补充的反旁白规则（挂在共享的「# 别像 AI」段末尾）。
         *
         * 真实问题：模型会把动作/神态写成旁白（「他沉默了一会儿」「顿了顿」），
         * 一条消息里出现两三次就像小说旁白，不像聊天。这里只加**服务端**的三条：
         * 共享七条（`PromptContract.AntiAi.RULES`）不改，避免影响两端已经对齐的文案。
         */
        val SERVER_ANTI_NARRATION_RULES: String = listOf(
            "- 不要写旁白、动作或神态（「沉默了一会儿」「顿了顿」「笑了笑」「叹了口气」「（他沉默）」）——只写说出口的话。",
            "- 不要用括号补充动作或心理；想表达情绪就靠措辞、标点和长短句。",
            "- 一条消息里只写你要说的话，不要「他说：」这种第三人称转述。"
        ).joinToString("\n")

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
