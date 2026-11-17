package com.yap.app.data.remote

import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 客户端模型配置推送（0.13，任务 1）。
 *
 * 钉住三件事：
 * 1. **只有回环地址才推**：远程/局域网一次都不发（远端是另一台机器，Key 不能交给它）；
 * 2. **本机推送**：请求体包含 baseUrl/model/apiKey/provider；
 * 3. **失败不阻断**：失败只返回 `pushed = false`，不抛；同一份配置只成功推一次，
 *    失败不记指纹（下次连接成功会重试）。
 */
class ServerLlmConfigPushTest {

    private fun local(baseUrl: String = "http://127.0.0.1:8765", model: String = "deepseek-chat") =
        LocalLlmConfig(baseUrl = baseUrl, model = model, apiKey = "sk-test", provider = "OPENAI")

    // ------------------------------------------------------------------ 策略

    @Test
    fun `回环地址才推送`() {
        assertTrue(ServerLlmConfigPushPolicy.shouldPush("http://127.0.0.1:8765", enabled = true))
        assertTrue(ServerLlmConfigPushPolicy.shouldPush("http://127.0.0.2:8765", enabled = true))
        assertTrue(ServerLlmConfigPushPolicy.shouldPush("http://localhost:8765", enabled = true))
        assertTrue(ServerLlmConfigPushPolicy.shouldPush("http://[::1]:8765", enabled = true))
        assertTrue(ServerLlmConfigPushPolicy.shouldPush("http://10.0.2.2:8765", enabled = true))
        assertTrue(ServerLlmConfigPushPolicy.shouldPush("https://localhost", enabled = true))
    }

    @Test
    fun `远程与局域网地址不推送`() {
        // 局域网也是「另一台机器」：推送等于把 Key 交给它，不能只因为同网段就放行。
        assertFalse(ServerLlmConfigPushPolicy.shouldPush("http://192.168.1.10:8765", enabled = true))
        assertFalse(ServerLlmConfigPushPolicy.shouldPush("https://example.com", enabled = true))
        assertFalse(ServerLlmConfigPushPolicy.shouldPush("http://10.0.0.5:8765", enabled = true))
        assertFalse(ServerLlmConfigPushPolicy.shouldPush("", enabled = true))
        assertFalse(ServerLlmConfigPushPolicy.shouldPush("not a url", enabled = true))
    }

    @Test
    fun `开关关闭时不推送`() {
        assertFalse(ServerLlmConfigPushPolicy.shouldPush("http://127.0.0.1:8765", enabled = false))
    }

    // ------------------------------------------------------------------ 编排

    @Test
    fun `本机地址推送一次 内容与指纹去重`() = runBlocking {
        var resolved = 0
        val sent = mutableListOf<LocalLlmConfig>()
        val push = ServerLlmConfigPush(
            resolve = { resolved++; local() },
            send = { sent += it }
        )

        val first = push.pushIfLocal("http://127.0.0.1:8765", enabled = true)
        assertEquals("pushed", first.reason)
        assertTrue(first.pushed)
        assertEquals(1, sent.size)
        assertEquals("sk-test", sent.single().apiKey)

        // 同一份配置再调一次：不再发（解析本身很便宜，但指纹相同就不该再打服务端）。
        val second = push.pushIfLocal("http://127.0.0.1:8765", enabled = true)
        assertFalse(second.pushed)
        assertEquals("unchanged", second.reason)
        assertTrue(resolved >= 1)
        assertEquals(1, sent.size)
    }

    @Test
    fun `远程地址不解析配置也不发请求`() = runBlocking {
        var resolved = 0
        var sent = 0
        val push = ServerLlmConfigPush(
            resolve = { resolved++; local() },
            send = { sent++ }
        )

        val outcome = push.pushIfLocal("https://example.com", enabled = true)

        assertFalse(outcome.pushed)
        assertEquals("not_local", outcome.reason)
        assertEquals(0, resolved)
        assertEquals(0, sent)
    }

