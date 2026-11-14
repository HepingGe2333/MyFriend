package com.myfriend.app.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 数据与资源边界的**接线**契约（0.11.0 P2 收尾：审计 P2-6 / P2-7 / P2-8 / P2-9 / P2-13，
 * 安全审计 P2-3 / P2-5 / P2-6 / P2-10）。
 *
 * 这个项目没有 Room/Robolectric 单测环境，所以纯逻辑各有真单测：
 * `MessageJumpTest` / `MessageOrderTest` / `InterruptedReplyTest` / `ImageContextBudgetTest` /
 * `BackupLimitsTest` / `BoundedTextReadTest` / `BackupAvatarPathTest` / `ApiKeyStateTest` /
 * `WebUrlPolicyTest`。这里钉的是「有没有真的接上线」——接错线时纯逻辑测试全绿，
 * 只有源码断言能拦住。
 */
class DataResilienceContractTest {

    private val chatRepo get() = source("src/main/java/com/myfriend/app/data/repo/ChatRepository.kt")
    private val daos get() = source("src/main/java/com/myfriend/app/data/local/Daos.kt")
    private val media get() = source("src/main/java/com/myfriend/app/util/MediaStorage.kt")
    private val backupRepo get() = source("src/main/java/com/myfriend/app/data/repo/BackupRepository.kt")
    private val backupCodec get() = source("src/main/java/com/myfriend/app/domain/BackupCodec.kt")
    private val apiConfig get() = source("src/main/java/com/myfriend/app/data/repo/ApiConfigRepository.kt")
    private val secretCipher get() = source("src/main/java/com/myfriend/app/data/secure/SecretCipher.kt")
    private val webTools get() = source("src/main/java/com/myfriend/app/network/DeepSeekWebTools.kt")

    // ------------------------------------------------------------------ 审计 P2-6

    @Test
    fun `createSession 是幂等 upsert 撞唯一索引就回读`() {
        val block = bodyOf(chatRepo, "suspend fun createSession(")
        assertTrue(
            "插入必须走 IGNORE：ABORT 会让首页点联系人直接失败，REPLACE 会删掉对方刚建的会话",
            block.contains("sessionDao.insertIgnore(session)")
        )
        assertTrue("撞索引（返回 -1）后回读一行", block.contains("inserted != -1L") && block.contains("latestForPersona(personaId)"))
        assertTrue(
            "DAO 里插入用的是 OnConflictStrategy.IGNORE",
            daos.contains("insertIgnore") && daos.contains("OnConflictStrategy.IGNORE")
        )
    }

    // ------------------------------------------------------------------ 审计 P2-7

    @Test
    fun `deleteAfter 用 createdAt 加 id 的稳定序`() {
        val block = bodyOf(chatRepo, "suspend fun editUserMessage(")
        assertTrue(
            "编辑重发要按 (createdAt, id) 删除之后的消息",
            block.contains("messageDao.deleteAfterCursor(sessionId, message.createdAt, message.id)")
        )
        assertFalse("旧版只删严格更晚的，同毫秒会漏", daos.contains("suspend fun deleteAfter(sessionId: String, after: Long)"))
        assertTrue(
            "SQL 必须是复合游标，且被编辑的这条自己不算「之后」",
            daos.contains("createdAt > :createdAt OR (createdAt = :createdAt AND id > :id)")
        )
    }

    @Test
    fun `时间戳严格递增走共用纯函数`() {
        assertTrue(
            "nextTimestamp 必须走 MessageOrder（同毫秒 +1 的语义只有一份实现）",
            chatRepo.contains("MessageOrder.nextTimestamp(")
        )
        assertFalse("不要再手写「读最新一条 + now」的推算", chatRepo.contains("now <= latest.createdAt"))
    }

    // ------------------------------------------------------------------ 审计 P2-8

    @Test
    fun `停止生成时由 InterruptedReply 决定落不落库`() {
        val block = bodyOf(chatRepo, "fun generateAssistant(")
        assertTrue("取消分支要用三分判定", block.contains("InterruptedReply.decide("))
        assertFalse(
            "旧条件 traces 非空就落库，会留下 content 为空的空气泡",
            block.contains("if (!completed && (shownText.isNotBlank() || traces.isNotEmpty()))")
        )
        assertTrue(
            "只有工具调用时预览不能被写成空串",
            bodyOf(chatRepo, "private suspend fun saveInterrupted(").contains("if (partial.isBlank())")
        )
        val screen = source("src/main/java/com/myfriend/app/ui/screens/chat/ChatScreen.kt")
        assertTrue("界面要给「只有工具调用」的中断显示状态占位", screen.contains("InterruptedReply.showsStatusPlaceholder("))
        assertTrue("占位文案走资源", screen.contains("R.string.chat_stopped_status"))
    }

