package com.yap.agentd.proactive

import java.time.Clock
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** 一拍要判定的人格 + 它的主动程度配置。 */
data class ProactiveTarget(
    val persona: PersonaState,
    val level: LevelPolicy = LevelPolicy()
)

/**
 * 一拍判定完的完整结果。
 *
 * [decisions] 是**每个人格一条**（包括没开口的），[fired] 只是其中 `shouldFire` 的那些。
 * 分开的理由：落库/事件流只关心掷中的，而「为什么没开口」（睡着、刚聊完、正在忙、概率降了）
 * 是客户端诊断与 `/v1/proactive/tick` 的 `skipped` 要用的。
 *
 * @param now 这一拍判定的时刻（同一个 `now` 重复调用会得到空 [decisions]）
 */
data class ProactiveTick(
    val now: Long,
    val decisions: List<ProactiveDecision>
) {
    val fired: List<ProactiveDecision> get() = decisions.filter { it.shouldFire }
}

/**
 * 一拍要判定谁。
 *
 * 由主线接存储层：从 `personas` / `sessions` / `messages` / `moods` 里读出
 * 「人格 + 睡眠设置 + 最后互动 + 今天主动次数 + 心情」。
 * 刻意用函数接口而不是直接依赖 `store/`：本模块可以独立测试，也避免和存储层互相绑死。
 */
fun interface ProactiveSource {
    fun targets(now: Long): List<ProactiveTarget>
}

/** 掷中之后把决定交给谁（落库 / 事件流 / 日志）。 */
fun interface ProactiveSink {
    fun onFired(decision: ProactiveDecision)
}

/**
 * 服务端的主动心跳定时器：`ScheduledExecutorService`，默认每 60 秒跑一拍。
 *
 * 与客户端的根本差别：**它不依赖任何客户端连接**。客户端进程被杀、退到后台、系统不给后台时间，
 * 这个定时器照常推进；客户端下次打开时通过状态同步/事件流拿到 `PendingProactive`（§3.4）。
 *
 * 三个细节：
 * - [tickOnce] 可手动触发（测试与 `POST /v1/proactive/tick` 调试接口都用它），并且**同一个 `now`
 *   只会生效一次**：重复调用返回空列表，不会因为一次重试把同一个人格连发两遍；
 * - 掷中后在本进程内记一份「刚发过」的重叠状态（[overlay]），这样存储层还没写回时，
 *   下一拍也不会重复开口；跨过本地零点自动清空（每日上限按自然日重置）；
 * - 任何一个人格的 source/sink 抛异常都只影响那一个人格：定时器不能被一条坏数据打死。
 */
