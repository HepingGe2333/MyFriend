package com.myfriend.app.data.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界预设补全（规范 §2.2 / §6.2）。
 *
 * 正常 JSON、markdown 代码块、缺字段、坏 JSON、没配置都要有确定结果；
 * 兜底必须**至少**返回 `worldName = 角色名` 和空数组，不能让「AI 补全」把名字弄丢。
 */
class WorldPresetExpanderTest {

    private class FakeLlm(private val reply: String?) : JsonLlmClient {
        var lastSystem: String? = null
        var lastUser: String? = null
        override suspend fun completeJson(system: String, user: String, configId: String?): String? {
            lastSystem = system
            lastUser = user
            return reply
        }
    }

    private val brief = "林深，28岁，建筑事务所项目负责人，最近在赶一个住宅项目，母亲每周三晚打电话。"

    private fun expand(reply: String?): WorldPresetResult = runBlocking {
        WorldPresetExpander(FakeLlm(reply)).expand(brief = brief, name = "林深")
    }

    @Test
    fun `正常 JSON 走模型结果`() {
        val json = """
            {"worldName":"临港新城·云筑建筑事务所","era":"当代，中国沿海新一线城市，2026 年",
             "description":"他带着六人小组负责一个高层住宅项目。",
             "schedule":[{"day":"工作日","start":"09:00","end":"18:30","activity":"画图、审图"}],
             "relationships":[{"name":"母亲","relation":"母亲","note":"每周三晚打电话"}],
             "obligations":["按节点交图"]}
        """.trimIndent()
        val result = expand(json)
        assertEquals(WorldPresetSource.LLM, result.source)
        assertEquals("临港新城·云筑建筑事务所", result.preset.worldName)
        assertEquals(1, result.preset.schedule.size)
        assertEquals("母亲", result.preset.relationships.single().name)
        assertFalse(result.isFallback)
    }

    @Test
    fun `带 markdown 代码块的回复也能解析`() {
        val reply = """
            这是补全结果：
            ```json
            {"worldName":"某城","era":"当代","description":"d","schedule":[],"relationships":[],"obligations":[]}
            ```
        """.trimIndent()
        val result = expand(reply)
        assertEquals(WorldPresetSource.LLM, result.source)
        assertEquals("某城", result.preset.worldName)
    }

    @Test
    fun `缺字段按空数组处理`() {
        val result = expand("""{"worldName":"某城"}""")
        assertEquals(WorldPresetSource.LLM, result.source)
        assertTrue(result.preset.schedule.isEmpty())
        assertTrue(result.preset.relationships.isEmpty())
        assertTrue(result.preset.obligations.isEmpty())
    }

    @Test
    fun `坏 JSON 走兜底 worldName 等于角色名`() {
        val result = expand("坏掉的结果")
        assertEquals(WorldPresetSource.FALLBACK, result.source)
        assertEquals("林深", result.preset.worldName)
        assertTrue(result.preset.schedule.isEmpty())
        assertTrue(result.isFallback)
    }

    @Test
    fun `没有配置走兜底`() {
        val result = expand(null)
        assertEquals(WorldPresetSource.FALLBACK, result.source)
        assertEquals("林深", result.preset.worldName)
    }

    @Test
    fun `worldName 为空的模型输出不能当成功`() {
        val result = expand("""{"worldName":"  ","era":"当代"}""")
        assertEquals(WorldPresetSource.FALLBACK, result.source)
        assertEquals("林深", result.preset.worldName)
    }

    @Test
    fun `两个提示词都是规范原文里的关键约束`() {
        val fake = FakeLlm(null)
        runBlocking { WorldPresetExpander(fake).expand(brief = brief, name = "林深") }
        assertTrue(fake.lastUser!!.contains("建筑事务所"))
        val prompt = WorldPresetExpander.PROMPT
        assertTrue(prompt.contains("你是角色设定助手"))
        assertTrue(prompt.contains("只输出 JSON"))
        assertTrue(prompt.contains("不要写剧情大纲"))
        val speechPrompt = SpeechStyleSummarizer.PROMPT
        assertTrue(speechPrompt.contains("你是对话风格分析器"))
        assertTrue(speechPrompt.contains("不要输出人名"))
        assertTrue(speechPrompt.contains("只输出 JSON"))
    }
}