    // ------------------------------------------------------------------ 审计 P2-9

    @Test
    fun `上下文带图有数量与尺寸双限 且发送前重编码`() {
        val context = bodyOf(chatRepo, "private suspend fun buildContext(")
        assertTrue("挑消息走 ImageContextBudget", context.contains("ImageContextBudget.ownersWithImages("))
        assertTrue("整体字符预算逐张校验", context.contains("ImageContextBudget.acceptsEncoded("))
        assertTrue("发送前按目标尺寸重编码（不是直接读原文件）", context.contains("MediaStorage.toContextDataUrl("))
        assertFalse("旧的「原样读字节 + base64」入口已退役", media.contains("fun toDataUrl("))
        assertTrue(
            "重编码要落在 IO 上、按质量阶梯压到单张预算内",
            bodyOf(media, "suspend fun toContextDataUrl(").contains("withContext(Dispatchers.IO)") &&
                media.contains("ImageContextBudget.JPEG_QUALITY_LADDER")
        )
        assertTrue("PNG 也要重编码（截图不压缩是 OOM 主因）", media.contains("Bitmap.CompressFormat.JPEG"))
    }

    // ------------------------------------------------------------------ 审计 P2-13

    @Test
    fun `定位窗口的 DAO 查询有 LIMIT 且只读一页上下文`() {
        assertTrue(
            "有界窗口查询必须带 LIMIT",
            before(daos, "fun observeWindow(", 400).contains("LIMIT :limit")
        )
        assertTrue("定位只需要一个 COUNT 就知道目标下标", daos.contains("suspend fun countBefore("))
        val vm = source("src/main/java/com/myfriend/app/ui/screens/chat/ChatViewModel.kt")
        assertTrue("窗口有上界时界面给「更新的消息」入口", vm.contains("MessagePaging.onJumped("))
        assertTrue("回到最新一屏要复位窗口与游标", vm.contains("fun showLatest()"))
        val screen = source("src/main/java/com/myfriend/app/ui/screens/chat/ChatScreen.kt")
        assertTrue("列表底部渲染「更新的消息」", screen.contains("if (state.hasMoreNewer)"))
        assertTrue("点了回到最新", screen.contains("onShowLatest"))
    }

    // ------------------------------------------------------------------ 安全 P2-6

    @Test
    fun `hasKey 改成能解出非空 Key 才算已配置`() {
        assertTrue(
            "SecretCipher.hasKey 不再只看密文非空",
            bodyOf(secretCipher, "fun hasKey(").let {
                it.contains("ApiKeyPolicy.isUsable(") && it.contains("decrypt(")
            }
        )
        assertTrue(
            "解析配置时解不出来要给出明确错误，而不是当成「没配」",
            bodyOf(apiConfig, "suspend fun resolve(").contains("API_KEY_UNRECOVERABLE")
        )
        assertTrue(
            "聊天链路把这条错误交到界面（资源化文案），不是让它降级成 401",
            bodyOf(chatRepo, "fun generateAssistant(").contains("catch (error: Exception)")
        )
    }

    // ------------------------------------------------------------------ 安全 P2-10

    @Test
    fun `SSE 单行上限是常量且只有一处读行入口`() {
        val sse = source("src/main/java/com/myfriend/app/network/SseLines.kt")
        assertTrue("上限是常量（8MB）", sse.contains("const val MAX_LINE_BYTES: Long = 8L * 1024 * 1024"))
        val adapter = source("src/main/java/com/myfriend/app/network/OpenAiAdapter.kt")
        assertTrue("适配器只通过 SseLines 读行", adapter.contains("SseLines.readLine("))
        assertFalse("不再有裸的 readUtf8Line 调用（会吃掉一整行）", adapter.contains(".readUtf8Line("))
    }

    // ------------------------------------------------------------------ 安全 P2-3

