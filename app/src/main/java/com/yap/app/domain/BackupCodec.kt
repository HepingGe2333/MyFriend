package com.yap.app.domain

import com.yap.app.data.local.AgentIntentEntity
import com.yap.app.data.local.AgentIntentKind
import com.yap.app.data.local.AgentIntentStatus
import com.yap.app.data.local.ApiConfigEntity
import com.yap.app.data.local.ChatSessionEntity
import com.yap.app.data.local.MemoryCategory
import com.yap.app.data.local.MemoryConsolidationEntity
import com.yap.app.data.local.MemoryEntity
import com.yap.app.data.local.MessageEntity
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.MessageStatus
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.PersonaPresetEntity
import com.yap.app.data.local.ProactiveKind
import com.yap.app.data.local.ProactiveRuleEntity
import com.yap.app.data.local.ProactiveSource
import com.yap.app.data.local.ProviderType
import com.yap.app.data.prefs.AppSettings
import com.yap.app.data.prefs.BubbleStyle
import com.yap.app.data.prefs.ThemeMode
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「把他带走」的那份文件。
 *
 * 这个 App 里和用户对话的是一个人，所以这份备份不是数据库转储：
 * 它装的是他的人格（含人设卡、作息、此刻的心情）、他记得的事（含抽屉、分量、漂移过的正文）、
 * 你们聊过的每一句（含他当时查了哪些网页）、他给自己定的规矩，以及那些可移植的设置。
 *
 * 两条硬规矩，写在这里是为了以后没人能「顺手」破掉：
 *
 * 1. **密钥永远不进备份**。任何 `*Cipher` 字段、`apiKey`、web tools 专用 Key、
 *    可能夹带 `Authorization` 的自定义 Headers，都不在下面的结构里出现——这是在类型层面掐死的，
 *    不是靠「记得删」。恢复时 API 配置只接回地址和模型，Key 留在本机或由用户重新填。
 * 2. **读坏数据不许崩**。[decode] 永远返回 [BackupDecodeResult]，缺字段用默认值、未知字段忽略、
 *    坏条目跳过并记一条中文警告；版本比本机高时明确拒绝，而不是半懂不懂地写库。
 *
 * v4（0.12）在 v3 的媒体之上加了 `apiConfigs[].webToolsProvider`（搜索后端，默认 MESSAGEAPI）；
 * v3 的媒体规则不变：头像与聊天图片的**二进制**也一起带走：顶层多一个 `media` 数组，
 * 每条是一张图（`PERSONA_AVATAR` / `USER_AVATAR` / `CHAT_IMAGE`）+ 标准 base64。
 * `personas.avatarUri` / `presets.avatarUri` 仍写裸文件名，`messages.attachmentsJson`
 * 也只写裸文件名（绝不写设备路径）；导入时用 `media[].fileName` 把引用接回**新的**本机文件。
 * 上限（[BackupMediaLimits]）：单张 8 MiB、总量 128 MiB；超出的图片不带走并记 warning。
 *
 * 结构上的取舍：
 * - `agentTicks` / `apiUsage` / `personaRevisions` 不导：那是过程流水，不是「他」；
 * - 设置只导能带走的（外观、常驻后台、正在聊的人）；1.9 起节奏、记忆开关、额度都从产品里删了；
 *   聊天背景图片是设备上的路径，不导；
 * - 孤儿图片文件不导：只收集本机当前还引用得到的头像与聊天图片。
 */
const val BACKUP_FORMAT_VERSION: Int = 5

/**
 * 写在文件里的格式标记：拿错文件时能早点说「这不是他的备份」。
 *
 * **刻意不随品牌更名**：这是跨版本/跨端的备份兼容契约，旧版本导出的文件、
 * iOS 端导出的文件都写着 `myfriend.backup`。改名会让所有旧备份无法导入。
 */
const val BACKUP_FORMAT_NAME: String = "myfriend.backup"

/** 备份文件的顶层结构。字段顺序就是 JSON 里的顺序。 */
@Serializable
data class BackupBundle(
    val format: String = BACKUP_FORMAT_NAME,
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val appVersion: String = "",
    val exportedAt: Long = 0L,
    /**
     * 常量 true，写给「打开这份 JSON 的人」看：里面没有他的 Key。
     *
     * 这不是开关，只是一句声明——真正的保证是下面这些结构里根本没有密钥字段。
     */
    val apiKeysOmitted: Boolean = true,
    val personas: List<BackupPersona> = emptyList(),
    val memories: List<BackupMemory> = emptyList(),
    val sessions: List<BackupSession> = emptyList(),
    val messages: List<BackupMessage> = emptyList(),
    /**
     * 图片二进制（v3）。没有图片时是空数组，不是 null：老版本读新备份会当成未知字段忽略，
     * 新版本读老备份则拿到空列表，行为与 v2 完全一致。
     */
    val media: List<BackupMedia> = emptyList(),
    val presets: List<BackupPreset> = emptyList(),
    val proactiveRules: List<BackupProactiveRule> = emptyList(),
    /** 他「想在某个时间段找你」的念头；过期就作废，属于可选内容。 */
    val agentIntents: List<BackupAgentIntent> = emptyList(),
    /** 他整理过记忆的留痕，用来保留「他最近整理过记忆」这条历史。 */
    val memoryConsolidations: List<BackupMemoryConsolidation> = emptyList(),
    val apiConfigs: List<BackupApiConfig> = emptyList(),
    /**
     * 可移植的设置。
     *
     * 为 null 表示这份备份里没有设置这一段（老版本或手写的备份）：接回来时设置页保持原样，
     * 绝不拿一份「没有设置的备份」把用户现在的偏好清成默认值。
     */
    val settings: BackupSettings? = null
)

// ---------------------------------------------------------------------------
// 各段的传输结构：全部字段都有默认值，所以缺字段不会炸；未知字段解码时忽略。
// ---------------------------------------------------------------------------

