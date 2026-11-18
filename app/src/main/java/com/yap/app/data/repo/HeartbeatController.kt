package com.yap.app.data.repo

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.yap.app.BuildConfig
import com.yap.app.data.local.AgentTickDao
import com.yap.app.data.local.ChatSessionDao
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.PersonaDao
import com.yap.app.data.local.PersonaEntity
import com.yap.app.core.AgentCoreClient
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.domain.Heartbeat
import com.yap.app.domain.HeartbeatState
import com.yap.app.domain.MoodBehavior
import com.yap.app.domain.ProactiveCapInput
import com.yap.app.domain.MoodTuning
import com.yap.app.domain.MoodState
import com.yap.app.domain.PersonaActivityCodec
import com.yap.app.domain.ProactiveDiagnostics
import com.yap.app.domain.ProactiveDiagnostics.Reason
import com.yap.app.domain.ProactiveLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** 心跳诊断日志的 logcat tag：`adb logcat -s Heartbeat` 就能只看着他这一条线。 */
private const val HEARTBEAT_LOG_TAG = "Heartbeat"

/**
 * 他的心跳：每半分钟问自己一句「现在想不想他」。
 *
 * 这不是一个「每 30 秒发一条消息」的机制，正好相反：绝大多数拍什么都不会发生。
 * 每一拍只做一件很轻的事 —— 看距离上次互动过了多久、把这股冲动的概率算出来、掷一次骰子。
 * 没中就什么都不做（几微秒 CPU）；中了才叫他去 [AgentLoop.tick]，由**他自己**看着处境决定
 * 说什么、去翻点什么、还是干脆什么都不做。
 *
 * 三个刻意的设计：
 *
 * 1. **真源是「最近一次互动」**：用户刚说话、他刚说话、上次冲动触发，取这几个时间里最新的那个。
 *    这样不用改聊天仓库，也不会漏掉某一种互动；而且他说的每句话都会让曲线重新走一遍。
 * 2. **跑完就重置**：不管模型最后决定做什么（甚至出错），概率时钟都回到起点。
 *    否则沉默越久越容易连击，那就成了推送。
 * 3. **门槛在掷骰子之前**：睡着、今天说够了、刚想过（5 分钟下限）——
 *    这些都不掷，直接跳过。既省电，也不会让「他」显得不知分寸。
 *    1.9 起「每天说几次」由人格的 proactiveLevel 决定，没有任何额度或设置参与。
 *
 * 这里没有任何用户可调的东西：没有开关、没有频率、没有概率。界面上看不到它。
 */
