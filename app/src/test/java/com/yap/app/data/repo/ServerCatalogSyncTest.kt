package com.yap.app.data.repo

import com.yap.app.data.local.DiaryNoteDao
import com.yap.app.data.local.DiaryNoteEntity
import com.yap.app.data.local.StickerDao
import com.yap.app.data.local.StickerEntity
import com.yap.app.data.remote.FakeHttpTestServer
import com.yap.app.data.remote.ServerApi
import com.yap.app.data.remote.ServerApiProvider
import com.yap.app.data.remote.ServerCatalogMedia
import com.yap.app.data.remote.ServerCatalogPersonas
import com.yap.app.data.remote.ServerConfig
import com.yap.app.data.remote.ServerDiaryRow
import com.yap.app.data.remote.ServerPersonaIdMapper
import com.yap.app.data.remote.ServerStickerRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 服务端表情 / 日记的**拉取缓存**单测（0.13）。
 *
 * 这里不碰 Room：DAO 是接口，用内存假实现；服务端用 `FakeHttpTestServer` 真发 HTTP。
 * 要钉住的是「只同步」这条策略下的具体行为：
 * 1. 拉取会把服务端列表写成本机缓存行（表情 id 用服务端 id，file 指本机落盘文件）；
 * 2. 表情字节落盘失败时保留 `/v1/media/<id>` 引用（界面还能按需下载，不是整行丢失）；
 * 3. 日记只同步行，personaId 缺失时用本机人格兜底；
 * 4. 不是服务端模式时什么都不请求。
 */
class ServerCatalogSyncTest {

    private lateinit var server: FakeHttpTestServer
    private lateinit var provider: FakeProvider
    private lateinit var stickers: FakeStickerDao
    private lateinit var diary: FakeDiaryDao
    private lateinit var media: FakeMedia
    private lateinit var sync: ServerCatalogSync

