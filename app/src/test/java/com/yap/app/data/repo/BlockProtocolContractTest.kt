package com.yap.app.data.repo

import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.remote.ServerMergePlan
import com.yap.app.data.remote.ServerPersona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 拉黑协议（`spec/experience/README.md` §9）的「接线」契约。
 *
 * 纯规则由 `BlockingTest` 钉住；这里盯三件接线错了就会静默分叉的事：
 * 1. `block_user` 工具回灌给模型的是 §9 的两行 JSON（而不是散文）；
 * 2. 「之后的一轮」用本轮最新的 `userMessageId` 判断（不是消息条数）；
 * 3. 服务端人格 DTO/合并带上 `blockProposalJson`，两端字段同名同义。
 */
class BlockProtocolContractTest {

    @Test
    fun `block_user 回灌给模型的是统一 JSON`() {
        val executor = source("src/main/java/com/yap/app/data/repo/ToolExecutor.kt")
        val body = bodyOf(executor, "private suspend fun blockUser(")
        assertTrue("pending 必须是 pending_confirmation JSON", body.contains("BlockPolicy.pendingJson("))
        assertTrue("生效必须是 blocked JSON", body.contains("BlockPolicy.blockedJson("))
        assertTrue("拒绝也要用稳定 code JSON", body.contains("BlockPolicy.rejectedJson("))
        assertTrue(
            "确认条件用本轮最新用户消息 id（不是条数）",
            body.contains("messageDao.latestUser(sessionId)?.id") && !body.contains("countUserMessages")
        )
        assertTrue("提议按固定 7 字段编码", body.contains("BlockPolicy.encode(plan.proposal)"))
        assertTrue("已经在拉黑期要与服务端同样拒绝", body.contains("BlockPolicy.REJECT_ALREADY_BLOCKED"))
    }

    @Test
    fun `服务端人格 DTO 与合并都带 blockProposalJson`() {
        val models = source("src/main/java/com/yap/app/data/remote/ServerModels.kt")
        assertTrue("服务端 DTO 要与 personas 列同名", models.contains("val blockProposalJson: String? = null"))

        val merge = source("src/main/java/com/yap/app/data/remote/ServerSync.kt")
        val persona = bodyOf(merge, "fun persona(")
        assertTrue("合并时要镜像提议", persona.contains("blockProposalJson = server.blockProposalJson"))
        assertTrue(
            "没变化时也要比较提议（否则同一轮确认会被本地旧值覆盖）",
            persona.contains("local.blockProposalJson == server.blockProposalJson")
        )
    }

    @Test
    fun `服务端没有新数据时 提议也能镜像到本地`() {
        val local = PersonaEntity(
            id = "p1",
            name = "林深",
            description = "",
            avatarUri = null,
            avatarSeed = 1,
            prompt = "",
            category = "默认",
            version = 1,
            defaultApiConfigId = null,
            modelOverride = null,
            isDefault = false,
            createdAt = 1,
            updatedAt = 1,
            blockProposalJson = null
        )
        val proposal = """{"proposalId":"p-1","personaId":"p1","hours":6,"reason":"","userMessageId":"u-1","proposedAt":1,"expiresAt":2}"""

        // updatedAt 没有变新：仍然要把提议镜像下来（服务端是权威）。
        val merged = ServerMergePlan.persona(
            local = local,
            server = ServerPersona(id = "p1", name = "林深", updatedAt = 1, blockProposalJson = proposal)
        )
        assertEquals(proposal, merged.blockProposalJson)

        // 服务端清掉提议（确认/过期）：本地也要清。
        val cleared = ServerMergePlan.persona(
            local = merged,
            server = ServerPersona(id = "p1", name = "林深", updatedAt = 1, blockProposalJson = null)
        )
        assertEquals(null, cleared.blockProposalJson)
    }

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

    private fun source(relative: String): String {
        val candidates = listOf(File("."), File(".."))
        val root = candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到工程根目录：${File(".").absolutePath}")
        return File(root, relative).readText()
    }
}
