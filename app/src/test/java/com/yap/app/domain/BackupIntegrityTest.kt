package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 备份「引用完整性闸」的测试（审计 P1-2 / 0.11.1）。
 *
 * 0.11.0 的空包闸只数顶层条数：`messages` 非空、`sessions` 缺失的备份能通过预览，
 * 用户点「替换」之后本机先被清空，随后这些行因为找不到会话被丢——本机没了、备份也没接回来。
 *
 * 这里分两层钉住：
 * 1. [BackupIntegrity] 是纯函数，直接构造 `BackupBundle` 断言「缺口计数」；
 * 2. 接线：`BackupRepository.importBundle` 必须在 `database.withTransaction` **之前**
 *    跑这个校验并整份拒绝（没有 Room/Robolectric，接线只能用源码断言——测试注释里写明
 *    它拦不住什么：它证明「顺序与抛错存在」，不证明「Room 真没被改」）。
 */
class BackupIntegrityTest {

    private fun person(id: String = "p1") = BackupPersona(id = id, name = "他")
    private fun session(id: String, personaId: String, at: Long = 1L) =
        BackupSession(id = id, personaId = personaId, lastMessageAt = at, updatedAt = at)

    private fun message(id: String, sessionId: String) = BackupMessage(id = id, sessionId = sessionId)
    private fun memory(id: String, personaId: String) = BackupMemory(id = id, personaId = personaId)
    private fun rule(id: String, personaId: String) = BackupProactiveRule(id = id, personaId = personaId)
    private fun intent(id: String, personaId: String) = BackupAgentIntent(id = id, personaId = personaId)
    private fun consolidation(id: String, personaId: String) = BackupMemoryConsolidation(id = id, personaId = personaId)

    // ------------------------------------------------------------------ 纯逻辑

    @Test
    fun `消息找不到会话 是缺口`() {
        // 审计里的最小复现：{"messages":[{...}]}，没有 sessions / personas。
        val holes = BackupIntegrity.check(
            BackupBundle(messages = listOf(message("m1", "s-missing")))
        )
        assertEquals(1, holes.messages)
        assertEquals(1, holes.total)
        assertFalse("这种备份不能过闸", holes.isClean)
    }

    @Test
    fun `只有记忆没有 人格 也是缺口`() {
        val holes = BackupIntegrity.check(BackupBundle(memories = listOf(memory("mem1", "p-missing"))))
        assertEquals(1, holes.memories)
        assertFalse(holes.isClean)
    }

    @Test
    fun `会话 规则 念头 整理记录 都必须挂在存在的人格上`() {
        val holes = BackupIntegrity.check(
            BackupBundle(
                personas = listOf(person("p1")),
                sessions = listOf(session("s1", "p1"), session("s2", "p-gone")),
                messages = listOf(message("m1", "s1")),
                memories = listOf(memory("mem1", "p1"), memory("mem2", "p-gone")),
                proactiveRules = listOf(rule("r1", "p1"), rule("r2", "p-gone")),
                agentIntents = listOf(intent("i1", "p-gone")),
                memoryConsolidations = listOf(consolidation("c1", "p-gone"))
            )
        )
        assertEquals(1, holes.sessions)
        assertEquals(0, holes.messages)
        assertEquals(1, holes.memories)
        assertEquals(1, holes.rules)
        assertEquals(1, holes.intents)
        assertEquals(1, holes.consolidations)
        assertEquals(5, holes.total)
    }

    @Test
    fun `指向被一人一段丢掉的会话 也要算缺口`() {
        // 同一人格两段会话：只保留最新那段。旧段里的消息同样落不下去，
        // 如果不算缺口，「先清空再丢行」仍然存在（审计 P1-2 的建议口径）。
        val bundle = BackupBundle(
            personas = listOf(person("p1")),
            sessions = listOf(
                session("s-old", "p1", at = 10L),
                session("s-new", "p1", at = 20L)
            ),
            messages = listOf(message("m1", "s-old"), message("m2", "s-new"))
        )
        val keep = BackupIntegrity.sessionsToRestore(bundle)
        assertEquals(listOf("s-new"), keep.map { it.id })
        val holes = BackupIntegrity.check(bundle, keep)
        assertEquals("旧段会话里的消息落不下去", 1, holes.messages)
        assertFalse(holes.isClean)
    }

    @Test
    fun `合法备份没有缺口`() {
        val bundle = BackupBundle(
            personas = listOf(person("p1")),
            sessions = listOf(session("s1", "p1")),
            messages = listOf(message("m1", "s1"), message("m2", "s1")),
            memories = listOf(memory("mem1", "p1")),
            proactiveRules = listOf(rule("r1", "p1")),
            agentIntents = listOf(intent("i1", "p1")),
            memoryConsolidations = listOf(consolidation("c1", "p1"))
        )
        assertTrue(BackupIntegrity.check(bundle).isClean)
        assertEquals(0, BackupIntegrity.check(bundle).total)
    }