    @Before
    fun setUp() {
        server = FakeHttpTestServer()
        provider = FakeProvider(
            ServerApi(
                config = ServerConfig(baseUrl = server.baseUrl, token = "t", enabled = true),
                debuggable = true
            )
        )
        stickers = FakeStickerDao()
        diary = FakeDiaryDao()
        media = FakeMedia()
        sync = ServerCatalogSync(
            mode = provider,
            ids = object : ServerPersonaIdMapper {
                override suspend fun serverPersonaId(localPersonaId: String): String = "server-$localPersonaId"
            },
            media = media,
            stickerDao = stickers,
            diaryDao = diary,
            personas = object : ServerCatalogPersonas {
                override suspend fun localPersonaIds(): List<String> = listOf("p1", "p2")
            }
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `拉取表情写入本机缓存并指向落盘文件`() = runBlocking {
        server.respondJson(
            """{"stickers":[
               {"id":"stk_1","personaId":"p1","file":"stk_1.gif","url":"/v1/media/stk_1",
                "mime":"image/gif","width":320,"height":320,"frames":3,"bytes":100,
                "source":"IMPORTED","createdBy":"USER","createdAt":11}
            ]}"""
        )
        media.uris = mapOf("/v1/media/stk_1" to "file:///tmp/stickers/sticker-stk_1.gif")

        val written = sync.syncStickers()

        assertEquals(1, written)
        val entity = stickers.getById("stk_1")
        assertEquals("file:///tmp/stickers/sticker-stk_1.gif", entity!!.file)
        assertEquals("p1", entity.personaId)
        assertEquals(3, entity.frames)
        assertEquals(11L, entity.createdAt)
    }

    @Test
    fun `表情字节没落盘时保留服务端引用而不是丢行`() = runBlocking {
        server.respondJson(
            """{"stickers":[{"id":"stk_2","file":"stk_2.gif","createdAt":5}]}"""
        )
        media.uris = emptyMap()

        assertEquals(1, sync.syncStickers())
        val entity = stickers.getById("stk_2")
        assertEquals("/v1/media/stk_2", entity!!.file)
        assertEquals("image/gif", entity.mime)
    }

    @Test
    fun `拉取日记只同步行并按本机人格兜底`() = runBlocking {
        server.responder = { request ->
            if (request.path == "/v1/personas/server-p1/diary") {
                com.yap.app.data.remote.FakeHttpTestServer.FakeReply.Json(
                    """{"personaId":"p1","notes":[
                       {"id":"n1","content":"周四给妈妈打电话","createdAt":10,"updatedAt":10}
                    ]}"""
                )
            } else {
                com.yap.app.data.remote.FakeHttpTestServer.FakeReply.Json("""{"notes":[]}""")
            }
        }

        val written = sync.syncDiary()

        assertEquals(1, written)
        val note = diary.getById("n1")
        assertEquals("周四给妈妈打电话", note!!.content)
        assertEquals("p1", note.personaId)
        assertTrue(diary.insertedIds.contains("n1"))
    }

    @Test
    fun `日记提醒时间原样同步`() = runBlocking {
        server.responder = {
            com.yap.app.data.remote.FakeHttpTestServer.FakeReply.Json(
                """{"notes":[{"id":"n2","personaId":"p1","content":"吃药","createdAt":1,
                   "remindAt":99,"remindedAt":100,"updatedAt":100}]}"""
            )
        }
        sync.syncDiary()
        val note = diary.getById("n2")
        assertEquals(99L, note!!.remindAt)
        assertEquals(100L, note.remindedAt)
    }

    @Test
    fun `本地模式一个请求都不发`() = runBlocking {
        provider.enabled = false

        assertEquals(0, sync.syncStickers())
        assertEquals(0, sync.syncDiary())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `拉取失败不会抛异常`() = runBlocking {
        server.respondJson("""{"error":{"code":"internal"}}""", status = 500)
        assertEquals(0, sync.syncStickers())
        assertEquals(0, sync.syncDiary())
    }

    @Test
    fun `syncAll 在节流窗口内只真拉一次`() = runBlocking {
        server.respondJson("""{"stickers":[],"notes":[]}""")
        sync.syncAll(force = true)
        val firstCount = server.requests.size
        sync.syncAll()
        assertEquals("10 分钟内不应再发请求", firstCount, server.requests.size)
    }

    // ------------------------------------------------------------------ 纯映射

    @Test
    fun `表情行映射缺省字段有默认值`() {
        val entity = ServerCatalogMapping.sticker(
            ServerStickerRow(id = "s1", file = "s1.gif"),
            emptyMap()
        )
        assertEquals("s1", entity!!.id)
        assertEquals("/v1/media/s1", entity.file)
        assertEquals(1, entity.frames)
        assertEquals("IMPORTED", entity.source)
        assertEquals("USER", entity.createdBy)
    }

    @Test
    fun `没有 id 也没有媒体引用的表情行被丢弃`() {
        assertNull(ServerCatalogMapping.sticker(ServerStickerRow(file = ""), emptyMap()))
    }

    @Test
    fun `日记行内容为空且没有既有缓存时丢弃`() {
        assertNull(
            ServerCatalogMapping.diary(
                ServerDiaryRow(id = "n1", content = ""),
                existing = null,
                fallbackPersonaId = "p1"
            )
        )
    }

    @Test
    fun `日记行缺 personaId 时沿用既有缓存的人格`() {
        val existing = DiaryNoteEntity(
            id = "n1",
            personaId = "old",
            content = "旧内容",
            createdAt = 1,
            remindAt = null,
            remindedAt = null,
            updatedAt = 1
        )
        val note = ServerCatalogMapping.diary(
            ServerDiaryRow(id = "n1", content = ""),
            existing = existing,
            fallbackPersonaId = "p9"
        )
        assertEquals("old", note!!.personaId)
        assertEquals("旧内容", note.content)
        assertEquals(1L, note.createdAt)
    }

    @Test
    fun `服务端媒体 id 取规范化引用`() {
        assertEquals("stk_1", ServerCatalogMapping.serverMediaIdOf(ServerStickerRow(id = "stk_1")))
        assertFalse(ServerCatalogMapping.serverMediaIdOf(ServerStickerRow(id = "")).let { it != null })
    }

    // ------------------------------------------------------------------ 假实现

    private class FakeProvider(var api: ServerApi?) : ServerApiProvider {
        var enabled: Boolean = true
        override fun api(): ServerApi? = api
        override fun isEnabled(): Boolean = enabled && api != null
    }

    private class FakeMedia : ServerCatalogMedia {
        var uris: Map<String, String> = emptyMap()
        override suspend fun localUris(refs: List<String>): Map<String, String> = uris
    }

    private class FakeStickerDao : StickerDao {
        val rows = LinkedHashMap<String, StickerEntity>()
        override fun observeRecent(limit: Int): Flow<List<StickerEntity>> =
            flowOf(rows.values.toList().take(limit))
        override fun observeAll(): Flow<List<StickerEntity>> = flowOf(rows.values.toList())
        override suspend fun recent(limit: Int): List<StickerEntity> = rows.values.toList().take(limit)
        override suspend fun getById(id: String): StickerEntity? = rows[id]
        override suspend fun findByFile(file: String): StickerEntity? = rows.values.firstOrNull { it.file == file }
        override suspend fun count(): Int = rows.size
        override suspend fun countForPersona(personaId: String): Int =
            rows.values.count { it.personaId == personaId }

        override suspend fun insert(sticker: StickerEntity): Long {
            rows[sticker.id] = sticker
            return 1
        }

        override suspend fun upsert(sticker: StickerEntity): Long {
            rows[sticker.id] = sticker
            return 1
        }

        override suspend fun setPersona(id: String, personaId: String?) {
            rows[id]?.let { rows[id] = it.copy(personaId = personaId) }
        }

        override suspend fun delete(id: String): Int = if (rows.remove(id) != null) 1 else 0
        override suspend fun deleteByPersona(personaId: String) {
            rows.values.filter { it.personaId == personaId }.forEach { rows.remove(it.id) }
        }

        override suspend fun deleteAll() {
            rows.clear()
        }

        override suspend fun allFiles(): List<String> = rows.values.map { it.file }
    }

    private class FakeDiaryDao : DiaryNoteDao {
        val rows = LinkedHashMap<String, DiaryNoteEntity>()
        val insertedIds = mutableListOf<String>()

        override suspend fun recent(personaId: String, limit: Int): List<DiaryNoteEntity> =
            rows.values.filter { it.personaId == personaId }.take(limit)

        override fun observeRecent(personaId: String, limit: Int): Flow<List<DiaryNoteEntity>> =
            flowOf(rows.values.filter { it.personaId == personaId })

        override suspend fun allForPersona(personaId: String): List<DiaryNoteEntity> =
            rows.values.filter { it.personaId == personaId }

        override suspend fun getById(id: String): DiaryNoteEntity? = rows[id]
        override suspend fun count(personaId: String): Int = rows.values.count { it.personaId == personaId }

        override suspend fun insert(note: DiaryNoteEntity) {
            rows[note.id] = note
            insertedIds += note.id
        }

        override suspend fun delete(id: String, personaId: String): Int {
            val row = rows[id] ?: return 0
            if (row.personaId != personaId) return 0
            rows.remove(id)
            return 1
        }

        override suspend fun dueReminders(now: Long, limit: Int): List<DiaryNoteEntity> = emptyList()
        override suspend fun markReminded(id: String, at: Long): Int = 0
        override suspend fun deleteByPersona(personaId: String) {
            rows.values.filter { it.personaId == personaId }.forEach { rows.remove(it.id) }
        }

        override suspend fun deleteAll() {
            rows.clear()
        }
    }
}
