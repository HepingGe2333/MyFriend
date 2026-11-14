package com.myfriend.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ProviderType { OPENAI, GEMINI, CLAUDE }

enum class MessageRole { USER, ASSISTANT, SYSTEM }

enum class MessageStatus { COMPLETE, STOPPED, ERROR }

enum class MemoryCategory { FACT, PREFERENCE, EVENT, RELATIONSHIP, OTHER }

/** 主动唤醒的触发方式：每天固定时刻，或每隔 N 小时。 */
enum class ProactiveKind { DAILY, INTERVAL }

/** 规则来源：由对话中的 AI 提议创建，或用户手动创建。 */
enum class ProactiveSource { AI, MANUAL }

@Entity(
    tableName = "personas",
    indices = [Index(value = ["isDefault"]), Index(value = ["updatedAt"])]
)
data class PersonaEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val avatarUri: String?,
    val avatarSeed: Int,
    val prompt: String,
    val category: String,
    val version: Int,
    val defaultApiConfigId: String?,
    val modelOverride: String?,
    val isDefault: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    /** 结构化人设卡 JSON（PersonaCardCodec），为空表示还在用旧的一整段提示词。 */
    val personaCardJson: String? = null,
    /** 他自己安排的作息：几点睡、几点醒（距零点分钟数），空表示不设。 */
    val quietSleepMinutes: Int? = null,
    val quietWakeMinutes: Int? = null,
    /** 此刻的心情：一个词 + 强度 + 原因 + 上次变化时间。 */
    val mood: String? = null,
    val moodIntensity: Int? = null,
    val moodReason: String? = null,
    val moodUpdatedAt: Long? = null,
    /** 上次被引导着回看心情的时间，避免同一句话反复追问。 */
    val moodNudgedAt: Long? = null,

    // ---- 1.9 联系人细分字段（全部可选，老数据给安全默认值） ------------------
    /** 性格，JSON 数组，例如 ["慢热","嘴硬心软"]，最多 6 个。 */
    @ColumnInfo(defaultValue = "[]")
    val traits: String = "[]",
    /** 喜欢的事，JSON 数组，最多 8 个。 */
    @ColumnInfo(defaultValue = "[]")
    val likes: String = "[]",
    /** 行为方式，JSON 数组，最多 6 个。 */
    @ColumnInfo(defaultValue = "[]")
    val behaviors: String = "[]",
    /** 成长经历 / 过去，最多 600 字。 */
    val backstory: String? = null,
    /** 个人设定：称呼、口头禅、忌讳，最多 300 字。 */
    val quirks: String? = null,
    /** 主动程度 1–5：1 很少主动，5 很黏。它同时决定每日主动上限与心跳权重。 */
    @ColumnInfo(defaultValue = "3")
    val proactiveLevel: Int = 3,
    /** 记性 1–5：1 忘得快，5 什么都记得。进上下文的行为参数，不进提示词正文。 */
    @ColumnInfo(defaultValue = "3")
    val memoryRetention: Int = 3,
    /** 每多少条消息抽一次记忆（3–12）。 */
    @ColumnInfo(defaultValue = "6")
    val extractionEvery: Int = 6,
    /** 他会不会主动睡觉（关掉 = 任何时间都会回）。 */
    @ColumnInfo(defaultValue = "1")
    val sleepEnabled: Boolean = true,
    /** 睡眠中被唤醒需要的连续消息数（1–20）。 */
    @ColumnInfo(defaultValue = "6")
    val wakeThreshold: Int = 6,
    /** 睡前是否开启免打扰。由模型在动作里改，用户不直接改。 */
    @ColumnInfo(defaultValue = "0")
    val dndOnSleep: Boolean = false
)

@Entity(
    tableName = "persona_revisions",
    indices = [Index(value = ["personaId", "version"], unique = true)]
)
data class PersonaRevisionEntity(
    @PrimaryKey val id: String,
    val personaId: String,
    val version: Int,
    val name: String,
    val description: String,
    val avatarUri: String?,
    val avatarSeed: Int,
    val prompt: String,
    val category: String,
    val reason: String,
    val createdAt: Long
)

