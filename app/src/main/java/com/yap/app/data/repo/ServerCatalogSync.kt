package com.yap.app.data.repo

import android.util.Log
import com.yap.app.data.local.DiaryNoteDao
import com.yap.app.data.local.DiaryNoteEntity
import com.yap.app.data.local.StickerDao
import com.yap.app.data.local.StickerEntity
import com.yap.app.data.remote.ServerApiProvider
import com.yap.app.data.remote.ServerCatalogMedia
import com.yap.app.data.remote.ServerCatalogPersonas
import com.yap.app.data.remote.ServerDiaryRow
import com.yap.app.data.remote.ServerMediaRef
import com.yap.app.data.remote.ServerPersonaIdMapper
import com.yap.app.data.remote.ServerStickerRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务端表情包 / 日记板的**拉取缓存**（0.13）。
 *
 * 服务端模式下的分工：
 * - 写（新增/删除）由仓储层直接调服务端 CRUD（`StickerRepository` / `DiaryRepository`）；
 * - 读（列表）走这里：把服务端列表同步到本机 Room，界面只读本机缓存。
 *
 * 为什么拉取要单独一层：
 * 1. 表情的字节要经 `/v1/media/<id>` 落盘（离线也能看、发送时不必先下载）；
 *    [ServerCatalogMedia] 背后的 `ServerMediaStore` 已经实现了幂等/原子落盘/失败保留引用；
 * 2. 日记只同步行（内容文本，没有媒体）；
 * 3. 拉取失败**不能影响**聊天同步：所有失败都吞成 0 并留一行日志，等下一轮前台重试。
 *
 * 不删除服务端已经删掉的本机行：本机缓存可能还留着本地模式时期的表情/日记（用户切到
 * 服务端模式前存下的），一次「列表对齐」把它们清掉是数据丢失。删除只由用户操作触发。
 */
@Singleton
class ServerCatalogSync @Inject constructor(
    private val mode: ServerApiProvider,
    private val ids: ServerPersonaIdMapper,
    private val media: ServerCatalogMedia,
    private val stickerDao: StickerDao,
    private val diaryDao: DiaryNoteDao,
    private val personas: ServerCatalogPersonas
) {

    /**
     * 拉取一次服务端表情列表并写进本机缓存。
     *
     * @return 写进缓存的行数；不是服务端模式 / 拉取失败时为 0
     */
    suspend fun syncStickers(): Int = withContext(Dispatchers.IO) {
        if (!mode.isEnabled()) return@withContext 0
        val api = mode.api() ?: return@withContext 0
        val rows = runCatching { api.stickers(limit = STICKER_PULL_LIMIT) }.getOrNull()
            ?: return@withContext 0
        if (rows.isEmpty()) return@withContext 0
        // 先把字节落到 files/stickers（同 id 幂等）：一条 HTTP 请求换离线可用。
        val localUris = runCatching {
            media.localUris(rows.mapNotNull { it.mediaRef() })
        }.getOrDefault(emptyMap())
        var written = 0
        rows.forEach { row ->
            val entity = ServerCatalogMapping.sticker(row, localUris) ?: return@forEach
            val inserted = runCatching { stickerDao.upsert(entity) }.getOrDefault(-1L)
            if (inserted != -1L || runCatching { stickerDao.getById(entity.id) }.getOrNull() != null) written++
        }
        written
    }

    /**
     * 拉取所有本机人格在服务端上的日记行，写进本机缓存。
     *
     * 为什么按「本机人格」遍历而不是只拉当前人格：日记提醒由服务端负责，但界面切人格时
     * 要能立刻看到那一页的缓存；一次前台同步把所有人格拉一遍，代价只是一人一个请求。
     *
     * @return 写进缓存的行数；不是服务端模式 / 拉取失败时为 0
     */
    suspend fun syncDiary(): Int = withContext(Dispatchers.IO) {
        if (!mode.isEnabled()) return@withContext 0
        val api = mode.api() ?: return@withContext 0
        val localPersonaIds = runCatching { personas.localPersonaIds() }.getOrDefault(emptyList())
        if (localPersonaIds.isEmpty()) return@withContext 0
        var written = 0
        localPersonaIds.forEach { personaId ->
            val serverPersonaId = runCatching { ids.serverPersonaId(personaId) }.getOrDefault(personaId)
            val rows = runCatching { api.diary(serverPersonaId) }.getOrNull() ?: return@forEach
            rows.forEach { row ->
                val existing = row.id.trim().takeIf { it.isNotBlank() }
                    ?.let { id -> runCatching { diaryDao.getById(id) }.getOrNull() }
                val note = ServerCatalogMapping.diary(row, existing, personaId) ?: return@forEach
                // 本机缓存一律用**本地**人格 id 存：服务端人格 id 与本地不同（bootstrap 之后），
                // 存服务端 id 会让「按当前人格查日记」查不到。
                if (runCatching { diaryDao.insert(note.copy(personaId = personaId)) }.isSuccess) written++
            }
        }
        written
    }

    /**
     * 一次同步表情 + 日记；日志只记异常类型，不记内容。
     *
     * 节流：前台增量同步每 5 分钟一次，但表情/日记的变化频率远低于聊天；这里默认
     * [CATALOG_SYNC_INTERVAL_MS] 内只真拉一次，避免每 5 分钟给每个人格打一次日记请求。
     * 状态变化（切换服务端、手动刷新）可以传 `force = true`。
     */
    suspend fun syncAll(force: Boolean = false) {
        if (!mode.isEnabled()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSyncAt < CATALOG_SYNC_INTERVAL_MS) return
        lastSyncAt = now
        runCatching { syncStickers() }.onFailure { warn("stickers", it) }
        runCatching { syncDiary() }.onFailure { warn("diary", it) }
    }

    /** 上次真的拉过表情/日记的时间（进程内节流，不落库：切服务端会重建同步循环）。 */
    @Volatile
    private var lastSyncAt: Long = 0L

    private fun warn(label: String, error: Throwable) {
        runCatching { Log.w(TAG, "server $label cache refresh failed: ${error.javaClass.simpleName}") }
    }

    private companion object {
        const val TAG = "ServerCatalogSync"

        /** 一次拉最近 100 个表情（服务端 `limit` 上限）：面板本来也只显示最近 30 个 + 全部。 */
        const val STICKER_PULL_LIMIT = 100

        /** 表情/日记的拉取间隔：它们不是实时内容，10 分钟一次足够。 */
        private const val CATALOG_SYNC_INTERVAL_MS = 10 * 60 * 1_000L
    }
}

