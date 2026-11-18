package com.yap.agentd.evolution

import com.yap.agentd.llm.LlmAccess
import com.yap.agentd.llm.LlmMessage
import com.yap.agentd.persona.PersonaFields
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.Message
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.PendingPatch
import com.yap.agentd.store.Persona
import com.yap.agentd.store.StorePersonaNotFoundException
import java.time.Clock
import java.util.UUID

/**
 * 人格进化的**服务端化**（0.13）：评估 → 待审补丁 → 接受/拒绝。
 *
 * | 步骤 | 做了什么 | 落盘 |
 * | --- | --- | --- |
 * | [evaluate] | 最近 12 条对话 + 人格四组值 + 当前情绪 → 模型 JSON → [PersonaEvolutionRules.sanitize] | `pending_patches.json` |
 * | [pending] | 列待审（顺手清掉过期） | 只读 |
 * | [accept] | 四字段合并 + `version + 1` | `personas.json`，删补丁 |
 * | [reject] | 只删补丁，人格一动不动 | 删补丁 |
 *
 * 三条与客户端一致的硬规则：
 * - **只碰四个字段**（traits/likes/behaviors/quirks）；名字、描述、世界、关系一律不碰；
 * - **没有真正变化不升版本**（去重后为空），但仍然把补丁删掉（它已经被处理过了）；
 * - **7 天过期**（[PENDING_TTL_MS]）：模型看到的是当时的对话，过期了就别再挂着烦人。
 *
 * 与客户端的差异：服务端**没有**「每 20 条用户消息自动评估」的触发器——本轮只有显式端点；
 * 自动触发要等客户端改为调这些端点（任务书里的"本轮只做服务端接口 + 测试"）。
 */
