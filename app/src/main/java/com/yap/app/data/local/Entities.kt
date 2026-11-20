package com.yap.app.data.local

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
    /**
     * 他会不会主动睡觉（关掉 = 任何时间都会回）。
     *
     * 数据类默认值保留 `true`（既有解码 / 迁移路径都按它走，改默认会让老库语义变味）；
     * 「新建人格」与「种子人格」在各自的入口显式给 `false` —— 睡眠是可选功能，
     * 用户在人格编辑里打开才算数（0.12 续 7）。
     */
    @ColumnInfo(defaultValue = "1")
    val sleepEnabled: Boolean = true,
    /**
     * 睡眠中被唤醒需要的连续消息数（1–20）。
     *
     * 默认 6 会让夜里发 1–5 条都只是「已读」，看起来像「发什么都没反应」；0.12 续 7 起新建/种子
     * 人格用 3。`@ColumnInfo(defaultValue)` 保持 6：那是已发布 schema 的列默认值，
     * 改它等于要求一次数据库迁移，而这里只是新建行的代码默认值。
     */
    @ColumnInfo(defaultValue = "6")
    val wakeThreshold: Int = 3,
    /** 睡前是否开启免打扰。由模型在动作里改，用户不直接改。 */
    @ColumnInfo(defaultValue = "0")
    val dndOnSleep: Boolean = false,

    // ---- 0.12 人设引导（schema v13） -----------------------------------------
    /** 世界预设 JSON（[com.yap.app.domain.WorldPresetCodec]），为空表示没有世界预设。 */
    val worldJson: String? = null,
    /** 话风 JSON（[com.yap.app.domain.SpeechStyleCodec]），为空表示没学过话风。 */
    val speechStyleJson: String? = null,
    /** 他此刻在做什么（[com.yap.app.domain.PersonaActivityCodec]）；到点自动失效。 */
    val activityJson: String? = null,

    // ---- 0.12 人格提示词翻译缓存（schema v14） --------------------------------
    /**
     * 翻译后的人设/世界 JSON（[com.yap.app.domain.PersonaTranslationCodec]），
     * 含 `lang` / `translatedAt` / `sourceHash`。为空表示还没翻译过。
     *
     * 只是缓存：源字段一变（`sourceHash` 对不上）或目标语言变了就重译；
     * 备份恢复时如果 `lang` 与当前不符，直接忽略它。
     */
    val translationJson: String? = null,
    /** 上面那份缓存对应的语言（BCP-47：`zh` / `en` …）；为空表示没有缓存。 */
    val translationLang: String? = null,

    // ---- 0.13 多维情绪（schema v17，`spec/mood/README.md` §1） ------------------
    /**
     * 愉快度：-2 很不悦 … 0 平静 … 2 很愉快。
     *
     * 与 `mood`（标签）、`moodIntensity`（强度）是同一份情绪的三个视角：标签给人看，
     * 三个维度给模型看——「累」和「烦」都可能强度 3，但唤醒度与掌控感完全不同，
     * 语气应该不一样。可空是为了让 v16 的老库升级后读出来是 NULL（由代码按旧标签映射），
     * 而不是被塞一个「看起来有值、其实是猜的」数字。
     */
    val moodValence: Int? = null,

    /** 唤醒度：0 很低（困/没劲）… 5 很高（激动）。 */
    val moodArousal: Int? = null,

    /** 掌控感：0 被压住 … 3 一般 … 5 很有把握。 */
    val moodDominance: Int? = null,

    // ---- 0.14 拉黑（schema v18，`spec/experience/README.md` §2） ----------------
    /**
     * 他主动把对方拉黑到什么时候（毫秒时间戳）；null = 没拉黑。
     *
     * 到期不通知、不补看：读路径只认 `blockedUntil > now`，到点自动失效；
     * 另有一个清理查询把过期值写回 NULL，免得界面一直显示「已拉黑」。
     */
    val blockedUntil: Long? = null,
    /** 拉黑原因（只给模型/调试看，不展示给对方）。 */
    val blockReason: String? = null,

    // ---- 0.15 人格提示词语言（schema v19） --------------------------------------
    /**
     * 这个人格用什么语言思考和说话（BCP-47 主标签：`zh` / `en` / `ja` …）。
     *
     * 与界面语言无关：用户把 App 切成英文，中文人格仍然说中文。
     * null = 没选过，按产品默认中文（[com.yap.app.domain.PromptLanguage.ZH]）。
     * 只影响提示词里的规则段与语言指令；人设正文是用户自己写的数据，不在这里翻译。
     */
    val languageTag: String? = null,
    /**
     * 还没生效的 `block_user` 提议（JSON；`spec/experience/README.md` §9 的七个固定字段：
     * `proposalId/personaId/hours/reason/userMessageId/proposedAt/expiresAt`）。
     *
     * 规范要求两轮确认：第一次调用只写这里，10 分钟过期；只有之后的一轮（`userMessageId`
     * 已经不同）再 `confirm=true` 才写 [blockedUntil]。单独一列比另建表更轻，
     * 也不会出现「提议表有孤儿人格」。老库里的 0.14.0 形状（at/afterUserCount）由
     * `BlockPolicy.decode` 兼容读取。
     */
    val blockProposalJson: String? = null
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
    val webToolsApiKeyCipher: String? = null,
    /**
     * 搜索后端（schema v12）：`MESSAGEAPI` | `TAVILY` | `BRAVE` | `SERPER` | `GENERIC`。
     *
     * 用字符串而不是枚举：备份格式本来就是字符串契约，遇到旧版本/手写脏值时要能退回默认，
     * 而不是让 Room 在反序列化时直接抛异常。合法值与默认地址见 [com.yap.app.domain.WebToolsProviders]。
     *
     * ## 为什么默认值还写着 `'DEEPSEEK'`（0.12 改名）
     *
     * 列的 SQL 默认值写进了 `app/schemas/12.json`，已装 v12 的库启动时会拿它跟实体注解比对；
     * 这里改成 `'MESSAGEAPI'` 会让那些库的 schema 校验失败（直接崩，或逼我们加一次没必要的迁移）。
     * 所以**注解与迁移 SQL 保持旧值**，旧值读出来由 `WebToolsProviders.normalize` 折算成
     * `MESSAGEAPI`；Kotlin 侧的默认参数是新值，新建/写入的行一律是 `MESSAGEAPI`。
     */
    @ColumnInfo(defaultValue = "'DEEPSEEK'")
    val webToolsProvider: String = com.yap.app.domain.WebToolsProviders.MESSAGEAPI
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
        Index(value = ["createdAt"]),
        // v11 区块分页：`WHERE sessionId = ? AND blockIndex = ? ORDER BY createdAt, id` 的覆盖索引，
        // 让「读一块」与历史长度无关（规范 §3）。
        Index(value = ["sessionId", "blockIndex", "createdAt"])
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
    val readAt: Long? = null,
    /**
     * 这条消息属于哪一块（v11，规范 §1）。
     *
     * 块 = 最多 [com.yap.app.domain.MessageBlocks.BLOCK_SIZE] 条的物理分页单位；
     * 编号由 `MessageBlockWriter` 在**每一条写入路径**上维护，块表见 `MessageBlockEntity`。
     * 默认值 0 让老库的 `ALTER TABLE` 不用回填两次，真正的回填由 `MIGRATION_10_11` 做。
     */
    @ColumnInfo(defaultValue = "0")
    val blockIndex: Int = 0,

    /**
     * 拉黑期间收到的用户消息（schema v18）。
     *
     * 永久排除在模型上下文之外：解除拉黑之后也不会补看、不会抽进记忆，所以这不是
     * 「暂时隐藏」而是一个写入时就定下的可见性标记。界面照常显示，并在气泡左侧标红。
     */
    @ColumnInfo(defaultValue = "0")
    val blocked: Boolean = false
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
    val updatedAt: Long,
    /** 世界预设 JSON（v13）；预设应用到人格时复制一份，之后互不覆盖。 */
    val worldJson: String? = null,
    /** 话风 JSON（v13）。 */
    val speechStyleJson: String? = null
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

