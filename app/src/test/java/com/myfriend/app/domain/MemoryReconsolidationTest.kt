package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回忆重构的纯领域测试。
 *
 * 重点不是「能不能改写」，而是「什么时候必须拒绝」：
 * 心情 / 年龄 / 改动次数怎么影响预算，相似度怎么算，事实（数字、称呼、否定）
 * 有没有被守住，提示词里有没有一句会放模型乱改的话。
 */
class MemoryReconsolidationTest {

    private fun mood(label: String, intensity: Int = 3) =
        MoodState(label = label, intensity = intensity, updatedAt = 1_800_000_000_000L)

    /** 最松的一档：心情很浓、记忆很旧、还没被改过。 */
    private fun looseBudget(): DriftBudget = MemoryReconsolidation.driftBudget(
        mood = mood("雀跃", 5),
        ageDays = 200.0,
        driftCount = 0
    )

    /** 最紧的一档：心里很静、刚发生、没改过。 */
    private fun strictBudget(): DriftBudget = MemoryReconsolidation.driftBudget(
        mood = mood("平静", 1),
        ageDays = 0.0,
        driftCount = 0
    )

    private val memory = "他妹妹三年前结婚了，他去当了伴郎。"
    private val smallDrift = "他妹妹三年前结婚了，那天他去当了伴郎。"

    // ------------------------------------------------------------- 预算

    @Test
    fun `心情越浓允许的改动越大`() {
        val calm = MemoryReconsolidation.driftBudget(mood("平静", 1), 30.0, 0)
        val sad = MemoryReconsolidation.driftBudget(mood("难过", 5), 30.0, 0)
        assertTrue(sad.allowance > calm.allowance)
        assertTrue(sad.minSimilarity < calm.minSimilarity)
    }

    @Test
    fun `记忆越旧允许的改动越大`() {
        val fresh = MemoryReconsolidation.driftBudget(mood("有点累", 3), 1.0, 0)
        val old = MemoryReconsolidation.driftBudget(mood("有点累", 3), 200.0, 0)
        assertTrue(old.allowance > fresh.allowance)
        assertTrue(old.minSimilarity < fresh.minSimilarity)
    }

    @Test
    fun `改写过一次比没改过更紧`() {
        val never = MemoryReconsolidation.driftBudget(mood("有点累", 3), 30.0, 0)
        val once = MemoryReconsolidation.driftBudget(mood("有点累", 3), 30.0, 1)
        assertTrue(once.allowance < never.allowance)
        assertTrue(once.minSimilarity > never.minSimilarity)
    }

    @Test
    fun `改写过三次就定型不再动`() {
        val third = MemoryReconsolidation.driftBudget(mood("难过", 5), 400.0, 3)
        assertFalse(third.allowed)
        assertTrue(third.reason.contains("定型"))
        assertFalse(MemoryReconsolidation.driftBudget(mood("难过", 5), 400.0, 5).allowed)
    }

    @Test
    fun `相似度门槛永远不低于零点六二`() {
        val labels = listOf("平静", "有点累", "难过", "雀跃", "说不上来")
        labels.forEach { label ->
            listOf(0.0, 30.0, 400.0).forEach { age ->
                listOf(0, 1, 2, 3).forEach { count ->
                    val budget = MemoryReconsolidation.driftBudget(mood(label, 5), age, count)
                    assertTrue(
                        "门槛低于 0.62：$label/$age/$count",
                        budget.minSimilarity >= MemoryReconsolidation.SIMILARITY_FLOOR
                    )
                }
            }
        }
    }

    @Test
    fun `长度额度有上限也知道深浅`() {
        val loose = looseBudget()
        val strict = strictBudget()
        assertTrue(loose.maxAddedChars > strict.maxAddedChars)
        assertTrue(loose.maxLengthRatio > strict.maxLengthRatio)
        assertTrue(strict.maxAddedChars >= 4)
        assertTrue(loose.maxAddedChars <= 60)
        assertTrue(strict.maxLengthRatio >= 1.10)
        assertTrue(loose.maxLengthRatio <= 1.25)
        assertTrue(loose.reason.isNotBlank())
    }

    @Test
    fun `锚点相似度比单次门槛更严`() {
        val loose = looseBudget()
        val anchor = MemoryReconsolidation.anchorSimilarity(loose)
        assertTrue(anchor >= MemoryReconsolidation.ANCHOR_SIMILARITY_FLOOR)
        assertTrue(anchor >= loose.minSimilarity)
    }

    // ------------------------------------------------------------- 相似度

    @Test
    fun `完全一样相似度是一`() {
        assertEquals(1.0, MemoryReconsolidation.similarity(memory, memory), 0.0001)
        assertEquals(1.0, MemoryReconsolidation.similarity("", ""), 0.0001)
    }

    @Test
    fun `空白和标点不影响相似度`() {
        assertEquals(
            1.0,
            MemoryReconsolidation.similarity("他 妹妹，结婚了。", "他妹妹结婚 了"),
            0.0001
        )
    }

    @Test
    fun `相似度是对称的`() {
        val left = MemoryReconsolidation.similarity(memory, smallDrift)
        val right = MemoryReconsolidation.similarity(smallDrift, memory)
        assertEquals(left, right, 0.0001)
        assertTrue(left > 0.7)
    }

    @Test
    fun `另写一段话相似度很低`() {
        val other = "那年冬天他一个人搬去了南方的城市，谁也没告诉"
        assertTrue(MemoryReconsolidation.similarity(memory, other) < 0.3)
        assertEquals(0.0, MemoryReconsolidation.similarity("", memory), 0.0001)
    }

    // ------------------------------------------------------------- 拒绝一条一条来

