package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生命周期的纯逻辑单测：衰减、阈值、pinned 保底、坏数据。
 * 全部走 domain 层，不需要 Android / Room。
 */
class MemoryLifecycleTest {

    private val now = 1_760_000_000_000L
    private val day = MemoryLifecycle.DAY_MILLIS

    private fun state(
        id: String = "m1",
        tier: MemoryTier = MemoryTier.ACTIVE,
        pinned: Boolean = false,
        importance: Int = 3,
        useCount: Int = 0,
        lastUsedAt: Long = 0L,
        createdAt: Long = now,
        strength: Double = 1.0,
        lastConsolidatedAt: Long = 0L,
        drawer: MemoryDrawer = MemoryDrawer.EVENT
    ) = MemoryLifecycleState(
        id = id,
        tier = tier,
        pinned = pinned,
        importance = importance,
        useCount = useCount,
        lastUsedAt = lastUsedAt,
        createdAt = createdAt,
        strength = strength,
        lastConsolidatedAt = lastConsolidatedAt,
        drawer = drawer
    )

    // ---------- 1. effectiveStrength ----------

    @Test
    fun `全新的重要记忆分量很高`() {
        val memory = state(importance = 5, createdAt = now, lastUsedAt = now, useCount = 3)
        val strength = MemoryLifecycle.effectiveStrength(memory, now)
        assertTrue("新记忆应该接近满值，实际 $strength", strength > 0.85)
        assertTrue(strength <= 1.0)
    }

    @Test
    fun `归档的记忆分量恒为 0`() {
        val memory = state(tier = MemoryTier.ARCHIVED, importance = 5, pinned = true)
        assertEquals(0.0, MemoryLifecycle.effectiveStrength(memory, now), 0.0001)
    }

    @Test
    fun `钉住的记忆即使放到最旧也不低于保底线`() {
        val memory = state(
            pinned = true,
            importance = 1,
            createdAt = now - 4000L * day,
            strength = 0.0
        )
        val strength = MemoryLifecycle.effectiveStrength(memory, now)
        assertTrue("pinned 应该被保底，实际 $strength", strength >= MemoryLifecycle.PINNED_FLOOR)
    }

    @Test
    fun `越界的重要度会被夹取`() {
        val tooHigh = MemoryLifecycle.effectiveStrength(state(importance = 9), now)
        val max = MemoryLifecycle.effectiveStrength(state(importance = 5), now)
        val tooLow = MemoryLifecycle.effectiveStrength(state(importance = -3), now)
        val min = MemoryLifecycle.effectiveStrength(state(importance = 1), now)
        assertEquals(max, tooHigh, 0.0001)
        assertEquals(min, tooLow, 0.0001)
    }

    @Test
    fun `坏的 strength 不会让分数跑到 0 到 1 之外`() {
        val nan = MemoryLifecycle.effectiveStrength(state(strength = Double.NaN), now)
        val negative = MemoryLifecycle.effectiveStrength(state(strength = -5.0), now)
        val huge = MemoryLifecycle.effectiveStrength(state(strength = 99.0), now)
        listOf(nan, negative, huge).forEach {
            assertTrue("坏数据也应该落在 0..1，实际 $it", it in 0.0..1.0)
        }
    }

    @Test
    fun `时间戳在未来也不会抛异常`() {
        val memory = state(createdAt = now + 10 * day, lastUsedAt = now + 10 * day)
        val strength = MemoryLifecycle.effectiveStrength(memory, now)
        assertTrue(strength in 0.0..1.0)
    }

    // ---------- 2. decayStep ----------

    @Test
    fun `没人用的记忆一天比一天少`() {
        val memory = state(importance = 3, createdAt = now - day, strength = 1.0)
        val next = MemoryLifecycle.decayStep(memory, now)
        assertTrue("未使用的一天应该掉，实际 $next", next < 1.0)
        assertTrue("一天不该掉太多，实际 $next", next > 0.9)
    }