    @Test
    fun `备份导入在读之前就有大小闸`() {
        val section = source("src/main/java/com/myfriend/app/ui/screens/settings/BackupSection.kt")
        assertTrue("按声明的文件大小先拒绝", section.contains("declaredSizeBytes("))
        assertTrue("读的时候按字节设上限", section.contains("BoundedTextRead.read("))
        assertFalse("不再 readText() 整份读进内存", section.contains(".bufferedReader()?.use { it.readText() }"))
        assertTrue("解析前再兜一次字符数", backupCodec.contains("BackupLimits.isTooManyChars("))
        assertTrue("超限给资源化错误", section.contains("BackupFailure.TooLarge("))
    }

    // ------------------------------------------------------------------ 安全 P2-5

    @Test
    fun `备份里不写设备绝对路径`() {
        assertTrue(
            "导出时把绝对路径收成文件名",
            backupCodec.contains("avatarUri = BackupAvatarPath.portable(avatarUri)")
        )
        assertTrue(
            "导入时按文件名在本机头像目录里找回",
            backupRepo.contains("MediaStorage.resolveImportedAvatar(appContext,")
        )
        assertTrue(
            "找回后仍要按归属目录校验（防目录穿越）",
            bodyOf(media, "fun resolveImportedAvatar(").contains("MediaOwnership.isWithin(")
        )
        assertTrue(
            "替换导入保留头像时也要先解析成绝对路径",
            backupRepo.contains("MediaStorage.resolveImportedAvatar(appContext, persona.avatarUri)")
        )
    }

    // ------------------------------------------------------------------ 安全 P2-11

    @Test
    fun `web_fetch 发出去之前校验 scheme 与 host`() {
        assertTrue(
            "抓取前走共用的 URL 校验",
            bodyOf(webTools, "suspend fun fetch(").contains("WebUrlPolicy.check(target)")
        )
        assertTrue(
            "拒绝原因要能上屏（资源化）",
            source("src/main/java/com/myfriend/app/ui/common/ErrorText.kt").contains("NetworkErrorKind.WebUrlRejected")
        )
    }

    // ------------------------------------------------------------------ 审计 P2-14

    @Test
    fun `空备份不再导出空文件`() {
        val block = bodyOf(backupRepo, "suspend fun exportJson(")
        assertTrue("没有任何内容时拒绝导出并给明确错误", block.contains("DomainErrorCode.BACKUP_EMPTY"))
    }

    @Test
    fun `通知能按人格和全部取消`() {
        val notifier = source("src/main/java/com/myfriend/app/util/ChatNotifier.kt")
        assertTrue("按人格取消", notifier.contains("fun cancelByPersona(context: Context, personaId: String)"))
        assertTrue("全部取消", notifier.contains("fun cancelAll(context: Context)"))
        assertTrue(
            "删人格时撤掉他的通知",
            source("src/main/java/com/myfriend/app/data/repo/PersonaRepository.kt").contains("chatNotifier.cancelByPersona(")
        )
        assertTrue("删除全部数据时撤掉所有聊天通知", chatRepo.contains("chatNotifier.cancelAll(appContext)"))
        assertTrue(
            "从首页点进会话也要撤掉那条通知",
            source("src/main/java/com/myfriend/app/ui/screens/chat/ChatViewModel.kt").contains("chatNotifier.cancel(context, sessionId)")
        )
    }

    // ------------------------------------------------------------------ 审计 P2-14（滑窗提示）

    @Test
    fun `首屏滑窗时仍会提示上面还有更早的消息`() {
        val vm = source("src/main/java/com/myfriend/app/ui/screens/chat/ChatViewModel.kt")
        assertTrue(
            "首屏每次窗口变化都重算 hasMoreOlder（只在第一次算会漏掉滑窗）",
            vm.contains("MessagePaging.onFirstWindow(it, rows.size)")
        )
        val screen = source("src/main/java/com/myfriend/app/ui/screens/chat/ChatScreen.kt")
        assertTrue("列表顶部有「加载更早」那一行", screen.contains("LoadEarlierRow("))
    }

    // ------------------------------------------------------------------ 0.11.1 审计：备份 IO（P1-3）

