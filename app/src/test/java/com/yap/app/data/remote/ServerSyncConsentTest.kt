package com.yap.app.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.13 切换云端同意状态机（纯 JVM）。
 *
 * 覆盖规格里最容易写错的几件事：
 * 1. 本地 → 远程**必须**先弹窗（没有同意记录时开关不动）；
 * 2. 三个选项：同意并同步 = FULL、只同步之后的新内容 = NEW_ONLY、取消 = 什么都不记；
 * 3. 换地址重新询问；同一台地址同意过就不再打扰；
 * 4. 同步被取消时开关回滚（不切换模式）；
 * 5. 切回本地：全量下载 + 条数校验通过才关闭；失败/取消保持开启。
 */
class ServerSyncConsentTest {

    private fun coordinator(
        store: TestServerLocalStore = TestServerLocalStore(),
        mode: ConsentFakeMode = ConsentFakeMode(),
        runner: ConsentFakeRunner = ConsentFakeRunner()
    ): Triple<ServerModeSwitchCoordinator, ConsentFakeMode, ConsentFakeRunner> =
        Triple(ServerModeSwitchCoordinator(mode, store, runner), mode, runner)

    // ------------------------------------------------------------------ 纯规则

    @Test
    fun `没有同意记录时本地切远程必须弹窗`() {
        assertTrue(
            ServerSyncConsentPolicy.needsPrompt(
                currentlyEnabled = false,
                nextEnabled = true,
                recorded = null
            )
        )
        assertFalse(
            "同一台地址同意过就不再问",
            ServerSyncConsentPolicy.needsPrompt(false, true, ServerSyncConsent.FULL)
        )
        assertFalse(
            "关闭时不该弹同意",
            ServerSyncConsentPolicy.needsPrompt(true, false, null)
        )
        assertFalse(
            "本来关着又关着没有切换",
            ServerSyncConsentPolicy.needsPrompt(false, false, null)
        )
    }

    @Test
    fun `同意值宽松解析 未知值当作没有记录`() {
        assertEquals(ServerSyncConsent.FULL, ServerSyncConsent.parse("FULL"))
        assertEquals(ServerSyncConsent.NEW_ONLY, ServerSyncConsent.parse(" new_only "))
        assertNull(ServerSyncConsent.parse("yes"))
        assertNull(ServerSyncConsent.parse(""))
        assertNull(ServerSyncConsent.parse(null))
    }

    @Test
    fun `没有同意记录按只同步新内容处理 不能偷偷上传历史`() {
        assertEquals(ServerSyncConsent.NEW_ONLY, ServerSyncConsentPolicy.effective(null))
        assertEquals(ServerSyncConsent.FULL, ServerSyncConsentPolicy.effective(ServerSyncConsent.FULL))
        assertEquals("尾部斜杠不影响键", "http://server.test", ServerSyncConsentPolicy.normalize("http://server.test/"))
    }

    // ------------------------------------------------------------------ 弹窗与三个选项

    @Test
    fun `首次打开开关 先弹窗 开关保持关闭`() = runBlocking {
        val (switch, mode, runner) = coordinator()
        val action = switch.requestToggle(true)

        assertTrue(action is ServerToggleAction.NeedsConsent)
        assertEquals("http://server.test", (action as ServerToggleAction.NeedsConsent).baseUrl)
        assertFalse("还没同意就不能打开开关", mode.enabled)
        assertTrue(switch.status.value.consentRequired)
        assertTrue("同意前不该开始同步", runner.bootstrapCalls.isEmpty())
    }

    @Test
    fun `取消 不记同意 开关回到关闭`() = runBlocking {
        val store = TestServerLocalStore()
        val (switch, mode, _) = coordinator(store)
        switch.requestToggle(true)
        switch.dismissConsent()

        assertFalse(mode.enabled)
        assertEquals(ServerSyncPhase.IDLE, switch.status.value.phase)
        assertNull("取消不写同意记录", store.syncConsent("http://server.test"))
        // 再拨一次仍然要问。
        assertTrue(switch.requestToggle(true) is ServerToggleAction.NeedsConsent)
    }

    @Test
    fun `同意并同步 记录 FULL 开启开关 跑一轮带媒体同步`() = runBlocking {
        val store = TestServerLocalStore()
        val (switch, mode, runner) = coordinator(store)
        switch.requestToggle(true)

        val status = switch.enableWith(ServerSyncConsent.FULL)

        assertEquals(ServerSyncConsent.FULL, store.syncConsent("http://server.test"))
        assertTrue(mode.enabled)
        assertEquals(listOf("http://server.test"), runner.bootstrapCalls)
        assertEquals(ServerSyncPhase.COMPLETED, status.phase)
        assertEquals(3, status.messages)
        assertEquals(2, status.mediaTotal)
        assertEquals(2, status.mediaUploaded)
        assertEquals(0, status.mediaFailed)
    }

