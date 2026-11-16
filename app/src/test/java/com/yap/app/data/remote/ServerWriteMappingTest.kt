package com.yap.app.data.remote

import com.yap.app.data.local.MemoryCategory
import com.yap.app.data.local.MemoryEntity
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.PersonaPresetEntity
import com.yap.app.data.prefs.AppSettings
import com.yap.app.data.prefs.BubbleStyle
import com.yap.app.data.prefs.ThemeMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端写路径的字段映射（缓存更新的纯逻辑）。
 *
 * 仓储层「服务端成功后写 Room」用的就是这些函数；把它们钉住，等于同时钉住了请求体
 * 与缓存行为，不需要在 JVM 里造一整套 Room。
 */
class ServerWriteMappingTest {

    private fun persona(
        id: String = "p1",
        avatarUri: String? = "file:///data/avatars/avatar-1.jpg"
    ) = PersonaEntity(
        id = id,
        name = "林深",
        description = "话不多",
        avatarUri = avatarUri,
        avatarSeed = 7,
        prompt = "你叫林深",
        category = "日常聊天",
        version = 2,
        defaultApiConfigId = "cfg-1",
        modelOverride = "deepseek-chat",
        isDefault = false,
        createdAt = 100,
        updatedAt = 200,
        traits = "[\"慢热\"]",
        likes = "[\"夜里散步\"]",
        worldJson = "{\"name\":\"小镇\"}",
        speechStyleJson = "{\"tone\":\"淡\"}",
        mood = "平静",
        moodIntensity = 2
    )

    private fun memory(id: String = "m1") = MemoryEntity(
        id = id,
        personaId = "p1",
        content = "对方喜欢夜里散步",
        category = MemoryCategory.PREFERENCE,
        importance = 4,
        pinned = true,
        sourceSessionId = "s1",
        createdAt = 10,
        updatedAt = 20,
        lastUsedAt = 0,
        useCount = 2,
        drawer = "USER",
        tags = "[\"散步\",\"夜\"]",
        happenedAt = 5
    )

    private fun preset(id: String = "pre1") = PersonaPresetEntity(
        id = id,
        name = "模板",
        description = "描述",
        prompt = "提示词",
        category = "日常聊天",
        avatarUri = "file:///data/avatars/avatar-2.jpg",
        avatarSeed = 3,
        createdAt = 1,
        updatedAt = 2,
        worldJson = "{\"name\":\"城\"}",
        speechStyleJson = null
    )

    // ------------------------------------------------------------------ 人格

    @Test
    fun `人格新建不带 id 更新带 id 头像没变则省略 avatarUrl`() {
        val created = ServerPersonaWrite.payload(persona(), avatarUrl = null, creating = true)
        assertFalse("新建由服务端分配 id", created.containsKey("id"))
        assertTrue(created["avatarUrl"] is JsonNull)

        val updated = ServerPersonaWrite.payload(
            persona(),
            avatarUrl = "/v1/media/a1",
            creating = false,
            includeAvatar = true
        )
        assertEquals("p1", updated.getValue("id").jsonPrimitive.content)
        assertEquals("/v1/media/a1", updated.getValue("avatarUrl").jsonPrimitive.content)

        val untouched = ServerPersonaWrite.payload(
            persona(),
            avatarUrl = null,
            creating = false,
            includeAvatar = false
        )
        assertFalse("头像没变时不能发 avatarUrl，否则会清掉服务端头像", untouched.containsKey("avatarUrl"))
        // 本地独有字段要带过去（服务端 extraFields 原样保留），不能在保存时丢。
        assertEquals("你叫林深", untouched.getValue("prompt").jsonPrimitive.content)
        assertEquals("{\"name\":\"小镇\"}", untouched.getValue("worldJson").jsonPrimitive.content)
        assertEquals(7, untouched.getValue("avatarSeed").jsonPrimitive.int)
    }

    @Test
    fun `人格响应映射回缓存 保留服务端不理解的本地字段`() {
        val server = JsonObject(
            mapOf(
                "id" to JsonPrimitive("server-p1"),
                "name" to JsonPrimitive("服务端名字"),
                "description" to JsonPrimitive("服务端描述"),
                "avatarUrl" to JsonPrimitive("/v1/media/av1"),
                "isDefault" to JsonPrimitive(true),
                "createdAt" to JsonPrimitive(1000L),
                "updatedAt" to JsonPrimitive(2000L)
            )
        )

        val local = ServerPersonaWrite.localEntity(server, persona(id = "local-p1", avatarUri = null))

        assertEquals("server-p1", local.id)
        assertEquals("服务端名字", local.name)
        assertEquals("服务端描述", local.description)
        assertEquals("/v1/media/av1", local.avatarUri)
        assertTrue(local.isDefault)
        assertEquals(1000L, local.createdAt)
        assertEquals(2000L, local.updatedAt)
        // 本地字段不被响应覆盖。
        assertEquals("你叫林深", local.prompt)
        assertEquals("平静", local.mood)
        assertEquals("{\"name\":\"小镇\"}", local.worldJson)
    }

    @Test
    fun `本机已有头像时响应里的服务端 avatarUrl 不顶替本地文件`() {
        val server = JsonObject(mapOf("id" to JsonPrimitive("p1"), "avatarUrl" to JsonPrimitive("/v1/media/av1")))

        val local = ServerPersonaWrite.localEntity(server, persona())

        assertEquals("file:///data/avatars/avatar-1.jpg", local.avatarUri)
    }

    // ------------------------------------------------------------------ 记忆

