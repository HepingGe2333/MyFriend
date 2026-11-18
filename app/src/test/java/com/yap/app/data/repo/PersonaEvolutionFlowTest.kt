package com.yap.app.data.repo

import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.PersonaPendingPatchEntity
import com.yap.app.domain.PersonaEvolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人格进化的「什么时候评估 / 哪条待审该过期 / 接受后到底改了什么」
 * （`docs/design/persona-evolution.md` §2）。
 *
 * JSON 解析、上限、字段白名单、置信度、去重这些纯规则在 `PersonaEvolutionTest` 里；
 * 这里盯的是仓库层的三个决定：**节奏**（20 条 / 24 小时）、**7 天过期**、**接受时只碰四个字段且升版本**。
 * 三件事都抽成了纯函数，所以不需要数据库就能钉住——真正的落库由 `PersonaEvolutionRepository`
 * 按同样的判断执行。
 */
class PersonaEvolutionFlowTest {

    // ------------------------------------------------------------ 节奏

    @Test
    fun `攒够 20 条新用户消息就该评估`() {
        assertTrue(PersonaEvolutionRepository.shouldEvaluate(userMessages = 20, cursor = 0, lastRunAt = 0L, now = 0L))
        assertTrue(PersonaEvolutionRepository.shouldEvaluate(userMessages = 45, cursor = 25, lastRunAt = 0L, now = 0L))
    }

    @Test
    fun `不到 20 条且没满 24 小时不评估`() {
        val now = 1_000_000_000L
        assertFalse(
            PersonaEvolutionRepository.shouldEvaluate(
                userMessages = 19, cursor = 0, lastRunAt = now - 60 * 60_000L, now = now
            )
        )
        assertFalse(
            PersonaEvolutionRepository.shouldEvaluate(userMessages = 0, cursor = 0, lastRunAt = 0L, now = now)
        )
    }

    @Test
    fun `满 24 小时但有新消息就评估`() {
        val now = 1_791_000_000_000L
        val day = 24 * 60 * 60 * 1000L
        assertTrue(
            PersonaEvolutionRepository.shouldEvaluate(userMessages = 3, cursor = 1, lastRunAt = now - day, now = now)
        )
        assertTrue(
            "正好 24 小时也算",
            PersonaEvolutionRepository.shouldEvaluate(userMessages = 3, cursor = 1, lastRunAt = now - day, now = now)
        )
    }

    @Test
    fun `满 24 小时但一条新消息都没有就不评估`() {
        val now = 1_791_000_000_000L
        val day = 24 * 60 * 60 * 1000L
        assertFalse(
            "没有说话可回顾，评估只会浪费一次调用",
            PersonaEvolutionRepository.shouldEvaluate(userMessages = 5, cursor = 5, lastRunAt = now - day * 3, now = now)
        )
    }

    @Test
    fun `刚满 23 小时 59 分不算满 24 小时`() {
        val now = 1_791_000_000_000L
        assertFalse(
            PersonaEvolutionRepository.shouldEvaluate(
                userMessages = 2, cursor = 0, lastRunAt = now - (24 * 60 * 60_000L - 1), now = now
            )
        )
    }

    @Test
    fun `换人格后游标从零算 不会拿上一个人的进度`() {
        // 仓库层在 personaId 与游标里的人对不上时把 cursor/lastRunAt 都当 0，等价于这次调用。
        assertTrue(PersonaEvolutionRepository.shouldEvaluate(userMessages = 20, cursor = 0, lastRunAt = 0L, now = 1L))
    }

    @Test
    fun `节奏常量本身就是规范里的 20 与 24 小时`() {
        assertEquals(20, PersonaEvolutionRepository.USER_MESSAGES_PER_EVAL)
        assertEquals(24 * 60 * 60 * 1000L, PersonaEvolutionRepository.EVAL_INTERVAL_MS)
        assertEquals(7 * 24 * 60 * 60 * 1000L, PersonaEvolutionRepository.PENDING_TTL_MS)
    }