class EvolutionService(
    private val store: FileStore,
    private val llm: LlmAccess,
    private val clock: Clock = Clock.systemDefaultZone(),
    /** 待审补丁有效期（默认 7 天）；测试可以调小。 */
    private val ttlMs: Long = PENDING_TTL_MS
) {

    /** 一次评估的结果：[pending] 为空表示模型没有给出可用的变化（或全部被规则丢掉）。 */
    data class Evaluation(
        val proposed: Int,
        val pending: PendingPatch?,
        val summary: String,
        val alreadyPending: Boolean = false
    )

    /** 接受的结果：[changed] 为 false 表示补丁合法但去重后没有真正变化（不升版本）。 */
    data class Acceptance(
        val patch: PendingPatch,
        val persona: Persona,
        val applied: List<PersonaEvolutionRules.Change>,
        val version: Int,
        val changed: Boolean
    )

    /** 补丁不存在（或不属于 URL 里的人格）→ 路由回 404。 */
    class PatchNotFoundException(val patchId: String) : NoSuchElementException("没有这条待审补丁：$patchId")

    /** 补丁已过期（7 天）→ 路由回 410，并顺手删掉。 */
    class PatchExpiredException(val patchId: String) : NoSuchElementException("这条待审补丁已经过期：$patchId")

    // ------------------------------------------------------------------ 评估

    /**
     * 问一次模型并把结果写进待审表。
     *
     * 没有 LLM 配置时**抛 [com.yap.agentd.llm.LlmException]**（`llm_not_configured`）：
     * 这是显式的用户动作，静默返回"没有变化"会让用户以为模型觉得他没问题。
     * 模型输出坏 JSON / 全部被规则丢掉时返回 `proposed=0`（这不是错误，是"没有值得改的"）。
     */
    fun evaluate(personaId: String, now: Long = clock.millis()): Evaluation {
        val persona = store.persona(personaId) ?: throw StorePersonaNotFoundException(personaId)
        val config = llm.config()
        val current = PersonaFields.current(persona)
        val sessionId = latestSessionId(personaId)
        val recent = recentSummary(sessionId)
        val messages = listOf(
            LlmMessage.system(PersonaEvolutionRules.systemPrompt()),
            LlmMessage.user(
                PersonaEvolutionRules.userPrompt(
                    personaName = persona.name,
                    current = current,
                    recentSummary = recent,
                    mood = store.mood(personaId),
                    now = now,
                    zone = clock.zone
                )
            )
        )
        val raw = llm.complete(
            config = config,
            messages = messages,
            temperature = EVALUATION_TEMPERATURE,
            maxTokens = MAX_OUTPUT_TOKENS
        ).text
        val changes = PersonaEvolutionRules.sanitize(PersonaEvolutionRules.parse(raw), current)
        if (changes.isEmpty()) return Evaluation(proposed = 0, pending = null, summary = "")

        val patchJson = PersonaEvolutionRules.encodePatch(changes)
        val summary = PersonaEvolutionRules.summary(changes)
        // 7 天没被处理的先丢掉，再判重（同一份 patchJson 不重复挂）。
        store.dropExpiredPendingPatches(personaId, now)
        val existing = store.pendingPatches(personaId, now).firstOrNull { it.patchJson == patchJson }
        if (existing != null) {
            return Evaluation(changes.size, existing, summary, alreadyPending = true)
        }
        val patch = store.putPendingPatch(
            PendingPatch(
                id = UUID.randomUUID().toString(),
                personaId = personaId,
                sessionId = sessionId,
                patchJson = patchJson,
                summary = summary.take(120),
                reason = REASON,
                createdAt = now,
                expiresAt = now + ttlMs
            )
        )
        return Evaluation(proposed = changes.size, pending = patch, summary = summary)
    }

    // ------------------------------------------------------------------ 待审

    fun pending(personaId: String, now: Long = clock.millis()): List<PendingPatch> {
        store.persona(personaId) ?: throw StorePersonaNotFoundException(personaId)
        return store.pendingPatches(personaId, now)
    }

    // ------------------------------------------------------------------ 接受 / 拒绝

    /**
     * 接受一条补丁：把四组值合并进人格并 `version + 1`（[PersonaFields.withCurrent] 保持字段形状）。
     *
     * 失败语义：补丁不存在 → [PatchNotFoundException]；已过期 → 删掉并抛 [PatchExpiredException]。
     */
    fun accept(personaId: String, patchId: String, now: Long = clock.millis()): Acceptance {
        val persona = store.persona(personaId) ?: throw StorePersonaNotFoundException(personaId)
        val patch = requirePatch(personaId, patchId, now)
        val current = PersonaFields.current(persona)
        val changes = PersonaEvolutionRules.decodePatch(patch.patchJson)
        val after = PersonaEvolutionRules.apply(current, changes)
        val previousVersion = PersonaFields.version(persona)
        if (after == current) {
            store.deletePendingPatch(patch.id)
            return Acceptance(
                patch = patch,
                persona = persona,
                applied = emptyList(),
                version = previousVersion,
                changed = false
            )
        }
        val nextVersion = previousVersion + 1
        val extras = PersonaFields.withCurrent(persona.extraFields, after, nextVersion)
        val updated = store.updatePersona(personaId) { existing, _ ->
            existing.copy(extraFields = extras)
        }
        store.deletePendingPatch(patch.id)
        // 只有真正变了的条目进响应（去重/上限可能丢掉一部分）。
        val effective = changes.filter { change ->
            when (change.field) {
                PersonaEvolutionRules.Field.TRAITS -> after.traits != current.traits
                PersonaEvolutionRules.Field.LIKES -> after.likes != current.likes
                PersonaEvolutionRules.Field.BEHAVIORS -> after.behaviors != current.behaviors
                PersonaEvolutionRules.Field.QUIRKS -> after.quirks != current.quirks
            }
        }
        return Acceptance(patch, updated, effective, nextVersion, changed = true)
    }

    /** 拒绝：只丢掉提议，人格一动不动（过期补丁同样删掉，但回 410）。 */
    fun reject(personaId: String, patchId: String, now: Long = clock.millis()): Boolean {
        store.persona(personaId) ?: throw StorePersonaNotFoundException(personaId)
        val patch = requirePatch(personaId, patchId, now)
        return store.deletePendingPatch(patch.id)
    }

    private fun requirePatch(personaId: String, patchId: String, now: Long): PendingPatch {
        val patch = store.pendingPatch(patchId)?.takeIf { it.personaId == personaId }
            ?: throw PatchNotFoundException(patchId)
        if (patch.expiresAt in 1..now) {
            store.deletePendingPatch(patch.id)
            throw PatchExpiredException(patchId)
        }
        return patch
    }

    // ------------------------------------------------------------------ 输入

    /** 最近 12 条（每条 120 字）：「对方：… / 他：…」；会话不存在时返回空串。 */
    fun recentSummary(sessionId: String?): String {
        val id = sessionId ?: return ""
        val messages: List<Message> = store.latestMessages(id, RECENT_MESSAGES * 2, includeBlocked = false)
        return messages
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .filter { it.content.isNotBlank() }
            .takeLast(RECENT_MESSAGES)
            .joinToString("\n") { message ->
                val who = if (message.role == MessageRole.USER) "对方" else "他"
                "$who：${message.content.replace('\n', ' ').take(RECENT_MESSAGE_CHARS)}"
            }
    }

    private fun latestSessionId(personaId: String): String? = store.sessions()
        .filter { it.personaId == personaId }
        .maxByOrNull { it.lastMessageAt ?: it.createdAt }
        ?.id

    companion object {
        /** 待审补丁有效期：7 天（与客户端 `PersonaEvolutionRepository.PENDING_TTL_MS` 一致）。 */
        const val PENDING_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000

        /** 评估请求要稳、要短：温度 0.3、输出 900（与客户端同一组参数）。 */
        const val EVALUATION_TEMPERATURE: Double = 0.3
        const val MAX_OUTPUT_TOKENS: Int = 900

        /** 给模型看的最近对话：12 条、每条 120 字。 */
        const val RECENT_MESSAGES: Int = 12
        const val RECENT_MESSAGE_CHARS: Int = 120

        const val REASON: String = "这几轮聊下来，他觉得自己有点变化（需要你点头才会写进人设）。"
    }
}
