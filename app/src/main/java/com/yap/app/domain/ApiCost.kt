package com.yap.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.ZoneId

/**
 * 他花了多少钱，只用纯计算算出来。
 *
 * 这里不碰 Android、不碰数据库、不碰网络：给一组 token 数，返回一个 Long（micro-USD），
 * 这样定价表、取整、月初边界全都能单测。
 *
 * 单位约定：**micro-USD（百万分之一美元）**，用整数存，避免浮点误差越攒越大。
 * 单价表里的数字也是 micro-USD / 百万 tokens（$0.27/百万 tokens = 270_000）。
 */

/** 一次模型调用读了多少、写了多少。 */
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0
) {
    companion object {
        val ZERO = TokenUsage()
    }
}

/** 某类模型的单价，单位：micro-USD / 百万 tokens。 */
data class ModelPrice(
    val inputMicroUsdPerMillion: Long,
    val outputMicroUsdPerMillion: Long
)

/**
 * 一次调用是干什么的。
 *
 * 这些不是给用户看的账单科目，而是「他自己在做哪件事」：
 * 聊天是他陪着你，整理记忆是他收拾自己的记性，联网打分是他判断值不值得记。
 */
object UsageKind {
    const val CHAT = "CHAT"
    const val CONSOLIDATION = "CONSOLIDATION"
    const val SCORING = "SCORING"
    const val AGENT = "AGENT"
    const val RECALL = "RECALL"
    const val EXTRACTION = "EXTRACTION"
    const val RECONSOLIDATION = "RECONSOLIDATION"

    /** 把太长的会话压成一段摘要的过程。 */
    const val HISTORY = "HISTORY"

    /**
     * 启动前把人设/世界设定翻译成 App 语言（`spec/persona-translation/README.md` §6）。
     *
     * 它是一次普通的 completion：不算聊天、不算记忆，单独记一类，
     * 这样「这个月花了多少在他说话上」不会被翻译悄悄摊薄。
     */
    const val TRANSLATION = "TRANSLATION"
    const val UNKNOWN = "UNKNOWN"

    /** 给「用量」页用的展示顺序：先聊天，再他背着你做的事。 */
    val displayOrder: List<String> = listOf(
        CHAT, CONSOLIDATION, SCORING, AGENT, RECALL, EXTRACTION, RECONSOLIDATION, HISTORY,
        TRANSLATION, UNKNOWN
    )

    /** 宽松归一：大写、去空白；不认识的一律归到 UNKNOWN，绝不抛异常。 */
    fun normalize(raw: String?): String {
        val key = raw?.trim()?.uppercase().orEmpty()
        return if (key in displayOrder) key else UNKNOWN
    }

    /**
     * 中文标签。刻意写成「他在做什么」而不是「计费科目」。
     */
    fun label(kind: String?): String = when (normalize(kind)) {
        CHAT -> "聊天"
        CONSOLIDATION -> "整理记忆"
        SCORING -> "判断值不值得记"
        AGENT -> "自己待着时想事情"
        RECALL -> "回忆你说过的事"
        EXTRACTION -> "从聊天里记东西"
        RECONSOLIDATION -> "想起旧事"
        TRANSLATION -> "翻译人设"
        else -> "其他"
    }

    /** 聊天是用户主动发起的，任何时候都不能拦。 */
    fun isChat(kind: String?): Boolean = normalize(kind) == CHAT
}

object ApiCost {

    private const val MICRO_PER_MILLION_TOKENS = 1_000_000L

    /**
     * 不认识的模型用这个保守值：$1.5 / $6 每百万 tokens。
     *
     * 宁可算贵一点让人早点发现「这个月花得有点多」，也别算成 0 让人以为不要钱。
     */
    val UNKNOWN_PRICE = ModelPrice(inputMicroUsdPerMillion = 1_500_000L, outputMicroUsdPerMillion = 6_000_000L)

