package com.myfriend.agentd

import com.myfriend.agentd.proactive.ActivityAvailability
import com.myfriend.agentd.proactive.ActivityState
import com.myfriend.agentd.store.FileStore
import com.myfriend.agentd.store.MessageRole
import com.myfriend.agentd.store.MessageSource
import com.myfriend.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「占用时间」的服务端闭环（0.12 M2）：落盘 / 路由 / `GET /v1/state` / 主动门禁。
 *
 * | 层 | 钉住的东西 |
 * | --- | --- |
 * | 存储 | `personas.activityJson` 原子落盘；重启后活动与主动计数都还在；清空 = `null` |
 * | 路由 | `POST /v1/personas/{id}/activity` 夹取 15–480、枚举归一化、非法值 400 |
 * | 状态 | `GET /v1/state` 的人格带 `activity`（没设过是 `null`） |
 * | 门禁 | BUSY → 不落库并给出 `skipped.reason = busy`；LIMITED → 概率降权；过期 → 恢复 |
 *
 * 存储时钟用 [TestSupport.FakeClock]（不 sleep），随机源通过
 * [AgentdServices.proactiveRoll] 注入（`0.0` 一定掷中、`0.99` 一定不中）。
 */
class ActivityApiTest {

    private lateinit var root: Path
    private val clock = TestSupport.FakeClock(1_600_000_000_000L)
    private lateinit var store: FileStore
    private val http = HttpClient.newHttpClient()
    private val servers = mutableListOf<AgentdServer.StartedServer>()

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-activity-test")
        clock.now = 1_600_000_000_000L
        store = FileStore.open(root, clock.reader)
    }

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.stop() } }
        TestSupport.deleteRecursively(root)
    }

    private fun startServer(token: String? = null): AgentdServer.StartedServer {
        val server = AgentdServer.start("127.0.0.1", 0, token, RateLimiter(), services())
        servers += server
        return server
    }

    private fun httpPost(port: Int, path: String, body: String, token: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (token != null) builder.header("Authorization", "Bearer $token")
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun httpGet(port: Int, path: String, token: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET()
        if (token != null) builder.header("Authorization", "Bearer $token")
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    // ------------------------------------------------------------------ 工具

    private fun services(roll: () -> Double = { 0.0 }): AgentdServices =
        AgentdServices(store = store, proactiveRoll = roll)

    private fun post(path: String, body: String, services: AgentdServices = services()): AgentdApi.Response =
        AuthorityRoutes.handle(services, "POST", path, body)

    private fun get(path: String, services: AgentdServices = services()): AgentdApi.Response =
        AuthorityRoutes.handle(services, "GET", path, null)

    private fun bodyOf(response: AgentdApi.Response): JsonValue.Obj {
        assertEquals("期望 200，实际 ${response.status}：${response.body}", 200, response.status)
        return TestSupport.parse(response.body)
    }

    private fun errorCode(response: AgentdApi.Response): String =
        TestSupport.parse(response.body).obj("error").str("code")

    private fun activityPath(id: String = personaId) = "/v1/personas/$id/activity"

    private fun tick(now: Long, services: AgentdServices = services()) =
        bodyOf(ToolsRoutes.handle(services, ToolsRoutes.TICK_PATH, """{"now":$now}"""))

    private fun proactiveMessages(): List<com.myfriend.agentd.store.Message> =
        store.latestMessages(sessionId, 100).filter { it.source == MessageSource.PROACTIVE }

    private fun localDate(epochMillis: Long): String =
        LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), zone).toString()

    /** 造一条「六小时前说过话」的互动，让「最近互动」过冷却期（否则引擎只记起点不掷）。 */
    private fun seedOldInteraction(): Long {
        store.appendMessage(sessionId, MessageRole.USER, "在吗")
        val now = clock.now + 6 * 60 * 60_000L
        clock.now = now
        return now
    }

    /** 直接写一份活动（绕过路由的「必须是未来」校验，用于造**已经过期**的活动）。 */
    private fun writeActivity(
        until: Long,
        availability: ActivityAvailability,
        text: String = "在事务所赶图"
    ) {
        store.updateActivity(
            personaId,
            ActivityState.encode(
                ActivityState(
                    kind = "WORK",
                    activity = text,
                    until = until,
                    availability = availability
                )
            )
        )
    }

    private fun assert400(response: AgentdApi.Response, code: String = "invalid_argument") {
        assertEquals("期望 400，实际 ${response.status}：${response.body}", 400, response.status)
        assertEquals(code, errorCode(response))
    }

    // ------------------------------------------------------------------ 存储：落盘 / 重启 / 清空

    @Test
    fun `activity and proactive counters both survive a restart`() {
        val until = clock.now + 90 * 60_000L
        val json = ActivityState.encode(
            ActivityState(
                kind = "WORK",
                activity = "在事务所赶第三版方案",
                until = until,
                availability = ActivityAvailability.BUSY
            )
        )
        store.updateActivity(personaId, json)
        store.recordProactiveFired(personaId, clock.now, localDate(clock.now))

        assertEquals(json, store.activity(personaId))
        assertNull("没设过活动的人格是 null", store.activity(FileStore.SEED_PERSONA_2_ID))
        val disk = Files.readString(root.resolve(FileStore.PERSONAS_FILE), Charsets.UTF_8)
        assertTrue("personas.json 必须带上 activityJson：$disk", disk.contains("activityJson"))

        val reopened = FileStore.open(root, clock.reader)
        assertEquals("重启后活动不能丢", json, reopened.activity(personaId))
        assertEquals(
            "重启后主动计数不能丢（上一轮已落盘，这里顺带核对）",
            1,
            reopened.proactiveState(personaId, localDate(clock.now)).proactiveCountToday
        )
        assertEquals(until, ActivityState.decode(reopened.activity(personaId))?.until)
    }

    @Test
    fun `clearing activity removes the field`() {
        writeActivity(clock.now + 60 * 60_000L, ActivityAvailability.BUSY)
        assertTrue(store.activity(personaId) != null)

        store.updateActivity(personaId, null)
        assertNull(store.activity(personaId))

        val reopened = FileStore.open(root, clock.reader)
        assertNull("清空也要落盘", reopened.activity(personaId))
    }

    @Test
    fun `updateActivity validates the persona and the payload size`() {
        val unknown = runCatching { store.updateActivity("nope", "{}") }.exceptionOrNull()
        assertTrue("不认识的人格应该报错：$unknown", unknown is IllegalArgumentException)

        val tooLong = runCatching {
            store.updateActivity(personaId, "x".repeat(FileStore.MAX_ACTIVITY_JSON_CHARS + 1))
        }.exceptionOrNull()
        assertTrue("过长的 activityJson 应该拒绝：$tooLong", tooLong is IllegalArgumentException)
        assertNull("被拒绝的写入不能留下半截数据", store.activity(personaId))
    }

    @Test
    fun `activity update bumps updatedAt so incremental sync sees it`() {
        val before = store.persona(personaId)!!.updatedAt
        clock.advance(10)
        val updated = store.updateActivity(personaId, """{"activity":"开会"}""")
        assertTrue("GET /v1/state?since= 靠 updatedAt 过滤人格", updated.updatedAt > before)

        val since = bodyOf(get("/v1/state?since=$before"))
        assertTrue(
            "活动变了，人格就该出现在增量快照里",
            since.objects("personas").any { it.str("id") == personaId }
        )
    }

    // ------------------------------------------------------------------ 路由：夹取 / 归一化

    @Test
    fun `activity route clamps minutes and normalizes enums`() {
        val response = post(
            activityPath(),
            """{"kind":"work","activity":" 在事务所赶第三版方案 ","minutes":1,"availability":"busy"}"""
        )
        val json = bodyOf(response)
        val activity = json.obj("activity")
        assertEquals("WORK", activity.str("kind"))
        assertEquals("活动文本要 trim", "在事务所赶第三版方案", activity.str("activity"))
        assertEquals("1 分钟要夹到 15", clock.now + 15 * 60_000L, activity.long("until"))
        assertEquals("BUSY", activity.str("availability"))
        assertEquals(
            "响应里的人格也要带同一个活动",
            activity.long("until"),
            json.obj("persona").obj("activity").long("until")
        )
        assertEquals(activity.long("until"), ActivityState.decode(store.activity(personaId))?.until)

        val maxed = bodyOf(
            post(activityPath(), """{"kind":"STUDY","activity":"复习","minutes":9999,"availability":"LIMITED"}""")
        )
        assertEquals("9999 分钟要夹到 480", clock.now + 480 * 60_000L, maxed.obj("activity").long("until"))
        assertEquals("STUDY", maxed.obj("activity").str("kind"))
        assertEquals("LIMITED", maxed.obj("activity").str("availability"))
    }

    @Test
    fun `activity route defaults kind availability and duration`() {
        val json = bodyOf(post(activityPath(), """{"activity":"在开会"}"""))
        val activity = json.obj("activity")
        assertEquals("认不出的/缺省的 kind 归一成 OTHER", "OTHER", activity.str("kind"))
        assertEquals("缺省的 availability 按 BUSY（不谎称有空）", "BUSY", activity.str("availability"))
        assertEquals("既没给 minutes 也没给 until：默认 60 分钟", clock.now + 60 * 60_000L, activity.long("until"))

        val unknownEnums = bodyOf(
            post(activityPath(), """{"kind":"开会","activity":"在开会","availability":"开会中","minutes":30}""")
        )
        assertEquals("OTHER", unknownEnums.obj("activity").str("kind"))
        assertEquals("BUSY", unknownEnums.obj("activity").str("availability"))
    }

    @Test
    fun `activity route accepts an explicit future until in millis or iso`() {
        val until = clock.now + 2 * 60 * 60_000L
        val millis = bodyOf(post(activityPath(), """{"activity":"出差","until":$until}"""))
        assertEquals(until, millis.obj("activity").long("until"))

        val isoText = Instant.ofEpochMilli(until + 60_000L).toString()
        val iso = bodyOf(post(activityPath(), """{"activity":"出差","until":"$isoText"}"""))
        assertEquals(until + 60_000L, iso.obj("activity").long("until"))
    }

    @Test
    fun `activity route rejects illegal bodies with 400`() {
        assert400(post(activityPath(), ""), "empty_body")
        assert400(post(activityPath(), "{oops"), "invalid_json")
        assert400(post(activityPath(), "[]"), "invalid_body")
        assert400(post(activityPath(), "{}"))
        assert400(post(activityPath(), """{"activity":"   "}"""))
        assert400(post(activityPath(), """{"activity":42}"""))
        assert400(post(activityPath(), """{"activity":"开会","minutes":"abc"}"""))
        assert400(post(activityPath(), """{"activity":"开会","minutes":1.5}"""))
        assert400(post(activityPath(), """{"activity":"开会","kind":7}"""))
        assert400(post(activityPath(), """{"activity":"开会","availability":true}"""))
        assert400(post(activityPath(), """{"activity":"开会","until":"明天"}"""))
        assert400(post(activityPath(), """{"activity":"开会","until":${clock.now - 1}}"""))
        assertNull("所有非法请求都不该落库", store.activity(personaId))
    }

    @Test
    fun `activity route answers 404 and 405 like the rest of the api`() {
        val missing = post(activityPath("does-not-exist"), """{"activity":"开会"}""")
        assertEquals(404, missing.status)
        assertEquals("not_found", errorCode(missing))

        val traversal = post("/v1/personas/../activity", """{"activity":"开会"}""")
        assertEquals(404, traversal.status)

        val wrongMethod = get(activityPath())
        assertEquals(405, wrongMethod.status)
        assertEquals("method_not_allowed", errorCode(wrongMethod))

        val unknownSub = post("/v1/personas/$personaId/nope", """{"activity":"开会"}""")
        assertEquals(404, unknownSub.status)
    }

    // ------------------------------------------------------------------ GET /v1/state 带 activity

    @Test
    fun `state carries null activity for personas without one`() {
        val personas = bodyOf(get("/v1/state")).objects("personas")
        assertEquals(3, personas.size)
        for (persona in personas) {
            assertTrue(
                "每个人格都要有 activity 这一格（可空）：${persona.fields}",
                persona.fields.containsKey("activity")
            )
            assertTrue("没设过就是 null：${persona.fields["activity"]}", persona.fields["activity"] is JsonValue.Null)
        }
    }

    @Test
    fun `state carries the activity object after reporting one`() {
        val until = clock.now + 45 * 60_000L
        bodyOf(post(activityPath(), """{"kind":"WORK","activity":"在事务所赶图","until":$until,"availability":"LIMITED"}"""))

        val personas = bodyOf(get("/v1/state")).objects("personas")
        val target = personas.first { it.str("id") == personaId }.obj("activity")
        assertEquals("WORK", target.str("kind"))
        assertEquals("在事务所赶图", target.str("activity"))
        assertEquals(until, target.long("until"))
        assertEquals("LIMITED", target.str("availability"))

        assertTrue(
            "别人格仍然没有活动",
            personas.filter { it.str("id") != personaId }
                .all { it.fields["activity"] is JsonValue.Null }
        )
        // 原样文本也还在（客户端可以直接拿 activityJson 再上报）。
        assertEquals(store.activity(personaId), personas.first { it.str("id") == personaId }.str("activityJson"))
    }

    // ------------------------------------------------------------------ 主动门禁（BUSY / LIMITED / 过期）

    @Test
    fun `busy activity stops the proactive tick and explains it in skipped`() {
        val now = seedOldInteraction()
        writeActivity(until = now + 30 * 60_000L, availability = ActivityAvailability.BUSY)

        // roll = 0.0 一定掷中：没开口只能是「他在忙」挡住的，不是运气。
        val json = tick(now, services(roll = { 0.0 }))

        assertEquals(0, json.int("fired"))
        assertTrue("没有落库就不该有 decisions", json.objects("decisions").isEmpty())
        val skipped = json.objects("skipped").first { it.str("personaId") == personaId }
        assertEquals("busy", skipped.str("reason"))
        assertEquals("下一次值得再看一眼的时刻是活动结束", now + 30 * 60_000L, skipped.long("scheduledAt"))
        assertTrue("正在忙时不能落一条主动消息", proactiveMessages().isEmpty())
        assertEquals(
            "也不能把主动计数记上去",
            0,
            store.proactiveState(personaId, localDate(now)).proactiveCountToday
        )
    }

    @Test
    fun `limited activity lowers the probability and says limited when the roll misses`() {
        val now = seedOldInteraction()
        writeActivity(until = now + 60 * 60_000L, availability = ActivityAvailability.LIMITED)

        // 0.99 在正常概率（~0.85）下会掷中，但 LIMITED 之后是 ~0.2975，所以不中。
        val missed = tick(now, services(roll = { 0.99 }))
        assertEquals(0, missed.int("fired"))
        val skipped = missed.objects("skipped").first { it.str("personaId") == personaId }
        assertEquals("limited", skipped.str("reason"))
        val probability = (skipped.fields["probability"] as JsonValue.Num).asDouble
        assertTrue("LIMITED 的概率要真的降下来（正常 ~0.85，降权后 ~0.30）：$probability", probability in 0.01..0.5)
        assertTrue(proactiveMessages().isEmpty())

        // 同一个 now 换一个新 ticker + 幸运的随机数：LIMITED 不是「绝对不开口」。
        val lucky = tick(now, services(roll = { 0.0 }))
        assertEquals(1, lucky.int("fired"))
        assertEquals(1, proactiveMessages().size)
    }

    @Test
    fun `expired activity is free again so the tick fires normally`() {
        // 路由允许写一条「很快就结束」的活动：写入时是未来，判定时已经过期。
        store.appendMessage(sessionId, MessageRole.USER, "在吗")
        val expiry = bodyOf(
            post(activityPath(), """{"activity":"在开会","minutes":15,"availability":"BUSY"}""")
        ).obj("activity").long("until")
        val now = clock.now + 6 * 60 * 60_000L
        clock.now = now
        assertTrue("15 分钟后结束，晚于写入时刻、早于 tick", expiry < now)

        val json = tick(now, services(roll = { 0.0 }))
        assertEquals("过期活动必须当 FREE 处理", 1, json.int("fired"))
        assertTrue(
            "这次不该有 skipped 说他在忙",
            json.objects("skipped").none { it.str("reason") == "busy" }
        )
    }

    @Test
    fun `activity gate applies to every persona and other personas still fire`() {
        // 二号人格也开一个会话（主动消息要落到会话里），它没有活动。
        store.ensureSession(FileStore.SEED_PERSONA_2_ID, "和阿岚的对话", "session-alan")
        store.appendMessage("session-alan", MessageRole.USER, "在吗")
        val now = clock.now + 6 * 60 * 60_000L
        clock.now = now
        writeActivity(until = now + 30 * 60_000L, availability = ActivityAvailability.BUSY)

        val json = tick(now, services(roll = { 0.0 }))
        assertEquals("被 BUSY 挡住的只有林深，阿岚照常（响应：$json）", 1, json.int("fired"))
        assertEquals(FileStore.SEED_PERSONA_2_ID, json.objects("decisions").single().str("personaId"))
        assertTrue(json.objects("skipped").any { it.str("personaId") == personaId && it.str("reason") == "busy" })
        assertEquals(
            "阿岚的主动消息落在她自己的会话里",
            1,
            store.latestMessages("session-alan", 100).count { it.source == MessageSource.PROACTIVE }
        )
        assertTrue("林深在忙，他的会话里不该有主动消息", proactiveMessages().isEmpty())
    }

    @Test
    fun `broken activity json does not mute the persona`() {
        val now = seedOldInteraction()
        store.updateActivity(personaId, "{这不是 JSON")

        val json = tick(now, services(roll = { 0.0 }))
        assertEquals("坏 JSON 当没有活动（不能把主动消息永久掐死）", 1, json.int("fired"))
    }

    @Test
    fun `state still reports the raw activity json when it is broken`() {
        store.updateActivity(personaId, "{这不是 JSON")
        val target = bodyOf(get("/v1/state")).objects("personas").first { it.str("id") == personaId }
        assertEquals("解析失败时 activity 是 null，但原文还在", "{这不是 JSON", target.str("activityJson"))
        assertTrue(target.fields["activity"] is JsonValue.Null)
    }

    @Test
    fun `real server dispatches the activity route behind the token`() {
        val server = startServer(token = "secret-token")
        val until = clock.now + 30 * 60_000L

        val unauthorized = httpPost(server.port, activityPath(), """{"activity":"在开会","until":$until}""")
        assertEquals("新路由同样要过 token", 401, unauthorized.statusCode())

        val created = httpPost(
            server.port,
            activityPath(),
            """{"activity":"在开会","until":$until}""",
            token = "secret-token"
        )
        assertEquals(200, created.statusCode())
        assertEquals(
            until,
            TestSupport.parse(created.body()).obj("activity").long("until")
        )

        val state = TestSupport.parse(httpGet(server.port, "/v1/state", token = "secret-token").body())
        val target = state.objects("personas").first { it.str("id") == personaId }
        assertEquals("在开会", target.obj("activity").str("activity"))
    }

    @Test
    fun `idle tick without activity is unaffected`() {
        val now = seedOldInteraction()
        val json = tick(now, services(roll = { 0.0 }))
        assertEquals(1, json.int("fired"))
        assertFalse("没有活动时 skipped 里不该出现 busy/limited", json.objects("skipped").any {
            it.str("reason") == "busy" || it.str("reason") == "limited"
        })
    }
}