/** 人格。人设卡 JSON、作息、心情都在这里——这是他「是谁」的全部。 */
@Serializable
data class BackupPersona(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val avatarUri: String? = null,
    val avatarSeed: Int = 0,
    val prompt: String = "",
    val category: String = "",
    val version: Int = 1,
    val defaultApiConfigId: String? = null,
    val modelOverride: String? = null,
    val isDefault: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** 结构化人设卡（PersonaCardCodec 的 JSON），原样带走。 */
    val personaCardJson: String? = null,
    val quietSleepMinutes: Int? = null,
    val quietWakeMinutes: Int? = null,
    val mood: String? = null,
    val moodIntensity: Int? = null,
    val moodReason: String? = null,
    val moodUpdatedAt: Long? = null,
    val moodNudgedAt: Long? = null,

    // ---- 1.9 联系人细分字段 ------------------------------------------------
    /** 性格标签，最多 6 个。用 JSON 数组跨端传输，不把「逗号分隔」这种界面习惯带进契约。 */
    val traits: List<String> = emptyList(),
    /** 喜欢的事，最多 8 个。 */
    val likes: List<String> = emptyList(),
    /** 行为方式 / 习惯，最多 6 个。 */
    val behaviors: List<String> = emptyList(),
    /** 成长经历 / 过去，最多 600 字。 */
    val backstory: String? = null,
    /** 个人设定：称呼、口头禅、忌讳，最多 300 字。 */
    val quirks: String? = null,
    /** 主动程度 1–5（行为参数，不进提示词正文）。 */
    val proactiveLevel: Int = 3,
    /** 记性 1–5（行为参数）。 */
    val memoryRetention: Int = 3,
    /** 每多少条消息抽一次记忆（3–12）。 */
    val extractionEvery: Int = 6,
    /** 他会不会主动睡觉。 */
    val sleepEnabled: Boolean = true,
    /** 睡眠中被唤醒需要的连续消息数（1–20）。 */
    val wakeThreshold: Int = 6,
    /** 睡前免打扰（模型可改，用户不能直接改）。 */
    val dndOnSleep: Boolean = false,

    // ---- v5：人设引导（世界预设 / 话风 / 当前活动） -------------------------
    /** 世界预设 JSON（`WorldPresetCodec`）；旧备份没有这个字段，读出来是 null。 */
    val worldJson: String? = null,
    /** 话风 JSON（`SpeechStyleCodec`）。 */
    val speechStyleJson: String? = null,
    /** 当前活动 JSON（`PersonaActivityCodec`）；跨端接回来时如果已经过期，读取端会当作「没在忙」。 */
    val activityJson: String? = null
)

/** 一条长期记忆，连「它还剩下多少分量」一起带走。 */
@Serializable
data class BackupMemory(
    val id: String = "",
    val personaId: String = "",
    val content: String = "",
    val category: String = "OTHER",
    val importance: Int = 50,
    val pinned: Boolean = false,
    val sourceSessionId: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastUsedAt: Long = 0L,
    val useCount: Int = 0,
    val drawer: String? = null,
    val sourceQuote: String? = null,
    val status: String = "ACTIVE",
    val strength: Double = 1.0,
    val archivedAt: Long? = null,
    val consolidatedAt: Long? = null,
    val tags: String? = null,
    val happenedAt: Long? = null,
    val originalContent: String? = null,
    val driftCount: Int = 0
)

/** 一段对话（一个会话）。 */
@Serializable
data class BackupSession(
    val id: String = "",
    val personaId: String = "",
    val apiConfigId: String? = null,
    val model: String = "",
    val title: String = "",
    val lastMessagePreview: String? = null,
    val lastMessageAt: Long? = null,
    val unreadCount: Int = 0,
    val pinned: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastPersonaVersion: Int = 0,
    val lastMemoryExtractedCount: Int = 0,
    /**
     * 长会话被收成的那段「他还记得的话」（1.7 的滚动摘要）。
     *
     * 换端接回来时要一起带走：不然在同一段对话里，他会突然把你说过的事忘掉。
     * 老备份没有这三个字段，读出来是 null，等同于「还没压过」，行为与 1.7 之前一致。
     */
    val summary: String? = null,
    val summarizedUpTo: Long? = null,
    val summaryAt: Long? = null,
    /** 会话级覆盖：为空 = 继承人格的记性 / 抽记忆频率。 */
    val memoryRetentionOverride: Int? = null,
    val extractionEveryOverride: Int? = null
)

/**
 * 一句话。
 *
 * [toolTraceJson] 原样带走（「他当时查了两个网页」这种痕迹也是相处的一部分）；
 * [attachmentsJson] 只写裸文件名（v3）：图片本体在 `media` 里，导入时按文件名重建本机路径。
 */
@Serializable
data class BackupMessage(
    val id: String = "",
    val sessionId: String = "",
    val personaId: String = "",
    val role: String = "USER",
    val content: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val status: String = "COMPLETE",
    val errorMessage: String? = null,
    val model: String? = null,
    val edited: Boolean = false,
    val attachmentsJson: String? = null,
    val toolTraceJson: String? = null,
    /** 模型看到这条用户消息的时刻；1.9 新增，可空。 */
    val readAt: Long? = null
)

/**
 * 一张图片的二进制（v3）。
 *
 * [fileName] 是**源文件名**，也是它和 `personas.avatarUri` / `messages.attachmentsJson`
 * 之间的关联键；导入时**不会**用它拼路径，落盘一律换成新的 UUID 文件名。
 * [bytes] 是 [data] 解码后的字节数，用来在解码前挡住「声称很小、实际很大」的坏数据。
 */
@Serializable
data class BackupMedia(
    val id: String = "",
    /** `PERSONA_AVATAR` / `USER_AVATAR` / `CHAT_IMAGE`（大小写敏感）。 */
    val kind: String = "",
    val personaId: String? = null,
    /** 裸文件名（不含目录、不含 scheme）。 */
    val fileName: String = "",
    val mime: String = "image/jpeg",
    val bytes: Int = 0,
    /** 标准 base64（带 padding、不换行）。 */
    val data: String = ""
)