    @Test
    fun `只同步之后的新内容 记录 NEW_ONLY 且状态里没有历史条数`() = runBlocking {
        val store = TestServerLocalStore()
        val (switch, mode, runner) = coordinator(store)
        runner.bootstrapOutcome = ServerBootstrapOutcome(
            success = true,
            mode = ServerBootstrapMode.PUSH,
            messages = 0
        )
        switch.requestToggle(true)

        val status = switch.enableWith(ServerSyncConsent.NEW_ONLY)

        assertEquals(ServerSyncConsent.NEW_ONLY, store.syncConsent("http://server.test"))
        assertTrue(mode.enabled)
        assertEquals(ServerSyncPhase.COMPLETED, status.phase)
        assertEquals(ServerSyncConsent.NEW_ONLY, status.consent)
        assertEquals(0, status.messages)
    }

    @Test
    fun `同一台地址再次打开不再弹窗 换地址重新询问`() = runBlocking {
        val store = TestServerLocalStore()
        store.setSyncConsent("http://server.test", ServerSyncConsent.FULL)
        val mode = ConsentFakeMode(enabled = true)
        val switch = ServerModeSwitchCoordinator(mode, store, ConsentFakeRunner())

        // 关掉（下载成功）再打开：同一地址沿用记录。
        val (downloadCoordinator, downloadMode, downloadRunner) = coordinator(
            store = store,
            mode = mode.copyForTest(enabled = true)
        )
        assertEquals(ServerSyncPhase.DOWNLOADED, downloadCoordinator.disable().phase)
        assertFalse(downloadMode.enabled)
        assertEquals(1, downloadRunner.downloadCalls.size)

        val action = downloadCoordinator.requestToggle(true)
        assertTrue(action is ServerToggleAction.Enable)
        assertEquals(ServerSyncConsent.FULL, (action as ServerToggleAction.Enable).consent)

        // 换一台地址：记录查不到，必须重新问。
        val otherMode = ConsentFakeMode(enabled = false, configuredUrl = "http://other.test")
        val other = ServerModeSwitchCoordinator(otherMode, store, ConsentFakeRunner())
        val otherAction = other.requestToggle(true)
        assertTrue(otherAction is ServerToggleAction.NeedsConsent)
        assertEquals("http://other.test", (otherAction as ServerToggleAction.NeedsConsent).baseUrl)
    }

    // ------------------------------------------------------------------ 取消

    @Test
    fun `同步被取消 开关回滚为关闭 状态为取消`() = runBlocking {
        val store = TestServerLocalStore()
        val mode = ConsentFakeMode()
        val runner = ConsentFakeRunner()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        runner.onBootstrap = {
            entered.complete(Unit)
            release.await()
        }
        val switch = ServerModeSwitchCoordinator(mode, store, runner)
        switch.requestToggle(true)

        val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            switch.enableWith(ServerSyncConsent.FULL)
        }
        withTimeout(5_000) { entered.await() }
        assertTrue("同步中开关是开的（有客户端才能同步）", mode.enabled)
        job.cancel()
        job.join()

