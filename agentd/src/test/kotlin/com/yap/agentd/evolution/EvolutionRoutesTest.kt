package com.yap.agentd.evolution

import com.yap.agentd.AgentdServices
import com.yap.agentd.EvolutionRoutes
import com.yap.agentd.TestSupport
import com.yap.agentd.bool
import com.yap.agentd.int
import com.yap.agentd.llm.FakeLlmServer
import com.yap.agentd.llm.changeJson
import com.yap.agentd.llm.changesJson
import com.yap.agentd.llm.llmAccess
import com.yap.agentd.llm.pushLlmConfig
import com.yap.agentd.llm.setPersonaExtras
import com.yap.agentd.obj
import com.yap.agentd.objects
import com.yap.agentd.persona.PersonaFields
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.PendingPatch
import com.yap.agentd.str
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.util.UUID

/**
 * 人格进化端点（`/v1/personas/{id}/evolution/…`）的契约测试：
 *
 * - evaluate：真调模型（假服务端）→ 规则过滤 → 写 `pending_patches.json`；
 * - pending：列表 + 7 天过期清理；
 * - accept：只合并四字段 + `version + 1` + 落盘（重启读回）；
 * - reject：补丁消失、人格一动不动；
 * - 无配置：503 `llm_not_configured`（显式动作不能静默成功）。
 */
class EvolutionRoutesTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private lateinit var server: FakeLlmServer

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val basePath: String get() = "/v1/personas/$personaId/evolution"

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-evolution-test")
        store = FileStore.open(root, { 1_000L })
        server = FakeLlmServer { exchange ->
            exchange.json(
                200,
                FakeLlmServer.completion(
                    changesJson(
                        changeJson("traits", "add", "嘴硬心软", 0.9),
                        changeJson("likes", "add", "夜里散步", 0.85)
                    )
                )
            )
        }
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        TestSupport.deleteRecursively(root)
    }

    private fun services(withConfig: Boolean = true): AgentdServices {
        if (withConfig) store.pushLlmConfig(server.baseUrl)
        val access = llmAccess(store)
        return AgentdServices(store, llm = access)
    }

    private fun call(services: AgentdServices, method: String, path: String, body: String? = null) =
        EvolutionRoutes.handle(services, method, path, body)

    private fun newPatch(
        createdAt: Long,
        expiresAt: Long,
        patchJson: String = changesJson(changeJson("traits", "add", "嘴硬心软", 0.9))
    ) = PendingPatch(
        id = UUID.randomUUID().toString(),
        personaId = personaId,
        sessionId = null,
        patchJson = patchJson,
        summary = "性格 +嘴硬心软",
        reason = "测试",
        createdAt = createdAt,
        expiresAt = expiresAt
    )

    // ------------------------------------------------------------------ evaluate

    @Test
    fun `evaluate asks the model and stores a pending patch`() {
        val services = services()
        store.appendMessage(FileStore.SEED_SESSION_ID, "USER", "你最近怎么这么会顶嘴")

        val response = call(services, "POST", "$basePath/evaluate")

        assertEquals(200, response.status)
        val body = TestSupport.parse(response.body)
        assertEquals(2, body.int("proposed"))
        val patch = body.obj("patch")
        assertEquals(personaId, patch.str("personaId"))
        assertTrue(patch.str("summary").contains("嘴硬心软"))

        val stored = store.pendingPatches(personaId, 2_000L)
        assertEquals(1, stored.size)
        assertEquals("性格 +嘴硬心软；喜欢的事 +夜里散步", stored.single().summary)

        // 真的调了模型：system/user 两条消息、参数是评估用的低温度。
        val request = server.onlyRequest()
        val requestBody = TestSupport.parse(request.body)
        val messages = requestBody.objects("messages")
        assertEquals(2, messages.size)
        assertTrue(messages[0].str("content").contains("人格进化评估器"))
        assertTrue(messages[1].str("content").contains("## 当前情绪"))
    }

    @Test
    fun `evaluate returns proposed zero when the model has nothing to change`() {
        server.close()
        server = FakeLlmServer { exchange -> exchange.json(200, FakeLlmServer.completion("""{"changes":[]}""")) }
        val services = services()

        val body = TestSupport.parse(call(services, "POST", "$basePath/evaluate").body)

        assertEquals(0, body.int("proposed"))
        assertTrue("没有变化时 patch 是 null", body.fields["patch"] == null ||
            body.fields["patch"] is com.yap.app.core.json.JsonValue.Null)
        assertTrue(store.pendingPatches(personaId, 2_000L).isEmpty())
    }

    @Test
    fun `evaluate without llm config is a clear 503`() {
        val response = call(services(withConfig = false), "POST", "$basePath/evaluate")

        assertEquals(503, response.status)
        val body = TestSupport.parse(response.body)
        assertEquals("llm_not_configured", body.obj("error").str("code"))
        assertTrue(body.obj("error").str("message").contains("--llm-base-url"))
    }

    @Test
    fun `evaluate with the same patch twice does not pile up duplicates`() {
        val services = services()
        call(services, "POST", "$basePath/evaluate")
        val second = TestSupport.parse(call(services, "POST", "$basePath/evaluate").body)

        assertEquals(1, store.pendingPatches(personaId, 2_000L).size)
        assertEquals(true, second.bool("alreadyPending"))
    }

    // ------------------------------------------------------------------ pending

    @Test
    fun `pending lists patches and prunes expired ones`() {
        val services = services()
        val active = store.putPendingPatch(newPatch(createdAt = 1_000L, expiresAt = System.currentTimeMillis() + 86_400_000L))
        store.putPendingPatch(newPatch(createdAt = 1L, expiresAt = 2L))

        val body = TestSupport.parse(call(services, "GET", "$basePath/pending").body)

        val patches = body.objects("patches")
        assertEquals(1, patches.size)
        assertEquals(active.id, patches.single().str("id"))
        assertEquals(1, store.pendingPatches(personaId, 1_000L).size)
    }

    // ------------------------------------------------------------------ accept

    @Test
    fun `accept merges only the four fields bumps the version and persists`() {
        val services = services()
        val evaluateBody = TestSupport.parse(call(services, "POST", "$basePath/evaluate").body)
        val patchId = evaluateBody.obj("patch").str("id")

        val response = call(services, "POST", "$basePath/$patchId/accept")

        assertEquals(200, response.status)
        val body = TestSupport.parse(response.body)
        assertEquals(true, body.bool("changed"))
        assertEquals("seed 人格缺 version 时按 1 起算，接受后是 2", 2, body.int("version"))

        val persona = store.persona(personaId)!!
        val current = PersonaFields.current(persona)
        assertEquals(listOf("嘴硬心软"), current.traits)
        assertEquals(listOf("夜里散步"), current.likes)
        assertEquals("名字/描述一律不碰", "林深", persona.name)
        assertTrue("version 要落进 extraFields", persona.extraFields[PersonaFields.VERSION] != null)
        assertTrue("补丁已经被消费", store.pendingPatch(patchId) == null)

        // 重启后读回：真的落盘了。
        val reopened = FileStore.open(root, { 2_000L })
        assertEquals(listOf("嘴硬心软"), PersonaFields.current(reopened.persona(personaId)!!).traits)
        assertEquals(2, PersonaFields.version(reopened.persona(personaId)!!))
    }

    @Test
    fun `accept keeps the client field shape for traits and quirks`() {
        // 客户端 Room 列的形状：traits 是「JSON 数组文本」，quirks 是自由文本。
        store.setPersonaExtras(
            extras = mapOf(
                PersonaFields.TRAITS to com.yap.app.core.json.JsonValue.Str("[]"),
                PersonaFields.QUIRKS to com.yap.app.core.json.JsonValue.Str("写字前会先转笔。")
            )
        )
        server.close()
        server = FakeLlmServer { exchange ->
            exchange.json(
                200,
                FakeLlmServer.completion(
                    changesJson(
                        changeJson("traits", "add", "慢热", 0.9),
                        changeJson("quirks", "add", "怕别人突然沉默。", 0.9)
                    )
                )
            )
        }
        val services = services()
        val patchId = TestSupport.parse(call(services, "POST", "$basePath/evaluate").body).obj("patch").str("id")
        call(services, "POST", "$basePath/$patchId/accept")

        val persona = store.persona(personaId)!!
        val traits = persona.extraFields[PersonaFields.TRAITS]
        assertTrue("字符串形状保持字符串", traits is com.yap.app.core.json.JsonValue.Str)
        assertEquals("[\"慢热\"]", (traits as com.yap.app.core.json.JsonValue.Str).value)
        assertTrue(PersonaFields.current(persona).quirks.contains("怕别人突然沉默"))
    }

    @Test
    fun `accept unknown patch is 404 and an expired patch is 410`() {
        val services = services()
        assertEquals(404, call(services, "POST", "$basePath/does-not-exist/accept").status)

        val expired = store.putPendingPatch(newPatch(createdAt = 1L, expiresAt = System.currentTimeMillis() - 1_000L))
        val response = call(services, "POST", "$basePath/${expired.id}/accept")
        assertEquals(410, response.status)
        assertEquals("patch_expired", TestSupport.parse(response.body).obj("error").str("code"))
        assertNull("过期的补丁顺手删掉", store.pendingPatch(expired.id))
    }

    // ------------------------------------------------------------------ reject

    @Test
    fun `reject drops the patch and leaves the persona untouched`() {
        val services = services()
        val patch = store.putPendingPatch(newPatch(createdAt = 1_000L, expiresAt = System.currentTimeMillis() + 86_400_000L))
        val before = store.persona(personaId)!!

        val response = call(services, "POST", "$basePath/${patch.id}/reject")

        assertEquals(200, response.status)
        assertTrue(TestSupport.parse(response.body).bool("rejected"))
        assertNull(store.pendingPatch(patch.id))
        val after = store.persona(personaId)!!
        assertEquals(before.extraFields, after.extraFields)
        assertEquals(before.name, after.name)
    }

    @Test
    fun `non post methods are rejected with 405`() {
        val services = services()
        assertEquals(405, call(services, "GET", "$basePath/evaluate").status)
        assertEquals(405, call(services, "GET", "$basePath/anything/accept").status)
    }

    @Test
    fun `unknown persona is 404`() {
        val services = services()
        val response = call(services, "GET", "/v1/personas/no-such-persona/evolution/pending")
        assertEquals(404, response.status)
        assertNotNull(response.body)
        assertFalse(TestSupport.parse(response.body).obj("error").str("message").isBlank())
    }
}