    @Test
    fun `记忆重要度在 1-5 与 0-100 之间换算`() {
        assertEquals(20, ServerMemoryWrite.importanceToServer(1))
        assertEquals(60, ServerMemoryWrite.importanceToServer(3))
        assertEquals(100, ServerMemoryWrite.importanceToServer(5))
        assertEquals(1, ServerMemoryWrite.importanceFromServer(0))
        assertEquals(3, ServerMemoryWrite.importanceFromServer(50))
        assertEquals(5, ServerMemoryWrite.importanceFromServer(100))
    }

    @Test
    fun `记忆请求体带 id 正文 重要度与标签`() {
        val payload = ServerMemoryWrite.payload(memory(), creating = false)

        assertEquals("m1", payload.getValue("id").jsonPrimitive.content)
        assertEquals("对方喜欢夜里散步", payload.getValue("content").jsonPrimitive.content)
        assertEquals(80, payload.getValue("importance").jsonPrimitive.int)
        assertEquals(
            listOf("散步", "夜"),
            payload.getValue("tags").jsonArray.map { it.jsonPrimitive.content }
        )
        assertFalse(ServerMemoryWrite.payload(memory(), creating = true).containsKey("id"))
    }

    @Test
    fun `记忆响应映射回缓存 保留抽屉分类与 pinned`() {
        val server = JsonObject(
            mapOf(
                "id" to JsonPrimitive("server-m1"),
                "content" to JsonPrimitive("服务端正文"),
                "importance" to JsonPrimitive(100),
                "createdAt" to JsonPrimitive(11L),
                "updatedAt" to JsonPrimitive(22L)
            )
        )

        val local = ServerMemoryWrite.localEntity(server, memory())

        assertEquals("server-m1", local.id)
        assertEquals("服务端正文", local.content)
        assertEquals(5, local.importance)
        assertEquals(11L, local.createdAt)
        assertEquals(22L, local.updatedAt)
        assertEquals(MemoryCategory.PREFERENCE, local.category)
        assertTrue(local.pinned)
        assertEquals("USER", local.drawer)
        assertEquals(2, local.useCount)
    }

    // ------------------------------------------------------------------ 预设

    @Test
    fun `预设请求体与响应映射`() {
        val payload = ServerPresetWrite.payload(preset(), avatarRef = "/v1/media/av1", creating = true)
        assertFalse(payload.containsKey("id"))
        assertEquals("模板", payload.getValue("name").jsonPrimitive.content)
        assertEquals("/v1/media/av1", payload.getValue("avatarUri").jsonPrimitive.content)

        val noAvatar = ServerPresetWrite.payload(
            preset(),
            avatarRef = null,
            creating = false,
            includeAvatar = false
        )
        assertFalse(noAvatar.containsKey("avatarUri"))

        val server = JsonObject(
            mapOf(
                "id" to JsonPrimitive("server-pre"),
                "name" to JsonPrimitive("服务端模板"),
                "prompt" to JsonPrimitive("服务端提示词"),
                "avatarUri" to JsonPrimitive("/v1/media/av2"),
                "createdAt" to JsonPrimitive(3L),
                "updatedAt" to JsonPrimitive(4L)
            )
        )
        val local = ServerPresetWrite.localEntity(server, preset())
        assertEquals("server-pre", local.id)
        assertEquals("服务端模板", local.name)
        assertEquals("服务端提示词", local.prompt)
        assertEquals("file:///data/avatars/avatar-2.jpg", local.avatarUri)
        assertEquals(3L, local.createdAt)
    }

    // ------------------------------------------------------------------ 设置

    @Test
    fun `设置只推白名单字段 且不含任何密钥键`() {
        val settings = AppSettings(
            themeMode = ThemeMode.DARK,
            accentColor = 0xFF112233,
            fontScale = 1.1f,
            bubbleStyle = BubbleStyle.COMPACT,
            animationsEnabled = false,
            chatBackgroundColor = 0xFF000000,
            activePersonaId = "p1",
            hapticsEnabled = false,
            agentLoopEnabled = false,
            autoTranslatePersona = false
        )

        val payload = ServerSettingsWrite.payload(settings)

        assertEquals(ServerSettingsWrite.WHITELIST, payload.keys)
        assertEquals("DARK", payload.getValue("themeMode").jsonPrimitive.content)
        assertEquals("COMPACT", payload.getValue("bubbleStyle").jsonPrimitive.content)
        assertEquals("p1", payload.getValue("activePersonaId").jsonPrimitive.content)
        assertFalse(payload.getValue("animationsEnabled").jsonPrimitive.boolean)
        assertFalse(payload.getValue("autoTranslatePersona").jsonPrimitive.boolean)
        assertFalse(payload.getValue("agentLoopEnabled").jsonPrimitive.boolean)
        // 密钥永远不在这条链路上：不仅是「服务端会忽略」，而是客户端根本不发。
        val encoded = payload.toString()
        listOf("apiKey", "apiKeys", "token", "secret", "cipher").forEach { key ->
            assertFalse("设置 payload 里出现了 $key", encoded.contains(key, ignoreCase = true))
        }
    }

    @Test
    fun `设置里的可空项用 JSON null 表示清空`() {
        val payload = ServerSettingsWrite.payload(AppSettings(activePersonaId = null, chatBackgroundColor = null))

        assertTrue(payload.getValue("activePersonaId") is JsonNull)
        assertTrue(payload.getValue("chatBackgroundColor") is JsonNull)
    }

    @Test
    fun `白名单与服务端 SETTINGS_WHITELIST 逐字一致`() {
        assertEquals(
            setOf(
                "themeMode",
                "accentColor",
                "fontScale",
                "bubbleStyle",
                "animationsEnabled",
                "hapticsEnabled",
                "chatBackgroundColor",
                "agentLoopEnabled",
                "activePersonaId",
                "autoTranslatePersona"
            ),
            ServerSettingsWrite.WHITELIST
        )
    }
}