@Singleton
class HeartbeatController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val agentLoop: AgentLoop,
    private val settingsRepository: SettingsRepository,
    private val personaDao: PersonaDao,
    private val personaCardRepository: PersonaCardRepository,
    private val moodRepository: MoodRepository,
    private val intentRepository: AgentIntentRepository,
    private val tickDao: AgentTickDao,
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    /** 他的日记板：到点的提醒在这里变成念头（`spec/diary/README.md`）。 */
    private val diaryRepository: DiaryRepository,
    /** 0.14 心理活动：活跃窗口里每 3 分钟/3 轮一次；后台心跳负责「时间到了」那一半。 */
    private val mentalActivityRepository: MentalActivityRepository,
    /** agent 决策（每日主动上限）：默认进程内，可切 agentd，失败自动回退。 */
    private val agentCore: AgentCoreClient,
    /**
     * 服务端模式：他此时在服务端那边活着（主动消息/情绪/日记提醒都由服务端自己跑）。
     * 客户端的心跳必须停下，否则就是双份主动消息、双份情绪判定。
     */
    private val serverMode: com.yap.app.data.remote.ServerModeRepository
) {

    /**
     * 一拍的结果。
     *
     * @param fired 这一拍是不是真的起心动念了（掷中了）
     * @param acted 他最后是不是真的做了点什么（说话/上网/记事…）；命中但决定「什么都不做」时为 false
     * @param summary 他这一轮的一句中文记录，只在真的做了事的时候才值得显示
     * @param why 没动的原因（内部用，中文，不进提示词也不给用户看）
     */
    data class Beat(
        val fired: Boolean = false,
        val acted: Boolean = false,
        val summary: String = "",
        val why: String = ""
    )

    /** 一次「心跳」：轻到可以每半分钟跑一回；只有掷中的时候才会花时间。 */
    suspend fun beat(
        now: Long = System.currentTimeMillis(),
        roll: Double = Random.nextDouble()
    ): Beat {
        // 服务端模式：这一拍什么都不做。放在最前面，连设置/库都不读。
        if (serverMode.isEnabled()) return stop(Reason.SERVER_MODE, why = "服务端模式：主动由服务端负责")
        val settings = runCatching { settingsRepository.settings.first() }.getOrNull()
            ?: return stop(Reason.SETTINGS_UNREADABLE, why = "读不到设置")
        if (!settings.agentLoopEnabled) return stop(Reason.LOOP_DISABLED, why = "常驻循环已关闭")

        val persona = pickPersona(settings.activePersonaId)
            ?: return stop(Reason.NO_PERSONA, why = "还没有可以对话的人格")
        // 0.14：拉黑期间不主动、不想、不打扰。
        if (com.yap.app.domain.BlockPolicy.isBlocked(persona.blockedUntil, now)) {
            return stop(Reason.BUSY, why = "拉黑期间不主动")
        }
        // 心理活动与「想不想开口」无关：活跃窗口里即使不掷骰子，也可以自己在心里想一句。
        // 内部会按 3 分钟 / 3 轮 / 激动 / 心情变化自行判断；失败安静跳过。
        runCatching {
            val sessionId = sessionDao.latestForPersona(persona.id)?.id
            if (sessionId != null) mentalActivityRepository.ensureAsync(sessionId, now, justSent = false)
        }

        // 日记里到点的提醒先变成「念头」，再走下面那套「想不想开口」的判定。
        // 放在睡觉/在忙的判断**之前**：提醒到点是他心里的一件事，不该因为他睡了就被丢掉；
        // 要不要因此说话，仍然由循环自己决定（睡着时它不会开口，念头留在窗口里）。
        // 只会触发一次：DiaryRepository 用带条件的 UPDATE 原子占位，错过 >24h 只清标记不补。
        runCatching { diaryRepository.remindDue(now) }

        // 他睡觉的时候不掷：既省电，也更像人。
        val minute = minuteOfDay(now)
        if (runCatching { personaCardRepository.isAsleep(persona, minute) }.getOrDefault(false)) {
            return stop(Reason.SLEEPING, why = "他在睡觉")
        }

        // 0.12 人设引导（规范 §4）：此刻在忙就别主动开口。
        // 这与安静时段是两件可以同时存在的事：安静 = 不被打扰，活动 = 我在做事。
        // 到 until 自动失效，由这里读取时判断，不另设定时任务。
        val activity = runCatching { PersonaActivityCodec.decode(persona.activityJson) }.getOrNull()
        val activityAvailability = activity?.availabilityAt(now)
        if (activityAvailability == com.yap.app.domain.ActivityAvailability.BUSY) {
            return stop(Reason.BUSY, why = "他正忙着：${activity?.activity.orEmpty().take(40)}")
        }

        // 刚聊完先安静一会儿：收尾期不是「概率低一点」，而是这段时间根本不掷骰子。
        // 否则 p(0)=0.35 每半分钟掷一次，一分钟内六成会触发，「先降后升」等于不存在。
        val snapshot = snapshotFor(persona, settings, now)
        if (snapshot.resetAt <= 0L) {
            // 第一次跑起来：从「刚刚互动过」开始，而不是从三个小时的思念开始。
            val saved = runCatching {
                settingsRepository.setHeartbeatState(now, settings.heartbeatLastFiredAt)
            }.isSuccess
            // 修 bug（真机反馈「FIRST_BEAT 连打两三次」）：写完盘必须同时更新内存快照。
            // 否则 60 秒 TTL 内接下来两拍拿到的还是 resetAt = 0，会把这个「首次起点」分支
            // 再走一遍。写盘失败时把起点留在进程内（unsavedResetAt），下一拍快照刷新还能接上，
            // 不会变成「每十分钟重新定一次起点、永远不掷骰子」。
            if (!saved) unsavedResetAt = now
            cached = snapshot.copy(resetAt = now, at = now)
            return stop(Reason.FIRST_BEAT, detail = "第一次心跳，把概率起点定在 $now")
        }
        val minutes = Heartbeat.minutesBetween(snapshot.resetAt, now)
        if (minutes < Heartbeat.COOL_DOWN_MINUTES) {
            return stop(
                Reason.COOL_DOWN,
                why = "刚聊完，先安静一会儿",
                detail = "刚聊完（距上次互动 ${oneDecimal(minutes)} 分钟，" +
                    "冷却 ${Heartbeat.COOL_DOWN_MINUTES.toInt()} 分钟）"
            )
        }

        // 概率是「每分钟」的，心跳是离散采样：换算成这一拍的概率再掷。
        // 拿的是上一次真正决定的节拍间隔（低电量时是 2 分钟），不是写死的 30 秒。
        val perMinute = Heartbeat.probability(minutes, snapshot.mood, now, hourOfDay(now))
        // 1.9：主动程度（人格字段）给心跳一个权重。1 的人很少心动，5 的人明显更黏。
        // LIMITED：可以想起你，但明显更少（沿用同一条心跳曲线，只降权）。
        val activityWeight = if (activityAvailability == com.yap.app.domain.ActivityAvailability.LIMITED) {
            com.yap.app.domain.PersonaActivity.LIMITED_PROACTIVE_FACTOR
        } else {
            1.0
        }
        val weighted = perMinute * ProactiveLevel.heartbeatWeight(persona.proactiveLevel) * activityWeight
        val perBeat = Heartbeat.perBeatProbability(weighted, intervalMinutes()).coerceIn(0.0, 1.0)
        if (!Heartbeat.shouldFire(perBeat, roll)) {
            return stop(
                Reason.ROLL_MISSED,
                detail = "本拍概率 ${percent(perBeat)}，掷出 ${percent(roll)}，没中"
            )
        }

        // 中了才算这些账：没中就不花查询去确认「今天说够了没」。
        val lastTick = runCatching { agentLoop.lastTickAt() }.getOrNull()
        if (lastTick != null && now - lastTick < FIRST_TICK_GAP_MS) {
            return stop(
                Reason.TICK_GAP,
                why = "刚想过，再等等",
                detail = "距上次思考 ${(now - lastTick) / 1000} 秒" +
                    "（下限 ${FIRST_TICK_GAP_MS / 60_000} 分钟）"
            )
        }
        // 每日上限由人格的主动程度决定；1.9 没有「每天最多主动几次」这个设置了。
        // 0.11.0：换算走 agent 决策层（默认进程内，可切 agentd），规则只有 :agentcore 一份。
        val dailyCap = withContext(Dispatchers.IO) {
            agentCore.proactiveCap(
                ProactiveCapInput(
                    level = persona.proactiveLevel,
                    moodLabel = snapshot.mood.label,
                    moodIntensity = snapshot.mood.intensity
                )
            )
        }
        if (dailyCap <= 0) {
            return stop(Reason.DAILY_CAP, why = "他今天不想主动开口", detail = "主动上限算出来是 0")
        }
        val spoken = runCatching { intentRepository.spokenToday(persona.id, now) }.getOrDefault(0)
        if (spoken >= dailyCap) {
            return stop(Reason.DAILY_CAP, why = "今天说够了（$spoken/$dailyCap）")
        }

        // 让他自己看着处境决定做什么。做什么都记在他自己的思考日志里。
        val outcome = runCatching { agentLoop.tick(now) }.getOrNull()
        // 不管他做了什么、甚至出错了，这一轮都算「想过一回」：曲线从此刻重新走。
        val finishedAt = maxOf(now, System.currentTimeMillis())
        val savedReset = runCatching {
            settingsRepository.setHeartbeatState(
                lastResetAt = finishedAt,
                lastFiredAt = finishedAt
            )
        }.isSuccess
        // 写盘失败时同样留一个进程内起点：否则下一次快照刷新会退回旧的 resetAt，
        // 可能立刻又掷中（频率变密），也可能像首拍那样反复重来。
        if (!savedReset) unsavedResetAt = finishedAt
        cached = cached?.copy(resetAt = finishedAt, at = finishedAt)
        // why 保持原样（调用方不看它，但别改既有返回值）；logcat 用 classify 把 tick 的
        // summary 翻译成「到底是没配置、没解析出来、被分寸拦住，还是真的做了点什么」。
        val acted = outcome?.acted == true
        val summary = outcome?.summary.orEmpty()
        val why = if (outcome == null) "这一轮没想成" else ""
        return stop(
            reason = ProactiveDiagnostics.classify(
                fired = true,
                acted = acted,
                why = why,
                tickSummary = summary
            ),
            why = why,
            fired = true,
            acted = acted,
            summary = summary,
            detail = summary.ifBlank { why }
        )
    }

    /**
     * 把这一拍记进本地 debug 日志，然后**原样**返回同一个 [Beat]。
     *
     * 为什么每个 return 都要走这里：真机反馈「他不主动找我」时，logcat 必须能直接回答
     * 「这一拍卡在哪道闸门」。如果只在函数末尾打一条，早退的拍（绝大多数）就什么都看不到。
     * 日志只在 debug 构建里打；它不读库、不改概率、不改顺序，只是把已经算好的结果记下来。
     *
     * `detail` 为空时退回 `why`，再退回 [Reason.explanation]，保证任何一条日志都能看懂。
     */
    private fun stop(
        reason: Reason,
        why: String = "",
        fired: Boolean = false,
        acted: Boolean = false,
        summary: String = "",
        detail: String = ""
    ): Beat {
        if (BuildConfig.DEBUG) {
            Log.d(
                HEARTBEAT_LOG_TAG,
                ProactiveDiagnostics.logLine(
                    fired = fired,
                    acted = acted,
                    reason = reason,
                    detail = detail.ifBlank { why }
                )
            )
        }
        return Beat(fired = fired, acted = acted, summary = summary, why = why)
    }

    /**
     * 下一次心跳隔多久（毫秒）。
     *
     * 正常是 30 秒。电量低于 20% 又没在充电时放宽到 2 分钟 ——
     * 这不是产品行为，是设备约束：后台常驻的长连接/心跳不该先把手机电耗光。
     * （他本来也不会在半小时内连着找你两次，放宽间隔不改变用户能看到的东西。）
     */
    fun checkIntervalMs(): Long =
        if (isBatterySaving()) LOW_BATTERY_BEAT_MS else BEAT_INTERVAL_MS

    // ---------------------------------------------------------------- 快照

    /**
     * 一拍要看的那几件事的缓存。
     *
     * 心跳每 30 秒一次，一天 2880 拍，而绝大多数拍注定什么都不做；如果每一拍都去问
     * 「他最近说过什么、心情怎样、上次重置在哪」，光是这几条查询就够把手机从睡梦里叫醒几十次。
     * 这些东西在 60 秒内的变化对他「想不想你」没有意义，所以缓存一分钟。
     *
     * @param resetAt 概率时钟的起点（上次互动 / 上次冲动里最新的那个）
     */
    private data class BeatSnapshot(
        val at: Long = 0L,
        val personaId: String = "",
        val resetAt: Long = 0L,
        val mood: MoodState = MoodState(),
        val tuning: MoodTuning = MoodTuning()
    )

    private var cached: BeatSnapshot? = null

    /**
     * 首拍起点写盘失败时的**进程内兜底**。
     *
     * 只在 DataStore 写不进去（磁盘满 / 被系统拒绝）时才会用到。没有它的话，快照 60 秒过期的
     * 下一次刷新会读到 `heartbeatLastResetAt = 0`，于是又一次「首次起点」→ 又冷却十分钟，
     * 如此循环，他永远不会真正掷骰子。它不是产品状态，只保证「一次写失败不该让心跳停摆」。
     */
    private var unsavedResetAt = 0L

    private suspend fun snapshotFor(
        persona: PersonaEntity,
        settings: com.yap.app.data.prefs.AppSettings,
        now: Long
    ): BeatSnapshot {
        val fresh = cached
        if (fresh != null && fresh.personaId == persona.id && now - fresh.at < SNAPSHOT_TTL_MS) {
            return fresh
        }
        val mood = runCatching { moodRepository.current(persona.id, now) }.getOrDefault(MoodState())
        val resetAt = Heartbeat.latestMark(
            settings.heartbeatLastResetAt,
            settings.heartbeatLastFiredAt,
            // 写盘失败时的进程内起点：取 max 让「持久化的值」始终优先，只在它缺席时兜底。
            unsavedResetAt,
            runCatching { lastInteractionAt(persona.id) }.getOrNull()
        )
        val snapshot = BeatSnapshot(
            at = now,
            personaId = persona.id,
            resetAt = resetAt,
            mood = mood,
            tuning = MoodBehavior.from(mood, now)
        )
        cached = snapshot
        return snapshot
    }

    /** 这一拍覆盖多少分钟：跟真正用的节拍走，低电量时是 2 分钟。 */
    private fun intervalMinutes(): Double = checkIntervalMs() / 60_000.0

    // ---------------------------------------------------------------- 互动时间

    /**
     * 最近一次「有互动」的时刻：用户说的最后一句、他说的最后一句、他上次主动开口，取最大值。
     *
     * 都查不到就返回 0，交给调用方当「还没有起点」处理。
     */
    private suspend fun lastInteractionAt(personaId: String): Long? {
        val session = runCatching {
            sessionDao.getByPersona(personaId)
                .maxByOrNull { it.lastMessageAt ?: it.createdAt }
        }.getOrNull()
        val userAt = session?.let {
            runCatching { messageDao.latestUser(it.id)?.createdAt }.getOrNull()
        }
        val ownAt = session?.let {
            runCatching { messageDao.latestAssistant(it.id)?.createdAt }.getOrNull()
        }
        // 他主动开口时也可能还没进最新那个会话，所以再直接问一次思考日志。
        val spokenAt = runCatching { tickDao.lastSpokenAt(personaId) }.getOrNull()
        return listOfNotNull(userAt, ownAt, spokenAt).maxOrNull()
    }

    // ---------------------------------------------------------------- 设备约束

    /** 电量低于 20% 且没在充电：心跳放慢，别把电耗在后台。 */
    private fun isBatterySaving(): Boolean = runCatching {
        // 传 null 的 receiver 拿的是系统的 sticky intent，不注册、不留监听。
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return@runCatching false
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return@runCatching false
        val percent = level * 100 / scale
        val state = status.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = state == BatteryManager.BATTERY_STATUS_CHARGING ||
            state == BatteryManager.BATTERY_STATUS_FULL
        percent < LOW_BATTERY_PERCENT && !charging
    }.getOrDefault(false)

    // ---------------------------------------------------------------- 辅助

    private suspend fun pickPersona(activePersonaId: String?): PersonaEntity? {
        activePersonaId?.let { id -> runCatching { personaDao.getById(id) }.getOrNull()?.let { return it } }
        val all = runCatching { personaDao.getAll() }.getOrDefault(emptyList())
        return all.firstOrNull { it.isDefault } ?: all.firstOrNull()
    }

    private fun minuteOfDay(now: Long): Int = runCatching {
        val time = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalTime()
        time.hour * 60 + time.minute
    }.getOrDefault(0)

    private fun hourOfDay(now: Long): Int = minuteOfDay(now) / 60

    /**
     * 日志里只留一位小数：几毫秒的精度对「他为什么没主动」没有意义，
     * 但整数会把 0.4 分钟抹成 0，看起来像「刚互动完」。
     */
    private fun oneDecimal(value: Double): String {
        if (!value.isFinite()) return "0.0"
        val scaled = (value * 10).toInt()
        return "${scaled / 10}.${scaled % 10}"
    }

    /** 概率写成 6.1% 这种样子：logcat 里比 0.061 好读，也方便和曲线参数对照。 */
    private fun percent(value: Double): String {
        if (!value.isFinite()) return "0.0%"
        val scaled = (value * 1000).toInt()
        return "${scaled / 10}.${scaled % 10}%"
    }

    companion object {
        /** 一拍里要读的那几件事（互动、心情、重置点），一分钟内不重复读。 */
        private const val SNAPSHOT_TTL_MS = 60_000L

        /** 心跳的节拍：30 秒。比人一次「走神」的间隔短得多，所以它只是判断，不是动作。 */
        const val BEAT_INTERVAL_MS = 30_000L

        /** 省电时的节拍：2 分钟（设备约束，不是产品行为）。 */
        const val LOW_BATTERY_BEAT_MS = 2 * 60_000L

        /** 电量低于这个百分比就省电。 */
        const val LOW_BATTERY_PERCENT = 20

        /**
         * 两次真正思考之间至少隔这么久，哪怕服务刚被重新拉起。
         *
         * 原来是服务里的「首次 tick 等待」；心跳之后每 30 秒都在判定，
         * 这个下限必须落在判定这一层，否则一波连掷就可能连着想好几次。
         */
        const val FIRST_TICK_GAP_MS = 5 * 60_000L
    }
}
