package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 长会话滚动摘要的纯逻辑单测。
 *
 * 只测「什么时候该压、压的时候怎么跟他说、写回来的能不能用」这三件事：
 * 不碰数据库、不碰网络、不碰 Android。压缩本身是为了「他还记得住」，
 * 所以这里的断言也刻意盯着「最近几条必须逐字留着」和「不许编造」这两条底线。
 */
class HistoryCompressionTest {

    private val step = 1_000L

    /** 造一串按时间递增的消息：0 是我说的，1 是你说的，正文长度固定，方便算字符。 */
    private fun messages(
        count: Int,
        startAt: Long = 1_000L,
        chars: Int = 20
    ): List<MsgView> = (0 until count).map { index ->
        MsgView(
            id = "m$index",
            at = startAt + index * step,
            fromUser = index % 2 == 1,
            text = ("第${index}句").padEnd(chars, '话')
        )
    }

    // ------------------------------------------------------------ plan：什么时候该压

    @Test
    fun `空列表不压也没什么可保留的`() {
        val plan = HistoryCompression.plan(emptyList(), summaryUpTo = null)
        assertFalse(plan.shouldCompress)
        assertNull(plan.compressUpTo)
        assertNull(plan.keepFrom)
        assertTrue(plan.reason.isNotBlank())
    }

    @Test
    fun `只有五条不压`() {
        val plan = HistoryCompression.plan(messages(5), summaryUpTo = null)
        assertFalse(plan.shouldCompress)
        // 全部逐字留着，所以「从第一条开始保留」。
        assertEquals(1_000L, plan.keepFrom)
    }

    @Test
    fun `刚好二十四条不压`() {
        val plan = HistoryCompression.plan(messages(24), summaryUpTo = null)
        assertFalse(plan.shouldCompress)
        assertEquals(1_000L, plan.keepFrom)
    }

    @Test
    fun `二十五条还差一点不压`() {
        // 多出的一条比「值得压一批」的门槛（12 条）少得多，为它花一次调用不划算。
        val plan = HistoryCompression.plan(messages(25), summaryUpTo = null)
        assertFalse(plan.shouldCompress)
        // 最近 24 条逐字留着，从第 2 条开始。
        assertEquals(2_000L, plan.keepFrom)
    }

    @Test
    fun `三十六条压最早十二条`() {
        val plan = HistoryCompression.plan(messages(36), summaryUpTo = null)
        assertTrue(plan.shouldCompress)
        assertEquals(12_000L, plan.compressUpTo)      // 第 12 条（索引 11）
        assertEquals(13_000L, plan.keepFrom)          // 最近 24 条的第一条
        assertTrue(plan.compressUpTo!! < plan.keepFrom!!)
    }

    @Test
    fun `一百条也只压一批四十条`() {
        val plan = HistoryCompression.plan(messages(100), summaryUpTo = null)
        assertTrue(plan.shouldCompress)
        assertEquals(40_000L, plan.compressUpTo)      // 索引 39
        assertEquals(77_000L, plan.keepFrom)          // 索引 76
    }

    @Test
    fun `已经被摘要盖住的部分不再重复压`() {
        val all = messages(40)
        // 摘要已经盖到索引 19。
        val plan = HistoryCompression.plan(all, summaryUpTo = all[19].at)
        assertFalse(plan.shouldCompress)
        assertEquals(17_000L, plan.keepFrom)
        assertTrue(plan.reason.contains("摘要"))
    }

    @Test
    fun `摘要盖了一部分时只压剩下的那段`() {
        val all = messages(60)
        val plan = HistoryCompression.plan(all, summaryUpTo = all[19].at)
        assertTrue(plan.shouldCompress)
        assertEquals(all[35].at, plan.compressUpTo)   // 索引 20..35 共 16 条
        assertEquals(all[36].at, plan.keepFrom)
    }