    @Test
    fun `备份的解析 映射 编码都在 IO 上`() {
        val export = bodyOf(backupRepo, "suspend fun exportJson(")
        assertTrue("导出（读库 + 映射 + 编码）要切 IO", export.contains("withContext(Dispatchers.IO)"))

        val preview = bodyOf(backupRepo, "suspend fun preview(")
        assertTrue("预览解码（DOM + 反序列化）要切 IO", preview.contains("withContext(Dispatchers.IO)"))
        assertTrue("解析仍走同一个 BackupCodec", preview.contains("BackupCodec.decode(raw)"))

        val importJson = bodyOf(backupRepo, "suspend fun importJson(")
        assertTrue("导入（解码 + 映射 + 写库）要切 IO", importJson.contains("withContext(Dispatchers.IO)"))
        assertTrue(
            "Room 事务自己会切线程，但解码/映射是 CPU 活，不能留在 Main",
            importJson.indexOf("BackupCodec.decode(raw)") > importJson.indexOf("withContext(Dispatchers.IO)")
        )
        assertTrue(
            "设置页的导入入口不再自己手写解码",
            source("src/main/java/com/myfriend/app/ui/screens/settings/SettingsViewModel.kt")
                .contains("backupRepository.preview(text)")
        )
    }

    // ------------------------------------------------------------------ 0.11.1 审计：主动消息时间戳（P2-1）

    @Test
    fun `主动规则消息也走 MessageOrder`() {
        val proactive = source("src/main/java/com/myfriend/app/data/repo/ProactiveRepository.kt")
        val body = bodyOf(proactive, "private suspend fun fire(")
        assertTrue(
            "主动消息要按会话最新一条 + now 取严格递增的时间戳",
            body.contains("MessageOrder.nextTimestamp(")
        )
        assertTrue("必须读会话里的最新一条", body.contains("messageDao.latestInSession(session.id)?.createdAt"))
        assertFalse("不能再自算 now/now+1（同一毫秒会排到用户消息前面）", body.contains("if (created <= now) now + 1 else created"))
    }

    // ------------------------------------------------------------------ 0.11.1 审计：清空聊天收尾（P2-2）

    @Test
    fun `清空聊天与记忆也会撤通知并结束 openSession`() {
        val body = bodyOf(chatRepo, "suspend fun clearAllChatData()")
        assertTrue("要结束「正开着」的会话", body.contains("openSessionTracker.clearAll()"))
        assertTrue("要撤掉残留通知", body.contains("chatNotifier.cancelAll(appContext)"))
        assertTrue(
            "与删除全部数据走同一套收尾语义",
            bodyOf(chatRepo, "suspend fun wipeAllData()").contains("openSessionTracker.clearAll()")
        )
    }

    // ------------------------------------------------------------------ 0.11.1 审计：备份替换世代（P2-3）

    @Test
    fun `备份替换先 bump 世代并取消在跑的生成`() {
        val body = bodyOf(backupRepo, "suspend fun importBundle(")
        val bumpIndex = body.indexOf("sessionGeneration.bump(sessionId)")
        // 注释里也会提到 database.withTransaction，所以找真正的赋值。
        val transactionIndex = body.indexOf("val erasure = database.withTransaction")
        assertTrue("替换前要对会复用的 sessionId bump 世代", bumpIndex >= 0)
        assertTrue("还要取消在跑的生成", body.contains("chatRepository.cancelGeneration(sessionId)"))
        assertTrue("必须在清空/写回事务之前", bumpIndex < transactionIndex)
        assertTrue("只在替换模式做", body.contains("if (mode == ImportMode.REPLACE)"))
    }

    // ------------------------------------------------------------------ 0.11.1 审计：隐私承诺（P2-5）

    @Test
    fun `purgeVersions 不再吞掉删除异常`() {
        val consolidator = source("src/main/java/com/myfriend/app/data/repo/MemoryReconsolidator.kt")
        val body = bodyOf(consolidator, "suspend fun purgeVersions(")
        assertFalse(
            "记忆旧正文删不掉时不能假装成功（runCatching 还会吞 CancellationException）",
            body.contains("runCatching {")
        )
        assertTrue("失败直接抛给事务", body.contains("memoryVersionDao.deleteAll()"))
        assertTrue(body.contains("memoryVersionDao.deleteOrphans()"))
    }

    // ------------------------------------------------------------------ 0.11.1 审计：API 26–27 图片缩放（P2-10）

    @Test
    fun `旧系统上粗采样之后还会缩到目标尺寸`() {
        val body = source("src/main/java/com/myfriend/app/util/MediaStorage.kt")
            .let { text ->
                val start = text.indexOf("private fun decodeForContext(")
                require(start >= 0)
                text.substring(start, minOf(text.length, start + 2_400))
            }
        assertTrue("API<28 分支要按目标尺寸再缩一次", body.contains("ImageContextBudget.scaledSize("))
        assertTrue("真的创建缩放位图", body.contains("Bitmap.createScaledBitmap("))
        assertTrue("用后释放中间位图", body.contains("decoded.recycle()"))
    }