    /**
     * 定价表：模型名前缀 → 单价。
     *
     * 按前缀长度从长到短匹配，所以 "gpt-4o-mini" 不会被 "gpt-4o" 抢先；
     * 带服务商前缀的 id（如 "openai/gpt-4o-mini"）会在第二圈用 contains 兜住。
     * 价格是公开标价的近似值，只为「心里有数」，不是账单。
     */
    private val PRICE_TABLE: List<Pair<String, ModelPrice>> = listOf(
        // DeepSeek 系：便宜、也最可能被这个 App 的用户使用。
        "deepseek-reasoner" to ModelPrice(550_000L, 2_190_000L),
        "deepseek-chat" to ModelPrice(270_000L, 1_100_000L),
        "deepseek" to ModelPrice(270_000L, 1_100_000L),
        // Claude
        "claude-3-5-haiku" to ModelPrice(800_000L, 4_000_000L),
        "claude-3-5-sonnet" to ModelPrice(3_000_000L, 15_000_000L),
        "claude-3-opus" to ModelPrice(15_000_000L, 75_000_000L),
        "claude-3-haiku" to ModelPrice(250_000L, 1_250_000L),
        "claude" to ModelPrice(3_000_000L, 15_000_000L),
        // OpenAI
        "gpt-4.1-mini" to ModelPrice(400_000L, 1_600_000L),
        "gpt-4.1" to ModelPrice(2_000_000L, 8_000_000L),
        "gpt-4o-mini" to ModelPrice(150_000L, 600_000L),
        "gpt-4o" to ModelPrice(2_500_000L, 10_000_000L),
        "gpt-4-turbo" to ModelPrice(10_000_000L, 30_000_000L),
        "gpt-4" to ModelPrice(30_000_000L, 60_000_000L),
        "gpt-3.5" to ModelPrice(500_000L, 1_500_000L),
        "o1-mini" to ModelPrice(1_100_000L, 4_400_000L),
        "o3-mini" to ModelPrice(1_100_000L, 4_400_000L),
        "o1" to ModelPrice(15_000_000L, 60_000_000L),
        // Gemini
        "gemini-2.0-flash" to ModelPrice(100_000L, 400_000L),
        "gemini-1.5-pro" to ModelPrice(1_250_000L, 5_000_000L),
        "gemini-1.5-flash" to ModelPrice(75_000L, 300_000L),
        "gemini" to ModelPrice(500_000L, 1_500_000L),
        // 国产系
        "qwen" to ModelPrice(400_000L, 1_200_000L),
        "kimi" to ModelPrice(1_700_000L, 1_700_000L),
        "moonshot" to ModelPrice(1_700_000L, 1_700_000L),
        "glm" to ModelPrice(600_000L, 600_000L),
        "doubao" to ModelPrice(110_000L, 280_000L)
    )

    /** 已按前缀长度降序排好，匹配时先长后短。 */
    private val MATCH_ORDER: List<Pair<String, ModelPrice>> =
        PRICE_TABLE.sortedByDescending { it.first.length }

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 把模型名洗成小写、去掉首尾空白；空名返回 null。 */
    private fun normalizeModel(modelName: String?): String? =
        modelName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /**
     * 查内置定价表。先按前缀匹配（长前缀优先），再按包含匹配（兜住 "vendor/model" 这种写法）。
     */
    fun basePriceFor(modelName: String?): ModelPrice {
        val name = normalizeModel(modelName) ?: return UNKNOWN_PRICE
        MATCH_ORDER.firstOrNull { name.startsWith(it.first) }?.let { return it.second }
        MATCH_ORDER.firstOrNull { name.contains(it.first) }?.let { return it.second }
        return UNKNOWN_PRICE
    }