@Entity(tableName = "api_configs", indices = [Index(value = ["updatedAt"])])
data class ApiConfigEntity(
    @PrimaryKey val id: String,
    val name: String,
    val provider: ProviderType,
    val baseUrl: String,
    val apiKeyCipher: String?,
    val model: String,
    val customHeadersJson: String,
    val allowInsecureHttp: Boolean,
    val temperature: Double?,
    val topP: Double?,
    val maxOutputTokens: Int?,
    val frequencyPenalty: Double?,
    val presencePenalty: Double?,
    val createdAt: Long,
    val updatedAt: Long,
    /** 是否让这个配置启用联网工具（DeepSeek 官方 Search / Fetch）。 */
    @ColumnInfo(defaultValue = "0")
    val webToolsEnabled: Boolean = false,
    /** 联网工具地址，留空用默认 https://api.deepseek.com。 */
    @ColumnInfo(defaultValue = "")
    val webToolsBaseUrl: String = "",
    /** 联网工具专用的密钥（加密存储）；留空则复用主 Key。 */
    val webToolsApiKeyCipher: String? = null
)

/**
 * 一个人的那一段会话。
 *
 * 1.9 起「一人一段」：`personaId` 上必须是唯一索引。用户不再能新建会话，
 * 界面上也不再分「人格」和「最近对话」，而是直接点一个联系人进他的那一段。
 */
@Entity(
    tableName = "chat_sessions",
    indices = [
        Index(name = "index_chat_sessions_personaId_unique", value = ["personaId"], unique = true),
        Index(value = ["lastMessageAt"]),
        Index(value = ["pinned"])
    ]
)
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val personaId: String,
    val apiConfigId: String?,
    val model: String,
    val title: String,
    val lastMessagePreview: String?,
    val lastMessageAt: Long?,
    val unreadCount: Int,
    val pinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastPersonaVersion: Int,
    val lastMemoryExtractedCount: Int,
    /** 早期对话压成的一段摘要：太长的历史不逐字塞进上下文，只留这段「我记得的」。 */
    val historySummary: String? = null,
    /** 摘要覆盖到哪条消息为止（该消息及更早的已被压缩）。 */
    val historySummarizedUpTo: Long? = null,
    val historySummaryAt: Long? = null,
    /** 会话级覆盖：为空表示继承人格的记性。 */
    val memoryRetentionOverride: Int? = null,
    /** 会话级覆盖：为空表示继承人格的抽记忆频率。 */
    val extractionEveryOverride: Int? = null
)

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["sessionId", "createdAt"]),
        Index(value = ["sessionId", "role"]),
        Index(value = ["createdAt"])
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val personaId: String,
    val role: MessageRole,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: MessageStatus,
    val errorMessage: String?,
    val model: String?,
    val edited: Boolean,
    /** JSON 数组：[{"uri":"file:///...","mime":"image/jpeg"}]，为空表示纯文本消息。 */
    val attachmentsJson: String? = null,
    /** 这轮他调用过哪些工具（JSON 数组），用于在气泡下展示「查了 2 个网页」。 */
    val toolTraceJson: String? = null,
    /**
     * 模型真正看到这条消息的时刻；只有用户消息会写。
     *
     * 已读不等于会回（见 defer_reply）：界面上只在「这一轮没有助手回复」时显示「已读」，
     * 免得每条消息都挂一个小字，那会像回执系统，而不像一个人。
     */
    val readAt: Long? = null
)

@Entity(tableName = "memories", indices = [Index(value = ["personaId"]), Index(value = ["personaId", "importance"]), Index(value = ["updatedAt"])])
data class MemoryEntity(
    @PrimaryKey val id: String,
    val personaId: String,
    val content: String,
    val category: MemoryCategory,
    val importance: Int,
    val pinned: Boolean,
    val sourceSessionId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long,
    val useCount: Int,
    /** 记忆抽屉：SELF / USER / EVENT；为空时按 category 推断。 */
    val drawer: String? = null,
    /** 触发这条记忆的原话片段，用于「这条记忆是怎么来的」。 */
    val sourceQuote: String? = null,
    /** 记忆状态：ACTIVE 还在用 / DORMANT 沉下去了 / ARCHIVED 已归档（不再进上下文）。 */
    val status: String = "ACTIVE",
    /** 还剩多少分量：时间和整理都会让它变小，先掉到阈值以下的人先被归档。 */
    val strength: Double = 1.0,
    /** 归档时间；为空表示还在用。 */
    val archivedAt: Long? = null,
    /** 上次被每日整理过的时间。 */
    val consolidatedAt: Long? = null,
    /** 结构化标签（JSON 数组）：人、事、物、情绪，让「他妹妹那件事」能被找回来。 */
    val tags: String? = null,
    /** 这件事发生的时间（不是被记住的时间）；推断不出来就空着。 */
    val happenedAt: Long? = null,
    /** 回忆重构：第一次被写下来时的原文。内容会随回忆漂移，这里是锚点。 */
    val originalContent: String? = null,
    /** 被重新回忆过几次——回忆本身会改写它。 */
    val driftCount: Int = 0
)

