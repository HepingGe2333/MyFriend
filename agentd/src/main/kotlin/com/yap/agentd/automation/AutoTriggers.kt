package com.yap.agentd.automation

import com.yap.agentd.evolution.EvolutionService
import com.yap.agentd.llm.LlmErrorKind
import com.yap.agentd.llm.LlmException
import com.yap.agentd.mood.MoodService
import com.yap.agentd.store.AutoState
import com.yap.agentd.store.FileStore
import java.time.Clock

/**
 * 聊天结束后的**服务端自动判定**（`spec/mood/README.md` §2、`spec/persona-evolution`）。
 *
 * 契约（`docs/design/server-authoritative.md` 的目标 3）：**这些动作不依赖客户端连接**。
 * 客户端连不连、在不在前台，都改变不了「聊满 4 条就判一次心情、满 20 条就评估一次进化」。
 *
 * | 动作 | 触发条件（各自独立） | 产物 |
 * | --- | --- | --- |
 * | 心情判定 | 攒够 [MOOD_MESSAGES] 条新用户消息，或距上次满 24h 且有新消息 | `moods.json` |
 * | 进化评估 | 攒够 [EVOLUTION_MESSAGES] 条新用户消息，或距上次满 24h 且有新消息 | `pending_patches.json`（待审，不自动改人设） |
 *
 * 三个刻意的行为：
 * 1. **游标落盘**（`auto_state.json`）：重启后不会把同一批消息再判一遍（多花两次钱）；
 * 2. **失败不重试到天荒地老**：心情判定失败按时间衰减（规范 §2 最后一条）；
 *    进化评估遇到「没有 LLM 配置」时**不推进游标**（等配好 key 再评估），其它失败推进游标；
 * 3. **不抛异常**：这里在聊天已经 `done` 之后跑，任何异常都只该进日志。
 *
 * 执行时机：`ChatService` 在**反手回完 `done` 之后**同步调用（客户端已经拿到回复，连接随后关闭）。
 * 只有每 4/20 条才会真的打模型，所以这条同步路径的额外延迟是可接受的，换来的是完全可测的确定性。
 */
class AutoTriggers(
    private val store: FileStore,
    private val mood: MoodService?,
    private val evolution: EvolutionService?,
    private val clock: Clock = Clock.systemDefaultZone()
) {

    /** 一次自动判定的结果（日志与测试断言用；不对外暴露成 HTTP）。 */
    data class Outcome(
        val personaId: String,
        val userMessagesSeen: Long,
        val moodRan: Boolean = false,
        val moodChanged: Boolean = false,
        val moodSource: String? = null,
        val evolutionRan: Boolean = false,
        val proposedChanges: Int = 0,
        val pendingPatchId: String? = null,
        val reason: String = ""
    )

    /** 每条聊天结束后调用：记一条用户消息，再按阈值决定要不要判定。 */
    fun onChatCompleted(personaId: String): Outcome {
        val state = store.recordUserMessageForAuto(personaId)
        return run(personaId, state)
    }

    /** 同步跑一次判定（测试与显式端点可以直接用）。 */
    fun run(personaId: String, state: AutoState = store.autoState(personaId)): Outcome {
        val now = clock.millis()
        var outcome = Outcome(personaId = personaId, userMessagesSeen = state.userMessagesSeen)

        // ---- 心情：每 4 条或每 24h（失败按时间衰减，规范 §2）
        val newForMood = state.userMessagesSeen - state.moodCursor
        if (mood != null && shouldRun(newForMood, state.moodEvaluatedAt, now, MOOD_MESSAGES)) {
            val result = runMood(personaId, now)
            outcome = outcome.copy(
                moodRan = true,
                moodChanged = result?.second ?: false,
                moodSource = result?.first
            )
        }

        // ---- 进化：每 20 条或每 24h（只写待审补丁，不自动改人设）
        val current = store.autoState(personaId)
        val newForEvolution = current.userMessagesSeen - current.evolutionCursor
        if (evolution != null &&
            shouldRun(newForEvolution, current.evolutionEvaluatedAt, now, EVOLUTION_MESSAGES)
        ) {
            val evaluation = runEvolution(personaId, now)
            if (evaluation != null) {
                outcome = outcome.copy(
                    evolutionRan = true,
                    proposedChanges = evaluation.first,
                    pendingPatchId = evaluation.second
                )
            }
        }
        return outcome
    }

    /**
     * 心情判定：返回 `(source, changed)`。
     *
     * 失败也返回一对值（`("failed", false)`）并**推进游标**：否则每条聊天都会再问一次，
     * 而规范明确要求「失败按时间衰减、不报错」。只有非 LLM 的意外异常才返回 null（不推进）。
     */
    private fun runMood(personaId: String, now: Long): Pair<String, Boolean>? = try {
        val result = mood!!.evaluate(personaId, now)
        store.recordMoodAutoEval(personaId, now)
        Pair(result.source.wireValue, result.changed)
    } catch (failure: LlmException) {
        System.err.println("[agentd] 自动心情判定失败（${failure.code}），跳过本次：${failure.userMessage()}")
        store.recordMoodAutoEval(personaId, now)
        Pair("failed", false)
    } catch (failure: Exception) {
        System.err.println("[agentd] 自动心情判定异常：${failure::class.simpleName}: ${failure.message}")
        null
    }

    /**
     * 进化评估：返回 `(提议条数, 补丁 id)`。
     *
     * 「没有模型配置」时**不推进游标**：等部署者/客户端配好 key，这批消息还该被评估一次。
     * 其它失败（限流/上游 500）推进游标，避免每条消息都撞一次同样的错。
     */
    private fun runEvolution(personaId: String, now: Long): Pair<Int, String?>? = try {
        val result = evolution!!.evaluate(personaId, now)
        store.recordEvolutionAutoEval(personaId, now)
        Pair(result.proposed, result.pending?.id)
    } catch (failure: LlmException) {
        if (failure.kind == LlmErrorKind.NOT_CONFIGURED || failure.kind == LlmErrorKind.INVALID_CONFIG) {
            System.err.println("[agentd] 自动进化评估跳过（${failure.code}）：等模型配置就绪")
        } else {
            System.err.println("[agentd] 自动进化评估失败（${failure.code}）：${failure.userMessage()}")
            store.recordEvolutionAutoEval(personaId, now)
        }
        null
    } catch (failure: Exception) {
        System.err.println("[agentd] 自动进化评估异常：${failure::class.simpleName}: ${failure.message}")
        null
    }

    companion object {
        /** 每 4 条用户消息判一次心情（`spec/mood` §2）。 */
        const val MOOD_MESSAGES: Long = 4

        /** 每 20 条用户消息评估一次人格进化（与客户端 `PersonaEvolutionRepository` 一致）。 */
        const val EVOLUTION_MESSAGES: Long = 20

        /** 就算没攒够条数，隔 24 小时也判一次（只要期间有新的用户消息）。 */
        const val INTERVAL_MS: Long = 24 * 60 * 60 * 1000L

        /**
         * 要不要跑：攒够 [threshold] 条新消息，或「有新消息且距上次满 [INTERVAL_MS]」。
         *
         * 纯函数（和 `MoodService.shouldEvaluate` 同构），这里多一个阈值参数好让心情/进化共用。
         */
        fun shouldRun(newMessages: Long, lastRunAt: Long, now: Long, threshold: Long = MOOD_MESSAGES): Boolean {
            if (newMessages >= threshold) return true
            return newMessages > 0 && lastRunAt > 0 && now - lastRunAt >= INTERVAL_MS
        }
    }
}
