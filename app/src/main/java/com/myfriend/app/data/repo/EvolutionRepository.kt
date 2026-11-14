package com.myfriend.app.data.repo

import com.myfriend.app.data.local.ApiConfigDao
import com.myfriend.app.data.local.ChatSessionDao
import com.myfriend.app.data.local.MessageDao
import com.myfriend.app.data.local.MessageRole
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.network.AiProviderRegistry
import com.myfriend.app.network.InferenceParams
import com.myfriend.app.network.WireMessage
import com.myfriend.app.domain.EvolutionProposal
import com.myfriend.app.domain.MessageWindows
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EvolutionRepository @Inject constructor(
    private val personaDao: PersonaDao,
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    private val memoryDao: com.myfriend.app.data.local.MemoryDao,
    private val apiConfigDao: ApiConfigDao,
    private val apiConfigRepository: ApiConfigRepository,
    private val registry: AiProviderRegistry,
    private val json: Json
) {
    suspend fun generateProposal(personaId: String): Result<EvolutionProposal> = runCatching {
        val persona = personaDao.getById(personaId) ?: error("人格不存在")
        val configId = persona.defaultApiConfigId
            ?: error("请先在人格设置中绑定 API，才能生成进化提案")
        val resolved = apiConfigRepository.resolve(configId) ?: error("API 配置不存在")
        val memories = memoryDao.getForPersona(personaId, 40)
        val sessions = sessionDao.getByPersona(personaId).sortedByDescending { it.lastMessageAt ?: it.createdAt }
        val recentTranscripts = buildString {
            sessions.take(3).forEach { session ->
                // 只查最近 40 行、取最后 10 句；进化提案不需要读整段历史。
                val messages = MessageWindows.lastN(
                    messageDao.recentBySession(session.id, MessageWindows.WORKER_HISTORY_ROWS),
                    10
                )
                if (messages.isNotEmpty()) {
                    append("\n### 会话：${session.title}\n")
                    messages.forEach { message ->
                        if (message.role == MessageRole.SYSTEM || message.content.isBlank()) return@forEach
                        val speaker = if (message.role == MessageRole.USER) "对方" else persona.name
                        append("$speaker：${message.content.take(1500)}\n")
                    }
                }
            }
        }.ifBlank { "（还没有足够对话）" }
        val memoriesText = memories.joinToString("\n") { "- [${it.category.name}] ${it.content}" }
            .ifBlank { "（暂无长期记忆）" }

        val system = """
            你是一位克制而敏锐的人格编辑，负责让一个长期陪伴型人格自然成长。
            你的任务不是把人格改写成另一个人，而是根据真实相处中积累的信息，让原有性格更具体、更有连续感，同时保留其核心身份与边界。

            原则：
            1. 保留原人格的核心气质、说话方式、价值观和名字，不做面目全非的改写。
            2. 吸收长期记忆与对话中体现出的相处方式：对方在意的边界、有效的安慰方式、共同的约定。
            3. 不要加入监视感、操控性、过度依赖或削弱用户自主性的内容。
            4. 不编造没有依据的经历。不要把临时情绪写成人格特质。
            5. 新版提示词必须是可直接使用、完整、自洽的中文人格设定，而不是修改建议或差异说明。
            6. 修改应克制。如果没有足够的新信息，也应在保留原设定的基础上做小幅、合理的细化。

            只输出一个 JSON 对象，不要 Markdown 代码块，不要额外解释。格式：
            {"summary":"一句话说明这次进化的方向","prompt":"完整的新版人格提示词"}
        """.trimIndent()
        val user = """
            ## 当前人格
            名字：${persona.name}
            简介：${persona.description}
            分类：${persona.category}
            当前版本：v${persona.version}
            当前提示词：
            ${persona.prompt}

            ## 长期记忆
            $memoriesText

            ## 最近对话
            $recentTranscripts

            请基于以上信息生成进化后的完整人格提示词。
        """.trimIndent()
        val raw = registry.adapterFor(resolved.config.provider).complete(
            config = resolved.config,
            apiKey = resolved.apiKey,
            model = persona.modelOverride?.takeIf { it.isNotBlank() } ?: resolved.config.model,
            messages = listOf(
                WireMessage("system", system),
                WireMessage("user", user)
            ),
            params = InferenceParams(temperature = 0.6, maxOutputTokens = 5000),
            kind = com.myfriend.app.domain.UsageKind.CONSOLIDATION,
            personaId = persona.id
        )
        parseProposal(raw)
    }

    private fun parseProposal(raw: String): EvolutionProposal {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val obj = runCatching {
                json.parseToJsonElement(cleaned.substring(start, end + 1)).jsonObject
            }.getOrNull()
            if (obj != null) {
                val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val summary = obj["summary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (prompt.isNotBlank()) {
                    return EvolutionProposal(
                        summary = summary.ifBlank { "人格自然进化" },
                        prompt = prompt
                    )
                }
            }
        }
        throw IllegalStateException("未能解析进化提案，请重试或检查模型是否支持 JSON 输出")
    }
}
