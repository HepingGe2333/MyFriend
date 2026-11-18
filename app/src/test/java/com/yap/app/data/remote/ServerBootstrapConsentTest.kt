package com.yap.app.data.remote

import com.yap.app.data.prefs.AppSettings
import com.yap.app.domain.MessageAttachment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.13「同意并同步」= 带媒体的全量推送；「只同步之后的新内容」= 不推历史；
 * 「切回本地」= 全量下载 + 条数校验。
 *
 * 全部纯 JVM：Room / 网络 / Android Bitmap 都换成内存假实现。
 */
class ServerBootstrapConsentTest {

    // ------------------------------------------------------------------ 假媒体上传

    private class RecordingMediaApi(
        private val events: MutableList<String>,
        private val failSizes: Set<Int> = emptySet()
    ) : ServerMediaApi {
        val uploads = mutableListOf<Pair<String, Int>>()

        override suspend fun uploadMedia(
            bytes: ByteArray,
            mime: String,
            kind: String,
            personaId: String?
        ): ServerMedia {
            val index = uploads.size + 1
            events += "upload-$index"
            uploads += mime to bytes.size
            if (bytes.size in failSizes) {
                throw ServerApiException("simulated media failure", code = "server_error")
            }
            val id = "media-$index"
            return ServerMedia(id = id, url = "/v1/media/$id", kind = kind, mime = mime)
        }
    }

    private fun mediaPort(api: ServerMediaApi, files: Map<String, ByteArray>): ServerHistoryMediaPort =
        object : ServerHistoryMediaPort {
            override suspend fun prepare(
                messages: List<com.yap.app.data.local.MessageEntity>,
                personaId: String?,
                progress: HistoryMediaProgress,
                onProgress: suspend (HistoryMediaProgress) -> Unit
            ): HistoryMediaBatchResult = ServerHistoryMediaUploader(
                api = api,
                readBytes = { uri -> files[uri] },
                compressImage = { bytes, _ -> CompressedImage(bytes, "image/jpeg") }
            ).prepare(messages, personaId, progress, onProgress)
        }

    private fun manager(
        local: ConsentFakeLocal,
        remote: ServerBootstrapRemote,
        store: TestServerLocalStore,
        consent: ServerSyncConsent = ServerSyncConsent.FULL,
        media: ServerHistoryMediaPort? = null
    ) = ServerBootstrapManager(
        local = local,
        remote = remote,
        store = store,
        consentFor = { consent },
        historyMedia = media
    )

    private fun seedLocalChat(local: ConsentFakeLocal, personaId: String = "p1") {
        val persona = consentPersona(personaId)
        local.personas[persona.id] = persona
        local.settings = AppSettings(activePersonaId = personaId)
        local.sessions["s1"] = consentSession("s1", personaId)
    }

    // ------------------------------------------------------------------ 同意并同步（FULL）

    @Test
    fun `同意并同步 媒体先上传再 import 附件改写成服务端引用`() = runBlocking {
        val store = TestServerLocalStore()
        val local = ConsentFakeLocal(store)
        val remote = ConsentFakeRemote()
        seedLocalChat(local)
        val events = mutableListOf<String>()
        remote.onImport = { events += "import" }
        val api = RecordingMediaApi(events)
        local.addMessage(
            "s1",
            consentMessage(
                id = "m1",
                sessionId = "s1",
                personaId = "p1",
                attachments = listOf(MessageAttachment(uri = "file:///media/a.jpg", mime = "image/jpeg"))
            )
        )
        local.addMessage("s1", consentMessage(id = "m2", sessionId = "s1", personaId = "p1"))

        val outcome = manager(
            local = local,
            remote = remote,
            store = store,
            media = mediaPort(api, mapOf("file:///media/a.jpg" to byteArrayOf(7)))
        ).runIfNeeded("http://server.test")

        assertTrue(outcome.success)
        assertEquals(ServerBootstrapMode.PUSH, outcome.mode)
        assertEquals(1, outcome.mediaTotal)
        assertEquals(1, outcome.mediaUploaded)
        assertEquals(0, outcome.mediaFailed)

        val imports = remote.importCalls.flatMap { it.second }
        assertEquals("文字照常全部导入", 2, imports.size)
        val first = imports.first { it.id == "m1" }
        assertEquals(
            "附件改写成服务端引用（否则 import 会 400）",
            listOf("/v1/media/media-1"),
            first.attachments
        )
        assertEquals("纯文字消息没有附件", emptyList<String>(), imports.first { it.id == "m2" }.attachments)
        assertEquals(
            "先上传、后 import",
            listOf("upload-1", "import"),
            events
        )
    }

