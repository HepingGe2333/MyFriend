package com.yap.app.data.repo

import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.PersonaDao
import com.yap.app.data.local.PersonaEntity
import com.yap.app.domain.MoodDimensions
import com.yap.app.domain.MoodDrift
import com.yap.app.domain.MoodJudgement
import com.yap.app.domain.MoodLexicon
import com.yap.app.domain.MoodPrompt
import com.yap.app.domain.MoodReading
import com.yap.app.domain.MoodState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 心情的存取。
 *
 * 只做三件事：读出来（顺手让时间把它冲淡一点）、写进去（谁的、什么心情、多浓、为什么、三个维度）、
 * 以及判断该不该提醒他回头看一眼。真正的「心情是什么」由模型自己说。
 *
 * 0.13 起心情有两条并存的读法：
 * - [current] 只给旧的标签 + 强度（心跳曲线、agentcore 的主动上限、界面都还只认这个）；
 * - [reading] 给多维（标签 + 强度 + valence/arousal/dominance），提示词与自动判定用它。
 *
 * 旧库（v16 及以前）只有标签与强度：三个维度列是 NULL，这里按规范 §1 现场折算
 * （label → valence、intensity → arousal、dominance=3），**不回写**——把「折算值」
 * 当成人真的说过的心情存下来，会让下一轮判定误以为「他已经有维度了」而不去重新判断。
 */