    // ------------------------------------------------------------ 7 天过期

    private fun patch(id: String, createdAt: Long, json: String = EVOLUTION_PATCH) =
        PersonaPendingPatchEntity(
            id = id,
            personaId = "p1",
            sessionId = null,
            patchJson = json,
            summary = "性格 +嘴硬心软",
            reason = "测试",
            createdAt = createdAt
        )

    @Test
    fun `超过 7 天的进化补丁会被丢掉`() {
        val now = 1_791_000_000_000L
        val ttl = PersonaEvolutionRepository.PENDING_TTL_MS
        val patches = listOf(
            patch("fresh", now - 60_000L),
            patch("edge", now - ttl),
            patch("stale", now - ttl - 1)
        )
        assertEquals(listOf("stale"), PersonaEvolutionRepository.expiredPatchIds(patches, now))
    }

    @Test
    fun `刚过 7 天的边界保留 多一秒才丢`() {
        val now = 1_791_000_000_000L
        val ttl = PersonaEvolutionRepository.PENDING_TTL_MS
        assertEquals(1, PersonaEvolutionRepository.expiredPatchIds(listOf(patch("old", now - ttl - 1)), now).size)
        assertEquals(0, PersonaEvolutionRepository.expiredPatchIds(listOf(patch("edge", now - ttl)), now).size)
    }

    @Test
    fun `过期清理不碰旧的人设卡补丁`() {
        val now = 1_791_000_000_000L
        val cardPatch = """{"bond":"更懂得先陪着"}"""
        val patches = listOf(
            patch("card", now - PersonaEvolutionRepository.PENDING_TTL_MS - 1, json = cardPatch),
            patch("evolution", now - PersonaEvolutionRepository.PENDING_TTL_MS - 1)
        )
        assertEquals(listOf("evolution"), PersonaEvolutionRepository.expiredPatchIds(patches, now))
        assertFalse(PersonaEvolution.isEvolutionPatch(cardPatch))
        assertTrue(PersonaEvolution.isEvolutionPatch(EVOLUTION_PATCH))
    }

    // ------------------------------------------------------------ 接受后的写回

    private fun persona(
        version: Int = 3,
        traits: String = """["慢热"]""",
        likes: String = """["夜里散步"]""",
        behaviors: String = "[]",
        quirks: String? = null
    ) = PersonaEntity(
        id = "p1",
        name = "林深",
        description = "建筑师",
        avatarUri = null,
        avatarSeed = 0,
        prompt = "用户手写的身份与边界",
        category = "日常聊天",
        version = version,
        defaultApiConfigId = null,
        modelOverride = null,
        isDefault = true,
        createdAt = 0L,
        updatedAt = 0L,
        traits = traits,
        likes = likes,
        behaviors = behaviors,
        quirks = quirks
    )

    private fun change(
        field: PersonaEvolution.Field,
        op: PersonaEvolution.Op,
        value: String
    ) = PersonaEvolution.Change(field = field, op = op, value = value, reason = "聊下来觉得是这样", confidence = 0.9)

    @Test
    fun `接受一条变化写回四个字段并升版本`() {
        val before = persona(version = 3)
        val after = PersonaEvolutionRepository.applyTo(
            before,
            listOf(change(PersonaEvolution.Field.TRAITS, PersonaEvolution.Op.ADD, "嘴硬心软")),
            now = 1_000L
        )
        assertTrue(after != null)
        assertEquals(4, after!!.version)
        assertTrue(after.traits.contains("嘴硬心软"))
        assertEquals(1_000L, after.updatedAt)
    }