    @Test
    fun `最近被用过的记忆会回血`() {
        val memory = state(
            strength = 0.5,
            lastUsedAt = now,
            useCount = 3,
            lastConsolidatedAt = now
        )
        val next = MemoryLifecycle.decayStep(memory, now)
        assertTrue("刚被用过应该回血，实际 $next", next > 0.5)
    }

    @Test
    fun `重要度高的记忆衰减更慢`() {
        val young = state(importance = 1, createdAt = now - 5 * day, strength = 0.6)
        val important = state(importance = 5, createdAt = now - 5 * day, strength = 0.6)
        val lowNext = MemoryLifecycle.decayStep(young, now)
        val highNext = MemoryLifecycle.decayStep(important, now)
        assertTrue("5 分应该比 1 分掉得慢：$highNext vs $lowNext", highNext > lowNext)
    }

    @Test
    fun `长期没整理也最多补算有限的天数`() {
        // 重要度 1 的衰减系数是 1.5，离线一整年也只按 MAX_DECAY_DAYS 天算。
        val memory = state(importance = 1, createdAt = now - 3650 * day, strength = 0.9)
        val next = MemoryLifecycle.decayStep(memory, now)
        val expected = (0.9 - MemoryLifecycle.DECAY_PER_DAY * 1.5 * MemoryLifecycle.MAX_DECAY_DAYS)
            .coerceAtLeast(0.0)
        assertEquals(expected, next, 0.0001)
    }

    @Test
    fun `归档的记忆不再衰减`() {
        val memory = state(tier = MemoryTier.ARCHIVED, strength = 0.7, createdAt = now - 30 * day)
        assertEquals(0.0, MemoryLifecycle.decayStep(memory, now), 0.0001)
    }

    @Test
    fun `钉住的记忆衰减后也不低于保底线`() {
        val memory = state(
            pinned = true,
            strength = 0.6,
            createdAt = now - 100 * day,
            importance = 1
        )
        val next = MemoryLifecycle.decayStep(memory, now)
        assertTrue(next >= MemoryLifecycle.PINNED_FLOOR)
    }

    // ---------- 3. nextStatus ----------

    @Test
    fun `活跃但分量很低会先沉下去`() {
        val memory = state(tier = MemoryTier.ACTIVE)
        val action = MemoryLifecycle.nextStatus(
            memory,
            MemoryLifecycle.DORMANT_THRESHOLD - 0.01,
            now
        )
        assertTrue(action is LifecycleAction.Demote)
        assertEquals(MemoryTier.DORMANT, (action as LifecycleAction.Demote).to)
        assertEquals("m1", action.id)
    }

    @Test
    fun `活跃且分量正常就留着`() {
        val memory = state(tier = MemoryTier.ACTIVE)
        val action = MemoryLifecycle.nextStatus(memory, 0.9, now)
        assertTrue(action is LifecycleAction.Keep)
        assertEquals("m1", action.id)
    }

    @Test
    fun `沉底的记忆低于归档线就归档`() {
        val memory = state(tier = MemoryTier.DORMANT, createdAt = now - 10 * day)
        val action = MemoryLifecycle.nextStatus(
            memory,
            MemoryLifecycle.ARCHIVE_THRESHOLD - 0.01,
            now
        )
        assertTrue(action is LifecycleAction.Demote)
        assertEquals(MemoryTier.ARCHIVED, (action as LifecycleAction.Demote).to)
    }

    @Test
    fun `沉底的记忆又被想起来会回升`() {
        val memory = state(tier = MemoryTier.DORMANT, lastUsedAt = now)
        val action = MemoryLifecycle.nextStatus(
            memory,
            MemoryLifecycle.DORMANT_THRESHOLD + MemoryLifecycle.RECOVER_MARGIN,
            now
        )
        assertTrue(action is LifecycleAction.Promote)
        assertEquals("m1", action.id)
    }

