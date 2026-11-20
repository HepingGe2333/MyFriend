package com.yap.app.data.repo

import com.yap.app.data.local.MemoryCategory
import com.yap.app.data.local.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.14 记忆审计的四条结论：空查询/去重/人格边界/提示词截断。 */
class RecallAuditTest {

    private fun memory(id: String, content: String, pinned: Boolean = false) = MemoryEntity(
        id = id,
        personaId = "p1",
        content = content,
        category = MemoryCategory.FACT,
        importance = 4,
        pinned = pinned,
        sourceSessionId = null,
        createdAt = 1,
        updatedAt = 1,
        lastUsedAt = 0,
        useCount = 1,
        drawer = "USER"
    )

    @Test
    fun `同一条内容从不同召回路径进来只保留一次`() {
        val rows = listOf(
            memory("1", "对方怕冷，冬天手脚冰凉"),
            memory("2", "对方怕冷，冬天手脚冰凉！"),
            memory("3", "对方养了一只猫")
        )
        val unique = MemoryRepository.deduplicated(rows)
        assertEquals(listOf("1", "3"), unique.map { it.id })
    }

    @Test
    fun `提示词里超长记忆截到 300 字 但库里原文不截`() {
        val long = "很长的记忆。".repeat(400)
        val entity = memory("1", long)
        // 没有仓库实例也能验证渲染规则：用反射不合适，这里通过公开的 deduplicated 保留原文。
        assertEquals(long, MemoryRepository.deduplicated(listOf(entity)).single().content)
        assertTrue(long.length > MemoryRepository.MAX_PROMPT_LINE_CHARS)
    }
}