    @Test
    fun `字数超限时条数不够也压`() {
        // 三条很长的话（每条 9000 字）就够撑爆上下文了，等不到 12 条。
        val long = (0 until 27).map { index ->
            MsgView(
                id = "m$index",
                at = 1_000L + index * step,
                fromUser = index % 2 == 0,
                text = if (index < 3) "长话".repeat(4_500) else "短的"
            )
        }
        val plan = HistoryCompression.plan(long, summaryUpTo = null, maxChars = 24_000)
        assertTrue(plan.shouldCompress)
        assertEquals(long[2].at, plan.compressUpTo)
    }

    @Test
    fun `负的逐字保留条数当作至少留一条`() {
        val all = messages(40)
        val plan = HistoryCompression.plan(all, summaryUpTo = null, maxVerbatim = -5)
        assertTrue(plan.shouldCompress)
        assertEquals(all.last().at, plan.keepFrom)
        assertTrue(plan.compressUpTo!! < plan.keepFrom!!)
    }

    @Test
    fun `负的批大小也至少压一条`() {
        val all = messages(60)
        val plan = HistoryCompression.plan(all, summaryUpTo = null, maxBatch = -3)
        assertTrue(plan.shouldCompress)
        assertEquals(all[0].at, plan.compressUpTo)
    }

    @Test
    fun `门槛传坏了也不至于一条都不压`() {
        val all = messages(30)
        val plan = HistoryCompression.plan(all, summaryUpTo = null, minToCompress = -9)
        assertTrue(plan.shouldCompress)
        assertNotNull(plan.compressUpTo)
    }

    @Test
    fun `乱序输入按时间排好再压`() {
        val ordered = messages(60)
        val shuffled = ordered.shuffled(Random(7))
        val a = HistoryCompression.plan(ordered, summaryUpTo = null)
        val b = HistoryCompression.plan(shuffled, summaryUpTo = null)
        assertEquals(a.compressUpTo, b.compressUpTo)
        assertEquals(a.keepFrom, b.keepFrom)
    }

    @Test
    fun `坏时间戳被忽略不影响结论`() {
        val valid = messages(40)
        val withBad = valid + listOf(
            MsgView(id = "bad1", at = 0L, fromUser = true, text = "时间不明的一句"),
            MsgView(id = "bad2", at = -5L, fromUser = false, text = "时间更不明的一句")
        )
        val a = HistoryCompression.plan(valid, summaryUpTo = null)
        val b = HistoryCompression.plan(withBad, summaryUpTo = null)
        assertEquals(a.compressUpTo, b.compressUpTo)
        assertEquals(a.keepFrom, b.keepFrom)
    }

    @Test
    fun `时间戳挤在一起时宁可不压`() {
        // 老消息和「要逐字保留的」落在同一个毫秒上：分不清谁更早，就不动。
        val same = (0 until 40).map { index ->
            MsgView(id = "m$index", at = 5_000L, fromUser = index % 2 == 0, text = "第${index}句")
        }
        val plan = HistoryCompression.plan(same, summaryUpTo = null)
        assertFalse(plan.shouldCompress)
        assertEquals(5_000L, plan.keepFrom)
        assertTrue(plan.reason.isNotBlank())
    }

    // ------------------------------------------------------------ summaryPrompt / mergePrompt

    @Test
    fun `摘要提示词要求第一人称且不许编造`() {
        val prompt = HistoryCompression.summaryPrompt(previous = null, chunk = messages(12))
        assertTrue(prompt.contains("第一人称"))
        assertTrue(prompt.contains("不许编造"))
        assertTrue(prompt.contains("200 字"))
        // 对话要按「你／我」给进去，不能是「用户／助手」。
        assertTrue(prompt.contains("你："))
        assertTrue(prompt.contains("我："))
        // 绝不放行「随便写」这类话术。
        assertFalse(prompt.contains("随便"))
        assertFalse(prompt.contains("自由发挥"))
    }