    @Test
    fun `没有任何内容的备份不是缺口 但替换不能用它`() {
        val empty = BackupBundle()
        assertTrue("空备份没有引用可断", BackupIntegrity.check(empty).isClean)
        assertTrue("替换模式必须拒绝四个主段全空的备份", BackupIntegrity.isEffectivelyEmpty(empty))
        assertFalse(BackupIntegrity.isEffectivelyEmpty(BackupBundle(personas = listOf(person("p1")))))
        assertFalse(
            BackupIntegrity.isEffectivelyEmpty(BackupBundle(messages = listOf(message("m1", "s1"))))
        )
    }

    @Test
    fun `空白 id 不算引用目标`() {
        val holes = BackupIntegrity.check(
            BackupBundle(
                personas = listOf(BackupPersona(id = "")),
                sessions = listOf(session("", "p1")),
                messages = listOf(message("m1", ""))
            )
        )
        assertEquals("空 id 的会话不算存在", 1, holes.sessions)
        assertEquals("空 id 的会话也不能让消息落地", 1, holes.messages)
    }

    @Test
    fun `真实 JSON 里只有消息没有会话 会被拒`() {
        // 审计里的最小复现文件：能解析、预览显示「1 句话」，旧闸放行。
        val raw = """
            {"format":"myfriend.backup","formatVersion":2,
             "messages":[{"id":"m1","sessionId":"s-gone","personaId":"p-gone","content":"你好","role":"USER"}]}
        """.trimIndent()
        val decoded = BackupCodec.decode(raw)
        val success = decoded as? BackupDecodeResult.Success
            ?: error("这份 JSON 应当能解析（旧闸正是放行它）：$decoded")
        assertFalse("预览的 isEmpty 为 false，界面会给出「替换」按钮", BackupPreview.from(success.bundle).isEmpty)
        val failure = BackupIntegrity.rejectionOf(success.bundle)
        assertTrue(
            "必须在清空本机之前整份拒绝",
            failure is BackupFailure.IncompleteReferences && failure.holes == 1
        )
    }

    @Test
    fun `合法的往返备份 能通过闸门`() {
        val bundle = BackupBundle(
            personas = listOf(person("p1")),
            sessions = listOf(session("s1", "p1")),
            messages = listOf(message("m1", "s1")),
            memories = listOf(memory("mem1", "p1"))
        )
        val decoded = BackupCodec.decode(BackupCodec.encode(bundle)) as BackupDecodeResult.Success
        assertEquals(null, BackupIntegrity.rejectionOf(decoded.bundle))
    }

    // ------------------------------------------------------------------ 接线（源码契约）

    @Test
    fun `导入在清空本机之前做引用完整性校验并整份拒绝`() {
        val repo = source("src/main/java/com/yap/app/data/repo/BackupRepository.kt")
        val body = bodyOf(repo, "suspend fun importBundle(")
        val checkIndex = body.indexOf("BackupIntegrity.rejectionOf(bundle)")
        // 注释里也会提到 database.withTransaction（「在 ... 之前」），所以找真正的调用。
        val transactionIndex = body.indexOf("database.withTransaction {")
        assertTrue("importBundle 必须调用引用完整性闸", checkIndex >= 0)
        assertTrue("必须真的开事务写库", transactionIndex >= 0)
        assertTrue(
            "校验必须在 database.withTransaction 之前（否则本机已经被清空了）",
            checkIndex < transactionIndex
        )
        assertTrue(
            "有缺口要整份拒绝，而不是只记 warning 然后丢行",
            body.contains("BackupIntegrity.rejectionOf(bundle)") &&
                body.contains("throw BackupImportException(failure)")
        )
        assertTrue(
            "替换模式还要拒绝「四个主段全空」的备份（绕过界面的调用方）",
            body.contains("BackupIntegrity.isEffectivelyEmpty(bundle)")
        )
        assertTrue(
            "预览阶段也要提前提醒（对话框里能看到这份备份会被拒）",
            repo.contains("BackupWarning.IncompleteReferences(holes.total)")
        )
    }

    @Test
    fun `预览解码不再返回裸解码结果`() {
        val repo = source("src/main/java/com/yap/app/data/repo/BackupRepository.kt")
        val body = bodyOf(repo, "suspend fun preview(")
        assertTrue("预览要跑完整性检查", body.contains("BackupIntegrity.check(decoded.bundle)"))
    }

    private fun projectRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到 app 模块目录：${File(".").absolutePath}")
    }

    private fun source(relative: String): String = File(projectRoot(), relative).readText()

    /** 从 [marker] 起截到本类下一个成员声明之前。 */
    private fun bodyOf(text: String, marker: String): String {
        val start = text.indexOf(marker)
        require(start >= 0) { "找不到 $marker" }
        val rest = text.substring(start + marker.length)
        val endTokens = listOf(
            "\n    private suspend fun ",
            "\n    private fun ",
            "\n    suspend fun ",
            "\n    override fun ",
            "\n    fun "
        )
        val end = endTokens.map { rest.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }
}
