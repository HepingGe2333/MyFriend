package com.yap.app.data.repo

import com.yap.app.data.local.ProactiveKind
import com.yap.app.domain.ProactiveProposal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 「主动唤醒」的对话协议。
 *
 * 客户端在系统消息里说明能力，模型如果想建立主动联系的节奏，就输出一个 yap-wake 代码块。
 * 0.12 更名前的 `myfriend-wake` 仅作为旧版模型输出/跨端客户端的兼容标记继续解析，不再写进提示词。
 *
 * ```yap-wake
 * {"kind":"daily","time":"22:30","note":"每晚睡前问候","hint":"轻声问对方今天过得怎么样"}
 * ```
 *
 * 客户端解析后从正文里去掉代码块，规则直接生效；聊天里只留一句提示，主动唤醒页可随时关掉。
 */
object ProactiveProtocol {

    const val BLOCK_LANGUAGE = "yap-wake"

    /**
     * 兼容常量：0.12 更名前客户端/模型输出使用的协议块标记。
     *
     * 只用于解析旧输出，不写进系统提示词；改名后模型应输出 [BLOCK_LANGUAGE]。
     * 保留它是为了避免升级后旧会话/旧模型响应里的主动唤醒提案变成可见正文。
     */
    const val LEGACY_BLOCK_LANGUAGE = "myfriend-wake"

    val CAPABILITY_PROMPT: String = """
        ## 你可以主动联系对方
        你可以为这段关系设定「主动唤醒」节奏：到了设定时间，应用会替你主动开口，这条消息由你按人格与本段对话的风格写出来。
        当对方希望你有规律地主动找他时（例如「每天晚上十点半来问我一句」「每隔几小时来陪我一下」），在回复末尾追加一个代码块，格式如下：

        ```$BLOCK_LANGUAGE
        {"kind":"daily","time":"22:30","note":"每晚睡前问候","hint":"像往常一样轻声问对方今天过得怎么样"}
        ```

        字段说明：
        - kind：daily（每天固定时刻）或 interval（每隔一段时间）
        - time：daily 时必填，24 小时制 HH:mm
        - intervalHours：interval 时必填，1-24 的整数小时
        - note：一句话说明这条规则，会展示在规则列表里
        - hint：到点时要怎么写这条消息的指引，用对你人格口吻的描述

        这个设定**不需要对方批准，会直接生效**，之后你会按这个节奏自己来找他；所以只在他明确表达过这种意愿时设定一次，别自作主张。
        时间不用精确：应用会在这个时刻前后浮动一段时间，你出现得更像人而不是闹钟。
        不要在普通闲聊里添加这个代码块，也不要承诺代码块以外的定时能力。
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val blockRegex = Regex(
        "```\\s*(?:$BLOCK_LANGUAGE|$LEGACY_BLOCK_LANGUAGE)\\s*\\n?([\\s\\S]*?)```",
        setOf(RegexOption.IGNORE_CASE)
    )

    /** 从模型输出中取出提案，并返回去掉代码块后的正文。 */
    fun extract(rawText: String): Pair<String, ProactiveProposal?> {
        val match = blockRegex.find(rawText) ?: return rawText.trim() to null
        val payload = match.groupValues.getOrNull(1)?.trim().orEmpty()
        val cleaned = rawText.removeRange(match.range).trim()
        val proposal = parse(payload) ?: return cleaned to null
        return cleaned to proposal
    }

    fun parse(payload: String): ProactiveProposal? {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val kindText = root.string("kind")?.lowercase()
        val note = root.string("note")?.take(120).orEmpty()
        val hint = root.string("hint")?.take(600).orEmpty()
        return when (kindText) {
            "daily", "daily_time", "daily_time_of_day" -> {
                val minutes = parseTimeOfDay(root.string("time")) ?: return null
                ProactiveProposal(
                    kind = ProactiveKind.DAILY,
                    timeOfDayMinutes = minutes,
                    intervalMinutes = null,
                    // 默认说明/指引由仓储层按界面语言补（它们会落库、也会显示在规则列表里），
                    // 这里不再拼中文；模型自己写了就用模型的原文。
                    note = note,
                    hint = hint
                )
            }
            "interval", "every", "interval_hours" -> {
                val hours = root.int("intervalHours") ?: root.int("interval_hours") ?: return null
                if (hours !in 1..24) return null
                ProactiveProposal(
                    kind = ProactiveKind.INTERVAL,
                    timeOfDayMinutes = null,
                    intervalMinutes = hours * 60,
                    note = note,
                    hint = hint
                )
            }
            else -> null
        }
    }

    fun parseTimeOfDay(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().replace('：', ':')
        val parts = cleaned.split(':')
        if (parts.size != 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    fun formatTimeOfDay(minutes: Int): String {
        val safe = ((minutes % 1440) + 1440) % 1440
        return "%02d:%02d".format(safe / 60, safe % 60)
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
}
