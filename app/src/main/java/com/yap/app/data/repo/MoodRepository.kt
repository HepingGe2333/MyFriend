package com.yap.app.data.repo

import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.PersonaDao
import com.yap.app.data.local.PersonaEntity
import com.yap.app.domain.MoodDrift
import com.yap.app.domain.MoodLexicon
import com.yap.app.domain.MoodPrompt
import com.yap.app.domain.MoodState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 心情的存取。
 *
 * 只做三件事：读出来（顺手让时间把它冲淡一点）、写进去（谁的、什么心情、多浓、为什么）、
 * 以及判断该不该提醒他回头看一眼。真正的「心情是什么」由模型自己说。
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

    /** 当前心情；如果时间把它冲淡了，顺手落库，免得界面与提示词对不上。 */
    suspend fun current(personaId: String, now: Long = System.currentTimeMillis()): MoodState {
        val entity = personaDao.getById(personaId) ?: return MoodState(label = MoodLexicon.BASELINE, intensity = 1)
        val stored = entity.toMoodState()
        val decayed = MoodDrift.decay(stored, now)
        if (decayed != stored) {
            personaDao.fadeMood(personaId, decayed.label, decayed.intensity)
        }
        return decayed
    }

    /**
     * 记下心情。label 会被规整（去空白、限长）；空标签理解为「回到平静」。
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
        val previous = entity.toMoodState()
        val word = MoodLexicon.normalize(label)
        val strength = (intensity ?: previous.intensity.coerceAtLeast(2)).coerceIn(1, 5)
        val cleanReason = reason?.trim()?.take(60).orEmpty()
        val next = MoodState(
            label = word,
            intensity = strength,
            reason = cleanReason,
            updatedAt = now
        )
        personaDao.setMood(
            id = personaId,
            mood = next.label,
            intensity = next.intensity,
            reason = next.reason.ifBlank { null },
            now = now
        )
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

    /** 拼进系统提示词的那一段；没有心情时也给一句基准描述。 */
    suspend fun promptLine(personaId: String, now: Long = System.currentTimeMillis()): String =
        MoodPrompt.line(current(personaId, now))

    /** 后台自己思考时要不要顺带看一眼心情（不返回文字，只返回状态，供 AgentLoop 决定）。 */
    suspend fun stateForAgent(personaId: String, now: Long = System.currentTimeMillis()): MoodState =
        current(personaId, now)
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