@Serializable
data class BackupPreset(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val prompt: String = "",
    val category: String = "",
    val avatarUri: String? = null,
    val avatarSeed: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** v5：预设也可以带世界与话风；应用到人格时复制一份。 */
    val worldJson: String? = null,
    val speechStyleJson: String? = null
)

/** 他给自己定的主动节奏（每天几点 / 每几小时）。 */
@Serializable
data class BackupProactiveRule(
    val id: String = "",
    val personaId: String = "",
    val sessionId: String? = null,
    val kind: String = "DAILY",
    val timeOfDayMinutes: Int? = null,
    val intervalMinutes: Int? = null,
    val hint: String = "",
    val note: String = "",
    val enabled: Boolean = true,
    val pending: Boolean = false,
    val source: String = "MANUAL",
    val nextTriggerAt: Long = 0L,
    val lastTriggeredAt: Long? = null,
    val lastError: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

@Serializable
data class BackupAgentIntent(
    val id: String = "",
    val personaId: String = "",
    val sessionId: String? = null,
    val kind: String = "REACH_OUT",
    val earliestAt: Long = 0L,
    val latestAt: Long = 0L,
    val note: String = "",
    val hint: String = "",
    val status: String = "PENDING",
    val ruleId: String? = null,
    val createdAt: Long = 0L,
    val sentAt: Long? = null,
    val lastError: String? = null
)

@Serializable
data class BackupMemoryConsolidation(
    val id: String = "",
    val personaId: String = "",
    val at: Long = 0L,
    val kept: Int = 0,
    val merged: Int = 0,
    val archived: Int = 0,
    val forgotten: Int = 0,
    val summary: String = "",
    val modelName: String? = null
)

/**
 * API 配置里**可以带走的那部分**。
 *
 * 刻意只有白名单字段：没有 `apiKeyCipher`、没有 `webToolsApiKeyCipher`、
 * 也没有 `customHeadersJson`（自定义 Header 里可能藏着 `Authorization: Bearer sk-…`）。
 * 接回来以后：地址、模型、开关都在，Key 需要在这台手机上重新填一次。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BackupApiConfig(
    val id: String = "",
    val name: String = "",
    val provider: String = "OPENAI",
    val baseUrl: String = "",
    val model: String = "",
    val allowInsecureHttp: Boolean = false,
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxOutputTokens: Int? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val webToolsEnabled: Boolean = false,
    val webToolsBaseUrl: String = "",
    /**
     * 搜索后端（v4）。缺字段的老备份读到默认 `MESSAGEAPI`；导出时**总是写**这一列
     * （即使值等于默认），否则「v3 备份导入后 provider 默认 MESSAGEAPI」与
     * 「v4 备份里确实带了 provider」这两件事在 JSON 上分不出来。
     *
     * 0.12 之前这一列的值叫 `DEEPSEEK`：读旧备份时由 [BackupCodec.decode] 归一化成
     * `MESSAGEAPI`，导出时由 [BackupCodec.encode] 一律写新值（见 [WebToolsProviders.normalize]）。
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val webToolsProvider: String = WebToolsProviders.MESSAGEAPI,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

/**
 * 可移植的设置。
 *
 * 故意不在这里的：`chatBackgroundImageUri`（设备上的私有路径，换机无意义）、
 * `backgroundTipDismissed`（纯本地的引导状态）、以及任何跟密钥有关的东西。
 */
@Serializable
data class BackupSettings(
    val themeMode: String = "SYSTEM",
    val accentColor: Long = 0xFFC76F4E,
    val fontScale: Float = 1f,
    val bubbleStyle: String = "SOFT",
    val animationsEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val chatBackgroundColor: Long? = null,
    val agentLoopEnabled: Boolean = true,
    val activePersonaId: String? = null
)

// ---------------------------------------------------------------------------
// 解码结果
// ---------------------------------------------------------------------------

/** [decode] 的返回值：坏数据也只在这里说话，不往上抛。 */
sealed interface BackupDecodeResult {
    /** 读懂了。[warnings] 是结构化的「有东西没读出来 / 有东西带不回来」，由 UI 翻成句子。 */
    data class Success(
        val bundle: BackupBundle,
        val warnings: List<BackupWarning> = emptyList(),
        /** v3 媒体段的提醒（坏条目 / 整段读不出来）；导入/预览都会带上。 */
        val mediaWarnings: List<BackupMediaWarning> = emptyList()
    ) : BackupDecodeResult

    /** 读不懂：[reason] 是结构化原因，由 UI 翻成句子。 */
    data class Failure(val reason: BackupFailure) : BackupDecodeResult
}

/** 选好文件、还没决定怎么接回来时，先给用户看的一句话摘要。 */
data class BackupPreview(
    val personas: Int = 0,
    val memories: Int = 0,
    val sessions: Int = 0,
    val messages: Int = 0,
    val rules: Int = 0,
    val presets: Int = 0,
    val apiConfigs: Int = 0,
    val exportedAt: Long = 0L,
    val appVersion: String = ""
) {
    val isEmpty: Boolean
        get() = personas == 0 && memories == 0 && sessions == 0 && messages == 0

    /**
     * 摘要不再自己拼句子：计数原样给 UI，由它按当前语言拼
     * （`backup_preview_*`）；[isEmpty] 也由 UI 判断要不要显示「这份备份里什么也没有」。
     */
    val hasAnyContent: Boolean
        get() = !isEmpty

    companion object {
        fun from(bundle: BackupBundle): BackupPreview = BackupPreview(
            personas = bundle.personas.size,
            memories = bundle.memories.size,
            sessions = bundle.sessions.size,
            messages = bundle.messages.size,
            rules = bundle.proactiveRules.size,
            presets = bundle.presets.size,
            apiConfigs = bundle.apiConfigs.size,
            exportedAt = bundle.exportedAt,
            appVersion = bundle.appVersion
        )
    }
}

// ---------------------------------------------------------------------------
// 编解码
// ---------------------------------------------------------------------------

object BackupCodec {

    /**
     * 导出一律写全默认值：这样文件自己说清楚「这一段是什么」，也方便以后加字段。
     * 读取时相反：能松就松，缺什么补什么。
     */
    private val writeJson = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val readJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        isLenient = true
    }

    /**
     * 导出前的最后一道自检：管线上真的会写这些键名吗？
     *
     * 结构上已经不可能带密钥了（见 [BackupApiConfig]），这一层是「万一以后有人改坏了」的报警器：
     * 命中就宁可不导出，也不把用户的 Key 写进一个明文文件。
     */
    private val SECRET_KEY_MARKERS = listOf(
        "\"apiKeyCipher\"",
        "\"webToolsApiKeyCipher\"",
        "\"apiKey\"",
        "\"Authorization\"",
        "\"customHeadersJson\""
    )

    fun containsSecretField(encoded: String): Boolean = SECRET_KEY_MARKERS.any { encoded.contains(it) }

    /**
     * 导出：**写新值**。
     *
     * `webToolsProvider` 在这里统一走一遍 [WebToolsProviders.normalize]：库里/内存里即使还残留
     * 旧值 `DEEPSEEK`（老库未重写、或调用方直接构造了模型），导出到另一端时也只会是 `MESSAGEAPI`。
     */
    fun encode(bundle: BackupBundle): String = writeJson.encodeToString(
        bundle.copy(
            apiConfigs = bundle.apiConfigs.map {
                it.copy(webToolsProvider = WebToolsProviders.normalize(it.webToolsProvider))
            }
        )
    )

    /** 坏 JSON、坏条目、版本过高都在这里被接住，返回中文原因而不是抛异常。 */
    fun decode(raw: String?): BackupDecodeResult {
        if (raw.isNullOrBlank()) return BackupDecodeResult.Failure(BackupFailure.EmptyFile)
        // 安全 P2-3：解析前再兜一次大小。读文件的闸（BoundedTextRead）挡的是 OOM 主路径，
        // 这里挡的是「别的调用方把一份超大字符串直接塞进来」（调试面板、将来的 CLI/同步）。
        if (raw.length.toLong() > BackupMediaLimits.effectiveBackupBytes()) {
            return BackupDecodeResult.Failure(BackupFailure.TooLarge(BackupMediaLimits.effectiveBackupBytes()))
        }

        val root = runCatching { readJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return BackupDecodeResult.Failure(BackupFailure.InvalidJson)

        val warnings = mutableListOf<BackupWarning>()

        val format = root.textOrNull("format")
        if (format != null && format != BACKUP_FORMAT_NAME) {
            return BackupDecodeResult.Failure(BackupFailure.WrongFormat(format))
        }

        val version = root.intOrNull("formatVersion") ?: BACKUP_FORMAT_VERSION
        if (version > BACKUP_FORMAT_VERSION) {
            return BackupDecodeResult.Failure(BackupFailure.NewerFormat(version, BACKUP_FORMAT_VERSION))
        }
        if (version < BACKUP_FORMAT_VERSION) {
            warnings += BackupWarning.OlderFormat(version, BACKUP_FORMAT_VERSION)
        }

        val personas = decodeList(root, "personas", BackupSection.PERSONA, warnings, BackupPersona.serializer()) { it.id }
        val memories = decodeList(root, "memories", BackupSection.MEMORY, warnings, BackupMemory.serializer()) { it.id }
        val sessions = decodeList(root, "sessions", BackupSection.SESSION, warnings, BackupSession.serializer()) { it.id }
        val messages = decodeList(root, "messages", BackupSection.MESSAGE, warnings, BackupMessage.serializer()) { it.id }
        val presets = decodeList(root, "presets", BackupSection.PRESET, warnings, BackupPreset.serializer()) { it.id }
        val rules =
            decodeList(root, "proactiveRules", BackupSection.PROACTIVE_RULE, warnings, BackupProactiveRule.serializer()) { it.id }
        val intents =
            decodeList(root, "agentIntents", BackupSection.AGENT_INTENT, warnings, BackupAgentIntent.serializer()) { it.id }
        val consolidations =
            decodeList(
                root,
                "memoryConsolidations",
                BackupSection.CONSOLIDATION,
                warnings,
                BackupMemoryConsolidation.serializer()
            ) { it.id }
        // 读旧值、写新值：0.12 之前的备份里 provider 叫 `DEEPSEEK`，归一化成 `MESSAGEAPI`；
        // 缺字段的 v3 备份也在这里落到默认值。归一化在校验之前完成，下游（导入/合并）只认新值。
        val configs = decodeList(
            root,
            "apiConfigs",
            BackupSection.API_CONFIG,
            warnings,
            BackupApiConfig.serializer()
        ) { it.id }.map { it.copy(webToolsProvider = WebToolsProviders.normalize(it.webToolsProvider)) }

        val mediaWarnings = mutableListOf<BackupMediaWarning>()
        val media = decodeMedia(root, mediaWarnings)

        val settings = root["settings"]?.takeIf { it !is JsonNull }?.let { element ->
            runCatching { readJson.decodeFromJsonElement(BackupSettings.serializer(), element) }
                .getOrElse {
                    warnings += BackupWarning.SettingsUnreadable
                    null
                }
        }

        val bundle = BackupBundle(
            format = BACKUP_FORMAT_NAME,
            formatVersion = BACKUP_FORMAT_VERSION,
            appVersion = root.textOrNull("appVersion") ?: "",
            exportedAt = root.longOrNull("exportedAt") ?: 0L,
            apiKeysOmitted = true,
            personas = personas,
            memories = memories,
            sessions = sessions,
            messages = messages,
            media = media,
            presets = presets,
            proactiveRules = rules,
            agentIntents = intents,
            memoryConsolidations = consolidations,
            apiConfigs = configs,
            settings = settings
        )

        if (BackupPreview.from(bundle).isEmpty) {
            warnings += BackupWarning.NoContent
        }
        return BackupDecodeResult.Success(bundle, warnings, mediaWarnings.toList())
    }

    /**
     * v3 媒体段的宽容解码。
     *
     * 与 [decodeList] 同样的「能救几条是几条」：整段不是数组、某几条读不动都只记一条
     * 媒体提醒，不让整份备份失败。注意这里**不按 id 过滤**：`media[].id` 只是给排查用的，
     * 关联靠 `kind + fileName`，没有 id 的条目照样可以恢复图片。
     */
    private fun decodeMedia(
        root: JsonObject,
        mediaWarnings: MutableList<BackupMediaWarning>
    ): List<BackupMedia> {
        val element = root["media"] ?: return emptyList()
        if (element is JsonNull) return emptyList()
        val array = element as? JsonArray ?: run {
            mediaWarnings += BackupMediaWarning.ImportSkipped(1)
            return emptyList()
        }
        if (array.isEmpty()) return emptyList()
        val decoded: List<BackupMedia> = runCatching {
            readJson.decodeFromJsonElement(ListSerializer(BackupMedia.serializer()), array)
        }.getOrElse {
            var dropped = 0
            val kept = array.mapNotNull { item ->
                runCatching { readJson.decodeFromJsonElement(BackupMedia.serializer(), item) }
                    .onFailure { dropped++ }
                    .getOrNull()
            }
            if (dropped > 0) mediaWarnings += BackupMediaWarning.ImportSkipped(dropped)
            kept
        }
        return decoded
    }

    /**
     * 一段列表的宽容解码：整段读不动就逐条读，能救几条是几条，丢掉的记进 warnings。
     */
    private fun <T> decodeList(
        root: JsonObject,
        key: String,
        section: BackupSection,
        warnings: MutableList<BackupWarning>,
        serializer: KSerializer<T>,
        idOf: (T) -> String
    ): List<T> {
        val element = root[key] ?: return emptyList()
        if (element is JsonNull) return emptyList()
        val array = element as? JsonArray ?: run {
            warnings += BackupWarning.SectionNotList(section)
            return emptyList()
        }

        val decoded: List<T> = runCatching {
            readJson.decodeFromJsonElement(ListSerializer(serializer), array)
        }.getOrElse {
            var dropped = 0
            val kept = array.mapNotNull { item ->
                runCatching { readJson.decodeFromJsonElement(serializer, item) }
                    .onFailure { dropped++ }
                    .getOrNull()
            }
            if (dropped > 0) warnings += BackupWarning.DroppedRows(section, dropped)
            kept
        }

        val valid = decoded.filter { idOf(it).isNotBlank() }
        val blank = decoded.size - valid.size
        if (blank > 0) warnings += BackupWarning.BlankIds(section, blank)
        return valid
    }
}

/** 备份文件建议名字：`yap-backup-20261114-0140.json`。 */
fun backupFileName(now: Long = System.currentTimeMillis()): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date(now))
    return "yap-backup-$stamp.json"
}