    @Test
    fun `心情浓的时候小小的改动可以接受`() {
        assertTrue(MemoryReconsolidation.acceptDrift(memory, smallDrift, looseBudget()))
    }

    @Test
    fun `心里很静时同样的改动会被拒绝`() {
        // 同一段改动，心情淡的时候就是「不需要动」——预算真的在起作用。
        assertFalse(MemoryReconsolidation.acceptDrift(memory, smallDrift, strictBudget()))
    }

    @Test
    fun `预算不允许时直接拒绝`() {
        val fixed = MemoryReconsolidation.driftBudget(mood("难过", 5), 400.0, 3)
        assertFalse(MemoryReconsolidation.acceptDrift(memory, smallDrift, fixed))
    }

    @Test
    fun `不许把妹妹改成哥哥`() {
        // 长句里改个称呼，字面相似度依然有 0.8 左右 —— 必须由「称呼/叠词」这一层拦住。
        val before = "他妹妹三年前结婚，他去当了伴郎，那天还下了很大的雨，回来的路上车坏了。"
        val after = "他哥哥三年前结婚，他去当了伴郎，那天还下了很大的雨，回来的路上车坏了。"
        assertTrue(MemoryReconsolidation.similarity(before, after) > 0.62)
        assertFalse(MemoryReconsolidation.acceptDrift(before, after, looseBudget()))
    }

    @Test
    fun `不许改掉数字与日期`() {
        val before = "他 2023 年搬去杭州，住在城西"
        val after = "他 2024 年搬去杭州，住在城西"
        assertFalse(MemoryReconsolidation.acceptDrift(before, after, looseBudget()))
        // 中文数字一样不给改：下周三不能变下周四。
        assertFalse(
            MemoryReconsolidation.acceptDrift(
                "他说他下周三要去一趟医院",
                "他说他下周四要去一趟医院",
                looseBudget()
            )
        )
    }

    @Test
    fun `不许把去了改成没去`() {
        val before = "他那天下午三点陪我去了医院，回来还给我带了粥"
        val after = "他那天下午三点陪我去了医院，回来没给我带了粥"
        assertTrue(MemoryReconsolidation.similarity(before, after) > 0.62)
        assertFalse(MemoryReconsolidation.acceptDrift(before, after, looseBudget()))
    }

    @Test
    fun `不许凭空加一段新情节`() {
        val after = memory + "那天他还说起了想搬去别的城市，连房子都看好了。"
        assertFalse(MemoryReconsolidation.acceptDrift(memory, after, looseBudget()))
    }

    @Test
    fun `不许删到只剩半句`() {
        assertFalse(MemoryReconsolidation.acceptDrift(memory, "他妹妹三年前结婚了。", looseBudget()))
    }

    @Test
    fun `空提议和原样提议都不算改动`() {
        assertFalse(MemoryReconsolidation.acceptDrift(memory, "", looseBudget()))
        assertFalse(MemoryReconsolidation.acceptDrift(memory, "   ", looseBudget()))
        assertFalse(MemoryReconsolidation.acceptDrift(memory, memory, looseBudget()))
    }

    @Test
    fun `锚点里的人名必须原样留着`() {
        val before = "小满来电话的时候已经是凌晨两点"
        val after = "她来电话的时候已经是凌晨两点"
        // 没有锚点时，文本层面拦不住（相似度 0.8，数字也对得上）；
        // 一旦知道这段记忆里有「小满」，就必须拒绝。
        assertTrue(MemoryReconsolidation.acceptDrift(before, after, looseBudget()))
        assertFalse(
            MemoryReconsolidation.acceptDrift(before, after, looseBudget(), listOf("小满"))
        )
    }

    // ------------------------------------------------------------- 提示词

    @Test
    fun `重构提示词明确不许改事实`() {
        val text = MemoryReconsolidation.prompt(
            content = memory,
            mood = mood("低落", 4),
            driftCount = 2,
            budget = looseBudget(),
            ageText = "这大概是 41 天前的事。"
        )
        assertTrue(text.contains(memory))
        assertTrue(text.contains("低落"))
        assertTrue(text.contains("想起过它 2 次"))
        assertTrue(text.contains("不许改变事实"))
        assertTrue(text.contains("不改"))
        assertTrue(text.contains("41 天前"))
    }

    @Test
    fun `重构提示词里没有放行话术`() {
        val text = MemoryReconsolidation.systemPrompt() +
            MemoryReconsolidation.prompt(memory, mood("雀跃", 5), 0, looseBudget())
        listOf(
            "随意改写", "尽情发挥", "可以改变事实", "允许改变事实",
            "自由改写", "随便改", "不受限制", "大胆改"
        ).forEach { phrase ->
            assertFalse("出现了放行话术：$phrase", text.contains(phrase))
        }
    }

    @Test
    fun `心情越淡提示里的尺度越小`() {
        val quiet = MemoryReconsolidation.prompt(memory, mood("平静", 1), 0, strictBudget())
        val lively = MemoryReconsolidation.prompt(memory, mood("雀跃", 5), 0, looseBudget())
        assertTrue(quiet.contains("几乎不动"))
        assertTrue(lively.contains("稍微动一点"))
    }

    @Test
    fun `版本留痕的理由带心情与次数`() {
        val reason = MemoryReconsolidation.versionReason(mood("有点累", 2), 2, 0.81)
        assertTrue(reason.contains("有点累"))
        assertTrue(reason.contains("第 2 次改动"))
        assertTrue(reason.contains("81%"))
        assertTrue(MemoryReconsolidation.versionReason(MoodState(), 1, 0.9).contains("没什么特别"))
    }
}
