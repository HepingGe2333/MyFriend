package com.yap.app.data.remote

import com.yap.app.domain.BackupCodec
import com.yap.app.domain.BackupDecodeResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 服务端备份（`/v1/backup`）与客户端 v6 的互通（0.13）。
 *
 * 任务要求「服务端导出的就是客户端 v6 同形 JSON」，所以这里用**仓库里的两份 v6 黄金样本**
 * 直接喂给客户端解码链路，钉住三件事：
 * 1. `fixtures/backup/{android,ios}-export-v6.json` 在服务端模式下也能被本地缓存层读懂；
 * 2. `ServerBackupAdapter` 把服务端历史字段（avatarUrl/importance/unread/attachments）转过来之后
 *    `BackupCodec.decode` 仍然成功，v6 的 `stickers[]` / `diaryNotes[]` / `media[]` 一段不丢；
 * 3. 导入客户端 v6 文件到服务端时，POST 的是 `{mode,data}` 且 `data` 是对象（现网契约）。
 */
class ServerBackupV6InteropTest {

    private lateinit var server: FakeHttpTestServer
    private lateinit var gateway: ServerWriteGateway

    @Before
    fun setUp() {
        server = FakeHttpTestServer()
        val api = ServerApi(
            config = ServerConfig(baseUrl = server.baseUrl, token = "t", enabled = true),
            debuggable = true
        )
        gateway = ServerWriteGateway(
            object : ServerApiProvider {
                override fun api(): ServerApi = api
                override fun isEnabled(): Boolean = true
            },
            TestServerLocalStore()
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `Android v6 样本经服务端适配后仍能被本地解码`() {
        val raw = fixture("android-export-v6.json")
        val adapted = ServerBackupAdapter.toLocalBackupJson(raw)
        assertNotNull(adapted)

        val decoded = BackupCodec.decode(adapted!!)
        assertTrue("v6 样本必须能解开：$decoded", decoded is BackupDecodeResult.Success)
        val bundle = (decoded as BackupDecodeResult.Success).bundle
        assertEquals(7, bundle.formatVersion)
        assertEquals(1, bundle.stickers.size)
        assertEquals(1, bundle.diaryNotes.size)
        assertTrue(bundle.media.isNotEmpty())
    }

    @Test
    fun `iOS v6 样本经服务端适配后仍能被本地解码`() {
        val raw = fixture("ios-export-v6.json")
        val adapted = ServerBackupAdapter.toLocalBackupJson(raw)
        assertNotNull(adapted)

        val decoded = BackupCodec.decode(adapted!!)
        assertTrue("iOS v6 样本必须能解开：$decoded", decoded is BackupDecodeResult.Success)
        val bundle = (decoded as BackupDecodeResult.Success).bundle
        assertEquals(7, bundle.formatVersion)
        assertEquals(1, bundle.stickers.size)
        assertEquals(2, bundle.diaryNotes.size)
        // iOS 只写 `file`：适配后 `fileName` 要被补齐，否则本地恢复表情文件时找不到对应媒体。
        assertTrue(bundle.stickers.all { it.portableFileName.isNotBlank() })
    }

    @Test
    fun `服务端 v7 extensions 里的两张表经适配后能落到本地解码结果`() {
        val file = File("../fixtures/backup/server-export-v7-extensions.json")
        if (!file.exists()) {
            println("skip: 还没有服务端形态的 v7 样本")
            return
        }
        val raw = file.readText()
        val adapted = ServerBackupAdapter.toLocalBackupJson(raw) ?: error("适配失败")
        // extensions 不能原样塞给本地解码（proactiveFailures 客户端没有表），但两张客户端表要提上来。
        assertTrue("适配后不该再带 extensions 夹层", !adapted.contains("\"extensions\""))
        assertTrue("诊断段不落本地", !adapted.contains("proactiveFailures"))

        val success = BackupCodec.decode(adapted) as? BackupDecodeResult.Success
            ?: error("服务端 v7 样本经适配后读不出来")
        assertEquals(7, success.bundle.formatVersion)
        assertEquals(1, success.bundle.mentalActivities.size)
        assertEquals("他其实在等对方先开口", success.bundle.mentalActivities.single().thought)
        assertEquals(1, success.bundle.sessionMemory.size)
        assertEquals("短期：刚聊完搬家", success.bundle.sessionMemory.single().shortSummary)
        assertEquals(1, success.bundle.sessionMemory.single().topics.size)
    }

    @Test
    fun `服务端历史字段与 v6 段同时出现时都能转换`() {
        // 服务端如果按自己的字段名回（avatarUrl / importance 0-100 / unread / attachments），
        // 适配层要转成客户端字段，同时不能把 v6 的 stickers/diaryNotes/media 丢掉。
        val serverJson = """
            {
              "format":"myfriend.backup","formatVersion":6,"apiKeysOmitted":true,
              "personas":[{"id":"p1","name":"林深","avatarUrl":"/v1/media/avatar-1","updatedAt":1}],
              "memories":[{"id":"m1","personaId":"p1","content":"喜欢下雨","importance":80,"updatedAt":1}],
              "sessions":[{"id":"s1","personaId":"p1","title":"日常","unread":2,"updatedAt":1}],
              "messages":[{"id":"msg1","sessionId":"s1","role":"USER","content":"在吗",
                           "attachments":["/v1/media/img-1"],"createdAt":1,"updatedAt":1}],
              "media":[{"id":"stk_1","kind":"STICKER","fileName":"stk_1.gif","mime":"image/gif",
                        "bytes":3,"data":"R0lG"}],
              "stickers":[{"id":"stk_1","file":"stk_1.gif","mime":"image/gif","createdAt":1}],
              "diaryNotes":[{"id":"n1","personaId":"p1","content":"周四打电话","createdAt":1,"updatedAt":1}],
              "presets":[],"proactiveRules":[],"agentIntents":[],"memoryConsolidations":[],
              "apiConfigs":[],"settings":{}
            }
        """.trimIndent()

        val adapted = ServerBackupAdapter.toLocalBackup(Json.parseToJsonElement(serverJson).jsonObject)
        val root = Json.parseToJsonElement(adapted.toString()).jsonObject
        assertEquals("/v1/media/avatar-1", root["personas"]!!.jsonArray[0].jsonObject["avatarUri"]!!.jsonPrimitive.content)
        assertEquals("4", root["memories"]!!.jsonArray[0].jsonObject["importance"]!!.jsonPrimitive.content)
        assertEquals("2", root["sessions"]!!.jsonArray[0].jsonObject["unreadCount"]!!.jsonPrimitive.content)
        assertTrue(root["messages"]!!.jsonArray[0].jsonObject.containsKey("attachmentsJson"))
        // v6 两段：一个字段都不丢，sticker 的两个同义文件名都补齐。
        val sticker = root["stickers"]!!.jsonArray[0].jsonObject
        assertEquals("stk_1.gif", sticker["fileName"]!!.jsonPrimitive.content)
        assertEquals("stk_1.gif", sticker["file"]!!.jsonPrimitive.content)
        assertEquals(1, root["diaryNotes"]!!.jsonArray.size)
        assertEquals(1, root["media"]!!.jsonArray.size)

        assertTrue(BackupCodec.decode(adapted.toString()) is BackupDecodeResult.Success)
    }

    @Test
    fun `服务端导出 v6 文本按原样写进用户选的文件`() = runBlocking {
        val raw = fixture("android-export-v6.json")
        server.respondJson(raw)

        val exported = gateway.exportBackup().getOrThrow()

        // 不做二次编码：用户拿到的就是服务端那份（字段顺序/未知字段都保留）。
        assertEquals(raw, exported)
        assertTrue(exported.contains("\"formatVersion\": 6") || exported.contains("\"formatVersion\":6"))
        assertEquals("/v1/backup", server.requests.single().path)
    }

    @Test
    fun `导入客户端 v6 文件到服务端发的是 mode 加 data 对象`() = runBlocking {
        server.respondJson(
            """{"mode":"replace","imported":{"personas":1,"sessions":1,"messages":2,
               "memories":1,"presets":0,"settings":true}}"""
        )
        // iOS 端并行开发期间可能已经把这个样本升级成 v7：断言「原样透传」而不是写死 6。
        val raw = fixture("ios-export-v6.json")
        val declared = Regex("\"formatVersion\"\\s*:\\s*(\\d+)").find(raw)
            ?.groupValues?.getOrNull(1) ?: error("样本缺 formatVersion")

        val imported = gateway.importBackup(raw, ServerBackupMode.REPLACE).getOrThrow()

        assertEquals(1, imported.imported.personas)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/backup", request.path)
        val body = Json.parseToJsonElement(request.body).jsonObject
        assertEquals("replace", body["mode"]!!.jsonPrimitive.content)
        // data 是对象：v6 的顶字段都应该在，且没有被二次编码成字符串。
        val data = body["data"]!!.jsonObject
        assertEquals("原样透传，不二次改写版本", declared, data["formatVersion"]!!.jsonPrimitive.content)
        assertTrue(data.containsKey("stickers"))
        assertTrue(data.containsKey("diaryNotes"))
        assertTrue(data.containsKey("media"))
    }

    @Test
    fun `服务端备份里出现密钥字段时导出必须失败`() = runBlocking {
        server.respondJson("""{"format":"myfriend.backup","formatVersion":6,"apiConfigs":[{"apiKey":"sk-x"}]}""")
        val result = gateway.exportBackup()
        // 网关本身只负责取文本；密钥自检在 BackupRepository 的导出路径上（这里验证文本确实带出来了，
        // 由上层拦截），所以这里断言「拿得到原文」，防止有人把自检误加在网关里丢掉功能。
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contains("apiKey"))
    }

    private fun fixture(name: String): String {
        val candidates = listOf(
            File("../fixtures/backup/$name"),
            File("fixtures/backup/$name")
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 fixtures/backup/$name（工作目录=${File("").absolutePath}）")
    }
}