/** 「2026年11月14日 01:40」，给界面和摘要共用。 */
fun formatBackupMoment(at: Long, pattern: String = "yyyy-M-d HH:mm"): String {
    if (at <= 0L) return ""
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(at))
}

// ---------------------------------------------------------------------------
// 实体 ⇄ 备份结构
//
// 这些映射只在这里写一遍：任何实体新增字段却忘了带进备份，BackupCodecTest 里的往返测试会当场变红。
// ---------------------------------------------------------------------------

private inline fun <reified T : Enum<T>> enumOr(raw: String?, default: T): T {
    val cleaned = raw?.trim().orEmpty()
    if (cleaned.isEmpty()) return default
    return runCatching { enumValueOf<T>(cleaned.uppercase(Locale.ROOT)) }.getOrDefault(default)
}

fun PersonaEntity.toBackupPersona(): BackupPersona = BackupPersona(
    id = id,
    name = name,
    description = description,
    // 安全 P2-5：备份里不写本机绝对路径（`file:///data/user/0/<pkg>/files/avatars/…`），
    // 只留文件名；导入时按「本机同名文件在不在」决定恢复还是清空。
    avatarUri = BackupAvatarPath.portable(avatarUri),
    avatarSeed = avatarSeed,
    prompt = prompt,
    category = category,
    version = version,
    defaultApiConfigId = defaultApiConfigId,
    modelOverride = modelOverride,
    isDefault = isDefault,
    createdAt = createdAt,
    updatedAt = updatedAt,
    personaCardJson = personaCardJson,
    quietSleepMinutes = quietSleepMinutes,
    quietWakeMinutes = quietWakeMinutes,
    mood = mood,
    moodIntensity = moodIntensity,
    moodReason = moodReason,
    moodUpdatedAt = moodUpdatedAt,
    moodNudgedAt = moodNudgedAt,
    traits = PersonaProfile.sanitizeList(PersonaProfile.decodeList(traits), PersonaProfile.MAX_TRAITS),
    likes = PersonaProfile.sanitizeList(PersonaProfile.decodeList(likes), PersonaProfile.MAX_LIKES),
    behaviors = PersonaProfile.sanitizeList(PersonaProfile.decodeList(behaviors), PersonaProfile.MAX_BEHAVIORS),
    backstory = backstory,
    quirks = quirks,
    proactiveLevel = ProactiveLevel.normalize(proactiveLevel),
    memoryRetention = memoryRetention.coerceIn(1, 5),
    extractionEvery = extractionEvery.coerceIn(3, 12),
    sleepEnabled = sleepEnabled,
    wakeThreshold = SleepPolicy.normalizeThreshold(wakeThreshold),
    dndOnSleep = dndOnSleep,
    worldJson = worldJson,
    speechStyleJson = speechStyleJson,
    activityJson = activityJson
)