    @Test
    fun `很旧又很低才会被彻底忘掉`() {
        val memory = state(
            tier = MemoryTier.DORMANT,
            createdAt = now - (MemoryLifecycle.FORGET_AGE_DAYS.toLong() + 1) * day
        )
        val action = MemoryLifecycle.nextStatus(memory, MemoryLifecycle.FORGET_THRESHOLD, now)
        assertTrue("很旧 + 很低应该忘掉", action is LifecycleAction.Forget)
        assertTrue((action as LifecycleAction.Forget).reason.isNotBlank())
    }

    @Test
    fun `很低但还很新不会忘掉`() {
        val memory = state(tier = MemoryTier.DORMANT, createdAt = now - 3 * day)
        val action = MemoryLifecycle.nextStatus(memory, MemoryLifecycle.FORGET_THRESHOLD, now)
        assertFalse(action is LifecycleAction.Forget)
    }

    @Test
    fun `钉住的记忆永远不会被沉底或忘掉`() {
        val memory = state(
            tier = MemoryTier.DORMANT,
            pinned = true,
            createdAt = now - 5000 * day
        )
        val action = MemoryLifecycle.nextStatus(memory, 0.0, now)
        assertTrue(action is LifecycleAction.Keep)
    }

    @Test
    fun `归档不久的记忆保持不动`() {
        val memory = state(tier = MemoryTier.ARCHIVED, createdAt = now - 40 * day)
        assertTrue(MemoryLifecycle.nextStatus(memory, 0.0, now) is LifecycleAction.Keep)
    }

    @Test
    fun `归档很久又很低的记忆会被忘掉`() {
        val memory = state(
            tier = MemoryTier.ARCHIVED,
            createdAt = now - (MemoryLifecycle.FORGET_AGE_DAYS.toLong() + 30) * day
        )
        assertTrue(
            MemoryLifecycle.nextStatus(memory, 0.0, now) is LifecycleAction.Forget
        )
    }

    // ---------- 4. dailyUpdate / 阈值辅助 ----------

    @Test
    fun `日常维护会一次性给出处置与分量`() {
        val memory = state(
            tier = MemoryTier.ACTIVE,
            strength = 0.46,
            createdAt = now - 20 * day,
            lastConsolidatedAt = now - 20 * day
        )
        val update = MemoryLifecycle.dailyUpdate(memory, now)
        // 20 天没动过，补算 14 天后分量被夹到 0。
        assertEquals(0.0, update.strength, 0.0001)
        assertTrue("掉到阈值以下应该沉底", update.action is LifecycleAction.Demote)
    }

    @Test
    fun `留着时写回的分量不会低于当前值`() {
        val memory = state(strength = 0.5, importance = 4)
        val strength = MemoryLifecycle.keepStrength(memory, now)
        assertTrue("keep 应该加固，实际 $strength", strength > 0.5)
        assertTrue(strength <= 1.0)
    }

    @Test
    fun `召回的回血比留着更轻`() {
        val memory = state(strength = 0.4, importance = 3)
        val keep = MemoryLifecycle.keepStrength(memory, now)
        val recall = MemoryLifecycle.recallStrength(memory, now)
        assertTrue("召回不该比 keep 更重：$recall vs $keep", recall < keep)
        assertTrue(recall > 0.4)
    }

    @Test
    fun `遗忘判定卡在阈值和年龄上`() {
        val old = state(createdAt = now - 300 * day)
        val young = state(createdAt = now - day)
        assertTrue(MemoryLifecycle.shouldForget(old, MemoryLifecycle.FORGET_THRESHOLD, now))
        assertFalse(
            MemoryLifecycle.shouldForget(old, MemoryLifecycle.FORGET_THRESHOLD + 0.05, now)
        )
        assertFalse(MemoryLifecycle.shouldForget(young, 0.0, now))
    }