    @Test
    fun `有旧摘要时请他合成一段`() {
        val prompt = HistoryCompression.summaryPrompt(
            previous = "你提过下个月要去哈尔滨，怕冷。",
            chunk = messages(8)
        )
        assertTrue(prompt.contains("你提过下个月要去哈尔滨"))
        assertTrue(prompt.contains("220 字"))
        assertTrue(prompt.contains("合成一段记忆"))
    }

    @Test
    fun `空的对话也不炸`() {
        val prompt = HistoryCompression.summaryPrompt(previous = null, chunk = emptyList())
        assertTrue(prompt.isNotBlank())
        assertTrue(prompt.contains("第一人称"))
    }

    @Test
    fun `合并提示词把两段都带上`() {
        val prompt = HistoryCompression.mergePrompt(
            previous = "你怕冷，说下个月想去哈尔滨。",
            chunkSummary = "你买了件厚羽绒服，说等出发那天穿。"
        )
        assertTrue(prompt.contains("你怕冷"))
        assertTrue(prompt.contains("厚羽绒服"))
        assertTrue(prompt.contains("220 字"))
        assertTrue(prompt.contains("第一人称"))
    }

    // ------------------------------------------------------------ isSummaryAcceptable

    @Test
    fun `正常的一段记忆可以收下`() {
        assertTrue(HistoryCompression.isSummaryAcceptable("你提过下个月要去哈尔滨，怕冷，让我提醒你带厚外套。"))
    }

    @Test
    fun `太长的不收`() {
        val tooLong = "你提过下个月要去哈尔滨，怕冷。".repeat(30)
        assertTrue(tooLong.length > HistoryCompression.MAX_SUMMARY_CHARS)
        assertFalse(HistoryCompression.isSummaryAcceptable(tooLong))
    }

    @Test
    fun `空的太短的和全是标点的不收`() {
        assertFalse(HistoryCompression.isSummaryAcceptable(""))
        assertFalse(HistoryCompression.isSummaryAcceptable("   \n\t "))
        assertFalse(HistoryCompression.isSummaryAcceptable("你怕冷"))              // 3 个字，太短
        assertFalse(HistoryCompression.isSummaryAcceptable("。。。……！！！……"))     // 标点不是记忆
    }

    @Test
    fun `只写一个无字的不收`() {
        assertFalse(HistoryCompression.isSummaryAcceptable("无"))
        assertFalse(HistoryCompression.isSummaryAcceptable("没有。"))
        assertFalse(HistoryCompression.isSummaryAcceptable("（无）"))
    }

    @Test
    fun `和旧摘要一模一样的不收`() {
        val previous = "你提过下个月要去哈尔滨，怕冷。"
        assertFalse(HistoryCompression.isSummaryAcceptable(previous, previous))
        // 标点或空白变了但意思没变的，也算没写新东西。
        assertFalse(HistoryCompression.isSummaryAcceptable("你提过下个月要去哈尔滨，怕冷", previous))
    }

    @Test
    fun `模型在道歉推脱时不收`() {
        assertFalse(HistoryCompression.isSummaryAcceptable("抱歉，我无法完成这个请求。"))
        assertFalse(HistoryCompression.isSummaryAcceptable("对不起，这段内容我不能处理。"))
    }

    @Test
    fun `工具调用和代码块不能当成记忆收下`() {
        // 真机上出现过：模型把一次 remember 调用当成「回忆」写了回来。
        val toolBlock = """
            嗯，这个我想记下来。

            ```yap-tool
            {"name":"remember","arguments":{"content":"对方怕冷","drawer":"USER","importance":4}}
            ```
        """.trimIndent()
        assertFalse(HistoryCompression.isSummaryAcceptable(toolBlock))
        // 任何代码块都不该成为记忆正文。
        assertFalse(HistoryCompression.isSummaryAcceptable("你提过下个月要去哈尔滨。\n```kotlin\nval x = 1\n```"))
        // 只是提到工具名、没有围栏的一段正常记忆仍然收下。
        assertTrue(HistoryCompression.isSummaryAcceptable("你提过下个月要去哈尔滨，怕冷，让我提醒你带厚外套。"))
    }