@Singleton
class MoodRepository @Inject constructor(
    private val personaDao: PersonaDao,
    private val messageDao: MessageDao
) {

    /** 界面上实时显示的心情（已经过时间衰减）。 */
    fun observe(personaId: String): Flow<MoodState> =
        personaDao.observeById(personaId).map { entity ->
            entity?.let { MoodDrift.decay(it.toMoodState(), System.currentTimeMillis()) } ?: MoodState(
                label = MoodLexicon.BASELINE,
                intensity = 1
            )
        }

    /** 当前心情（标签 + 强度）；如果时间把它冲淡了，顺手落库，免得界面与提示词对不上。 */
    suspend fun current(personaId: String, now: Long = System.currentTimeMillis()): MoodState {
        val entity = personaDao.getById(personaId) ?: return MoodState(label = MoodLexicon.BASELINE, intensity = 1)
        val stored = entity.toMoodState()
        val decayed = MoodDrift.decay(stored, now)
        if (decayed != stored) {
            // 三个维度只在库里本来就有值时一起衰减：老库的 NULL 表示「还没算过」，
            // 不该被这里的写路径顺手写成一个折算出来的数字。
            val dims = MoodDimensions.decay(entity.toMoodReading(), MoodDimensions.elapsedHours(stored.updatedAt, now))
            personaDao.fadeMood(
                id = personaId,
                mood = decayed.label,
                intensity = decayed.intensity,
                valence = entity.moodValence?.let { dims.valence },
                arousal = entity.moodArousal?.let { dims.arousal },
                dominance = entity.moodDominance?.let { dims.dominance }
            )
        }
        return decayed
    }

    /**
     * 当前多维心情：标签/强度按 [MoodDrift] 淡去，三个维度按 [MoodDimensions] 每小时向基准靠一步。
     *
     * 落库只在真的淡了时发生（写标签/强度/维度，但不动 `moodUpdatedAt`：
     * 衰减不是一次新的情绪，下一轮仍从「上次真正变化」的时间算）。
     */
    suspend fun reading(personaId: String, now: Long = System.currentTimeMillis()): MoodReading {
        val entity = personaDao.getById(personaId)
            ?: return MoodDimensions.fromLegacy(MoodLexicon.BASELINE, 1)
        val stored = entity.toMoodReading()
        val decayed = decayed(stored, now)
        if (decayed != stored) {
            // 三个维度只在库里本来就有值时一起衰减：老库/没判定过的行是 NULL，
            // 那代表「还没算过」；把折算值写回去会让下一轮判定误以为「已经有维度了」。
            val hasStoredDims = entity.moodValence != null ||
                entity.moodArousal != null ||
                entity.moodDominance != null
            personaDao.fadeMood(
                id = personaId,
                mood = decayed.label,
                intensity = decayed.intensity,
                valence = if (hasStoredDims) decayed.valence else null,
                arousal = if (hasStoredDims) decayed.arousal else null,
                dominance = if (hasStoredDims) decayed.dominance else null
            )
        }
        return decayed
    }

    /**
     * 记下心情（`set_mood` 工具与 agent 决策的老入口）。
     *
     * 只给了标签/强度时：标签没变就保留原来的三个维度（他可能只是「更烦了一点」），
     * 标签变了就按旧数据映射给一组维度、并保留原来的掌控感——比一步跳回 3 更连贯。
     * 返回落库后的状态，人设不存在时返回 null。
     */
    suspend fun setMood(
        personaId: String,
        label: String,
        intensity: Int? = null,
        reason: String? = null,
        now: Long = System.currentTimeMillis()
    ): MoodState? {
        val entity = personaDao.getById(personaId) ?: return null
        val previous = entity.toMoodReading()
        val word = MoodLexicon.normalize(label)
        val strength = (intensity ?: previous.intensity.coerceAtLeast(2)).coerceIn(1, 5)
        val dims = if (word == MoodLexicon.normalize(previous.label)) {
            MoodDimensions.decay(previous, MoodDimensions.elapsedHours(previous.updatedAt, now)).copy(
                intensity = strength
            )
        } else {
            MoodDimensions.fromLegacy(word, strength).copy(dominance = previous.dominance)
        }
        val next = MoodDimensions.normalize(
            label = word,
            intensity = strength,
            valence = dims.valence,
            arousal = dims.arousal,
            dominance = dims.dominance,
            reason = reason?.trim()?.take(60).orEmpty(),
            updatedAt = now
        )
        write(personaId, next)
        return next.toMoodState()
    }

    /**
     * 整组写一次多维心情（自动判定与迁移兜底用）。
     *
     * 与 [setMood] 的区别：这里三个维度是模型/判定算出来的真值，不做事后再推导。
     * 返回落库后的结果，人设不存在时返回 null。
     */
    suspend fun setReading(
        personaId: String,
        reading: MoodReading,
        now: Long = System.currentTimeMillis()
    ): MoodReading? {
        if (personaDao.getById(personaId) == null) return null
        val next = MoodDimensions.normalize(
            label = reading.label,
            intensity = reading.intensity,
            valence = reading.valence,
            arousal = reading.arousal,
            dominance = reading.dominance,
            reason = reading.reason,
            updatedAt = now
        )
        write(personaId, next)
        return next
    }

    /**
     * 「判定失败 / 没有 API 配置」的兜底：按时间衰减，不报错（规范 §2）。
     *
     * 只在真的淡了（超过一小时）时写库；返回衰减后的结果，人设不存在时返回 null。
     */
    suspend fun decayForFailure(personaId: String, now: Long = System.currentTimeMillis()): MoodReading? {
        val entity = personaDao.getById(personaId) ?: return null
        val stored = entity.toMoodReading()
        val next = MoodJudgement.decayAfterFailure(stored, now)
        if (next == stored) return stored
        write(personaId, next.copy(updatedAt = stored.updatedAt))
        return next
    }

    /** 从上次动过心情（或上次被问过）到现在，他自己说了几句（跨会话）。 */
    private suspend fun spokenSinceTouch(personaId: String, since: Long): Int =
        messageDao.countAssistantSince(personaId, since.coerceAtLeast(0L))

    /**
     * 该引导心情了吗？
     *
     * 每说几句话就问一次「现在我的心情变得……」，把引导词交回给调用方拼进消息里；
     * 不该问的时候返回 null。
     */
    suspend fun reflectionNudge(
        personaId: String,
        now: Long = System.currentTimeMillis(),
        threshold: Int = 3
    ): String? {
        val entity = personaDao.getById(personaId) ?: return null
        val state = current(personaId, now)
        val lastTouch = maxOf(state.updatedAt, entity.moodNudgedAt ?: 0L)
        val spoken = spokenSinceTouch(personaId, lastTouch)
        if (!MoodDrift.shouldReflect(state, spoken, now, threshold, lastTouchAt = lastTouch)) return null
        // 先记下「已经问过」，免得同一句话下一轮又被追问。
        personaDao.markMoodNudged(personaId, now)
        return MoodPrompt.reflectionNudge(state, spoken)
    }

    /** 拼进系统提示词的那一段（兼容旧调用方，只给标签 + 强度）。 */
    suspend fun promptLine(personaId: String, now: Long = System.currentTimeMillis()): String =
        MoodPrompt.line(current(personaId, now))

    /** 后台自己思考时要不要顺带看一眼心情（不返回文字，只返回状态，供 AgentLoop 决定）。 */
    suspend fun stateForAgent(personaId: String, now: Long = System.currentTimeMillis()): MoodState =
        current(personaId, now)

    // ------------------------------------------------------------------ 内部

    private suspend fun write(personaId: String, reading: MoodReading) {
        personaDao.setMoodVector(
            id = personaId,
            mood = reading.label,
            intensity = reading.intensity,
            reason = reading.reason.ifBlank { null },
            valence = reading.valence,
            arousal = reading.arousal,
            dominance = reading.dominance,
            now = reading.updatedAt
        )
    }

    /** 标签/强度用 [MoodDrift]，三个维度用 [MoodDimensions]；两边都从「上次真正变化」算起。 */
    private fun decayed(reading: MoodReading, now: Long): MoodReading {
        val state = MoodDrift.decay(reading.toMoodState(), now)
        val dims = MoodDimensions.decay(reading, MoodDimensions.elapsedHours(reading.updatedAt, now))
        // 标签已经淡回基准（超过 6 小时）：三个维度也一起归位，免得出现「平静（愉快度 -1）」。
        val reset = state.label == MoodLexicon.BASELINE && state.intensity <= 1
        return dims.copy(
            label = state.label,
            intensity = state.intensity,
            reason = state.reason,
            updatedAt = state.updatedAt,
            valence = if (reset) MoodDimensions.BASELINE_VALENCE else dims.valence,
            arousal = if (reset) MoodDimensions.BASELINE_AROUSAL else dims.arousal,
            dominance = if (reset) MoodDimensions.BASELINE_DOMINANCE else dims.dominance
        )
    }
}

