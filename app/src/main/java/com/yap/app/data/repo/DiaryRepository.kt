package com.yap.app.data.repo

import com.yap.app.data.local.AgentIntentKind
import com.yap.app.data.local.DiaryNoteDao
import com.yap.app.data.local.DiaryNoteEntity
import com.yap.app.domain.AgentWindow
import com.yap.app.domain.Diary
import com.yap.app.domain.DomainErrorCode
import com.yap.app.domain.UserError
import com.yap.app.network.userError
import com.yap.app.data.remote.ServerModeRepository
import com.yap.app.data.remote.ServerWriteGateway
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 他的日记板（`spec/diary/README.md`）。
 *
 * 数据只有一张表，行为只有四件事：写一条、翻最近的、按 id 删、把到点的提醒变成念头。
 * 所有规则（内容上限、条数上限、提醒换算、错过 24 小时不补）都在 [Diary] 里，
 * 这里只负责落库与把提醒接进**既有的念头机制**（`agent_intents`）——不新造一套提醒系统，
 * 这样「他想不想因为这条备忘开口」仍然由心跳/主动循环自己决定。
 */
@Singleton
class DiaryRepository @Inject constructor(
    private val diaryDao: DiaryNoteDao,
    private val intentRepository: AgentIntentRepository,
    /** 0.13：服务端模式下日记板是服务端的东西（写路径要走 `/v1/personas/{id}/diary`）。 */
    private val serverMode: ServerModeRepository,
    private val serverWrites: ServerWriteGateway
) {

    companion object {
        /** 提醒检查的节流间隔：心跳每 30 秒一拍，没必要每拍都查一次库。 */
        private const val REMIND_CHECK_INTERVAL_MS = 60_000L

        /** 一次最多处理多少条到点提醒：再多说明积压很久了，剩下的下一拍继续。 */
        private const val REMIND_BATCH = 20
    }

    /** 上次真的去库里找「到点提醒」的时间（进程内节流，不落库）。 */
    private val lastRemindCheckAt = AtomicLong(0L)

    /**
     * 写一条备忘。
     *
     * @param remindAtMillis 显式提醒时间点；与 [remindInMinutes] 同时给时以它为准
     * @param remindInMinutes 过多少分钟提醒（`now` 起算）
     */
    suspend fun add(
        personaId: String,
        content: String?,
        remindAtMillis: Long? = null,
        remindInMinutes: Int? = null,
        now: Long = System.currentTimeMillis()
    ): Result<DiaryNoteEntity> = runCatching {
        val clean = Diary.cleanContent(content) ?: userError(DomainErrorCode.TOOL_CONTENT_MISSING)
        val remindAt = Diary.resolveRemindAt(remindAtMillis, remindInMinutes, now)
        // 服务端模式：先写服务端（服务端分配 id 与时间），成功后再落本机缓存。
        // 失败原样上抛：日记是「他记下的一件事」，不能让用户以为写进去了、其实只在缓存里。
        if (serverMode.isEnabled()) {
            val saved = serverWrites.saveDiary(
                personaId = personaId,
                payload = buildJsonObject {
                    put("content", clean)
                    if (remindAt != null) put("remindAt", remindAt) else put("remindAt", JsonNull)
                }
            ).getOrThrow()
            val note = DiaryNoteEntity(
                id = saved.id.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                // 本机缓存存**本地**人格 id（服务端 id 只在请求里用）：否则按当前人格查不到。
                personaId = personaId,
                content = saved.content.takeIf { it.isNotBlank() } ?: clean,
                createdAt = saved.createdAt.takeIf { it > 0L } ?: now,
                remindAt = saved.remindAt,
                remindedAt = saved.remindedAt,
                updatedAt = saved.updatedAt.takeIf { it > 0L } ?: now
            )
            diaryDao.insert(note)
            return@runCatching note
        }
        val note = DiaryNoteEntity(
            id = UUID.randomUUID().toString(),
            personaId = personaId,
            content = clean,
            createdAt = now,
            remindAt = remindAt,
            remindedAt = null,
            updatedAt = now
        )
        diaryDao.insert(note)
        note
    }

    /** 按写入时间倒序翻最近的笔记（默认 20 条，最多 50）。 */
    suspend fun recent(personaId: String, limit: Int? = null): List<Diary.Entry> =
        diaryDao.recent(personaId, Diary.limitOf(limit)).map { it.toEntry() }

    /**
     * 按 id 删（硬删除；只能删自己人格的笔记）。返回是否真的删掉了。
     *
     * 服务端模式：先删服务端（404 = 已经没了，当成功），再删本机缓存。
     * 服务端失败返回 false（界面提示一句），绝不「只删本机」让下一次同步把它捞回来。
     */
    suspend fun remove(personaId: String, noteId: String?): Boolean {
        val id = noteId?.trim().orEmpty()
        if (id.isBlank()) return false
        if (serverMode.isEnabled()) {
            if (serverWrites.deleteDiary(personaId, id).isFailure) return false
        }
        return diaryDao.delete(id, personaId) > 0
    }

    /**
     * 检查到点的提醒，把它们变成念头。
     *
     * 顺序刻意是「**先原子占位，再决定要不要生成念头**」：
     * 心跳与主动 tick 可能同时跑，`markReminded` 只有在 `remindedAt IS NULL` 时才成功，
     * 谁先抢到谁负责这一条，另一条路径拿不到行就直接跳过 —— 这就是「只触发一次」的实现方式。
     *
     * 错过超过 24 小时的提醒**只清标记不补发**：一周没开 App 之后被十几条「到点提醒」砸中，
     * 不是他该做的事。
     *
     * @return 这一拍真正生成念头的条数
     */
    suspend fun remindDue(now: Long = System.currentTimeMillis()): Int {
        // 服务端模式：提醒由服务端自己负责（它独立运行），客户端再变一次念头就是双份提醒。
        if (serverMode.isEnabled()) return 0
        val last = lastRemindCheckAt.get()
        if (now - last < REMIND_CHECK_INTERVAL_MS) return 0
        lastRemindCheckAt.set(now)

        val due = runCatching { diaryDao.dueReminders(now, REMIND_BATCH) }.getOrDefault(emptyList())
        if (due.isEmpty()) return 0
        var fired = 0
        due.forEach { note ->
            val claimed = runCatching { diaryDao.markReminded(note.id, now) }.getOrDefault(0)
            if (claimed != 1) return@forEach
            if (Diary.isMissed(note.remindAt, now)) return@forEach
            val planned = runCatching {
                intentRepository.plan(
                    personaId = note.personaId,
                    window = AgentWindow(earliestAt = now, latestAt = now + Diary.REMINDER_WINDOW_MS),
                    kind = AgentIntentKind.REACH_OUT,
                    note = Diary.reminderNote(note.content),
                    hint = Diary.reminderHint(note.createdAt, now),
                    sessionId = null,
                    now = now
                )
            }.isSuccess
            if (planned) fired++
        }
        return fired
    }

    /** 删人格 / 整库清空时连带清掉日记：它是**他的**东西，人没了就不该留在库里。 */
    suspend fun deleteForPersona(personaId: String) {
        runCatching { diaryDao.deleteByPersona(personaId) }
    }

    suspend fun wipeAll() {
        runCatching { diaryDao.deleteAll() }
    }

    private fun DiaryNoteEntity.toEntry(): Diary.Entry = Diary.Entry(
        id = id,
        content = content,
        createdAt = createdAt,
        remindAt = remindAt,
        remindedAt = remindedAt
    )
}
