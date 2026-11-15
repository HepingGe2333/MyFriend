package com.myfriend.app.data.repo

import androidx.room.withTransaction
import com.myfriend.app.data.local.MyFriendDatabase
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.data.local.PersonaPendingPatchDao
import com.myfriend.app.data.local.PersonaPendingPatchEntity
import com.myfriend.app.data.local.PersonaRevisionDao
import com.myfriend.app.domain.PersonaActivityCodec
import com.myfriend.app.domain.PersonaCard
import com.myfriend.app.domain.PromptBuilder
import com.myfriend.app.domain.SpeechStyleCodec
import com.myfriend.app.domain.WorldPresetCodec
import com.myfriend.app.domain.PersonaCardCodec
import com.myfriend.app.domain.PersonaCardPatch
import com.myfriend.app.domain.PersonaCardPrompt
import com.myfriend.app.domain.DomainErrorCode
import com.myfriend.app.domain.PersonaProfile
import com.myfriend.app.domain.PersonaRevisionReason
import com.myfriend.app.domain.PromptBudget
import com.myfriend.app.domain.SleepPolicy
import com.myfriend.app.network.userError
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 人设卡：结构化的人格设定 + 他自己想改的地方。
 *
 * 设计取舍：
 * - 卡是给人看、给模型看、能给 AI 自己改的结构；prompt 是它的渲染镜像，
 *   用来兼容旧流程（版本历史、导出）。
 * - 写卡就一定重写 prompt，两者不会互相漂移。
 * - AI 想改自己时只写 pendingPatches，用户点了确认才真正写进卡里。
 */
