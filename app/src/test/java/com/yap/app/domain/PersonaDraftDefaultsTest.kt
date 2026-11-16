package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * `PersonaDraft` 是「新建一个人」的入口草稿（从预设复制、JSON 导入都走它）。
 *
 * 0.12 续 7 把它的默认值改成「不睡 / 唤醒阈值 3」：编辑页表单会显式传值，
 * 但**不经过编辑页**的新建路径（从预设复制）会用这里的默认值 ——
 * 让它继承 `true` 等于用户复制一个预设就得到一个夜里只标已读的人。
 *
 * 已有数据不受影响：编辑页、换头像、回滚版本这几条保存路径都显式带上库里的值。
 */
class PersonaDraftDefaultsTest {

    private fun draft() = PersonaDraft(
        name = "林深",
        description = "",
        avatarUri = null,
        avatarSeed = 1,
        prompt = "",
        category = "日常聊天",
        defaultApiConfigId = null,
        modelOverride = null
    )

    @Test
    fun `新建草稿默认不睡`() {
        assertFalse(draft().sleepEnabled)
    }

    @Test
    fun `新建草稿的唤醒阈值是 3`() {
        assertEquals(3, draft().wakeThreshold)
    }
}