fun BackupPersona.toEntity(): PersonaEntity = PersonaEntity(
    id = id,
    name = name,
    description = description,
    avatarUri = avatarUri,
    avatarSeed = avatarSeed,
    prompt = prompt,
    category = category,
    version = version,
    defaultApiConfigId = defaultApiConfigId,
    modelOverride = modelOverride,
    isDefault = isDefault,
    createdAt = createdAt,
    updatedAt = updatedAt,
    personaCardJson = personaCardJson,
    quietSleepMinutes = quietSleepMinutes,
    quietWakeMinutes = quietWakeMinutes,
    mood = mood,
    moodIntensity = moodIntensity,
    moodReason = moodReason,
    moodUpdatedAt = moodUpdatedAt,
    moodNudgedAt = moodNudgedAt,
    traits = PersonaProfile.encodeList(PersonaProfile.sanitizeList(traits, PersonaProfile.MAX_TRAITS)),
    likes = PersonaProfile.encodeList(PersonaProfile.sanitizeList(likes, PersonaProfile.MAX_LIKES)),
    behaviors = PersonaProfile.encodeList(PersonaProfile.sanitizeList(behaviors, PersonaProfile.MAX_BEHAVIORS)),
    backstory = backstory?.trim()?.take(PersonaProfile.MAX_BACKSTORY)?.ifBlank { null },
    quirks = quirks?.trim()?.take(PersonaProfile.MAX_QUIRKS)?.ifBlank { null },
    proactiveLevel = ProactiveLevel.normalize(proactiveLevel),
    memoryRetention = memoryRetention.coerceIn(1, 5),
    extractionEvery = extractionEvery.coerceIn(3, 12),
    sleepEnabled = sleepEnabled,
    wakeThreshold = SleepPolicy.normalizeThreshold(wakeThreshold),
    dndOnSleep = dndOnSleep,
    worldJson = worldJson,
    speechStyleJson = speechStyleJson,
    activityJson = activityJson
)

