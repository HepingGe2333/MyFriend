package com.yap.app.data.repo

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
    /** agent 决策（每日主动上限）：默认进程内，可切 agentd，失败自动回退。 */
    private val agentCore: AgentCoreClient
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
        val settings = runCatching { settingsRepository.settings.first() }.getOrNull()
            ?: return Beat(why = "读不到设置")
        if (!settings.agentLoopEnabled) return Beat(why = "常驻循环已关闭")

        val persona = pickPersona(settings.activePersonaId) ?: return Beat(why = "还没有可以对话的人格")

        // 他睡觉的时候不掷：既省电，也更像人。
        val minute = minuteOfDay(now)
        if (runCatching { personaCardRepository.isAsleep(persona, minute) }.getOrDefault(false)) {
            return Beat(why = "他在睡觉")
        }

        // 0.12 人设引导（规范 §4）：此刻在忙就别主动开口。
        // 这与安静时段是两件可以同时存在的事：安静 = 不被打扰，活动 = 我在做事。
        // 到 until 自动失效，由这里读取时判断，不另设定时任务。
        val activity = runCatching { PersonaActivityCodec.decode(persona.activityJson) }.getOrNull()
        val activityAvailability = activity?.availabilityAt(now)
        if (activityAvailability == com.yap.app.domain.ActivityAvailability.BUSY) {
            return Beat(why = "他正忙着：${activity?.activity.orEmpty().take(40)}")
        }

        // 刚聊完先安静一会儿：收尾期不是「概率低一点」，而是这段时间根本不掷骰子。
        // 否则 p(0)=0.35 每半分钟掷一次，一分钟内六成会触发，「先降后升」等于不存在。
        val snapshot = snapshotFor(persona, settings, now)
        if (snapshot.resetAt <= 0L) {
            // 第一次跑起来：从「刚刚互动过」开始，而不是从三个小时的思念开始。
            runCatching { settingsRepository.setHeartbeatState(now, settings.heartbeatLastFiredAt) }
            return Beat(why = "") // 这一拍只把起点定下来，不掷
        }
        val minutes = Heartbeat.minutesBetween(snapshot.resetAt, now)
        if (minutes < Heartbeat.COOL_DOWN_MINUTES) {
            return Beat(why = "刚聊完，先安静一会儿")
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
        if (!Heartbeat.shouldFire(perBeat, roll)) return Beat(why = "")

        // 中了才算这些账：没中就不花查询去确认「今天说够了没」。
        val lastTick = runCatching { agentLoop.lastTickAt() }.getOrNull()
        if (lastTick != null && now - lastTick < FIRST_TICK_GAP_MS) {
            return Beat(why = "刚想过，再等等")
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
        if (dailyCap <= 0) return Beat(why = "他今天不想主动开口")
        val spoken = runCatching { intentRepository.spokenToday(persona.id, now) }.getOrDefault(0)
        if (spoken >= dailyCap) return Beat(why = "今天说够了（$spoken/$dailyCap）")

        // 让他自己看着处境决定做什么。做什么都记在他自己的思考日志里。
        val outcome = runCatching { agentLoop.tick(now) }.getOrNull()
        // 不管他做了什么、甚至出错了，这一轮都算「想过一回」：曲线从此刻重新走。
        val finishedAt = maxOf(now, System.currentTimeMillis())
        runCatching {
            settingsRepository.setHeartbeatState(
                lastResetAt = finishedAt,
                lastFiredAt = finishedAt
            )
        }
        cached = cached?.copy(resetAt = finishedAt, at = finishedAt)
        return Beat(
            fired = true,
            acted = outcome?.acted == true,
            summary = outcome?.summary.orEmpty(),
            why = if (outcome == null) "这一轮没想成" else ""
        )
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