    @Test
    fun `接受时绝不改名字 描述 提示词 世界 关系这些用户写的东西`() {
        val before = persona()
        val after = PersonaEvolutionRepository.applyTo(
            before,
            listOf(
                change(PersonaEvolution.Field.LIKES, PersonaEvolution.Op.ADD, "喝手冲"),
                change(PersonaEvolution.Field.BEHAVIORS, PersonaEvolution.Op.ADD, "先问对方吃了没")
            ),
            now = 2_000L
        )!!
        assertEquals(before.name, after.name)
        assertEquals(before.description, after.description)
        assertEquals(before.prompt, after.prompt)
        assertEquals(before.category, after.category)
        assertEquals(before.avatarUri, after.avatarUri)
        assertEquals(before.avatarSeed, after.avatarSeed)
        assertEquals(before.defaultApiConfigId, after.defaultApiConfigId)
        assertEquals(before.isDefault, after.isDefault)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(before.worldJson, after.worldJson)
        assertEquals(before.speechStyleJson, after.speechStyleJson)
        assertEquals(before.identityRelationForTest(), after.identityRelationForTest())
    }

    @Test
    fun `删一条已有值会真的从字段里去掉`() {
        val before = persona(traits = """["慢热","嘴硬心软"]""")
        val after = PersonaEvolutionRepository.applyTo(
            before,
            listOf(change(PersonaEvolution.Field.TRAITS, PersonaEvolution.Op.REMOVE, "嘴硬心软")),
            now = 3_000L
        )!!
        assertFalse(after.traits.contains("嘴硬心软"))
        assertTrue(after.traits.contains("慢热"))
    }

    @Test
    fun `与现状完全一样的变化不升版本`() {
        val before = persona(traits = """["慢热"]""")
        assertNull(
            "已经是这样了：不该产生一个空版本",
            PersonaEvolutionRepository.applyTo(
                before,
                listOf(change(PersonaEvolution.Field.TRAITS, PersonaEvolution.Op.ADD, "慢热")),
                now = 4_000L
            )
        )
    }

    @Test
    fun `空变化不升版本`() {
        assertNull(PersonaEvolutionRepository.applyTo(persona(), emptyList(), now = 5_000L))
    }

    @Test
    fun `quirks 是文本 接受后写回成一句话`() {
        val before = persona(quirks = null)
        val after = PersonaEvolutionRepository.applyTo(
            before,
            listOf(change(PersonaEvolution.Field.QUIRKS, PersonaEvolution.Op.ADD, "句尾偶尔用省略号")),
            now = 6_000L
        )!!
        assertTrue(after.quirks.orEmpty().contains("句尾偶尔用省略号"))
    }

    @Test
    fun `接受后 traits 不会超过人设上限`() {
        val many = (1..com.yap.app.domain.PersonaProfile.MAX_TRAITS).joinToString(",", "[", "]") { "\"t$it\"" }
        val before = persona(traits = many)
        assertNull(
            "已经满了：再加一条应该是「没有变化」，而不是把某人挤掉",
            PersonaEvolutionRepository.applyTo(
                before,
                listOf(change(PersonaEvolution.Field.TRAITS, PersonaEvolution.Op.ADD, "新的一条")),
                now = 7_000L
            )
        )
    }

    @Test
    fun `补丁 JSON 里带着理由 接受后仍然可读`() {
        val changes = listOf(change(PersonaEvolution.Field.LIKES, PersonaEvolution.Op.ADD, "喝手冲"))
        val json = PersonaEvolution.encodePatch(changes)
        val decoded = PersonaEvolution.decodePatch(json)
        assertEquals(1, decoded.size)
        assertEquals("喝手冲", decoded.first().value)
        assertEquals("聊下来觉得是这样", decoded.first().reason)
        assertNotEquals("", PersonaEvolution.summary(decoded))
    }

    private companion object {
        const val EVOLUTION_PATCH =
            """{"changes":[{"field":"traits","op":"add","value":"嘴硬心软","reason":"聊下来觉得是这样"}]}"""
    }
}

/** 人设卡里的「关系」不在这份测试的写入范围内：这里只用来断言接受后它没被动过。 */
private fun PersonaEntity.identityRelationForTest(): String =
    com.yap.app.domain.PersonaCardCodec.decode(personaCardJson).identity.relation
