package com.yap.app.data.repo

import com.yap.app.data.local.ChatSessionDao
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.PersonaDao
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.data.remote.ServerApiException
import com.yap.app.data.remote.ServerLocalStore
import com.yap.app.data.remote.ServerModeRepository
import com.yap.app.data.remote.ServerMood
import com.yap.app.domain.MoodDimensions
import com.yap.app.domain.MoodJudgement
import com.yap.app.domain.MoodReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务端返回的心情 → 本地多维心情（纯映射，0.13）。
 *
 * 服务端 `MoodRoutes` 已经做过夹取，但客户端仍要自己再规整一次：
 * 旧版本服务端可能只给 label/intensity（三个维度缺省），而本地提示词依赖它们连续存在。
 * 这里复用 [MoodDimensions] 的同一套规则，绝不写第二份范围/夹取逻辑。
 */
object ServerMoodMapping {

    /**
     * @param previous 上一轮本地心情（服务端没给某个字段时沿用）
     * @return 可直接 `MoodRepository.setReading` 的值；服务端没给标签时返回 null（不写库）
     */
    fun toReading(server: ServerMood, previous: MoodReading, now: Long): MoodReading? {
        if (server.label.isBlank()) return null
        val normalized = MoodDimensions.normalize(
            label = server.label,
            intensity = server.intensity.takeIf { it > 0 } ?: previous.intensity,
            valence = server.valence,
            arousal = server.arousal,
            dominance = server.dominance,
            reason = server.reason,
            updatedAt = now
        )
        // 旧服务端没有三维字段时解出来是 0/2/3（DTO 默认值）：对 valence 来说 0 是合法值，
        // 但「缺字段」与「真的平静」在本地无法区分。这里按「服务端说了算」处理：
        // /v1/state 与 evaluate 的 Mood.toJson 一定带三维，缺字段只可能是更老的服务端。
        // 0.15：取消「每维最多变 2」的限制，只做物理范围夹取（normalize 已做）。
        // 服务端算出来的心情就是他的真实反应，客户端不再二次压平。
        return normalized
    }
}

/**
 * 服务端模式的自动情绪判定（0.13，`POST /v1/personas/{id}/mood/evaluate`）。
 *
 * 与本地 [MoodJudgementCoordinator] 的分工：
 * - **本地模式**：本机解析 API 配置、本机问一次模型（原路径一字未改）；
 * - **服务端模式**：调用服务端端点，模型与 Key 都在服务端；客户端只把结果写进本地缓存，
 *   UI/离线/提示词继续读同一份 `Personas.mood*` 列。
 *
 * 四条纪律与本地判定完全一致：
 * 1. **每条用户消息后**判定一次（0.15），游标存在 SettingsRepository 的
 *    同一组 `moodJudge*` 键里——切模式不会重复判定；
 * 2. **单飞**：同一时刻只发一次请求；
 * 3. **游标先推进**：失败也算这一轮过去了，不会变成每条消息一次请求；
 * 4. **失败/服务端没配 LLM 一律静默**：聊天主路径不能因为它报错。
 */
@Singleton
class ServerMoodEvaluator @Inject constructor(
    private val mode: ServerModeRepository,
    private val localStore: ServerLocalStore,
    private val personaDao: PersonaDao,
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    private val settingsRepository: SettingsRepository,
    private val moodRepository: MoodRepository
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val inFlight = AtomicBoolean(false)

    /** 一轮助手回复落库之后调用（与人格进化同一个触发点）；异步、不抛。 */
    fun onReplyDone(personaId: String, now: Long = System.currentTimeMillis()) {
        ensureAsync(personaId, now)
    }

    /** 与 [MoodJudgementCoordinator.ensureAsync] 同形的入口：条件满足才在后台判定一次。 */
    fun ensureAsync(personaId: String, now: Long = System.currentTimeMillis()) {
        if (personaId.isBlank()) return
        if (!mode.isEnabled()) return
        if (!inFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                runCatching { maybeEvaluate(personaId, now) }
            } finally {
                inFlight.set(false)
            }
        }
    }

    /**
     * 条件满足才请求一次服务端判定。
     *
     * @return 写入本地缓存后的心情；没到条件 / 服务端没配置模型 / 请求失败时 null
     */
    suspend fun maybeEvaluate(personaId: String, now: Long = System.currentTimeMillis()): MoodReading? {
        if (!mode.isEnabled()) return null
        val persona = runCatching { personaDao.getById(personaId) }.getOrNull() ?: return null
        val session = runCatching { sessionDao.getByPersona(personaId) }.getOrDefault(emptyList())
            .maxByOrNull { it.lastMessageAt ?: it.createdAt }
            ?: return null
        val settings = runCatching { settingsRepository.settings.first() }.getOrNull() ?: return null
        val userMessages = runCatching { messageDao.countUserMessages(session.id) }.getOrDefault(0)
        val samePersona = settings.moodJudgePersonaId == personaId
        val cursor = if (samePersona) settings.moodJudgeUserMessageCount else 0
        val lastRunAt = if (samePersona) settings.moodJudgeLastRunAt else 0L
        if (!MoodJudgement.shouldJudge(userMessages, cursor, lastRunAt, now)) return null

        // 先推进游标再请求：失败不该变成「每条消息都打一次服务端」的重试循环。
        runCatching { settingsRepository.setMoodJudgeCursor(personaId, userMessages, now) }

        val api = mode.api() ?: return null
        val serverPersonaId = runCatching { localStore.serverPersonaId(persona.id) }.getOrNull()
            ?: persona.id
        val evaluation = try {
            api.evaluateMood(serverPersonaId)
        } catch (error: Exception) {
            // 503 = 服务端没有可用的模型配置：规范要求静默跳过（下一次前台/配置好之后自然再来）。
            if ((error as? ServerApiException)?.status == 503) return null
            return null
        }
        val mood = evaluation.mood ?: return null
        val previous = runCatching { moodRepository.reading(persona.id, now) }.getOrNull()
            ?: MoodReading()
        val next = ServerMoodMapping.toReading(mood, previous, now) ?: return null
        return runCatching { moodRepository.setReading(persona.id, next, now) }.getOrNull()
    }
}