    // ------------------------------------------------------------ salvageSummary

    @Test
    fun `剥掉工具调用后只剩半句的不收`() {
        val raw = """
            嗯，这个我想记下来。

            ```yap-tool
            {"name":"remember","arguments":{"content":"对方怕冷","drawer":"USER","importance":4}}
            ```
        """.trimIndent()
        assertNull(HistoryCompression.salvageSummary(raw))
    }

    @Test
    fun `代码块前后都有像样正文时留下正文`() {
        val raw = """
            你提过下个月要去哈尔滨，怕冷，让我提醒你带厚外套；还说不想被反复叮嘱。

            ```json
            {"name":"remember"}
            ```
        """.trimIndent()
        val salvaged = HistoryCompression.salvageSummary(raw)
        assertNotNull(salvaged)
        assertTrue(salvaged!!.contains("哈尔滨"))
        assertFalse(salvaged.contains("```"))
        assertTrue(HistoryCompression.isSummaryAcceptable(salvaged))
    }

    @Test
    fun `整段被代码围栏包住的正常摘要仍然收下`() {
        val raw = "```\n你提过下个月要去哈尔滨，怕冷，让我提醒你带厚外套；还说不想被反复叮嘱。\n```"
        val salvaged = HistoryCompression.salvageSummary(raw)
        assertNotNull(salvaged)
        assertTrue(HistoryCompression.isSummaryAcceptable(salvaged!!))
    }

    @Test
    fun `前缀和引号会被清掉`() {
        assertEquals("你提过下个月要去哈尔滨，怕冷。", HistoryCompression.salvageSummary("摘要：「你提过下个月要去哈尔滨，怕冷。」"))
        assertEquals("你提过下个月要去哈尔滨，怕冷。", HistoryCompression.salvageSummary("【记忆】你提过下个月要去哈尔滨，怕冷。"))
    }

    @Test
    fun `旧摘要自检比通用判据更严`() {
        // 通用判据认为这半句「合法」，但作为一段回忆太短，启动自检要清掉。
        val stub = "嗯，这个我想记下来。"
        assertTrue(HistoryCompression.isSummaryAcceptable(stub))
        assertFalse(HistoryCompression.isLegacySummaryHealthy(stub))
        assertTrue(
            HistoryCompression.isLegacySummaryHealthy("你提过下个月要去哈尔滨，怕冷，让我提醒你带厚外套。")
        )
    }

    // ------------------------------------------------------------ memoryBlock

    @Test
    fun `真的压过才把摘要放进系统提示`() {
        val block = HistoryCompression.memoryBlock("你提过下个月要去哈尔滨，怕冷。", summarizedUpTo = 1_700_000_000_000L)
        assertTrue(block.contains(HistoryCompression.SUMMARY_HEADER))
        assertTrue(block.contains("哈尔滨"))
    }

    @Test
    fun `没有摘要或者不知道压到哪条时什么都不注入`() {
        assertEquals("", HistoryCompression.memoryBlock(null, summarizedUpTo = 1_700_000_000_000L))
        assertEquals("", HistoryCompression.memoryBlock("   ", summarizedUpTo = 1_700_000_000_000L))
        assertEquals("", HistoryCompression.memoryBlock("你提过去哈尔滨。", summarizedUpTo = null))
        assertEquals("", HistoryCompression.memoryBlock("你提过去哈尔滨。", summarizedUpTo = 0L))
    }

    @Test
    fun `系统提示本身也不放行编造`() {
        val system = HistoryCompression.systemPrompt()
        assertTrue(system.contains("不许编造"))
        assertTrue(system.contains("第一人称"))
        assertFalse(system.contains("随便"))
    }
}