/**
 * 检索粗筛回来的一行（不是表，只是 `MessageSearchSql` 的 SELECT 结果投影）。
 *
 * `role` 用来在结果里区分「我说的」和「他说的」；`personaId` 是人格过滤的依据。
 */
data class MessageSearchRow(
    val messageId: String,
    val sessionId: String,
    val sessionTitle: String,
    val personaId: String,
    val personaName: String,
    val personaAvatarUri: String?,
    val personaAvatarSeed: Int,
    val content: String,
    val createdAt: Long,
    val role: String
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

/**
 * 日记板上的一条笔记：**他自己的备忘录**（`spec/diary/README.md`，schema v15）。
 *
 * 和记忆不是一回事：记忆是「他记得关于你的事」，由抽取器与整理器维护、会衰减、会归档；
 * 日记是他主动写下的备忘（「周五提醒我问她面试怎么样」「那家店在巷子口第二家」），
 * 只有他自己会写、会翻、会删，别人看不到。
 *
 * 字段语义：
 * - [remindAt]：要不要到点提醒（毫秒时间戳，可空 = 不提醒）；
 * - [remindedAt]：这条提醒**已经变成念头**的时刻，非空即「提醒过」——
 *   它是「只触发一次」的唯一凭据（心跳/主动 tick 用带条件的 UPDATE 原子占位）；
 * - 删除是硬删除（规范要求），不保留墓碑。
 */
@Entity(
    tableName = "diary_notes",
    indices = [Index(value = ["personaId"]), Index(value = ["remindAt"])]
)
data class DiaryNoteEntity(
    @PrimaryKey val id: String,
    val personaId: String,
    val content: String,
    val createdAt: Long,
    val remindAt: Long? = null,
    val remindedAt: Long? = null,
    val updatedAt: Long
)

/**
 * 一张表情包（`spec/stickers/SCHEMA.md`，schema v16）。
 *
 * 与聊天附件的关系：消息里的 `attachmentsJson` 只存 `{uri, mime, kind=STICKER}`，
 * 表情本身的元数据（帧数、字节、来源、谁做的）在这张表里——同一条消息里的图片不需要这些，
 * 而表情需要「最近 30 个 / 全部 / 我的收藏」这类列表，所以它值得一张表。
 *
 * 字段语义：
 * - [file]：本机 `file://` 路径（`files/stickers/<id>.gif`）或服务端模式的 `/v1/media/<id>`；
 * - [mime]：永远是 `image/gif`（静态图也存成单帧 GIF：渲染/上传只有一条路径）；
 * - [frames]、[bytes]：落库时算好，列表页不必解码就知道「是不是动图、多大」；
 * - [source] / [createdBy]：`IMPORTED | MODEL_MADE | FROM_USER_MESSAGE | WEB` 与 `USER | MODEL`，
 *   与规范逐字一致，两端靠字符串对齐（不写枚举列，避免迁移时改值）；
 * - [ownerUserId]、[personaId]：谁收藏的、属于哪个人格（可空 = 不属于某个人格）。
 */
@Entity(
    tableName = "stickers",
    indices = [Index(value = ["createdAt"]), Index(value = ["personaId"])]
)
data class StickerEntity(
    @PrimaryKey val id: String,
    val ownerUserId: String? = null,
    val personaId: String? = null,
    val file: String,
    val mime: String = "image/gif",
    // 这四个默认值是 `spec/stickers/SCHEMA.md` 里写死的 DDL 默认值：Room 的 schema 校验会比对
    // 「实体声明的默认值」与「数据库里的默认值」，不写 @ColumnInfo 就会升级即崩。
    @ColumnInfo(defaultValue = "0") val width: Int = 0,
    @ColumnInfo(defaultValue = "0") val height: Int = 0,
    @ColumnInfo(defaultValue = "1") val frames: Int = 1,
    @ColumnInfo(defaultValue = "0") val bytes: Int = 0,
    val source: String = "IMPORTED",
    val createdBy: String = "USER",
    val createdAt: Long
)

/**
 * 一次「心理活动」（schema v18，`spec/experience/README.md` §3）。
 *
 * 这是他不说出口的念头：活跃窗口里每 3 分钟或每 3 轮生成一句，写在这里，
 * 下一轮以「你刚才心里想：…」注入提示词；同时留下当时的心情快照，供「心情变化 ≥2 必做」判定。
 * 每个会话/人格只保留最近 50 条，由 DAO 在写入后裁掉更老的。
 */
@Entity(
    tableName = "mental_activities",
    indices = [
        Index(value = ["personaId", "at"]),
        Index(value = ["sessionId", "at"]),
        Index(value = ["at"])
    ]
)
data class MentalActivityEntity(
    @PrimaryKey val id: String,
    val personaId: String,
    val sessionId: String?,
    val at: Long,
    /** 私密念头；规范上限 60 字，写入前已截断。 */
    val thought: String,
    // ---- 生成那一刻的心情快照 ------------------------------------------------
    val moodLabel: String? = null,
    val moodIntensity: Int? = null,
    val moodValence: Int? = null,
    val moodArousal: Int? = null,
    val moodDominance: Int? = null
)

/**
 * 会话级「小本子」（schema v18，`spec/experience/README.md` §6）。
 *
 * 与 `chat_sessions.historySummary` 的区别：historySummary 是长历史的压缩，属于上下文装配；
 * 这里的两段摘要是**他的备忘录**，短期覆盖最近 5 轮、长期覆盖更早的对话，作为可变区域
 * 注入系统提示词（短期在前、长期在后），不写进人设。
 *
 * [shortUserCount] / [longUserCount] 是节流凭据：每 5 / 30 条用户消息才各更新一次，
 * 失败时不写，所以旧摘要会保留。
 */
@Entity(
    tableName = "session_memory",
    indices = [Index(value = ["personaId"]), Index(value = ["updatedAt"])]
)
data class SessionMemoryEntity(
    @PrimaryKey val sessionId: String,
    val personaId: String,
    val shortSummary: String? = null,
    val longSummary: String? = null,
    val shortUserCount: Int = 0,
    val longUserCount: Int = 0,
    val shortUpdatedAt: Long? = null,
    val longUpdatedAt: Long? = null,
    val updatedAt: Long,
    // ---- 0.15 对话主题总结（schema v19，docs/design/context-memory-v2.md） --------
    /** 主题总结 JSON：`[{"time":"2026-11-18 晚上","summary":"…"}]`，最多 12 段。 */
    val topicsJson: String? = null,
    /** 上次滚主题总结时的可见用户消息数。 */
    @ColumnInfo(defaultValue = "0")
    val topicUserCount: Int = 0,
    /** 上次滚主题总结的时间。 */
    val topicUpdatedAt: Long? = null
)