/**
 * 一次「每日记忆整理」的留痕。
 *
 * 整理本身跑在一个用完即弃的分叉会话里，这里只记结果：谁被留下、谁被并进别人、
 * 谁被移出上下文、谁被彻底忘了。界面上据此显示「他最近整理过记忆」。
 */
@Entity(
    tableName = "memory_consolidations",
    indices = [Index("personaId", "at"), Index("at")]
)
data class MemoryConsolidationEntity(
    @PrimaryKey val id: String,
    val personaId: String,
    val at: Long,
    val kept: Int,
    val merged: Int,
    val archived: Int,
    val forgotten: Int,
    val summary: String,
    val modelName: String? = null
)

@Entity(
    tableName = "persona_presets",
    indices = [Index(value = ["updatedAt"]), Index(value = ["category"])]
)
data class PersonaPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val prompt: String,
    val category: String,
    val avatarUri: String?,
    val avatarSeed: Int,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "proactive_rules",
    indices = [
        Index(value = ["personaId"]),
        Index(value = ["nextTriggerAt"]),
        Index(value = ["enabled"])
    ]
)
data class ProactiveRuleEntity(
    @PrimaryKey val id: String,
    val personaId: String,
    val sessionId: String?,
    val kind: ProactiveKind,
    val timeOfDayMinutes: Int?,
    val intervalMinutes: Int?,
    val hint: String,
    val note: String,
    val enabled: Boolean,
    val pending: Boolean,
    val source: ProactiveSource,
    val nextTriggerAt: Long,
    val lastTriggeredAt: Long?,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/** 一条「想联系你」的意向：只有时间窗口，没有准点。 */
enum class AgentIntentKind { REACH_OUT, FOLLOW_UP }

enum class AgentIntentStatus { PENDING, SENT, DROPPED, EXPIRED }

/**
 * 他想在某个时间段来找你。
 *
 * 和 [ProactiveRuleEntity] 的区别：规则是「节奏偏好」（每天/每几小时），
 * 意向是「这一次具体的念头」，带一个 earliestAt–latestAt 的窗口 —— 人不会准点，
 * 只会在「差不多那会儿」想起你。窗口过期就作废，不需要谁去清理。
 */
@Entity(
    tableName = "agent_intents",
    indices = [Index(value = ["personaId", "status"]), Index(value = ["earliestAt"]), Index(value = ["latestAt"])]
)
data class AgentIntentEntity(
    @PrimaryKey val id: String,
    val personaId: String,
    val sessionId: String?,
    val kind: AgentIntentKind,
    val earliestAt: Long,
    val latestAt: Long,
    val note: String,
    val hint: String,
    val status: AgentIntentStatus,
    val ruleId: String? = null,
    val createdAt: Long,
    val sentAt: Long? = null,
    val lastError: String? = null
)

/**
 * 他每一次「想」的留痕。
 *
 * 用来保证两件事：一是进程被杀之后还能接着来（状态在库里），
 * 二是用户可以看见他到底在想什么、做了什么，而不是一个黑箱推送机。
 */
@Entity(tableName = "agent_ticks", indices = [Index(value = ["personaId", "at"]), Index(value = ["at"])])
data class AgentTickEntity(
    @PrimaryKey val id: String,
    val personaId: String,
    val at: Long,
    val thought: String,
    val actionJson: String,
    val outcome: String,
    val messageId: String? = null
)

/**
 * 只被提到过一次的候选记忆（回声）。同一件事第二次出现才由 [MemoryScreening] 提升为长期记忆。
 */
@Entity(tableName = "memory_echoes", indices = [Index(value = ["personaId", "drawer"]), Index(value = ["createdAt"])])
data class MemoryEchoEntity(
    @PrimaryKey val id: String,
    val personaId: String,
    val drawer: String,
    val content: String,
    val importance: Int,
    val sourceQuote: String,
    val categoryName: String,
    val confidence: Float,
    val createdAt: Long,
    val lastSeenAt: Long
)

/** 历史遗留的「等确认」人设补丁；1.3 起 AI 改自己直接生效，这张表只为兼容旧数据。 */
@Entity(tableName = "persona_pending_patches", indices = [Index(value = ["personaId"]), Index(value = ["createdAt"])])
data class PersonaPendingPatchEntity(
    @PrimaryKey val id: String,
    val personaId: String,
    val sessionId: String?,
    val patchJson: String,
    val summary: String,
    val reason: String,
    val createdAt: Long
)

data class MessageSearchRow(
    val messageId: String,
    val sessionId: String,
    val sessionTitle: String,
    val personaId: String,
    val personaName: String,
    val personaAvatarUri: String?,
    val personaAvatarSeed: Int,
    val content: String,
    val createdAt: Long
)

/**
 * 一次模型调用的 token 流水。
 *
 * 1.9 起不再有任何额度、单价或拦截：这一行只用来告诉用户「这个月他说了多少话、都花在什么事上」。
 * 记在本机，不上传；[sessionId] 是「按人看用量」的唯一依据（老数据可能为空）。
 */
@Entity(
    tableName = "api_usage",
    indices = [Index("at"), Index("kind", "at"), Index("personaId", "at"), Index("sessionId", "at")]
)
data class ApiUsageEntity(
    @PrimaryKey val id: String,
    val at: Long,
    /** 哪一类调用：CHAT / CONSOLIDATION / SCORING / AGENT / RECALL / EXTRACTION / RECONSOLIDATION / HISTORY */
    val kind: String,
    val personaId: String? = null,
    val sessionId: String? = null,
    val modelName: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** 估算成本（micro-USD），只作内部参考；界面上只显示 token，不显示钱。 */
    val costMicroUsd: Long = 0
)

/**
 * 「按会话（= 按人）」汇总的用量行。
 *
 * 设置页只显示前 10 个，名字取人格名；会话已经被删掉的历史行归到「未知联系人」。
 */
data class UsageBySessionRow(
    val sessionId: String?,
    val personaName: String?,
    val calls: Int,
    val promptTokens: Long,
    val completionTokens: Long
)

/**
 * 记忆的中文倒排索引：字二元组 → 记忆。
 *
 * 中文没有分词，靠 LIKE 只能整串匹配；拆成二元组之后，「项目压力」也能命中「项目压得我睡不着」。
 * 由 MemoryRepository 在写入/更新/删除时维护，查不到就退回 LIKE，坏数据不影响主流程。
 */
@Entity(
    tableName = "memory_terms",
    primaryKeys = ["memoryId", "term"],
    indices = [Index("term")]
)
data class MemoryTermEntity(
    val memoryId: String,
    val term: String,
    val weight: Double = 1.0
)

/**
 * 回忆重构的留痕：每次他「想起」一件事，措辞可能被当时的心情改写一点。
 *
 * [content] 是这次改完的样子，[victimContent] 是改之前；不改就都不写。
 */
@Entity(
    tableName = "memory_versions",
    indices = [Index("memoryId", "at")]
)
data class MemoryVersionEntity(
    @PrimaryKey val id: String,
    val memoryId: String,
    val at: Long,
    val content: String,
    val beforeContent: String? = null,
    val reason: String? = null,
    val moodLabel: String? = null
)

/**
 * 本地向量索引：一条记忆一个向量。
 *
 * 中文换一种说法（「项目压力」vs「项目压得睡不着」）时，字面匹配会漏，向量余弦能接住。
 * 向量由内置的本地嵌入（字 n-gram 哈希 + IDF 加权，纯 Kotlin、零依赖、不联网）算出，
 * 存在这里只为一件事：重启后不必重算。dim 一起存，方便以后换模型时按维度淘汰旧向量。
 */
@Entity(
    tableName = "memory_vectors",
    primaryKeys = ["memoryId", "provider"],
    indices = [Index("provider")]
)
data class MemoryVectorEntity(
    val memoryId: String,
    /** 嵌入实现的名字（换模型时旧向量自然失效，不会混用）。 */
    val provider: String,
    val dim: Int,
    /** L2 归一化后的 float32 小端字节。 */
    val vector: ByteArray,
    val updatedAt: Long
) {
    // ByteArray 的 equals/hashCode 要手写，否则数据类比较的是引用。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryVectorEntity) return false
        return memoryId == other.memoryId && provider == other.provider &&
            dim == other.dim && vector.contentEquals(other.vector) && updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = memoryId.hashCode()
        result = 31 * result + provider.hashCode()
        result = 31 * result + dim
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}
