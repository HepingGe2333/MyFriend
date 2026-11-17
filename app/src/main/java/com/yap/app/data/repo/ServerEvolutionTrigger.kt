package com.yap.app.data.repo

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 触发一轮人格进化评估。
 *
 * 本地模式（`ChatRepository.sendUserMessage` 写完之后）与服务端模式（助手回复 `done` / 落库之后）
 * 走的是**同一个实现**（[PersonaEvolutionRepository]）：同一个游标、同一把单飞锁。
 * 这也是「切模式不会重复评估」的实现方式——不是两份各自计数的节流，而是同一个闸门。
 */
fun interface PersonaEvolutionTrigger {
    fun ensureAsync(personaId: String, now: Long)
}

/**
 * 服务端模式的进化触发（0.13）。
 *
 * 只做两件事，真正的节流仍由 [PersonaEvolutionTrigger] 的实现负责：
 *
 * 1. **一轮一次**：SSE 的 `done` 在重连/重放时可能重复到达，同一条 assistant 消息 id
 *    只触发一次（[lastRoundKey]）；没有 id 时退回不按轮去重，让仓库自己的游标节流兜底。
 * 2. **空人格不触发**：会话找不到人格时直接返回 false，不制造一次空评估。
 *
 * 异步、不抛异常：调用点在聊天主路径上，评估失败绝不能影响用户看到回复。
 */
@Singleton
class ServerEvolutionTrigger @Inject constructor(
    private val evolution: PersonaEvolutionTrigger
) {

    private val lastRoundKey = AtomicReference<String?>(null)

    /**
     * 助手回复完成（`done` / 落库）后调用。
     *
     * @param roundKey 这一轮的稳定 id：assistant 消息 id；没有就传 clientMessageId
     * @return 这次是否把触发交给了 [PersonaEvolutionTrigger]（仓库仍可能因节流不评估）
     */
    fun onReplyDone(
        personaId: String,
        roundKey: String,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (personaId.isBlank()) return false
        val key = roundKey.trim()
        if (key.isNotEmpty()) {
            // 同一轮只评估一次：`done` 重复到达（重连/重放）时第二次直接返回。
            if (lastRoundKey.getAndSet(key) == key) return false
        }
        evolution.ensureAsync(personaId, now)
        return true
    }
}