    // ------------------------------------------------------------------ 0.11.1 审计：选图落盘竞态（P2-12）

    @Test
    fun `选图落盘与登记在不可取消区域`() {
        val viewModel = source("src/main/java/com/myfriend/app/ui/screens/chat/ChatViewModel.kt")
        val addImages = bodyOf(viewModel, "fun addImages(")
        assertTrue("落盘与登记要放在 NonCancellable 里", addImages.contains("withContext(NonCancellable)"))
        assertTrue(
            "落盘必须在这个区域里",
            addImages.indexOf("MediaStorage.persistChatImage(") > addImages.indexOf("withContext(NonCancellable)")
        )
        assertTrue("ViewModel 销毁后要删掉刚写的文件", addImages.contains("draftWindowClosed"))
        assertTrue(
            "onCleared 先立旗子再删已登记的图片",
            bodyOf(viewModel, "override fun onCleared()").let { body ->
                body.indexOf("draftWindowClosed = true") in 0 until body.indexOf("MediaStorage.deleteAttachments(")
            }
        )
        val editor = source("src/main/java/com/myfriend/app/ui/screens/persona/PersonaEditorScreen.kt")
        assertTrue("头像选图同样要 NonCancellable", editor.contains("withContext(NonCancellable)"))
        val editorVm = source("src/main/java/com/myfriend/app/ui/screens/persona/PersonaEditorViewModel.kt")
        assertTrue("晚到的头像登记直接删文件", bodyOf(editorVm, "fun setAvatarUri(").contains("draftAvatarWindowClosed") ||
            bodyOf(editorVm, "fun setAvatarUri(").contains("draftWindowClosed"))
    }

    // ------------------------------------------------------------------ 0.11.1 审计：发布构建（P2-14）

    @Test
    fun `release 构建强制进程内 并且版本号是 0_11_1`() {
        val gradle = source("build.gradle.kts")
        val release = gradle.substringAfter("buildTypes {", "")
            .substringAfter("release {", "")
            .substringBefore("debug {")
        assertTrue(
            "release 不能允许 -PagentCoreRemote=true 把决策请求发到远端",
            release.contains("""buildConfigField("boolean", "AGENT_CORE_REMOTE", "false")""")
        )
        assertTrue(
            "release 不把编译期地址打进包（remote 已强制 false，baseUrl 留空）",
            release.contains("AGENT_CORE_BASE_URL")
        )
        assertTrue("versionCode 必须递增", gradle.contains("versionCode = 16"))
        assertTrue("versionName 是 0.11.1", gradle.contains("""versionName = "0.11.1""""))
    }

    @Test
    fun `远程客户端禁重定向 限制响应体 回退可观测`() {
        val client = source("src/main/java/com/myfriend/app/core/AgentCoreClient.kt")
        assertTrue("禁跟随重定向", client.contains(".followRedirects(false)"))
        assertTrue("禁跟随 SSL 重定向", client.contains(".followSslRedirects(false)"))
        assertTrue("响应体有上限", client.contains("MAX_RESPONSE_BYTES"))
        assertTrue("读响应时累计检查大小", client.contains("readBoundedBody(response)"))
        assertTrue("回退计数", client.contains("AgentCoreRemoteStats.recordFallback(action)"))
        assertTrue("成功计数", client.contains("AgentCoreRemoteStats.recordSuccess(action)"))
        assertTrue("日志里截断服务端文案", client.contains("boundedForLog("))
        assertTrue(
            "计数器是独立可测的单例",
            source("src/main/java/com/myfriend/app/core/AgentCoreRemoteStats.kt")
                .contains("object AgentCoreRemoteStats")
        )
    }

    // ------------------------------------------------------------------ 工具

    private fun projectRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/myfriend/app").isDirectory }
            ?: error("找不到工程根目录：${File(".").absolutePath}")
    }

    private fun source(relative: String): String = File(projectRoot(), relative).readText()

    private fun before(text: String, marker: String, chars: Int): String {
        val index = text.indexOf(marker)
        require(index >= 0) { "找不到 $marker" }
        return text.substring(maxOf(0, index - chars), index)
    }

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