fun MemoryEntity.toBackupMemory(): BackupMemory = BackupMemory(
    id = id,
    personaId = personaId,
    content = content,
    category = category.name,
    importance = importance,
    pinned = pinned,
    sourceSessionId = sourceSessionId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastUsedAt = lastUsedAt,
    useCount = useCount,
    drawer = drawer,
    sourceQuote = sourceQuote,
    status = status,
    strength = strength,
    archivedAt = archivedAt,
    consolidatedAt = consolidatedAt,
    tags = tags,
    happenedAt = happenedAt,
    originalContent = originalContent,
    driftCount = driftCount
)

fun BackupMemory.toEntity(): MemoryEntity = MemoryEntity(
    id = id,
    personaId = personaId,
    content = content,
    category = enumOr(category, MemoryCategory.OTHER),
    importance = importance.coerceIn(0, 100),
    pinned = pinned,
    sourceSessionId = sourceSessionId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastUsedAt = lastUsedAt,
    useCount = useCount,
    drawer = drawer,
    sourceQuote = sourceQuote,
    status = status.ifBlank { "ACTIVE" },
    strength = strength.coerceIn(0.0, 1.0),
    archivedAt = archivedAt,
    consolidatedAt = consolidatedAt,
    tags = tags,
    happenedAt = happenedAt,
    originalContent = originalContent,
    driftCount = driftCount
)

fun ChatSessionEntity.toBackupSession(): BackupSession = BackupSession(
    id = id,
    personaId = personaId,
    apiConfigId = apiConfigId,
    model = model,
    title = title,
    lastMessagePreview = lastMessagePreview,
    lastMessageAt = lastMessageAt,
    unreadCount = unreadCount,
    pinned = pinned,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastPersonaVersion = lastPersonaVersion,
    lastMemoryExtractedCount = lastMemoryExtractedCount,
    summary = historySummary,
    summarizedUpTo = historySummarizedUpTo,
    summaryAt = historySummaryAt,
    memoryRetentionOverride = memoryRetentionOverride,
    extractionEveryOverride = extractionEveryOverride
)

fun BackupSession.toEntity(): ChatSessionEntity = ChatSessionEntity(
    id = id,
    personaId = personaId,
    apiConfigId = apiConfigId,
    model = model,
    title = title,
    lastMessagePreview = lastMessagePreview,
    lastMessageAt = lastMessageAt,
    unreadCount = unreadCount,
    pinned = pinned,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastPersonaVersion = lastPersonaVersion,
    lastMemoryExtractedCount = lastMemoryExtractedCount,
    // 摘要只认「有正文也有压到哪一条」的完整组合，避免只导了一半的旧数据把提示词写歪。
    historySummary = summary?.takeIf { it.isNotBlank() && (summarizedUpTo ?: 0L) > 0L },
    historySummarizedUpTo = summarizedUpTo?.takeIf { it > 0L },
    historySummaryAt = summaryAt,
    memoryRetentionOverride = memoryRetentionOverride?.coerceIn(1, 5),
    extractionEveryOverride = extractionEveryOverride?.coerceIn(3, 12)
)

/**
 * 消息 → 备份。
 *
 * [chatImageFileNames] 是这次真的装进 `media` 的聊天图片文件名集合：只有它们会被写进
 * `attachmentsJson`（裸文件名）。没有对应媒体的附件直接不写——换台手机它也恢复不出来。
 */
fun MessageEntity.toBackupMessage(
    chatImageFileNames: Set<String> = emptySet()
): BackupMessage = BackupMessage(
    id = id,
    sessionId = sessionId,
    personaId = personaId,
    role = role.name,
    content = content,
    createdAt = createdAt,
    updatedAt = updatedAt,
    status = status.name,
    errorMessage = errorMessage,
    model = model,
    edited = edited,
    attachmentsJson = BackupAttachmentsPortable.portable(attachmentsJson, chatImageFileNames),
    toolTraceJson = toolTraceJson,
    readAt = readAt
)

/**
 * 消息接回来：`toolTraceJson` 保留（他当时做过什么），`attachmentsJson` 默认丢掉。
 *
 * v3 由 `BackupRepository` 先用 `media` 重建附件、再 `copy(attachmentsJson = …)`；
 * 这里保持「默认没有附件」，让「没有媒体可恢复」的旧备份仍然只留文字。
 */
fun BackupMessage.toEntity(): MessageEntity = MessageEntity(
    id = id,
    sessionId = sessionId,
    personaId = personaId,
    role = enumOr(role, MessageRole.USER),
    content = content,
    createdAt = createdAt,
    updatedAt = updatedAt,
    status = enumOr(status, MessageStatus.COMPLETE),
    errorMessage = errorMessage,
    model = model,
    edited = edited,
    attachmentsJson = null,
    toolTraceJson = toolTraceJson,
    readAt = readAt
)