    @Test
    fun `媒体失败不阻断文字同步 失败项计数并去掉附件`() = runBlocking {
        val store = TestServerLocalStore()
        val local = ConsentFakeLocal(store)
        val remote = ConsentFakeRemote()
        seedLocalChat(local)
        val api = RecordingMediaApi(mutableListOf(), failSizes = setOf(99))
        local.addMessage(
            "s1",
            consentMessage(
                id = "m1",
                sessionId = "s1",
                personaId = "p1",
                attachments = listOf(MessageAttachment(uri = "file:///media/bad.jpg", mime = "image/jpeg"))
            )
        )
        local.addMessage("s1", consentMessage(id = "m2", sessionId = "s1", personaId = "p1"))

        val outcome = manager(
            local = local,
            remote = remote,
            store = store,
            media = mediaPort(api, mapOf("file:///media/bad.jpg" to ByteArray(99) { 1 }))
        ).runIfNeeded("http://server.test")

        assertTrue("一张图失败不能拖垮整轮同步", outcome.success)
        assertEquals(1, outcome.mediaFailed)
        assertEquals(0, outcome.mediaUploaded)
        val imports = remote.importCalls.flatMap { it.second }
        assertEquals(2, imports.size)
        assertEquals("失败附件不进服务端副本（文字还在）", emptyList<String>(), imports.first { it.id == "m1" }.attachments)
        assertTrue("本地消息保留 file://，不做改写", local.messages["s1"]!!.first { it.id == "m1" }.attachmentsJson!!.contains("file:///media/bad.jpg"))
    }

    @Test
    fun `不能读取的本地附件计入失败数`() = runBlocking {
        val store = TestServerLocalStore()
        val local = ConsentFakeLocal(store)
        val remote = ConsentFakeRemote()
        seedLocalChat(local)
        local.addMessage(
            "s1",
            consentMessage(
                id = "m1",
                sessionId = "s1",
                personaId = "p1",
                attachments = listOf(MessageAttachment(uri = "file:///media/missing.jpg", mime = "image/jpeg"))
            )
        )

        val outcome = manager(
            local = local,
            remote = remote,
            store = store,
            media = mediaPort(RecordingMediaApi(mutableListOf()), emptyMap())
        ).runIfNeeded("http://server.test")

        assertTrue(outcome.success)
        assertEquals(1, outcome.mediaFailed)
        assertEquals(0, outcome.mediaUploaded)
    }

    @Test
    fun `同一张图被两条消息引用 只上传一次`() = runBlocking {
        val store = TestServerLocalStore()
        val local = ConsentFakeLocal(store)
        val remote = ConsentFakeRemote()
        seedLocalChat(local)
        val api = RecordingMediaApi(mutableListOf())
        val file = "file:///media/same.jpg"
        repeat(2) { index ->
            local.addMessage(
                "s1",
                consentMessage(
                    id = "m${index + 1}",
                    sessionId = "s1",
                    personaId = "p1",
                    attachments = listOf(MessageAttachment(uri = file, mime = "image/jpeg"))
                )
            )
        }

        val outcome = manager(
            local = local,
            remote = remote,
            store = store,
            media = mediaPort(api, mapOf(file to byteArrayOf(1)))
        ).runIfNeeded("http://server.test")

        assertTrue(outcome.success)
        assertEquals(1, api.uploads.size)
        val imports = remote.importCalls.flatMap { it.second }
        assertTrue(
            "两条消息都指向同一个服务端媒体",
            imports.all { it.attachments == listOf("/v1/media/media-1") }
        )
    }

    // ------------------------------------------------------------------ 只同步之后的新内容（NEW_ONLY）

    @Test
    fun `只同步之后的新内容 不推历史消息和记忆 元数据照建`() = runBlocking {
        val store = TestServerLocalStore()
        val local = ConsentFakeLocal(store)
        val remote = ConsentFakeRemote()
        seedLocalChat(local)
        local.memories += consentMemory("mem-1", "p1", "他喜欢咖啡")
        repeat(3) { index ->
            local.addMessage("s1", consentMessage(id = "m$index", sessionId = "s1", personaId = "p1"))
        }
        val api = RecordingMediaApi(mutableListOf())

        val outcome = manager(
            local = local,
            remote = remote,
            store = store,
            consent = ServerSyncConsent.NEW_ONLY,
            media = mediaPort(api, emptyMap())
        ).runIfNeeded("http://server.test")

        assertTrue(outcome.success)
        assertEquals(ServerBootstrapMode.PUSH, outcome.mode)
        assertEquals("历史消息一条都不推", 0, remote.importCalls.size)
        assertEquals("历史记忆也不推", 0, remote.memories.values.sumOf { it.size })
        assertEquals("历史媒体不上传", 0, api.uploads.size)
        assertEquals("人格元数据照建", 1, remote.createdPersonas.size)
        assertEquals("会话元数据照建（消息为空）", 1, remote.createdSessions.size)
        assertNotNull("设置照推", remote.pushedSettings)
        assertEquals(0, outcome.messages)
        assertEquals(0, outcome.mediaTotal)
        assertTrue("标记仍要写：下一轮不要再推历史", store.bootstrapDone("http://server.test"))
    }

