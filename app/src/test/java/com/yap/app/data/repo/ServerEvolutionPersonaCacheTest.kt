package com.yap.app.data.repo

import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.remote.ServerEvolutionAcceptance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端 `evolution/{patchId}/accept` 响应 → 本地缓存人格（0.13，任务 3）。
 *
 * 服务端的四字段可能是「JSON 数组文本」（客户端 Room 形状）或真正的 JSON 数组；
 * 这里钉住三种形状、`version` 的来源、以及「服务端不理解的本地字段不能被清掉」。
 */
class ServerEvolutionPersonaCacheTest {

    private fun persona(
        traits: String = """["慢热"]""",
        likes: String = """["夜里散步"]""",
        behaviors: String = "[]",
        quirks: String? = "不说「加油」",
        version: Int = 3
    ) = PersonaEntity(
        id = "local-1",
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

    private fun acceptance(json: String): ServerEvolutionAcceptance =
        Json.decodeFromString(ServerEvolutionAcceptance.serializer(), json)

    @Test
    fun `四字段与版本写回本地 其它字段原样保留`() {
        val local = persona()
        val locallyComputed = local.copy(version = 4, updatedAt = 5_000L)
        val response = acceptance(
            """{"personaId":"server-1","patchId":"patch-1","changed":true,"version":4,
               "persona":{"id":"server-1","version":4,"updatedAt":9999,
                 "traits":"[\"慢热\",\"嘴硬心软\"]","likes":"[\"夜里散步\",\"雨天\"]",
                 "behaviors":"[\"先听完再给建议\"]","quirks":"叫你「阿深」"}}"""
        )

        val applied = ServerEvolutionPersonaCache.apply(local, locallyComputed, response)

        assertEquals("""["慢热","嘴硬心软"]""", applied.traits)
        assertEquals("""["夜里散步","雨天"]""", applied.likes)
        assertEquals("""["先听完再给建议"]""", applied.behaviors)
        assertEquals("叫你「阿深」", applied.quirks)
        assertEquals(4, applied.version)
        assertEquals(9_999L, applied.updatedAt)
        // 本地字段：名字/描述/提示词/世界/话风/头像/配置绑定都不动。
        assertEquals("林深", applied.name)
        assertEquals("用户手写的身份与边界", applied.prompt)
        assertEquals("""{"name":"海边小城"}""", applied.worldJson)
        assertEquals("""{"tone":"温和"}""", applied.speechStyleJson)
        assertNull(applied.avatarUri)
        assertEquals("cfg-1", applied.defaultApiConfigId)
    }

    @Test
    fun `服务端返回真正的 JSON 数组也能落成 Room 文本`() {
        val local = persona()
        val response = acceptance(
            """{"changed":true,"version":5,
               "persona":{"traits":["a","b"],"likes":["c"],"behaviors":[],"quirks":null}}"""
        )

        val applied = ServerEvolutionPersonaCache.apply(local, local.copy(version = 4), response)

        assertEquals("""["a","b"]""", applied.traits)
        assertEquals("""["c"]""", applied.likes)
        assertEquals("[]", applied.behaviors)
        assertNull("服务端 quirk=null 表示清空设定", applied.quirks)
        assertEquals(5, applied.version)
    }

    @Test
    fun `响应缺字段时逐项回退到本地合并结果`() {
        val local = persona()
        val locallyComputed = local.copy(traits = """["慢热","嘴硬心软"]""", version = 4)
        val response = acceptance("""{"changed":true,"persona":{"id":"server-1"}}""")

        val applied = ServerEvolutionPersonaCache.apply(local, locallyComputed, response)

        assertEquals("""["慢热","嘴硬心软"]""", applied.traits)
        assertEquals(locallyComputed.likes, applied.likes)
        assertEquals("版本没有可用来源时用本地计算值", 4, applied.version)
        assertEquals(locallyComputed.updatedAt, applied.updatedAt)
    }

    @Test
    fun `changed=false 时本地一行都不改`() {
        val local = persona()
        val response = acceptance("""{"changed":false,"version":3,"persona":{"traits":["别的"]}}""")

        val applied = ServerEvolutionPersonaCache.apply(local, local.copy(version = 4), response)

        assertEquals(local, applied)
    }

    @Test
    fun `没有 persona 对象时用本地合并结果`() {
        val local = persona()
        val locallyComputed = local.copy(quirks = "新增设定", version = 4)
        val response = acceptance("""{"changed":true,"version":4}""")

        val applied = ServerEvolutionPersonaCache.apply(local, locallyComputed, response)

        assertEquals(locallyComputed, applied)
    }

    @Test
    fun `quirks 用数组形状时折成多行文本`() {
        val local = persona()
        val response = acceptance("""{"changed":true,"persona":{"quirks":["第一句","第二句"]}}""")

        val applied = ServerEvolutionPersonaCache.apply(local, local.copy(version = 4), response)

        assertEquals("第一句\n第二句", applied.quirks)
    }

    @Test
    fun `列表超过上限时丢掉多余项而不是截断已有值`() {
        val local = persona()
        val response = acceptance(
            """{"changed":true,"persona":{"traits":["1","2","3","4","5","6","7","8"]}}"""
        )

        val applied = ServerEvolutionPersonaCache.apply(local, local.copy(version = 4), response)
        val count = (Json.parseToJsonElement(applied.traits) as kotlinx.serialization.json.JsonArray).size

        assertEquals(6, count)
        assertTrue(applied.traits.startsWith("[\"1\""))
    }

    @Test
    fun `响应里的 version 优先于 persona 内的 version`() {
        val local = persona()
        val response = acceptance("""{"changed":true,"version":7,"persona":{"version":4}}""")
        val applied = ServerEvolutionPersonaCache.apply(local, local.copy(version = 4), response)
        assertEquals(7, applied.version)
    }
}
