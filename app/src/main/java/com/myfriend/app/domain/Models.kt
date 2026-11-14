package com.myfriend.app.domain

import com.myfriend.app.data.local.ChatSessionEntity
import com.myfriend.app.data.local.MessageEntity

data class PersonaDraft(
    val id: String? = null,
    val name: String,
    val description: String,
    val avatarUri: String?,
    val avatarSeed: Int,
    val prompt: String,
    val category: String,
    val defaultApiConfigId: String?,
    val modelOverride: String?,
    // ---- 1.9 联系人细分字段（编辑页分节填写） --------------------------------
    val traits: List<String> = emptyList(),
    val likes: List<String> = emptyList(),
    val behaviors: List<String> = emptyList(),
    val backstory: String? = null,
    val quirks: String? = null,
    val proactiveLevel: Int = 3,
    val memoryRetention: Int = 3,
    val extractionEvery: Int = 6,
    val sleepEnabled: Boolean = true,
    val wakeThreshold: Int = 6,
    val quietSleepMinutes: Int? = null,
    val quietWakeMinutes: Int? = null
)

data class ApiConfigDraft(
    val id: String? = null,
    val name: String,
    val provider: com.myfriend.app.data.local.ProviderType,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val customHeadersJson: String,
    val allowInsecureHttp: Boolean,
    val temperature: Double?,
    val topP: Double?,
    val maxOutputTokens: Int?,
    val frequencyPenalty: Double?,
    val presencePenalty: Double?
)

data class ResolvedApiConfig(
    val config: com.myfriend.app.data.local.ApiConfigEntity,
    val apiKey: String?
)

data class PersonaSwitchResult(
    val success: Boolean,
    val session: ChatSessionEntity?,
    val message: String? = null
)

/** AI 在回复中提议的主动唤醒规则，需要用户确认后才会真正生效。 */
data class ProactiveProposal(
    val kind: com.myfriend.app.data.local.ProactiveKind,
    val timeOfDayMinutes: Int?,
    val intervalMinutes: Int?,
    val note: String,
    val hint: String
)

sealed class GenerationEvent {
    data object Started : GenerationEvent()
    data class Delta(val fullText: String) : GenerationEvent()
    data class Completed(
        val message: MessageEntity,
        val autoMemoryPending: Boolean,
        val proposal: ProactiveProposal? = null
    ) : GenerationEvent()
    /** 他中途用了工具：界面在「正在输入」下方显示动作卡片。 */
    data class ToolCall(val traces: List<ToolTraceItem>) : GenerationEvent()
    data class Failed(val message: String, val errorMessage: MessageEntity?) : GenerationEvent()
    data object Stopped : GenerationEvent()

    /**
     * 这一轮他选择不开口：睡着 / 免打扰 / 被 defer_reply 主动推迟。
     *
     * 界面收到它要做的只有一件事：把「正在输入」收掉。不弹解释、不显示「他可能不想回」，
     * 用户消息自己会因为 readAt 非空而显示「已读」——已读不等于会回，这是规格里的核心语义。
     */
    data object Silent : GenerationEvent()
}

data class EvolutionProposal(
    val summary: String,
    val prompt: String
)

data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val message: String
)

data class ExportFile(
    val fileName: String,
    val content: String
)

data class ChatExport(
    val session: ChatSessionEntity,
    val personaName: String,
    val messages: List<MessageEntity>,
    val configName: String?
)
