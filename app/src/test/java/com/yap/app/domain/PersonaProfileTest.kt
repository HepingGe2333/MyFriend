package com.yap.app.domain

import com.yap.app.data.local.PersonaEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人格细分字段 → 提示词（规格 §三 / §九）。
 *
 * 验收要求：「新建人格填满 5 个结构化字段后，system 提示词里出现对应小节；老数据不填也能用」。
 * 这里直接测渲染，不依赖数据库。
 */
class PersonaProfileTest {

    private fun persona(overrides: PersonaEntity.() -> PersonaEntity = { this }) = PersonaEntity(
        id = "p1",
        name = "林深",
        description = "",
        avatarUri = null,
        avatarSeed = 1,
        prompt = "你是林深。",
        category = "朋友",
        version = 1,
        defaultApiConfigId = null,
        modelOverride = null,
        isDefault = true,
        createdAt = 0,
        updatedAt = 0
    ).overrides()

    @Test
    fun `填满五个字段后 提示词里出现对应小节`() {
        val block = PersonaProfile.promptBlock(
            persona {
                copy(
                    traits = PersonaProfile.encodeList(listOf("慢热", "嘴硬心软")),
                    likes = PersonaProfile.encodeList(listOf("夜里散步")),
                    behaviors = PersonaProfile.encodeList(listOf("先听完再给建议")),
                    backstory = "在南方长大。",
                    quirks = "不说「加油」。"
                )
            }
        )
        assertTrue(block.startsWith("## 你是谁"))
        listOf("### 性格", "### 喜欢的事", "### 习惯", "### 过去", "### 设定").forEach {
            assertTrue("缺少小节 $it：\n$block", block.contains(it))
        }
        assertTrue(block.contains("慢热"))
        assertTrue(block.contains("夜里散步"))
        assertTrue(block.contains("在南方长大。"))
    }

    @Test
    fun `没填的字段不占位`() {
        val block = PersonaProfile.promptBlock(persona())
        assertTrue("什么都没有时整段不出现", block.isBlank())
        val partial = PersonaProfile.promptBlock(
            persona { copy(traits = PersonaProfile.encodeList(listOf("慢热"))) }
        )
        assertTrue(partial.contains("### 性格"))
        assertFalse("没填喜欢的字段不该出现占位", partial.contains("### 喜欢的事"))
        assertFalse(partial.contains("### 过去"))
    }

    @Test
    fun `行为参数不进提示词正文`() {
        val block = PersonaProfile.promptBlock(
            persona {
                copy(
                    traits = PersonaProfile.encodeList(listOf("慢热")),
                    proactiveLevel = 5,
                    memoryRetention = 1
                )
            }
        )
        assertFalse("主动程度是行为参数，不该出现在正文", block.contains("主动程度"))
        assertFalse("记性同理", block.contains("记性"))
        assertFalse(block.contains("5"))
    }

    @Test
    fun `坏数据不崩 且列表会去重限长`() {
        assertTrue(PersonaProfile.decodeList("not json").isEmpty())
        assertTrue(PersonaProfile.decodeList("""{"a":1}""").isEmpty())
        val many = (1..20).map { "标签$it" }
        val encoded = PersonaProfile.encodeList(PersonaProfile.sanitizeList(many, PersonaProfile.MAX_TRAITS))
        assertTrue(PersonaProfile.decodeList(encoded).size <= PersonaProfile.MAX_TRAITS)
        assertTrue(PersonaProfile.sanitizeList(PersonaProfile.decodeList("""["a","a",""," b "]"""), 8).size == 2)
    }
}
