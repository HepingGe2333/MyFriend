package com.yap.app.data.repo

import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.PersonaPendingPatchEntity
import com.yap.app.data.local.PersonaRevisionEntity
import com.yap.app.data.remote.FakeHttpTestServer
import com.yap.app.data.remote.ServerApi
import com.yap.app.data.remote.ServerApiProvider
import com.yap.app.data.remote.ServerConfig
import com.yap.app.data.remote.ServerWriteGateway
import com.yap.app.data.remote.TestServerLocalStore
import com.yap.app.domain.PersonaEvolution
import com.yap.app.domain.PersonaRevisionReason
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 「人格进化在服务端模式也能触发、也能接受」（0.13）的纯 JVM 守卫。
 *
 * 本项目的单测没有 Room/Robolectric，所以这里不建真库：
 * - **本地读写**用内存假实现 [PersonaEvolutionStore]（生产实现只有一句 DAO 转发）；
 * - **服务端**用真实的 [ServerWriteGateway] + [FakeHttpTestServer]：请求路径/方法/请求体都是
 *   真的从 OkHttp 出来的，能钉住「接受确实是一次 `POST /v1/personas`」。
 *
 * 覆盖五件事：
 * 1. 服务端模式接受：先写服务端（全量字段、保留其它字段），成功后写本地缓存并 `version + 1`；
 * 2. 服务端失败：本地一行都不写、待审项保留（界面才能提示「没写进去」）；
 * 3. 拒绝：只删本地待审，不发请求、不动人格；
 * 4. 本地模式接受：行为与 0.13 之前一致（不发网络、写回四个字段 + 版本历史）；
 * 5. 触发：一轮只触发一次、共享游标节流、切模式不重复评估。
 */
class ServerPersonaEvolutionTest {

    // ------------------------------------------------------------------ 测试替身

    private class FakeProvider(
        var api: ServerApi?,
        var enabled: Boolean
    ) : ServerApiProvider {
        override fun api(): ServerApi? = api
        override fun isEnabled(): Boolean = enabled
    }

    /** 内存版 [PersonaEvolutionStore]：把「写了什么」原样记下来供断言。 */
    private class FakeStore : PersonaEvolutionStore {
        val personas = mutableMapOf<String, PersonaEntity>()
        val patches = mutableMapOf<String, PersonaPendingPatchEntity>()
        val revisions = mutableListOf<PersonaRevisionEntity>()
        var transactions = 0

        override suspend fun patch(id: String): PersonaPendingPatchEntity? = patches[id]

        override suspend fun persona(id: String): PersonaEntity? = personas[id]

        override suspend fun deletePatch(id: String) {
            patches.remove(id)
        }

        override suspend fun updatePersona(persona: PersonaEntity) {
            personas[persona.id] = persona
        }