    /** tokens 数乘以单价，四舍五入到 micro-USD。 */
    private fun costOfTokens(tokens: Long, pricePerMillion: Long): Long {
        if (tokens <= 0L || pricePerMillion <= 0L) return 0L
        return (tokens * pricePerMillion + MICRO_PER_MILLION_TOKENS / 2) / MICRO_PER_MILLION_TOKENS
    }

    /**
     * 估算一次调用的成本（micro-USD）。
     *
     * 坏输入不抛异常：负数当成 0，null 模型名走保守默认值。
     */
    fun estimateMicroUsd(price: ModelPrice, usage: TokenUsage): Long {
        val prompt = usage.promptTokens.coerceAtLeast(0).toLong()
        val completion = usage.completionTokens.coerceAtLeast(0).toLong()
        return costOfTokens(prompt, price.inputMicroUsdPerMillion) +
            costOfTokens(completion, price.outputMicroUsdPerMillion)
    }

    /**
     * 估算成本（micro-USD）。
     *
     * 1.9 不再有任何「单价设置 / 额度」：单价只走内置公开标价表，结果只写进 `api_usage` 备查，
     * 界面上不显示钱、也没有任何拦截。留着它是为了未来「用量」页能加一句「大概相当于多少」。
     */
    fun estimateCostMicroUsd(modelName: String?, usage: TokenUsage): Long =
        estimateMicroUsd(basePriceFor(modelName), usage)

    /**
     * 从模型返回的 JSON 里抠 usage。
     *
     * 三种协议一次认全：
     * - OpenAI 兼容：`usage.prompt_tokens / usage.completion_tokens`
     * - Gemini：`usageMetadata.promptTokenCount / candidatesTokenCount / totalTokenCount`
     * - Claude：`usage.input_tokens / usage.output_tokens`
     * 认不出就当 0，不抛异常（记账失败也不能把聊天搞挂）。
     */
    fun parseUsage(rawBody: String?): TokenUsage {
        if (rawBody.isNullOrBlank()) return TokenUsage.ZERO
        val root = runCatching { lenientJson.parseToJsonElement(rawBody) as? JsonObject }.getOrNull()
            ?: return TokenUsage.ZERO
        return parseUsage(root)
    }

    fun parseUsage(root: JsonObject): TokenUsage {
        (root["usage"] as? JsonObject)?.let { usage ->
            val prompt = usage.intOf("prompt_tokens") ?: usage.intOf("input_tokens")
            val completion = usage.intOf("completion_tokens") ?: usage.intOf("output_tokens")
            if (prompt != null || completion != null) {
                return TokenUsage(
                    promptTokens = (prompt ?: 0).coerceAtLeast(0),
                    completionTokens = (completion ?: 0).coerceAtLeast(0)
                )
            }
        }
        (root["usageMetadata"] as? JsonObject)?.let { meta ->
            val prompt = meta.intOf("promptTokenCount")
            val completion = meta.intOf("candidatesTokenCount")
            if (prompt != null || completion != null) {
                return TokenUsage(
                    promptTokens = (prompt ?: 0).coerceAtLeast(0),
                    completionTokens = (completion ?: 0).coerceAtLeast(0)
                )
            }
        }
        return TokenUsage.ZERO
    }

    private fun JsonObject.intOf(key: String): Int? {
        val element: JsonElement = this[key] ?: return null
        val primitive = element as? JsonPrimitive ?: return null
        primitive.intOrNull?.let { return it }
        return primitive.contentOrNull?.trim()?.toDoubleOrNull()?.toInt()
    }

    /**
     * 本月起点（本地时区的自然月 1 号 00:00）。
     *
     * 预算按自然月算，所以跨月那一刻额度自动回来。
     */
    fun monthStart(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now)
            .atZone(zone)
            .toLocalDate()
            .withDayOfMonth(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    /** 下个月起点：跨年也算得对（12 月 → 次年 1 月）。 */
    fun nextMonthStart(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now)
            .atZone(zone)
            .toLocalDate()
            .withDayOfMonth(1)
            .plusMonths(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

}
