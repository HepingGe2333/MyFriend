package com.myfriend.app.data.repo

import com.myfriend.app.data.local.ApiUsageDao
import com.myfriend.app.data.local.ApiUsageEntity
import com.myfriend.app.domain.ApiCost
import com.myfriend.app.domain.TokenUsage
import com.myfriend.app.domain.UsageKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 某一类用途这个月的用量。1.9 起只统计 token，不显示钱。 */
data class KindUsage(
    val kind: String,
    val label: String,
    val calls: Int,
    val promptTokens: Long,
    val completionTokens: Long
) {
    val totalTokens: Long get() = promptTokens + completionTokens
}

/** 某一段会话（= 某个人）这个月的用量；设置页只显示前 10。 */
data class SessionUsage(
    val sessionId: String?,
    val label: String,
    val calls: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    /**
     * 这一行是不是「没名字」的兜底行（人格被删了，或者老数据没有 sessionId）。
     * 界面按它换成自己语言的说法，[label] 只留给日志和旧调用方。
     */
    val nameless: Boolean = false
) {
    val totalTokens: Long get() = promptTokens + completionTokens
}

/** 这个月的总量 + 按用途 + 按人。 */
data class UsageTotals(
    val calls: Int = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val byKind: List<KindUsage> = emptyList(),
    val bySession: List<SessionUsage> = emptyList()
) {
    val isEmpty: Boolean get() = calls == 0
    val totalTokens: Long get() = promptTokens + completionTokens

    companion object {
        val EMPTY = UsageTotals()

        /** 把一段时间内的流水汇总成「这个月他说了多少话」。 */
        fun from(rows: List<ApiUsageEntity>, bySession: List<SessionUsage> = emptyList()): UsageTotals {
            if (rows.isEmpty() && bySession.isEmpty()) return EMPTY
            var prompt = 0L
            var completion = 0L
            val buckets = LinkedHashMap<String, LongArray>() // kind -> [calls, prompt, completion]
            rows.forEach { row ->
                val kind = UsageKind.normalize(row.kind)
                val promptTokens = row.promptTokens.coerceAtLeast(0).toLong()
                val completionTokens = row.completionTokens.coerceAtLeast(0).toLong()
                prompt += promptTokens
                completion += completionTokens
                val bucket = buckets.getOrPut(kind) { LongArray(3) }
                bucket[0] += 1
                bucket[1] += promptTokens
                bucket[2] += completionTokens
            }
            // 展示顺序固定：聊天在最前，其余按「他在做什么」排；没发生过的类别不出现。
            val byKind = UsageKind.displayOrder.mapNotNull { kind ->
                buckets[kind]?.let { bucket ->
                    KindUsage(
                        kind = kind,
                        label = UsageKind.label(kind),
                        calls = bucket[0].toInt(),
                        promptTokens = bucket[1],
                        completionTokens = bucket[2]
                    )
                }
            }
            return UsageTotals(
                calls = rows.size,
                promptTokens = prompt,
                completionTokens = completion,
                byKind = byKind,
                bySession = bySession
            )
        }
    }
}

/**
 * 记「他这一次用了多少 token」。
 *
 * 记账是旁路：任何一步失败都只是这一笔没记上，绝不能把聊天或后台任务搞挂。
 * 数据只在本机，不上传；1.9 起没有任何额度判定，这里也不再有 guard。
 */
@Singleton
class ApiUsageRepository @Inject constructor(
    private val dao: ApiUsageDao
) {

    /**
     * 记一笔。失败时安静吞掉（返回 false 只给调用方参考）。
     *
     * 只吞「写不进去」，不吞取消：协程被取消时账记不上是应该的，聊天该停也得停。
     */
    suspend fun record(
        kind: String,
        modelName: String?,
        usage: TokenUsage,
        at: Long = System.currentTimeMillis(),
        personaId: String? = null,
        sessionId: String? = null
    ): Boolean = try {
        dao.insert(
            ApiUsageEntity(
                id = UUID.randomUUID().toString(),
                at = at,
                kind = UsageKind.normalize(kind),
                personaId = personaId,
                // 规格 §5.2：api_usage 必须写 sessionId，「按会话（人）」的统计才有依据。
                sessionId = sessionId,
                modelName = modelName?.trim()?.takeIf { it.isNotEmpty() }?.take(120),
                promptTokens = usage.promptTokens.coerceAtLeast(0),
                completionTokens = usage.completionTokens.coerceAtLeast(0),
                // 只作内部参考；界面不显示钱。成本估算用内置公开标价，不再受任何设置影响。
                costMicroUsd = ApiCost.estimateCostMicroUsd(modelName, usage)
            )
        )
        true
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        false
    }

    /** 本月汇总（只含总量与按用途）。 */
    suspend fun monthTotals(now: Long = System.currentTimeMillis()): UsageTotals = try {
        UsageTotals.from(dao.since(ApiCost.monthStart(now)))
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        UsageTotals.EMPTY
    }

    /**
     * 本月流水的实时订阅：设置页「用量」靠它随聊天/后台任务即时刷新。
     *
     * 一次最多取 [OBSERVE_LIMIT] 条：一个月内正常用量远低于这个数，
     * 真有病态数据时也不至于把界面拖死。
     */
    fun observeMonth(now: Long = System.currentTimeMillis()): Flow<UsageTotals> =
        dao.observeSince(ApiCost.monthStart(now), OBSERVE_LIMIT).map { rows -> UsageTotals.from(rows) }

    /** 本月「按人」排行（前 10）：会话 → 人格名。没有 sessionId 的老数据归到「其他」。 */
    fun observeMonthBySession(
        now: Long = System.currentTimeMillis(),
        limit: Int = TOP_SESSIONS
    ): Flow<List<SessionUsage>> =
        dao.observeBySession(ApiCost.monthStart(now), limit).map { rows ->
            rows.map { row ->
                val name = row.personaName?.takeIf { it.isNotBlank() }
                SessionUsage(
                    sessionId = row.sessionId,
                    // 名字为空时界面按 `nameless` / `sessionId` 取资源，不显示这里的中文；
                    // 这里只保留一个空串，避免数据层再拼用户可见文案。
                    label = name.orEmpty(),
                    calls = row.calls,
                    promptTokens = row.promptTokens,
                    completionTokens = row.completionTokens,
                    nameless = name == null
                )
            }
        }

    /** 只留最近 [days] 天：更早的明细对新的一月没有用，留着只占地方。 */
    suspend fun pruneOlderThan(days: Int = RETAIN_DAYS, now: Long = System.currentTimeMillis()) {
        if (days <= 0) return
        val before = now - days.toLong() * MILLIS_PER_DAY
        try {
            dao.deleteOlderThan(before)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            // 清理失败不值得打扰任何人，下次启动再来。
        }
    }

    /** 用户选择删掉全部本地数据时，一起把流水清掉。 */
    suspend fun wipe() {
        try {
            dao.deleteAll()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            // 同上：删不掉也不影响「数据已删除」这件事的其它部分。
        }
    }

    private companion object {
        const val OBSERVE_LIMIT = 4000
        const val RETAIN_DAYS = 90
        const val TOP_SESSIONS = 10
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