    @Test
    fun `没有时间戳的记忆按最旧处理但不抛异常`() {
        val unknown = state(createdAt = 0L, lastUsedAt = 0L)
        assertEquals(Double.MAX_VALUE, MemoryLifecycle.ageInDays(unknown, now), 0.0)
        assertTrue(MemoryLifecycle.effectiveStrength(unknown, now) in 0.0..1.0)
    }

    // ---------- 7. 三个抽屉的衰减差异 ----------

    @Test
    fun `抽屉衰减系数是 关于对方最慢、共同经历最快`() {
        assertTrue(
            "USER 应该最慢",
            MemoryLifecycle.DRAWER_DECAY_USER < MemoryLifecycle.DRAWER_DECAY_SELF
        )
        assertTrue(
            "SELF 应该居中",
            MemoryLifecycle.DRAWER_DECAY_SELF < MemoryLifecycle.DRAWER_DECAY_EVENT
        )
        assertEquals("共同经历保持基准速度", 1.0, MemoryLifecycle.DRAWER_DECAY_EVENT, 0.0001)
    }

    @Test
    fun `关于对方的事实比共同经历衰减得慢`() {
        val user = state(drawer = MemoryDrawer.USER, importance = 3, createdAt = now - day)
        val event = state(drawer = MemoryDrawer.EVENT, importance = 3, createdAt = now - day)

        val userNext = MemoryLifecycle.decayStep(user, now)
        val eventNext = MemoryLifecycle.decayStep(event, now)

        assertTrue("USER 应该掉得少：$userNext vs $eventNext", userNext > eventNext)
        assertTrue("USER 一天只掉一点点，实际 $userNext", userNext > 0.97)
    }

    @Test
    fun `他自己居中`() {
        val user = MemoryLifecycle.decayStep(
            state(drawer = MemoryDrawer.USER, createdAt = now - 10 * day, strength = 0.8),
            now
        )
        val self = MemoryLifecycle.decayStep(
            state(drawer = MemoryDrawer.SELF, createdAt = now - 10 * day, strength = 0.8),
            now
        )
        val event = MemoryLifecycle.decayStep(
            state(drawer = MemoryDrawer.EVENT, createdAt = now - 10 * day, strength = 0.8),
            now
        )

        assertTrue("USER 最慢：$user", user > self)
        assertTrue("SELF 居中：$self vs $event", self > event)
    }

    @Test
    fun `同一件事放在不同抽屉里沉下去的速度不同`() {
        // 同样是 12 天没人提起、分量 0.6：共同经历已经接近沉底，关于对方的事还稳稳的。
        val event = MemoryLifecycle.decayStep(
            state(drawer = MemoryDrawer.EVENT, createdAt = now - 12 * day, strength = 0.6),
            now
        )
        val user = MemoryLifecycle.decayStep(
            state(drawer = MemoryDrawer.USER, createdAt = now - 12 * day, strength = 0.6),
            now
        )

        assertTrue("USER 应该保留更多分量：$user vs $event", user - event > 0.05)
    }

    @Test
    fun `旧数据没有抽屉时按共同经历处理`() {
        val legacy = MemoryLifecycleState(id = "m1", createdAt = now - 5 * day, strength = 0.7)
        val explicitEvent = state(drawer = MemoryDrawer.EVENT, createdAt = now - 5 * day, strength = 0.7)

        assertEquals(MemoryDrawer.EVENT, legacy.drawer)
        assertEquals(
            MemoryLifecycle.decayStep(explicitEvent, now),
            MemoryLifecycle.decayStep(legacy, now),
            0.0001
        )
    }

    @Test
    fun `未知状态字符串按活跃处理`() {
        assertEquals(MemoryTier.ACTIVE, MemoryTier.parse(null))
        assertEquals(MemoryTier.ACTIVE, MemoryTier.parse(""))
        assertEquals(MemoryTier.ACTIVE, MemoryTier.parse("乱写的"))
        assertEquals(MemoryTier.DORMANT, MemoryTier.parse(" dormant "))
        assertEquals(MemoryTier.ARCHIVED, MemoryTier.parse("ARCHIVED"))
    }
}