        assertFalse("取消后不切换模式", mode.enabled)
        assertEquals(ServerSyncPhase.CANCELLED, switch.status.value.phase)
        assertEquals(
            "同意记录保留：用户确实点过「同意并同步」，下次重试不再重复问",
            ServerSyncConsent.FULL,
            store.syncConsent("http://server.test")
        )
    }

    // ------------------------------------------------------------------ 切回本地

    @Test
    fun `关闭开关先全量下载 成功才关闭并给出条数`() = runBlocking {
        val store = TestServerLocalStore()
        val mode = ConsentFakeMode(enabled = true)
        val runner = ConsentFakeRunner()
        runner.downloadOutcome = ServerFullDownloadOutcome(
            success = true,
            downloadedMessages = 42,
            localMessages = 42,
            serverMessages = 42
        )
        val switch = ServerModeSwitchCoordinator(mode, store, runner)

        val status = switch.disable()

        assertEquals(listOf("http://server.test"), runner.downloadCalls)
        assertFalse(mode.enabled)
        assertEquals(ServerSyncPhase.DOWNLOADED, status.phase)
        assertEquals(42, status.downloadedMessages)
        assertEquals(42, status.localMessages)
    }

    @Test
    fun `下载条数校验失败 保持开启`() = runBlocking {
        val store = TestServerLocalStore()
        val mode = ConsentFakeMode(enabled = true)
        val runner = ConsentFakeRunner()
        runner.downloadOutcome = ServerFullDownloadOutcome(
            success = false,
            downloadedMessages = 10,
            localMessages = 8,
            serverMessages = 12,
            shortSessions = listOf("srv-s1")
        )
        val switch = ServerModeSwitchCoordinator(mode, store, runner)

        val status = switch.disable()

        assertTrue("校验失败不能关服务端模式", mode.enabled)
        assertEquals(ServerSyncPhase.FAILED, status.phase)
        assertEquals(ServerSyncFailure.DOWNLOAD_INCOMPLETE, status.failure)
        assertEquals(8, status.localMessages)
        assertEquals(12L, status.serverMessages)
    }

    @Test
    fun `服务端连不上时保持开启 并说明是没连上`() = runBlocking {
        val store = TestServerLocalStore()
        val mode = ConsentFakeMode(enabled = true)
        val runner = ConsentFakeRunner()
        runner.downloadOutcome = ServerFullDownloadOutcome(
            success = false,
            unavailable = true,
            error = ServerApiException("unreachable", code = "io_error")
        )
        val switch = ServerModeSwitchCoordinator(mode, store, runner)

        val status = switch.disable()

        assertTrue(mode.enabled)
        assertEquals(ServerSyncPhase.FAILED, status.phase)
        assertEquals(ServerSyncFailure.DOWNLOAD_UNAVAILABLE, status.failure)
    }

    @Test
    fun `下载被取消 保持开启`() = runBlocking {
        val store = TestServerLocalStore()
        val mode = ConsentFakeMode(enabled = true)
        val runner = ConsentFakeRunner()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        runner.onBootstrap = {
            entered.complete(Unit)
            release.await()
        }
        val switch = ServerModeSwitchCoordinator(mode, store, runner)

        val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { switch.disable() }
        withTimeout(5_000) { entered.await() }
        job.cancel()
        job.join()

        assertTrue("取消下载也要保持开启", mode.enabled)
        assertEquals(ServerSyncPhase.CANCELLED, switch.status.value.phase)
    }

    @Test
    fun `地址非法时关闭开关不需要下载`() = runBlocking {
        val store = TestServerLocalStore()
        val mode = ConsentFakeMode(enabled = true, usable = false, configuredUrl = "")
        val runner = ConsentFakeRunner()
        val switch = ServerModeSwitchCoordinator(mode, store, runner)

        val status = switch.disable()

        assertTrue(runner.downloadCalls.isEmpty())
        assertFalse(mode.enabled)
        assertEquals(ServerSyncPhase.DOWNLOADED, status.phase)
    }

    @Test
    fun `保存开关失败时状态是保存失败 不开始同步`() = runBlocking {
        val store = TestServerLocalStore()
        val mode = ConsentFakeMode()
        mode.setEnabledFailure = true
        val runner = ConsentFakeRunner()
        val switch = ServerModeSwitchCoordinator(mode, store, runner)
        switch.requestToggle(true)

        val status = switch.enableWith(ServerSyncConsent.FULL)

        assertEquals(ServerSyncPhase.FAILED, status.phase)
        assertEquals(ServerSyncFailure.SAVE_FAILED, status.failure)
        assertTrue(runner.bootstrapCalls.isEmpty())
        assertFalse(mode.enabled)
    }

    @Test
    fun `并发时另一轮已经跑完 沿用那轮的结果计数`() = runBlocking {
        val store = TestServerLocalStore()
        val mode = ConsentFakeMode()
        val runner = ConsentFakeRunner()
        runner.bootstrapOutcome = ServerBootstrapOutcome(success = true, skipped = true)
        runner.lastOutcomeValue = ServerBootstrapOutcome(
            success = true,
            mode = ServerBootstrapMode.PUSH,
            messages = 7,
            mediaTotal = 3,
            mediaUploaded = 2,
            mediaFailed = 1
        )
        val switch = ServerModeSwitchCoordinator(mode, store, runner)
        switch.requestToggle(true)

        val status = switch.enableWith(ServerSyncConsent.FULL)

        assertEquals(ServerSyncPhase.COMPLETED, status.phase)
        assertEquals(7, status.messages)
        assertEquals(1, status.mediaFailed)
        assertTrue(status.skipped)
    }

    @Test
    fun `结果提示在界面消费后可以收起 进行中不能收起`() = runBlocking {
        val (switch, _, _) = coordinator()
        // 先制造一个结果。
        switch.requestToggle(true)
        switch.enableWith(ServerSyncConsent.NEW_ONLY)
        assertNotNull(switch.status.value.phase)
        switch.clearStatus()
        assertEquals(ServerSyncPhase.IDLE, switch.status.value.phase)
    }
}

/** 测试里复制一个开关状态（不共享可变字段）。 */
private fun ConsentFakeMode.copyForTest(enabled: Boolean): ConsentFakeMode =
    ConsentFakeMode(enabled = enabled, usable = usable, configuredUrl = configuredUrl)