    @Test
    fun `本机没有可用配置时安静跳过`() = runBlocking {
        var sent = 0
        val push = ServerLlmConfigPush(resolve = { null }, send = { sent++ })

        val outcome = push.pushIfLocal("http://127.0.0.1:8765", enabled = true)

        assertFalse(outcome.pushed)
        assertEquals("no_local_config", outcome.reason)
        assertEquals(0, sent)
    }

    @Test
    fun `推送失败不抛 也不记指纹 下次会重试`() = runBlocking {
        var attempts = 0
        val logs = mutableListOf<String>()
        val push = ServerLlmConfigPush(
            resolve = { local() },
            send = {
                attempts++
                if (attempts == 1) throw ServerApiException("remote disallowed", code = "remote_config_disallowed", status = 403)
            },
            log = { logs += it }
        )

        val failed = push.pushIfLocal("http://127.0.0.1:8765", enabled = true)
        assertFalse("失败不能阻断任何调用方", failed.pushed)
        assertEquals("failed:remote_config_disallowed", failed.reason)
        assertTrue(logs.any { it.contains("remote_config_disallowed") })

        // 第二次（比如用户改了服务端配置/重连）应该真的再试一次，并成功。
        val retried = push.pushIfLocal("http://127.0.0.1:8765", enabled = true)
        assertTrue(retried.pushed)
        assertEquals(2, attempts)
    }

    @Test
    fun `配置变化会重新推送`() = runBlocking {
        var current = local(model = "model-a")
        val sent = mutableListOf<String>()
        val push = ServerLlmConfigPush(resolve = { current }, send = { sent += it.model })

        push.pushIfLocal("http://127.0.0.1:8765", enabled = true)
        current = local(model = "model-b")
        val outcome = push.pushIfLocal("http://127.0.0.1:8765", enabled = true)

        assertTrue(outcome.pushed)
        assertEquals(listOf("model-a", "model-b"), sent)
    }

    @Test
    fun `指纹里不出现明文 Key`() = runBlocking {
        // 只是结构上的保险：toString / 日志都不该带出 Key。
        val config = local().copy(apiKey = "sk-super-secret")
        val text = ServerLlmConfigWrite(
            baseUrl = config.baseUrl,
            model = config.model,
            apiKey = config.apiKey,
            provider = config.provider
        ).toString()
        assertFalse(text.contains("sk-super-secret"))
        assertTrue(text.contains("<redacted>"))
    }

    @Test
    fun `三个连接成功点都接了推送`() {
        // 运行时（开开关/回前台）、开关状态机（bootstrap 成功）、设置页（测试连接成功）。
        val runtime = source("src/main/java/com/yap/app/data/remote/ServerModeRuntime.kt")
        assertTrue("运行时连接成功后要推送", runtime.contains("llmConfig.pushIfLocal()"))
        val coordinator = source("src/main/java/com/yap/app/data/remote/ServerModeSwitchCoordinator.kt")
        assertTrue("开关打开成功后要推送", coordinator.contains("llmConfig?.pushIfLocal()"))
        val settings = source("src/main/java/com/yap/app/ui/screens/settings/SettingsViewModel.kt")
        assertTrue("测试连接成功后要推送", settings.contains("llmConfigPusher.pushIfLocal()"))
        val di = source("src/main/java/com/yap/app/di/AppModule.kt")
        assertTrue("生产装配要传推送器", di.contains("ServerLlmConfigPusher"))
    }

    /** 读 `app/` 源码（与项目其它 contract 测试同一套根目录定位）。 */
    private fun source(relative: String): String {
        val root = listOf(File("."), File(".."))
            .firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到工程根目录")
        return File(root, relative).readText()
    }

    @Test
    fun `URL 主机解析为空时按非回环处理`() {
        assertFalse(ServerLlmConfigPushPolicy.isLoopbackHost(null))
        assertFalse(ServerLlmConfigPushPolicy.isLoopbackHost(""))
        assertFalse(ServerLlmConfigPushPolicy.shouldPush(null, enabled = true))
    }
}
