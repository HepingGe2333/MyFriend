package com.myfriend.agentd

import com.myfriend.agentd.store.FileStore
import com.myfriend.agentd.store.MessageRole
import com.myfriend.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 备份 v5 导出/导入 + 事务性 + 重启持久化测试。
 *
 * 重点不是「字段能写进去」，而是：
 * - 导出里绝不含 secrets / API Key；
 * - merge 按 id 更新/新增，replace 清空后导入；
 * - 校验失败时一条数据都不写（先校验、后落盘）；
 * - 导入后重新打开数据目录仍能读回。
 */
class BackupApiTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_000L)
    private val extraRoots = mutableListOf<Path>()

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-backup-test")
        clock.now = 1_000L
        store = FileStore.open(root, clock.reader)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
        extraRoots.forEach { TestSupport.deleteRecursively(it) }
    }

    private fun call(method: String, path: String, body: String? = null): AgentdApi.Response =
        AuthorityRoutes.handle(AgentdServices(store), method, path, body)

    private fun callOn(target: FileStore, method: String, path: String, body: String? = null): AgentdApi.Response =
        AuthorityRoutes.handle(AgentdServices(target), method, path, body)

    private fun json(response: AgentdApi.Response): JsonValue.Obj = TestSupport.parse(response.body)

    private fun errorCode(response: AgentdApi.Response): String = json(response).obj("error").str("code")

    private fun export(): JsonValue.Obj = json(call("GET", "/v1/backup"))

    private fun import(mode: String, data: JsonValue.Obj): AgentdApi.Response {
        val payload = JsonValue.Obj(
            linkedMapOf(
                "mode" to JsonValue.Str(mode),
                "data" to data
            )
        )
        return call("POST", "/v1/backup", com.myfriend.app.core.json.Json.stringify(payload))
    }

    /** 往当前 store 放一套有内容的可导出数据。 */
    private fun seedContent() {
        call(
            "POST",
            "/v1/personas",
            """{"name":"备份人格","description":"描述","worldJson":"W","traits":["稳"]}"""
        )
        call(
            "POST",
            "/v1/personas/${FileStore.SEED_PERSONA_1_ID}/memories",
            """{"content":"记住的事","importance":70,"tags":["tag"]}"""
        )
        call("POST", "/v1/presets", """{"name":"备份预设","worldJson":"PW"}""")
        store.appendMessage(FileStore.SEED_SESSION_ID, MessageRole.USER, "用户消息")
        store.appendMessage(FileStore.SEED_SESSION_ID, MessageRole.ASSISTANT, "助手消息")
        call("POST", "/v1/settings", """{"themeMode":"DARK","activePersonaId":"seed-linshen"}""")
    }

    // ------------------------------------------------------------------ 导出

    @Test
    fun `backup export is v5 and contains no secrets`() {
        store.setApiKey("cfg-1", "sk-very-secret")
        store.setSetting("apiKey", JsonValue.Str("sk-in-settings"))
        seedContent()
        call(
            "POST",
            "/v1/personas",
            """{"name":"夹带密钥的人","apiKey":"sk-in-persona","webToolsApiKeyCipher":"cipher"}"""
        )

        val backup = export()
        assertEquals(FileStore.BACKUP_FORMAT_NAME, backup.str("format"))
        assertEquals(5, backup.int("formatVersion"))
        assertEquals(AgentdApi.VERSION, backup.str("appVersion"))
        assertTrue(backup.bool("apiKeysOmitted"))
        assertEquals(5, backup.objects("personas").size)
        assertEquals(1, backup.objects("sessions").size)
        assertEquals(2, backup.objects("messages").size)
        assertEquals(1, backup.objects("memories").size)
        assertEquals(1, backup.objects("presets").size)
        assertFalse(backup.fields.containsKey("secrets"))
        assertFalse(backup.fields.containsKey("apiConfigs"))
        val text = call("GET", "/v1/backup").body
        assertFalse("备份不能出现 API Key：$text", text.contains("sk-very-secret"))
        assertFalse("备份不能出现 settings 里的密钥：$text", text.contains("sk-in-settings"))
        assertFalse("人格未知字段里的密钥也不能带走：$text", text.contains("sk-in-persona"))
        assertFalse(text.contains("cipher"))
    }

    @Test
    fun `backup export carries persona client fields and settings safely`() {
        seedContent()
        val backup = export()
        val persona = backup.objects("personas").first { it.str("name") == "备份人格" }
        assertEquals("W", persona.str("worldJson"))
        assertEquals(listOf("稳"), persona.strings("traits"))
        val memory = backup.objects("memories").single()
        assertEquals(listOf("tag"), memory.strings("tags"))
        assertEquals("DARK", backup.obj("settings").str("themeMode"))
        assertFalse(backup.obj("settings").fields.containsKey("apiKey"))
    }

    // ------------------------------------------------------------------ merge / replace

    @Test
    fun `replace backup restores a v5 payload and clears the old data`() {
        val data = TestSupport.parse(
            """
            {
              "format":"myfriend.backup","formatVersion":5,"apiKeysOmitted":true,
              "personas":[{"id":"p1","name":"唯一","description":"","createdAt":10,"updatedAt":10,
                           "worldJson":"W","traits":["a"]}],
              "sessions":[{"id":"s1","personaId":"p1","title":"对话","createdAt":10,"updatedAt":10}],
              "messages":[
                {"id":"m1","sessionId":"s1","role":"USER","content":"嗨","createdAt":11},
                {"id":"m2","sessionId":"s1","role":"ASSISTANT","content":"在","createdAt":12}
              ],
              "memories":[{"id":"mem1","personaId":"p1","content":"记得","importance":60,
                           "tags":["x"],"createdAt":13,"updatedAt":13}],
              "presets":[{"id":"pre1","name":"预设","worldJson":"PW","createdAt":10,"updatedAt":10}],
              "settings":{"themeMode":"DARK","fontScale":1.1,"apiKey":"must-not-land"}
            }
            """.trimIndent()
        )
        val response = import("replace", data)
        assertEquals(response.body, 200, response.status)
        assertEquals(1, json(response).obj("imported").int("personas"))
        assertEquals(1, store.personas().size)
        assertEquals("p1", store.personas().single().id)
        assertEquals(listOf("a"), (store.personas().single().extraFields["traits"] as JsonValue.Arr).items.map { (it as JsonValue.Str).value })
        assertEquals("s1", store.sessions().single().id)
        assertEquals(2, store.blockMessages("s1", 0).size)
        assertEquals(1, store.session("s1")!!.unread)
        assertEquals("记得", store.memories("p1").single().content)
        assertEquals("预设", store.presets().single().name)
        assertEquals("DARK", store.publicSettings().str("themeMode"))
        assertFalse("导入的设置也不许带密钥", store.publicSettings().fields.containsKey("apiKey"))
        assertNull("旧种子会话应该被清掉", store.session(FileStore.SEED_SESSION_ID))
    }

    @Test
    fun `merge updates by id and keeps everything not mentioned`() {
        seedContent()
        val exported = export()
        val targetRoot = TestSupport.tempDir("agentd-backup-target")
        extraRoots.add(targetRoot)
        val target = FileStore.open(targetRoot, clock.reader)
        assertEquals(200, callOn(target, "POST", "/v1/backup", com.myfriend.app.core.json.Json.stringify(
            JsonValue.Obj(linkedMapOf("mode" to JsonValue.Str("merge"), "data" to exported))
        )).status)
        assertEquals(4, target.personas().size)
        assertEquals(2, target.blockMessages(FileStore.SEED_SESSION_ID, 0).size)

        // 改 id 相同的名字，同时新增一条记忆；未提及的会话/消息/预设必须保留。
        val mutated = TestSupport.parse(com.myfriend.app.core.json.Json.stringify(exported))
        val persona = mutated.objects("personas").first { it.str("id") == FileStore.SEED_PERSONA_1_ID }
        persona.fields["name"] = JsonValue.Str("改过的名字")
        mutated.fields["memories"] = JsonValue.Arr(
            mutableListOf(
                TestSupport.parse("""{"id":"new-memory","personaId":"seed-linshen","content":"新增","importance":40,"createdAt":99,"updatedAt":99}""")
            )
        )
        val response = callOn(target, "POST", "/v1/backup", com.myfriend.app.core.json.Json.stringify(
            JsonValue.Obj(linkedMapOf("mode" to JsonValue.Str("merge"), "data" to mutated))
        ))
        assertEquals(response.body, 200, response.status)
        assertEquals("改过的名字", target.persona(FileStore.SEED_PERSONA_1_ID)?.name)
        assertEquals(2, target.blockMessages(FileStore.SEED_SESSION_ID, 0).size)
        assertEquals("merge 只按 id 覆盖，未提及的旧记忆保留", 2, target.memories(FileStore.SEED_PERSONA_1_ID).size)
        assertEquals("新增", target.memories(FileStore.SEED_PERSONA_1_ID).first { it.content == "新增" }.content)
    }

    @Test
    fun `merge adds new personas sessions and messages without duplicating ids`() {
        seedContent()
        val backup = export()
        val response = import("merge", backup)
        assertEquals(200, response.status)
        assertEquals("按 id 合并，不重复", 4, store.personas().size)
        assertEquals(1, store.sessions().size)
        assertEquals(2, store.blockMessages(FileStore.SEED_SESSION_ID, 0).size)
        assertEquals(1, store.memories(FileStore.SEED_PERSONA_1_ID).size)
        assertEquals(1, store.presets().size)
    }

    // ------------------------------------------------------------------ 校验 / 事务

    @Test
    fun `export import export round trip keeps every content section identical`() {
        seedContent()
        val exported = export()
        val targetRoot = TestSupport.tempDir("agentd-backup-roundtrip")
        extraRoots.add(targetRoot)
        val target = FileStore.open(targetRoot, clock.reader)
        val payload = JsonValue.Obj(
            linkedMapOf("mode" to JsonValue.Str("replace"), "data" to exported)
        )
        assertEquals(
            200,
            callOn(target, "POST", "/v1/backup", com.myfriend.app.core.json.Json.stringify(payload)).status
        )
        val reExported = callOn(target, "GET", "/v1/backup").let { TestSupport.parse(it.body) }
        for (section in listOf("personas", "sessions", "messages", "memories", "presets", "settings")) {
            assertEquals(
                "$section 在导出→导入→再导出后应一致",
                com.myfriend.app.core.json.Json.stringify(exported.fields.getValue(section)),
                com.myfriend.app.core.json.Json.stringify(reExported.fields.getValue(section))
            )
        }
        assertFalse(reExported.bodyContainsSecret())
    }

    private fun JsonValue.Obj.bodyContainsSecret(): Boolean =
        com.myfriend.app.core.json.Json.stringify(this).contains("sk-")

    @Test
    fun `a newer backup version is 409 and writes nothing`() {
        val before = store.personas().size
        val data = TestSupport.parse("""{"formatVersion":6,"personas":[]}""")
        val response = import("replace", data)
        assertEquals(409, response.status)
        assertEquals("unsupported_backup_version", errorCode(response))
        assertEquals(before, store.personas().size)
    }

    @Test
    fun `invalid references fail validation before anything is written`() {
        val beforePersonas = store.personas().size
        val beforeFiles = Files.list(root).use { stream -> stream.count() }
        val data = TestSupport.parse(
            """
            {"formatVersion":5,
             "personas":[{"id":"ok","name":"合法","createdAt":1,"updatedAt":1}],
             "sessions":[{"id":"s1","personaId":"missing","title":"x","createdAt":1,"updatedAt":1}],
             "messages":[],"memories":[],"presets":[]}
            """.trimIndent()
        )
        val response = import("replace", data)
        assertEquals(400, response.status)
        assertEquals("invalid_backup", errorCode(response))
        assertEquals("校验失败不能写一半", beforePersonas, store.personas().size)
        assertNull(store.persona("ok"))
        assertEquals(beforeFiles, Files.list(root).use { stream -> stream.count() })
    }

    @Test
    fun `backup import validates mode and required data`() {
        assertEquals(400, call("POST", "/v1/backup", """{"mode":"merge"}""").status)
        assertEquals(400, call("POST", "/v1/backup", """{"mode":"other","data":{}}""").status)
        assertEquals(400, call("POST", "/v1/backup", """{"mode":"merge","data":[]}""").status)
    }

    @Test
    fun `backup import survives a restart`() {
        val data = TestSupport.parse(
            """
            {"formatVersion":5,
             "personas":[{"id":"p1","name":"持久","createdAt":10,"updatedAt":10}],
             "sessions":[{"id":"s1","personaId":"p1","title":"对话","createdAt":10,"updatedAt":10}],
             "messages":[{"id":"m1","sessionId":"s1","role":"USER","content":"在","createdAt":11}],
             "memories":[{"id":"mem1","personaId":"p1","content":"记住","createdAt":11,"updatedAt":11}],
             "presets":[{"id":"pre1","name":"预设","createdAt":11,"updatedAt":11}],
             "settings":{"themeMode":"LIGHT"}}
            """.trimIndent()
        )
        assertEquals(200, import("replace", data).status)
        val reopened = FileStore.open(root, clock.reader)
        assertEquals("持久", reopened.persona("p1")?.name)
        assertEquals("对话", reopened.session("s1")?.title)
        assertEquals("在", reopened.blockMessages("s1", 0).single().content)
        assertEquals("记住", reopened.memories("p1").single().content)
        assertEquals("预设", reopened.preset("pre1")?.name)
        assertEquals("LIGHT", reopened.publicSettings().str("themeMode"))
    }

    @Test
    fun `backup import rejects duplicate ids before writing`() {
        val duplicate = TestSupport.parse(
            """
            {"formatVersion":5,
             "personas":[{"id":"p1","name":"a","createdAt":1,"updatedAt":1},
                         {"id":"p1","name":"b","createdAt":1,"updatedAt":1}],
             "sessions":[],"messages":[],"memories":[],"presets":[]}
            """.trimIndent()
        )
        assertEquals(400, import("replace", duplicate).status)
        assertEquals(3, store.personas().size)
    }

    @Test
    fun `merge backup keeps local settings that the backup does not mention`() {
        call("POST", "/v1/settings", """{"themeMode":"LIGHT","fontScale":1.0}""")
        val data = TestSupport.parse(
            """
            {"formatVersion":5,"personas":[],"sessions":[],"messages":[],
             "memories":[],"presets":[],"settings":{"fontScale":1.4}}
            """.trimIndent()
        )
        assertEquals(200, import("merge", data).status)
        assertEquals("LIGHT", store.publicSettings().str("themeMode"))
        assertEquals(1.4, (store.publicSettings().fields["fontScale"] as JsonValue.Num).asDouble, 0.0001)
    }
}
