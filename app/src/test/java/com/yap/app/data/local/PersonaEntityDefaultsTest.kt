package com.yap.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PersonaEntity` 的「代码默认值」与「列默认值」不是一回事（0.12 续 7）。
 *
 * - `sleepEnabled` 的数据类默认值**保持 `true`**：既有解码 / 迁移路径都按它走，
 *   改它会让老库、备份导入的语义变味。新装的人不睡这件事由「种子」与「新建表单」两个入口负责。
 * - `wakeThreshold` 的代码默认值降到 **3**：这些人格睡着时，夜里发 3 条就该被叫醒。
 *   列的 `@ColumnInfo(defaultValue = "6")` 保持不动 —— 那是已发布 schema 的一部分，
 *   改它要一次数据库迁移，而这里只是新建行用的值。
 */
class PersonaEntityDefaultsTest {

    private fun persona() = PersonaEntity(
        id = "persona-1",
        name = "林深",
        description = "",
        avatarUri = null,
        avatarSeed = 1,
        prompt = "",
        category = "日常聊天",
        version = 1,
        defaultApiConfigId = null,
        modelOverride = null,
        isDefault = true,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Test
    fun `数据类默认睡眠开关保持 true`() {
        assertTrue("数据类默认值不能动：老库/备份解码都按它走", persona().sleepEnabled)
    }

    @Test
    fun `默认唤醒阈值是 3`() {
        assertEquals(3, persona().wakeThreshold)
    }
}