@Singleton
class PersonaCardRepository @Inject constructor(
    private val personaDao: PersonaDao,
    private val pendingPatchDao: PersonaPendingPatchDao,
    private val revisionDao: PersonaRevisionDao,
    private val database: MyFriendDatabase
) {

    private companion object {
        const val MIRROR_MAX = 8000
    }

    // ------------------------------------------------------------ 读

    fun readCard(persona: PersonaEntity): PersonaCard {
        val json = persona.personaCardJson
        if (!json.isNullOrBlank()) return PersonaCardCodec.decode(json)
        // 还没有卡：从旧的一整段提示词里拆一份出来（不写库，读的时候便宜）。
        return PersonaCardPrompt.fromLegacyPrompt(persona.prompt)
    }

    suspend fun cardOf(personaId: String): PersonaCard {
        val persona = personaDao.getById(personaId) ?: return PersonaCard()
        return readCard(persona)
    }

    /**
     * 聊天/主动消息要用的系统人格文本。
     *
     * 0.12 起统一走 [PromptBuilder] 的五段结构（身份 → 世界 → 此刻状态 → 话风 → 关系）：
     * 主动思考与主动开口看到的「他是谁」和聊天里完全一致，世界/话风/当前活动都会带进去。
     * 记忆、工具说明、语言指令这些可变部分仍由调用方自己接在后面；边界由这里收尾。
     *
     * `budget` 保留给旧调用方兼容，新的五段结构本身已经按字段长度裁剪（见 `PromptBuilder`）；
     * 没填的字段整段不出现；`proactiveLevel` 与 `memoryRetention` 是行为参数，刻意不进正文。
     */
    suspend fun systemText(
        persona: PersonaEntity,
        budget: PromptBudget = PromptBudget.NORMAL,
        now: Long = System.currentTimeMillis()
    ): String {
        val card = readCard(persona)
        val base = PromptBuilder.base(
            PromptBuilder.Inputs(
                name = persona.name,
                age = card.identity.age,
                role = card.identity.role,
                relation = card.identity.relation,
                address = card.voice.address,
                card = card,
                legacyPrompt = persona.prompt,
                profileBlock = PersonaProfile.promptBlock(persona),
                world = WorldPresetCodec.decode(persona.worldJson),
                speech = SpeechStyleCodec.decode(persona.speechStyleJson),
                activity = PersonaActivityCodec.decode(persona.activityJson),
                now = now
            )
        )
        return "$base\n\n${PromptBuilder.boundaries()}"
    }

    /** 第一次用到这张卡时把它落库，之后 AI 才能按字段改。 */
    suspend fun ensureCard(personaId: String): PersonaCard {
        val persona = personaDao.getById(personaId) ?: return PersonaCard()
        if (!persona.personaCardJson.isNullOrBlank()) return readCard(persona)
        val card = PersonaCardPrompt.fromLegacyPrompt(persona.prompt)
        if (card.isEmpty()) return card
        val now = System.currentTimeMillis()
        personaDao.update(persona.copy(personaCardJson = PersonaCardCodec.encode(card), updatedAt = now))
        return card
    }

    // ------------------------------------------------------------ 写

    /**
     * 保存整张卡（用户在卡编辑器里改，或确认了 AI 的补丁）。
     * prompt 会按卡重新渲染一遍，版本号只在渲染结果真的变了时才 +1。
     */
    suspend fun saveCard(
        personaId: String,
        card: PersonaCard,
        reason: String,
        bumpVersion: Boolean = true
    ): Result<Unit> = runCatching {
        val persona = personaDao.getById(personaId) ?: userError(DomainErrorCode.PERSONA_NOT_FOUND)
        val now = System.currentTimeMillis()
        val json = PersonaCardCodec.encode(card)
        val mirrored = mirrorOf(card).take(MIRROR_MAX)
        val changed = mirrored != persona.prompt
        val version = if (changed && bumpVersion) persona.version + 1 else persona.version
        val updated = persona.copy(
            personaCardJson = json,
            prompt = mirrored,
            version = version,
            updatedAt = now
        )
        database.withTransaction {
            if (changed) {
                insertRevision(persona, PersonaRevisionReason.CARD_BEFORE_ADJUST, now)
                insertRevision(updated, reason.ifBlank { PersonaRevisionReason.CARD_EDITED }, now)
            }
            personaDao.update(updated)
        }
    }

    /** 只改一部分字段（AI 补丁确认后走这里）。 */
    suspend fun applyPatch(
        personaId: String,
        patchJson: String,
        summary: String
    ): Result<PersonaCard> = runCatching {
        val persona = personaDao.getById(personaId) ?: userError(DomainErrorCode.PERSONA_NOT_FOUND)
        val before = readCard(persona)
        val after = PersonaCardPatch.apply(before, patchJson)
        saveCard(personaId, after, PersonaRevisionReason.selfAdjusted(summary)).getOrThrow()
        after
    }

    /**
     * 他自己安排作息。为空表示取消。
     * 只动作息两个字段，不产生新版本（不是人格变化）。
     */
    suspend fun setQuietHours(
        personaId: String,
        sleepMinutes: Int?,
        wakeMinutes: Int?,
        reason: String
    ): Result<Unit> = runCatching {
        val persona = personaDao.getById(personaId) ?: userError(DomainErrorCode.PERSONA_NOT_FOUND)
        val now = System.currentTimeMillis()
        val card = readCard(persona).let { card ->
            val schedule = when {
                sleepMinutes == null || wakeMinutes == null -> ""
                else -> "每天 ${formatMinutes(sleepMinutes)} 睡觉，${formatMinutes(wakeMinutes)} 醒来" +
                    reason.trim().takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            }
            if (schedule.isBlank()) {
                card.copy(life = card.life.copy(schedule = ""))
            } else {
                card.copy(life = card.life.copy(schedule = schedule))
            }
        }
        database.withTransaction {
            personaDao.update(
                persona.copy(
                    personaCardJson = PersonaCardCodec.encode(card),
                    quietSleepMinutes = sleepMinutes,
                    quietWakeMinutes = wakeMinutes,
                    updatedAt = now
                )
            )
        }
    }

    /**
     * 他是不是在自己安排的作息里睡着。
     *
     * 1.9 的两条新规则：
     * - `sleepEnabled = false`：他不睡觉，任何时间都会回；
     * - 开着但没填时间：按默认 23:30–08:00 睡（沿用 `quietSleepMinutes/quietWakeMinutes`）。
     *
     * 判定本身在 [SleepPolicy] 里，纯函数、可单测；这里只负责把人格字段喂进去。
     */
    fun isAsleep(persona: PersonaEntity, minuteOfDay: Int): Boolean =
        SleepPolicy.evaluate(
            sleepEnabled = persona.sleepEnabled,
            sleepMinutes = persona.quietSleepMinutes,
            wakeMinutes = persona.quietWakeMinutes,
            minuteOfDay = minuteOfDay,
            dndOnSleep = persona.dndOnSleep,
            recentUserMessages = 0,
            wakeThreshold = persona.wakeThreshold
        ).asleep

    /** 睡前免打扰：只由模型的动作写（dnd / quiet_hours 的 dnd 参数），界面上没有开关。 */
    suspend fun setDndOnSleep(personaId: String, enabled: Boolean): Result<Unit> = runCatching {
        personaDao.setDndOnSleep(personaId, enabled, System.currentTimeMillis())
    }

    /** 「他会不会主动睡觉」由用户的编辑页决定；这里只给后台一处便捷入口。 */
    suspend fun setSleepEnabled(personaId: String, enabled: Boolean): Result<Unit> = runCatching {
        val persona = personaDao.getById(personaId) ?: userError(DomainErrorCode.PERSONA_NOT_FOUND)
        personaDao.update(persona.copy(sleepEnabled = enabled, updatedAt = System.currentTimeMillis()))
    }

    // ------------------------------------------------------------ 待确认补丁

    fun observePending(personaId: String): Flow<List<PersonaPendingPatchEntity>> =
        pendingPatchDao.observeByPersona(personaId)

    suspend fun pending(personaId: String): List<PersonaPendingPatchEntity> =
        pendingPatchDao.byPersona(personaId)

    suspend fun pendingCount(personaId: String): Int = pendingPatchDao.byPersona(personaId).size

    /**
     * 启动时把历史遗留的「待确认」补丁一次性落库。
     *
     * 1.3 起他改自己不再等谁点头，这些老数据再挂着只会变成永远没人点的死按钮。
     */
    suspend fun applyAllPendingOnce(): Int {
        var applied = 0
        runCatching { personaDao.getAll() }.getOrDefault(emptyList()).forEach { persona ->
            runCatching { pendingPatchDao.byPersona(persona.id) }.getOrDefault(emptyList()).forEach { patch ->
                val ok = runCatching {
                    applyPatch(persona.id, patch.patchJson, patch.summary).isSuccess
                }.getOrDefault(false)
                runCatching { pendingPatchDao.deleteById(patch.id) }
                if (ok) applied++
            }
        }
        return applied
    }

    /** 用户点了「确认」：把补丁真的写进人设卡，并归档版本。 */
    suspend fun approvePatch(patchId: String): Result<PersonaCard> = runCatching {
        val patch = pendingPatchDao.getById(patchId) ?: userError(DomainErrorCode.PENDING_PATCH_NOT_FOUND)
        val result = applyPatch(patch.personaId, patch.patchJson, patch.summary).getOrThrow()
        pendingPatchDao.deleteById(patchId)
        result
    }

    /** 用户点了「不用了」：只丢掉这条提议，人设不变。 */
    suspend fun rejectPatch(patchId: String) {
        pendingPatchDao.deleteById(patchId)
    }

    suspend fun rejectAll(personaId: String) {
        pendingPatchDao.deleteByPersona(personaId)
    }

    /**
     * 写入一条待确认的自我调整。同一人格已有相似提议时不重复堆。
     * @return 真正写入的补丁 id，重复时返回已有那条的 id
     */
    suspend fun addPendingPatch(
        personaId: String,
        sessionId: String?,
        patchJson: String,
        summary: String,
        reason: String
    ): String {
        val existing = pendingPatchDao.byPersona(personaId).firstOrNull { it.summary == summary }
        if (existing != null) return existing.id
        val id = UUID.randomUUID().toString()
        pendingPatchDao.insert(
            PersonaPendingPatchEntity(
                id = id,
                personaId = personaId,
                sessionId = sessionId,
                patchJson = patchJson,
                summary = summary.take(120),
                reason = reason.take(300),
                createdAt = System.currentTimeMillis()
            )
        )
        return id
    }

    // ------------------------------------------------------------ 内部

    private fun mirrorOf(card: PersonaCard): String = buildString {
        append(PersonaCardPrompt.stableStrategy(card))
        append("\n\n")
        append(PersonaCardPrompt.renderContext(card, PromptBudget.RICH))
    }.trim()

    private suspend fun insertRevision(
        persona: PersonaEntity,
        reason: String,
        now: Long
    ) {
        revisionDao.insert(
            com.myfriend.app.data.local.PersonaRevisionEntity(
                id = UUID.randomUUID().toString(),
                personaId = persona.id,
                version = persona.version,
                name = persona.name,
                description = persona.description,
                avatarUri = persona.avatarUri,
                avatarSeed = persona.avatarSeed,
                prompt = persona.prompt,
                category = persona.category,
                reason = reason,
                createdAt = now
            )
        )
    }

    private fun formatMinutes(minutes: Int): String {
        val m = ((minutes % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /**
     * 提醒模型「你现在该睡了」时用的口吻提示。
     *
     * 1.9：不睡觉的人没有这一句；开着睡觉但没填时间的人，按默认 23:30–08:00 告诉他，
     * 否则他会不知道自己为什么被拦下来。
     */
    fun sleepHint(persona: PersonaEntity): String? {
        if (!persona.sleepEnabled) return null
        val (sleep, wake) = SleepPolicy.window(persona.quietSleepMinutes, persona.quietWakeMinutes)
        return "你给自己定的作息是 ${formatMinutes(sleep)} 睡觉、${formatMinutes(wake)} 醒来"
    }
}