    // ------------------------------------------------------------------ 切回本地（全量下载 + 条数校验）

    private fun seedServerChat(remote: ConsentFakeRemote, messages: Int = 2) {
        remote.personas += buildJsonObject {
            put("id", JsonPrimitive("srv-p1"))
            put("name", JsonPrimitive("林深"))
            put("prompt", JsonPrimitive("server prompt"))
            put("createdAt", JsonPrimitive(10))
            put("updatedAt", JsonPrimitive(100))
        }
        remote.sessions += ServerSession(
            id = "srv-s1",
            personaId = "srv-p1",
            title = "和srv-p1的对话",
            lastBlockIndex = 0,
            messageCount = 0,
            updatedAt = 100,
            createdAt = 10
        )
        remote.seedBlock(
            "srv-s1",
            0,
            (1..messages).map { index ->
                ServerMessage(
                    id = "s$index",
                    sessionId = "srv-s1",
                    blockIndex = 0,
                    role = "USER",
                    content = "server-$index",
                    createdAt = 100L + index,
                    attachments = if (index == 1) listOf("/v1/media/chat-1") else emptyList()
                )
            }
        )
    }

    @Test
    fun `切回本地 全量下载后条数校验通过`() = runBlocking {
        val store = TestServerLocalStore()
        val local = ConsentFakeLocal(store)
        val remote = ConsentFakeRemote()
        seedServerChat(remote, messages = 2)
        store.setSyncConsent("http://server.test", ServerSyncConsent.FULL)

        val outcome = manager(local, remote, store).runFullDownload("http://server.test")

        assertTrue(outcome.success)
        assertEquals(2, outcome.downloadedMessages)
        assertEquals(2, outcome.localMessages)
        assertEquals(2L, outcome.serverMessages)
        assertTrue(outcome.shortSessions.isEmpty())
        assertEquals("人格拉回", 1, local.personas.size)
        assertEquals("会话拉回", 1, local.sessions.size)
        assertEquals("消息拉回", 2, local.messages.values.sumOf { it.size })
        assertTrue("下载不能反向推送", remote.importCalls.isEmpty())
        assertEquals("/v1/media/chat-1", local.messages.values.first().first { it.id == "s1" }.attachmentsJson!!.let {
            com.yap.app.domain.AttachmentsCodec.decode(it).single().uri
        })
    }

    @Test
    fun `切回本地 本地缺消息导致条数校验失败`() = runBlocking {
        val store = TestServerLocalStore()
        val local = ConsentFakeLocal(store)
        val remote = ConsentFakeRemote()
        seedServerChat(remote, messages = 3)
        // 模拟写库失败：服务端有 3 条，本地一条都没落下。
        local.dropIncomingPages = true

        val outcome = manager(local, remote, store).runFullDownload("http://server.test")

        assertFalse("条数不够时不能允许关闭服务端模式", outcome.success)
        assertNotNull(outcome.error)
        assertEquals(0, outcome.localMessages)
        assertEquals(3L, outcome.serverMessages)
        assertEquals(listOf("srv-s1"), outcome.shortSessions)
    }

    @Test
    fun `切回本地 允许本地独有消息合并 本地多于服务端仍算成功`() = runBlocking {
        val store = TestServerLocalStore()
        val local = ConsentFakeLocal(store)
        val remote = ConsentFakeRemote()
        seedServerChat(remote, messages = 2)
        // 本地已经有一段同一人格的会话 + 一条离线时发的消息。
        local.personas["srv-p1"] = consentPersona("srv-p1", name = "本地")
        local.sessions["local-s1"] = consentSession("local-s1", "srv-p1")
        local.addMessage("local-s1", consentMessage(id = "local-only", sessionId = "local-s1", personaId = "srv-p1"))

        val outcome = manager(local, remote, store).runFullDownload("http://server.test")

        assertTrue("本地独有消息允许合并（本地 3 ≥ 服务端 2）", outcome.success)
        assertEquals(3, outcome.localMessages)
        assertEquals(2L, outcome.serverMessages)
        assertTrue("本地独有消息不能被推上去", remote.importCalls.isEmpty())
    }

    @Test
    fun `下载失败服务端不可用时返回失败`() = runBlocking {
        val store = TestServerLocalStore()
        val local = ConsentFakeLocal(store)
        val remote = object : ServerBootstrapRemote by ConsentFakeRemote() {
            override suspend fun state(): StateSnapshot = throw ServerApiException(
                "server unreachable",
                code = "io_error"
            )
        }

        val outcome = manager(local, remote, store).runFullDownload("http://server.test")

        assertFalse(outcome.success)
        assertTrue("网络失败要能区分「没连上」而不是「条数不对」", outcome.unavailable)
        assertEquals("io_error", (outcome.error as ServerApiException).code)
        assertTrue("失败时本地没写进任何消息", local.messages.isEmpty())
    }
}