        override suspend fun insertRevision(revision: PersonaRevisionEntity) {
            revisions += revision
        }

        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            transactions++
            return block()
        }
    }

    private lateinit var server: FakeHttpTestServer
    private lateinit var api: ServerApi
    private lateinit var provider: FakeProvider
    private lateinit var localStore: TestServerLocalStore
    private lateinit var gateway: ServerWriteGateway
    private lateinit var store: FakeStore
    private lateinit var acceptance: PersonaEvolutionAcceptance

    @Before
    fun setUp() {
        server = FakeHttpTestServer()
        api = ServerApi(
            config = ServerConfig(baseUrl = server.baseUrl, token = "test-token", enabled = true),
            debuggable = true
        )
        provider = FakeProvider(api = api, enabled = true)
        localStore = TestServerLocalStore()
        gateway = ServerWriteGateway(provider, localStore)
        store = FakeStore()
        acceptance = PersonaEvolutionAcceptance(store, gateway)
    }

    @After
    fun tearDown() {
        server.close()
    }

    // ------------------------------------------------------------------ 夹具

    private fun persona(
        id: String = LOCAL_PERSONA_ID,
        version: Int = 3,
        traits: String = """["慢热"]""",
        likes: String = """["夜里散步"]""",
        behaviors: String = "[]",
        quirks: String? = null
    ) = PersonaEntity(
        id = id,
        name = "林深",
        description = "建筑师",
        avatarUri = null,
        avatarSeed = 7,
        prompt = "用户手写的身份与边界",
        category = "日常聊天",
        version = version,
        defaultApiConfigId = "cfg-1",
        modelOverride = null,
        isDefault = true,
        createdAt = 100L,
        updatedAt = 200L,
        traits = traits,
        likes = likes,
        behaviors = behaviors,
        quirks = quirks,
        worldJson = """{"name":"海边小城"}""",
        speechStyleJson = """{"tone":"温和"}"""
    )

    private fun patch(
        id: String = PATCH_ID,
        personaId: String = LOCAL_PERSONA_ID,
        patchJson: String = EVOLUTION_PATCH
    ) = PersonaPendingPatchEntity(
        id = id,
        personaId = personaId,
        sessionId = "s1",
        patchJson = patchJson,
        summary = "性格 +嘴硬心软",
        reason = "测试",
        createdAt = 300L
    )

    private suspend fun seedServerPersonaMapping() {
        localStore.setPersonaMapping(serverPersonaId = SERVER_PERSONA_ID, localPersonaId = LOCAL_PERSONA_ID)
    }

    // ------------------------------------------------------------------ 1. 服务端模式接受

    @Test
    fun `服务端模式接受 先 POST v1 personas 再写本地并升版本`() = runBlocking {
        seedServerPersonaMapping()
        store.personas[LOCAL_PERSONA_ID] = persona()
        store.patches[PATCH_ID] = patch()
        server.respondJson(
            """{"persona":{"id":"$SERVER_PERSONA_ID","name":"林深","createdAt":100,"updatedAt":9999}}"""
        )

        val result = acceptance.accept(PATCH_ID, now = 5_000L)

        assertTrue(result.isSuccess)
        val updated = result.getOrThrow()
        // 写回本地：四个字段合并、version + 1、updatedAt 用服务端时钟。
        assertEquals(4, updated.version)
        assertTrue(updated.traits.contains("嘴硬心软"))
        assertEquals(9_999L, updated.updatedAt)
        assertEquals(4, store.personas.getValue(LOCAL_PERSONA_ID).version)

        // 请求：POST /v1/personas，id 用服务端 id，四个字段与其它字段一起发（保留其它字段）。
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/personas", request.path)
        val body = Json.parseToJsonElement(request.body).jsonObject
        assertEquals(SERVER_PERSONA_ID, body.getValue("id").jsonPrimitive.content)
        assertEquals(4, body.getValue("version").jsonPrimitive.int)
        assertTrue(body.getValue("traits").jsonPrimitive.content.contains("嘴硬心软"))
        assertEquals("用户手写的身份与边界", body.getValue("prompt").jsonPrimitive.content)
        assertEquals("""{"name":"海边小城"}""", body.getValue("worldJson").jsonPrimitive.content)
        assertFalse("头像没变就不发，避免把服务端头像清空", body.containsKey("avatarUrl"))

        // 待审删掉；版本历史记了「变化前 → 变化后」两条。
        assertNull(store.patches[PATCH_ID])
        assertEquals(2, store.revisions.size)
        assertEquals(PersonaRevisionReason.BEFORE_EVOLUTION, store.revisions[0].reason)
        assertTrue(store.revisions[1].reason.startsWith(PersonaRevisionReason.PREFIX))
        assertEquals(1, store.transactions)
    }

    @Test
    fun `服务端模式接受时没有 id 映射就按本地 id 发送`() = runBlocking {
        // bootstrap 之前 / 本地与服务端 id 相同的场景：没有映射时原样用本地 id。
        store.personas[LOCAL_PERSONA_ID] = persona()
        store.patches[PATCH_ID] = patch()
        server.respondJson("""{"persona":{"id":"$LOCAL_PERSONA_ID","updatedAt":9999}}""")

        assertTrue(acceptance.accept(PATCH_ID, now = 5_000L).isSuccess)
        val body = Json.parseToJsonElement(server.requests.single().body).jsonObject
        assertEquals(LOCAL_PERSONA_ID, body.getValue("id").jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ 2. 失败保留待审

    @Test
    fun `服务端模式接受失败 不写本地 待审项留着`() = runBlocking {
        seedServerPersonaMapping()
        store.personas[LOCAL_PERSONA_ID] = persona()
        store.patches[PATCH_ID] = patch()
        server.respondJson(
            """{"error":{"message":"boom","code":"state_unavailable"}}""",
            status = 503
        )

        val result = acceptance.accept(PATCH_ID, now = 5_000L)

        assertTrue(result.isFailure)
        assertNotNull("待审项必须留着，界面才能让用户重试", store.patches[PATCH_ID])
        assertEquals("本地人格一行都不能被改", 3, store.personas.getValue(LOCAL_PERSONA_ID).version)
        assertFalse(store.personas.getValue(LOCAL_PERSONA_ID).traits.contains("嘴硬心软"))
        assertTrue("失败不该留下版本历史", store.revisions.isEmpty())
        assertEquals(0, store.transactions)
    }

    @Test
    fun `服务端不可达时也是失败而不是悄悄本地写`() = runBlocking {
        seedServerPersonaMapping()
        store.personas[LOCAL_PERSONA_ID] = persona()
        store.patches[PATCH_ID] = patch()
        provider.api = null // 比方说设置页把地址清空了：本机还有「服务端模式」状态

        val result = acceptance.accept(PATCH_ID, now = 5_000L)

        assertTrue(result.isFailure)
        assertNotNull(store.patches[PATCH_ID])
        assertEquals(3, store.personas.getValue(LOCAL_PERSONA_ID).version)
    }

    // ------------------------------------------------------------------ 3. 拒绝

    @Test
    fun `拒绝只删待审 不发请求 人格一动不动`() = runBlocking {
        seedServerPersonaMapping()
        store.personas[LOCAL_PERSONA_ID] = persona()
        store.patches[PATCH_ID] = patch()

        acceptance.reject(PATCH_ID)

        assertNull(store.patches[PATCH_ID])
        assertEquals(3, store.personas.getValue(LOCAL_PERSONA_ID).version)
        assertTrue(server.requests.isEmpty())
        assertTrue(store.revisions.isEmpty())
    }

    // ------------------------------------------------------------------ 4. 本地模式回归

    @Test
    fun `本地模式接受不发网络 写回四个字段并升版本`() = runBlocking {
        provider.enabled = false
        store.personas[LOCAL_PERSONA_ID] = persona()
        store.patches[PATCH_ID] = patch()

        val result = acceptance.accept(PATCH_ID, now = 5_000L)

        assertTrue(result.isSuccess)
        assertTrue(server.requests.isEmpty())
        val updated = result.getOrThrow()
        assertEquals(4, updated.version)
        assertEquals(5_000L, updated.updatedAt)
        assertTrue(updated.traits.contains("嘴硬心软"))
        assertNull(store.patches[PATCH_ID])
        assertEquals(2, store.revisions.size)
        assertEquals(1, store.transactions)
    }

    @Test
    fun `与现状完全一样的变化只清待审 不发请求也不升版本`() = runBlocking {
        seedServerPersonaMapping()
        store.personas[LOCAL_PERSONA_ID] = persona(traits = """["慢热","嘴硬心软"]""")
        store.patches[PATCH_ID] = patch()

        val result = acceptance.accept(PATCH_ID, now = 5_000L)

        assertTrue(result.isSuccess)
        assertEquals("没有真正变化：不升版本", 3, result.getOrThrow().version)
        assertNull(store.patches[PATCH_ID])
        assertTrue("没有变化就不该打服务端", server.requests.isEmpty())
        assertTrue(store.revisions.isEmpty())
    }

    @Test
    fun `补丁不存在时返回失败且什么都不写`() = runBlocking {
        store.personas[LOCAL_PERSONA_ID] = persona()

        val result = acceptance.accept("missing", now = 5_000L)

        assertTrue(result.isFailure)
        assertTrue(server.requests.isEmpty())
        assertTrue(store.revisions.isEmpty())
    }

    // ------------------------------------------------------------------ 5. 触发

    /** 记录「仓库层真正评估了几次」的假实现；游标/节流规则用生产代码。 */
    private class CursorAwareTarget : PersonaEvolutionTrigger {
        var userMessages: Int = 0
        var cursor: EvolutionCursor = EvolutionCursor().forPersona(PERSONA_ID)
        var evaluations: Int = 0

        override fun ensureAsync(personaId: String, now: Long) {
            if (!cursor.shouldEvaluate(userMessages, now)) return
            // 与 `PersonaEvolutionRepository.maybeEvaluate` 一致：调用模型之前推进游标。
            cursor = cursor.advanced(userMessages, now)
            evaluations++
        }
    }

    @Test
    fun `同一轮 done 重复到达只触发一次`() {
        val target = CursorAwareTarget()
        val trigger = ServerEvolutionTrigger(target)

        assertTrue(trigger.onReplyDone(PERSONA_ID, "assistant-1", now = 1_000L))
        assertFalse("同一条 assistant 消息的 done 重放不该再触发", trigger.onReplyDone(PERSONA_ID, "assistant-1", now = 1_000L))
        assertEquals(0, target.evaluations)

        // 下一轮：换了 id 才再触发（是否真的评估仍由共享游标决定）。
        assertTrue(trigger.onReplyDone(PERSONA_ID, "assistant-2", now = 2_000L))
    }

    @Test
    fun `空人格不触发`() {
        val target = CursorAwareTarget()
        val trigger = ServerEvolutionTrigger(target)

        assertFalse(trigger.onReplyDone("", "assistant-1", now = 1_000L))
        assertFalse(trigger.onReplyDone("   ", "assistant-2", now = 1_000L))
        // 没有轮次 id 时不按轮去重，但仍然交给仓库（仓库自己的游标兜底）。
        assertTrue(trigger.onReplyDone(PERSONA_ID, "", now = 1_000L))
        assertTrue(trigger.onReplyDone(PERSONA_ID, "", now = 2_000L))
    }

    @Test
    fun `服务端模式触发受共享游标节流 20 条一次`() {
        val target = CursorAwareTarget()
        val trigger = ServerEvolutionTrigger(target)

        // 只有 19 条用户消息：任何一轮回复完成都不评估。
        target.userMessages = 19
        trigger.onReplyDone(PERSONA_ID, "m-1", now = 1_000L)
        assertEquals(0, target.evaluations)

        // 第 20 条：评估一次。
        target.userMessages = 20
        trigger.onReplyDone(PERSONA_ID, "m-2", now = 2_000L)
        assertEquals(1, target.evaluations)

        // 紧接着又一轮回复：游标已经是 20，不重复。
        target.userMessages = 21
        trigger.onReplyDone(PERSONA_ID, "m-3", now = 3_000L)
        assertEquals(1, target.evaluations)

        // 再攒够 20 条：评估第二次。
        target.userMessages = 40
        trigger.onReplyDone(PERSONA_ID, "m-4", now = 4_000L)
        assertEquals(2, target.evaluations)
    }

    @Test
    fun `切模式不重复评估 本地与服务端共用同一个游标`() {
        val target = CursorAwareTarget()
        val trigger = ServerEvolutionTrigger(target)

        // 本地模式：发完第 20 条用户消息，评估一次（ChatRepository.sendUserMessage 的触发点）。
        target.userMessages = 20
        target.ensureAsync(PERSONA_ID, now = 1_000L)
        assertEquals(1, target.evaluations)

        // 立刻切到服务端模式，下一轮回复 done：服务端看到的是同一个游标 → 不重复评估。
        target.userMessages = 20
        assertTrue("触发交给仓库了，但仓库按游标决定不评估", trigger.onReplyDone(PERSONA_ID, "m-1", now = 2_000L))
        assertEquals(1, target.evaluations)

        // 服务端模式又攒了 20 条 → 评估一次。
        target.userMessages = 40
        trigger.onReplyDone(PERSONA_ID, "m-2", now = 3_000L)
        assertEquals(2, target.evaluations)
    }

    @Test
    fun `游标属于别的人格时从零算`() {
        val cursor = EvolutionCursor(personaId = "other", userMessages = 99, lastRunAt = 9_999L)
            .forPersona(PERSONA_ID)
        assertEquals(PERSONA_ID, cursor.personaId)
        assertEquals(0, cursor.userMessages)
        assertEquals(0L, cursor.lastRunAt)
        assertTrue(cursor.shouldEvaluate(userMessages = 20, now = 1_000L))
    }

    @Test
    fun `24 小时节流也适用于服务端模式`() {
        val now = 1_791_000_000_000L
        val day = PersonaEvolutionRepository.EVAL_INTERVAL_MS
        val base = EvolutionCursor(personaId = PERSONA_ID).advanced(userMessages = 20, now = now)

        assertFalse(base.shouldEvaluate(userMessages = 21, now = now + 60_000L))
        assertTrue(base.shouldEvaluate(userMessages = 21, now = now + day))
        assertFalse("没新消息就不评估", base.shouldEvaluate(userMessages = 20, now = now + day * 2))
    }

    // ------------------------------------------------------------------ 6. 接线（读源码）

    /**
     * 单测能钉住「触发/节流/接受」的行为，但钉不住「聊天主路径真的调了它」。
     * 这条按项目惯例读源码：服务端发送在 `done` 之后必须调 [ServerEvolutionTrigger]。
     */
    @Test
    fun `服务端发送在 done 之后触发进化`() {
        val source = source("src/main/java/com/yap/app/data/repo/ChatRepository.kt")
        val block = source
            .substringAfter("suspend fun sendServerMessage(")
            .substringBefore("private suspend fun patchServerAttachments(")
        assertTrue("sendServerMessage 里要判断 result.done", block.contains("result.done"))
        assertTrue(
            "done 之后要交给 ServerEvolutionTrigger（与本地模式共用同一个评估入口）",
            block.contains("serverEvolution.onReplyDone(")
        )
    }

    /** 人设页在服务端模式也必须显示这块；接受/拒绝都走同一个仓库入口。 */
    @Test
    fun `人设页在服务端模式显示他最近的变化`() {
        val screen = source("src/main/java/com/yap/app/ui/screens/persona/PersonaEditorScreen.kt")
        assertTrue("要把 serverMode 传进「他最近的变化」", screen.contains("serverMode = state.serverMode"))
        assertTrue(
            "服务端模式要有一句「会同步到服务端」的说明",
            screen.contains("R.string.persona_evolution_section_note_server")
        )
    }

    /** 读 `app/` 下的源码（与 `ChatPagingContractTest` 同一套根目录定位）。 */
    private fun source(relative: String): String {
        val candidates = listOf(File("."), File(".."))
        val root = candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到工程根目录：${File(".").absolutePath}")
        return File(root, relative).readText()
    }

    private companion object {
        const val LOCAL_PERSONA_ID = "local-p1"
        const val SERVER_PERSONA_ID = "server-p1"
        const val PATCH_ID = "patch-1"
        const val PERSONA_ID = LOCAL_PERSONA_ID

        const val EVOLUTION_PATCH =
            """{"changes":[{"field":"traits","op":"add","value":"嘴硬心软","reason":"聊下来觉得是这样"}]}"""
    }
}