/** 实体 → 多维心情：维度列为 NULL（v16 老库 / 从没判定过）时按规范 §1 现场折算。 */
private fun PersonaEntity.toMoodReading(): MoodReading {
    val label = mood?.let(MoodLexicon::normalize).orEmpty()
    val intensity = (moodIntensity ?: 2).coerceIn(1, 5)
    val legacy = MoodDimensions.fromLegacy(label, intensity, moodReason.orEmpty(), moodUpdatedAt ?: 0L)
    if (label.isBlank()) return legacy.copy(updatedAt = moodUpdatedAt ?: 0L)
    return legacy.copy(
        valence = moodValence ?: legacy.valence,
        arousal = moodArousal ?: legacy.arousal,
        dominance = moodDominance ?: legacy.dominance,
        updatedAt = moodUpdatedAt ?: 0L
    )
}

private fun PersonaEntity.toMoodState(): MoodState {
    val label = mood?.let(MoodLexicon::normalize).orEmpty()
    if (label.isBlank()) {
        return MoodState(label = MoodLexicon.BASELINE, intensity = 1, updatedAt = moodUpdatedAt ?: 0L)
    }
    return MoodState(
        label = label,
        intensity = (moodIntensity ?: 2).coerceIn(1, 5),
        reason = moodReason.orEmpty(),
        updatedAt = moodUpdatedAt ?: 0L
    )
}