fun PersonaPresetEntity.toBackupPreset(): BackupPreset = BackupPreset(
    id = id,
    name = name,
    description = description,
    prompt = prompt,
    category = category,
    // 安全 P2-6（审计 P2-8）：预设头像与人格同一策略——备份里只写裸文件名，
    // 不把设备沙盒绝对路径（`file:///data/user/0/<pkg>/files/avatars/…`）带进备份。
    avatarUri = BackupAvatarPath.portable(avatarUri),
    avatarSeed = avatarSeed,
    createdAt = createdAt,
    updatedAt = updatedAt,
    worldJson = worldJson,
    speechStyleJson = speechStyleJson
)

fun BackupPreset.toEntity(): PersonaPresetEntity = PersonaPresetEntity(
    id = id,
    name = name,
    description = description,
    prompt = prompt,
    category = category,
    avatarUri = avatarUri,
    avatarSeed = avatarSeed,
    createdAt = createdAt,
    updatedAt = updatedAt,
    worldJson = worldJson,
    speechStyleJson = speechStyleJson
)

fun ProactiveRuleEntity.toBackupRule(): BackupProactiveRule = BackupProactiveRule(
    id = id,
    personaId = personaId,
    sessionId = sessionId,
    kind = kind.name,
    timeOfDayMinutes = timeOfDayMinutes,
    intervalMinutes = intervalMinutes,
    hint = hint,
    note = note,
    enabled = enabled,
    pending = pending,
    source = source.name,
    nextTriggerAt = nextTriggerAt,
    lastTriggeredAt = lastTriggeredAt,
    lastError = lastError,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun BackupProactiveRule.toEntity(): ProactiveRuleEntity = ProactiveRuleEntity(
    id = id,
    personaId = personaId,
    sessionId = sessionId,
    kind = enumOr(kind, ProactiveKind.DAILY),
    timeOfDayMinutes = timeOfDayMinutes,
    intervalMinutes = intervalMinutes,
    hint = hint,
    note = note,
    enabled = enabled,
    pending = pending,
    source = enumOr(source, ProactiveSource.MANUAL),
    nextTriggerAt = nextTriggerAt,
    lastTriggeredAt = lastTriggeredAt,
    lastError = lastError,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun AgentIntentEntity.toBackupIntent(): BackupAgentIntent = BackupAgentIntent(
    id = id,
    personaId = personaId,
    sessionId = sessionId,
    kind = kind.name,
    earliestAt = earliestAt,
    latestAt = latestAt,
    note = note,
    hint = hint,
    status = status.name,
    ruleId = ruleId,
    createdAt = createdAt,
    sentAt = sentAt,
    lastError = lastError
)

fun BackupAgentIntent.toEntity(): AgentIntentEntity = AgentIntentEntity(
    id = id,
    personaId = personaId,
    sessionId = sessionId,
    kind = enumOr(kind, AgentIntentKind.REACH_OUT),
    earliestAt = earliestAt,
    latestAt = latestAt,
    note = note,
    hint = hint,
    status = enumOr(status, AgentIntentStatus.PENDING),
    ruleId = ruleId,
    createdAt = createdAt,
    sentAt = sentAt,
    lastError = lastError
)

fun MemoryConsolidationEntity.toBackupConsolidation(): BackupMemoryConsolidation = BackupMemoryConsolidation(
    id = id,
    personaId = personaId,
    at = at,
    kept = kept,
    merged = merged,
    archived = archived,
    forgotten = forgotten,
    summary = summary,
    modelName = modelName
)

fun BackupMemoryConsolidation.toEntity(): MemoryConsolidationEntity = MemoryConsolidationEntity(
    id = id,
    personaId = personaId,
    at = at,
    kept = kept,
    merged = merged,
    archived = archived,
    forgotten = forgotten,
    summary = summary,
    modelName = modelName
)

/**
 * API 配置 → 备份：白名单拷贝。
 *
 * 这里是「密钥不进备份」的第一道闸：只逐项抄下面这些字段，密文与自定义 Headers 连碰都不碰。
 */
fun ApiConfigEntity.toBackupApiConfig(): BackupApiConfig = BackupApiConfig(
    id = id,
    name = name,
    provider = provider.name,
    baseUrl = baseUrl,
    model = model,
    allowInsecureHttp = allowInsecureHttp,
    temperature = temperature,
    topP = topP,
    maxOutputTokens = maxOutputTokens,
    frequencyPenalty = frequencyPenalty,
    presencePenalty = presencePenalty,
    webToolsEnabled = webToolsEnabled,
    webToolsProvider = WebToolsProviders.normalize(webToolsProvider),
    webToolsBaseUrl = webToolsBaseUrl,
    createdAt = createdAt,
    updatedAt = updatedAt
)

/**
 * [mergeInto] 的结果：合并后的配置，加上「因为安全原因没有采纳备份值」的标记。
 *
 * 导入摘要要把这些标记翻成一句中英文都有的提醒（`BackupWarning`），
 * 用户才知道「这份备份想改你的服务地址，但我们没改」。
 */
data class MergedApiConfig(
    val entity: ApiConfigEntity,
    /** 备份里的 `baseUrl` / `webToolsBaseUrl` 与本机不同，按安全策略保留了本机值。 */
    val addressKept: Boolean = false,
    /** 备份要求打开明文 HTTP，被忽略（本机开关没有被备份改掉）。 */
    val insecureIgnored: Boolean = false
)

/**
 * 把备份里的配置盖回一条**已存在**的配置：只动非敏感字段。
 *
 * 密钥、自定义 Headers、创建时间都留在本机——备份里从来没有它们，
 * 要是这里顺手把整个实体换掉，用户在本机存的 Key 就被一份「没有 Key 的备份」抹掉了。
 *
 * ## 0.10.2 安全修复：地址与明文开关不是可迁移内容
 *
 * 以前的实现无条件覆盖 `baseUrl` 与 `allowInsecureHttp`，同时保留本机 Key：
 * 攻击者只要知道本机某条配置的 id（任何一份导出过的备份里都明文带着），
 * 就能拼一份备份，把地址改成自己的主机 / 改成 `http://`，用户「合并导入」之后
 * 下一次聊天就把**真实 Key 与全部上下文**发过去，或者被悄悄降级成明文。
 *
 * 现在：
 * - `baseUrl` / `webToolsBaseUrl` 与本地不同 → **保留本机值**，标 `addressKept`；
 *   要改地址请用户手动编辑并重新确认；
 * - `allowInsecureHttp` 一律保留本机值；备份要求打开而本机没开时标 `insecureIgnored`。
 */
fun BackupApiConfig.mergeInto(existing: ApiConfigEntity): MergedApiConfig {
    val incomingBaseUrl = baseUrl.trim().trimEnd('/')
    val localBaseUrl = existing.baseUrl.trim().trimEnd('/')
    val keepLocalBaseUrl = incomingBaseUrl.isNotEmpty() && incomingBaseUrl != localBaseUrl
    val incomingWebTools = webToolsBaseUrl.trim().trimEnd('/')
    val localWebTools = existing.webToolsBaseUrl.trim().trimEnd('/')
    val keepLocalWebTools = incomingWebTools.isNotEmpty() && incomingWebTools != localWebTools
    val insecureIgnored = allowInsecureHttp && !existing.allowInsecureHttp
    val merged = existing.copy(
        name = name.ifBlank { existing.name },
        provider = enumOr(provider, existing.provider),
        // 地址是「这台设备对这个服务商的信任决定」，不从备份迁移。
        baseUrl = existing.baseUrl,
        model = model.ifBlank { existing.model },
        // 明文开关从不因备份打开：只在用户当面的设置页里才能勾。
        allowInsecureHttp = existing.allowInsecureHttp,
        temperature = temperature ?: existing.temperature,
        topP = topP ?: existing.topP,
        maxOutputTokens = maxOutputTokens ?: existing.maxOutputTokens,
        frequencyPenalty = frequencyPenalty ?: existing.frequencyPenalty,
        presencePenalty = presencePenalty ?: existing.presencePenalty,
        webToolsEnabled = webToolsEnabled,
        // provider 是非敏感字段，可以跟着备份走；地址与 Key 仍保留本机值。
        webToolsProvider = WebToolsProviders.normalize(webToolsProvider),
        webToolsBaseUrl = existing.webToolsBaseUrl,
        updatedAt = updatedAt.takeIf { it > 0L } ?: existing.updatedAt
    )
    return MergedApiConfig(
        entity = merged,
        addressKept = keepLocalBaseUrl || keepLocalWebTools,
        insecureIgnored = insecureIgnored
    )
}

/**
 * 把备份里的配置建成一条新配置：密钥留空，等用户在这台手机上重新填一次。
 *
 * `customHeadersJson` 给 `{}`：备份里没有它（可能夹带 Authorization），
 * 与其猜，不如让用户在「API 配置」里显式补。
 *
 * 新配置没有「本机信任决定」可保留，但 `allowInsecureHttp` **一律从 false 开始**：
 * 明文只能由用户在这台设备上手动打开。备份里写了 `true` 时标 `insecureIgnored`，
 * 并且因为默认只允许 HTTPS，`http://` 的新地址会在导入校验时被拒（记一条 warning）。
 */
fun BackupApiConfig.toNewEntity(): MergedApiConfig {
    val insecureIgnored = allowInsecureHttp
    val entity = ApiConfigEntity(
        id = id,
        name = name.ifBlank { "接回来的配置" },
        provider = enumOr(provider, ProviderType.OPENAI),
        baseUrl = baseUrl,
        apiKeyCipher = null,
        model = model,
        customHeadersJson = "{}",
        allowInsecureHttp = false,
        temperature = temperature,
        topP = topP,
        maxOutputTokens = maxOutputTokens,
        frequencyPenalty = frequencyPenalty,
        presencePenalty = presencePenalty,
        createdAt = createdAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
        updatedAt = updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
        webToolsEnabled = webToolsEnabled,
        webToolsProvider = WebToolsProviders.normalize(webToolsProvider),
        webToolsBaseUrl = webToolsBaseUrl,
        webToolsApiKeyCipher = null
    )
    return MergedApiConfig(entity = entity, insecureIgnored = insecureIgnored)
}

fun AppSettings.toBackupSettings(): BackupSettings = BackupSettings(
    themeMode = themeMode.name,
    accentColor = accentColor,
    fontScale = fontScale,
    bubbleStyle = bubbleStyle.name,
    animationsEnabled = animationsEnabled,
    hapticsEnabled = hapticsEnabled,
    chatBackgroundColor = chatBackgroundColor,
    agentLoopEnabled = agentLoopEnabled,
    activePersonaId = activePersonaId
)

/**
 * 备份里的设置 → 本机设置。
 *
 * v1 备份里已经删掉的那批设置（聊天节奏、记忆开关、额度、单价）会作为未知字段被忽略；
 * 枚举值不认识就用默认档，不因为一个坏字段整段失败。这正是「必须能读 v1」的实现方式：
 * 不是给旧字段写兼容分支，而是让它们在新结构里**根本不存在**。
 */
fun BackupSettings.toAppSettings(): AppSettings = AppSettings(
    themeMode = enumOr(themeMode, ThemeMode.SYSTEM),
    accentColor = accentColor,
    fontScale = fontScale.coerceIn(0.85f, 1.3f),
    bubbleStyle = enumOr(bubbleStyle, BubbleStyle.SOFT),
    animationsEnabled = animationsEnabled,
    hapticsEnabled = hapticsEnabled,
    chatBackgroundColor = chatBackgroundColor,
    agentLoopEnabled = agentLoopEnabled,
    activePersonaId = activePersonaId
)

// ---------------------------------------------------------------------------
// 宽容的 JSON 取值：类型不符一律当作「字段没出现」
// ---------------------------------------------------------------------------

private fun JsonObject.textOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.intOrNull(key: String): Int? =
    runCatching { (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull }.getOrNull()

private fun JsonObject.longOrNull(key: String): Long? =
    runCatching { (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull() }.getOrNull()
