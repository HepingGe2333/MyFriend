package com.yap.app.domain

import com.yap.app.data.local.PersonaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一次性迁移：把「从没配置过睡眠」的旧默认关掉（0.12 续 7）。
 *
 * 迁移的唯一目的是不让老用户升级后继续在夜里被静默，所以**边界比覆盖面重要**：
 * 用户自己填过作息、或者改过人格（version > 1）的人一律不动 —— 那是明确的选择。
 *
 * 只跑一次由 `plan(alreadyMigrated = true)` 返回 `null` 来保证（调用方据此连 `UPDATE` 都不发）；
 * 写库与标记在 `PersonaRepositoryInitializer` 里，有校验/失败兜底。
 */
class SleepDefaultsMigrationTest {

    private fun persona(
        id: String = "persona-1",
        sleepEnabled: Boolean = true,
        sleepAt: Int? = null,
        wakeAt: Int? = null,
        version: Int = 1
    ) = PersonaEntity(
        id = id,
        name = "人格-$id",
        description = "",
        avatarUri = null,
        avatarSeed = 1,
        prompt = "",
        category = "日常聊天",
        version = version,
        defaultApiConfigId = null,
        modelOverride = null,
        isDefault = false,
        createdAt = 0L,
        updatedAt = 0L,
        sleepEnabled = sleepEnabled,
        quietSleepMinutes = sleepAt,
        quietWakeMinutes = wakeAt
    )

    @Test
    fun `从没配置过睡眠的默认人格会被关掉`() {
        val candidate = persona()
        assertTrue(SleepDefaultsMigration.shouldDisableSleep(candidate))
        assertEquals(listOf("persona-1"), SleepDefaultsMigration.candidateIds(listOf(candidate)))
    }

    @Test
    fun `用户自己设了作息的不动`() {
        assertFalse(SleepDefaultsMigration.shouldDisableSleep(persona(sleepAt = 23 * 60, wakeAt = 8 * 60)))
        assertFalse("只填了睡觉时间也是用户的选择", SleepDefaultsMigration.shouldDisableSleep(persona(sleepAt = 23 * 60)))
        assertFalse("只填了起床时间也是用户的选择", SleepDefaultsMigration.shouldDisableSleep(persona(wakeAt = 8 * 60)))
        assertEquals(emptyList<String>(), SleepDefaultsMigration.candidateIds(listOf(persona(sleepAt = 23 * 60))))
    }

    @Test
    fun `改过人格版本的不动`() {
        assertFalse(SleepDefaultsMigration.shouldDisableSleep(persona(version = 2)))
        assertFalse(SleepDefaultsMigration.shouldDisableSleep(persona(version = 7)))
        assertEquals(emptyList<String>(), SleepDefaultsMigration.candidateIds(listOf(persona(version = 2))))
    }

    @Test
    fun `本来就关了睡眠的不动`() {
        assertFalse(SleepDefaultsMigration.shouldDisableSleep(persona(sleepEnabled = false)))
        assertEquals(
            emptyList<String>(),
            SleepDefaultsMigration.candidateIds(listOf(persona(sleepEnabled = false)))
        )
    }

    @Test
    fun `只挑符合条件的那几条`() {
        val personas = listOf(
            persona(id = "default-sleeping"),
            persona(id = "user-schedule", sleepAt = 22 * 60, wakeAt = 6 * 60),
            persona(id = "edited", version = 3),
            persona(id = "already-awake", sleepEnabled = false),
            persona(id = "another-default")
        )
        assertEquals(
            listOf("default-sleeping", "another-default"),
            SleepDefaultsMigration.candidateIds(personas)
        )
    }

    @Test
    fun `只跑一次`() {
        val personas = listOf(persona())
        assertEquals(listOf("persona-1"), SleepDefaultsMigration.plan(alreadyMigrated = false, personas = personas))
        assertNull("标记已经在，就不该再改任何人", SleepDefaultsMigration.plan(alreadyMigrated = true, personas = personas))
    }

    @Test
    fun `空库什么也不做但仍然算跑过`() {
        assertEquals(emptyList<String>(), SleepDefaultsMigration.plan(alreadyMigrated = false, personas = emptyList()))
    }
}