class ProactiveScheduler(
    private val engine: ProactiveEngine,
    private val source: ProactiveSource,
    private val sink: ProactiveSink = ProactiveSink { },
    private val policy: ProactivePolicy = ProactivePolicy(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val executor: ScheduledExecutorService = defaultExecutor()
) : AutoCloseable {

    private val lock = Any()

    /** 上一次真正生效的 tick 时刻；同 `now` 的重入会被跳过。 */
    @Volatile
    var lastTickAt: Long = 0L
        private set

    /** 本进程内「刚发过」的重叠状态（`personaId` → 最新状态）；存储层写回后即被覆盖。 */
    private val overlay = HashMap<String, PersonaState>()

    /** 重叠状态属于哪一天（本地 epoch day）：跨日就清空，避免昨天的次数压住今天。 */
    private var overlayDay: Long = Long.MIN_VALUE

    private var running = false
    private var startedAt = 0L

    /**
     * 启动定时器（幂等）。第一拍也等一个节拍之后：服务刚起来时先让状态层加载完，不抢跑。
     *
     * 已经 [stop] 过的实例不能再启动（线程池已关闭）：这里吃掉 `RejectedExecutionException`
     * 并把 `running` 复位，避免把「生命周期用错」变成定时器看似在跑、其实从不 tick。
     */
    fun start() {
        synchronized(lock) {
            if (running) return
            startedAt = clock.millis()
            running = try {
                executor.scheduleWithFixedDelay(
                    {
                        runCatching { tickOnce() }
                    },
                    policy.tickIntervalMs,
                    policy.tickIntervalMs,
                    TimeUnit.MILLISECONDS
                )
                true
            } catch (rejected: java.util.concurrent.RejectedExecutionException) {
                System.err.println("[proactive] 定时器已关闭，无法重新启动")
                false
            }
        }
    }

    /** 停掉定时器（幂等）；已在跑的这一拍会自然结束。 */
    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            executor.shutdownNow()
        }
    }

    override fun close() = stop()

    /** 定时器是不是在跑（测试/日志用）。 */
    val isRunning: Boolean get() = running

    /** 定时器启动的时刻（毫秒）；没启动时是 0。 */
    val startedAtMillis: Long get() = startedAt

    /**
     * 跑一拍：判定 [ProactiveSource] 给出的每个人格，返回**真正掷中**的那些决定。
     *
     * @param now 要判定的时刻；默认取 [clock]。同一个 `now` 重复调用只生效第一次
     */
    fun tickOnce(now: Long = clock.millis()): List<ProactiveDecision> = tick(now).fired

    /**
     * 跑一拍，返回**全部**判定（含没开口的原因）。
     *
     * 与 [tickOnce] 是同一次判定（同一个 `now` 只生效一次；「刚发过」overlay 也照旧），
     * 区别只是把 `BUSY` / `LIMITED` / `DAILY_CAP` 这些「为什么没开口」一起交回去——
     * `POST /v1/proactive/tick` 的 `skipped` 数组就是从这里来的。
     *
     * @param now 要判定的时刻；默认取 [clock]
     */
    fun tick(now: Long = clock.millis()): ProactiveTick = synchronized(lock) {
        if (now <= lastTickAt) return ProactiveTick(now, emptyList())
        lastTickAt = now
        resetOverlayIfDayChanged(now)

        val decisions = ArrayList<ProactiveDecision>(1)
        for (target in source.targets(now)) {
            val persona = merged(target.persona)
            val decision = try {
                engine.evaluate(persona, target.level, now)
            } catch (error: Exception) {
                // 一个人格的坏数据不该拖垮整个心跳。
                System.err.println("[proactive] 判定失败 persona=${persona.personaId.take(32)}：${error::class.simpleName}")
                continue
            }
            // 没掷中的也要留着：客户端/日志靠它解释「他为什么没开口」（BUSY/LIMITED/…）。
            decisions += decision
            if (!decision.shouldFire) continue
            overlay[persona.personaId] = persona.copy(
                lastProactiveAt = now,
                proactiveCountToday = persona.proactiveCountToday + 1
            )
            try {
                sink.onFired(decision)
            } catch (error: Exception) {
                System.err.println("[proactive] 落库失败 persona=${persona.personaId.take(32)}：${error::class.simpleName}")
            }
        }
        ProactiveTick(now, decisions)
    }

    // ------------------------------------------------------------------ 内部

    /** 用本进程的「刚发过」状态盖住存储层可能还没写回的那几格。 */
    private fun merged(persona: PersonaState): PersonaState {
        val cached = overlay[persona.personaId] ?: return persona
        return persona.copy(
            lastInteractionAt = maxOf(persona.lastInteractionAt, cached.lastInteractionAt),
            lastProactiveAt = maxOf(persona.lastProactiveAt, cached.lastProactiveAt),
            proactiveCountToday = maxOf(persona.proactiveCountToday, cached.proactiveCountToday)
        )
    }

    private fun resetOverlayIfDayChanged(now: Long) {
        val day = Instant.ofEpochMilli(now).atZone(clock.zone).toLocalDate().toEpochDay()
        if (day != overlayDay) {
            overlay.clear()
            overlayDay = day
        }
    }

    companion object {
        /** 单线程守护线程池：主动心跳很轻，且绝不能让 JVM 因为一个定时器停不下来。 */
        fun defaultExecutor(): ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "yap-proactive").apply { isDaemon = true }
        }
    }
}