/**
 * 服务端行 → 本机缓存行的**纯映射**（不碰 Room / Android）。
 *
 * 单独抽出来是为了能在 JVM 单测里逐条钉住两个最容易出错的地方：
 * - 表情行：服务端只给裸文件名/mediaId，本机要 `file://` 路径（落盘成功）或 `/v1/media/<id>`（失败兜底）；
 * - 日记行：服务端可能缺 `personaId`，要从本机人格兜底；时间缺省时不能写成 0。
 */
object ServerCatalogMapping {

    fun sticker(row: ServerStickerRow, localUris: Map<String, String>): StickerEntity? {
        val id = row.localId().takeIf { it.isNotBlank() } ?: return null
        val mediaRef = row.mediaRef()
        val localUri = mediaRef?.let { localUris[it] }?.takeIf { it.isNotBlank() }
        return StickerEntity(
            id = id,
            ownerUserId = row.ownerUserId,
            personaId = row.personaId?.takeIf { it.isNotBlank() },
            // 落盘成功 → 本机文件；失败 → 保留服务端引用（渲染层按需下载）。
            file = localUri ?: mediaRef ?: row.file.takeIf { it.isNotBlank() } ?: return null,
            mime = row.mime.takeIf { it.isNotBlank() } ?: "image/gif",
            width = row.width.coerceAtLeast(0),
            height = row.height.coerceAtLeast(0),
            frames = row.frames.coerceAtLeast(1),
            bytes = row.bytes.coerceAtLeast(0),
            source = row.source.takeIf { it.isNotBlank() } ?: "IMPORTED",
            createdBy = row.createdBy.takeIf { it.isNotBlank() } ?: "USER",
            createdAt = row.createdAt.takeIf { it > 0L } ?: 0L
        )
    }

    fun diary(
        row: ServerDiaryRow,
        existing: DiaryNoteEntity?,
        fallbackPersonaId: String
    ): DiaryNoteEntity? {
        val id = row.id.trim().takeIf { it.isNotBlank() } ?: return null
        val content = row.content.ifBlank { existing?.content.orEmpty() }
        if (content.isBlank()) return null
        return DiaryNoteEntity(
            id = id,
            personaId = row.personaId.trim().ifBlank { existing?.personaId ?: fallbackPersonaId },
            content = content,
            createdAt = row.createdAt.takeIf { it > 0L } ?: existing?.createdAt ?: 0L,
            remindAt = row.remindAt,
            remindedAt = row.remindedAt,
            updatedAt = row.updatedAt.takeIf { it > 0L } ?: existing?.updatedAt ?: 0L
        )
    }

    /** 服务端表情行 → 附件里的服务端媒体 id（发送时不重复上传）。 */
    fun serverMediaIdOf(row: ServerStickerRow): String? =
        ServerMediaRef.idOf(row.mediaRef())
}
