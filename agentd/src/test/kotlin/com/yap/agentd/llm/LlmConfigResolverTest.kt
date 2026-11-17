package com.yap.agentd.llm

import com.yap.agentd.TestSupport
import com.yap.agentd.store.ApiConfig
import com.yap.agentd.store.FileStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path

/**
 * LLM 配置的**优先级**（任务书第 1 条的核心）：
 *
 * | 部署 | 首选 | 回落 |
 * | --- | --- | --- |
 * | 本机（127.0.0.1） | 客户端推送的 `api_configs.json` + `secrets.json` | 服务端 `--llm-*` |
 * | 远程（0.0.0.0/网卡地址） | 服务端 `--llm-*` / `YAP_LLM_*` | **没有**（绝不读客户端） |
 *
 * 这里用真实临时目录（`FileStore` 会种 3 个人格）：配置是磁盘数据，不是 mock。
 */
class LlmConfigResolverTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_000L)

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-llm-config-test")
        clock.now = 1_000L
        store = FileStore.open(root, clock.reader)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun pushConfig(
        id: String,
        baseUrl: String,
        model: String,
        key: String? = null
    ) {
        store.putApiConfig(
            ApiConfig(
                id = id,
                name = id,
                provider = "OPENAI",
                baseUrl = baseUrl,
                model = model,
                createdAt = 0,
                updatedAt = 0
            )
        )
        if (key != null) store.setApiKey(id, key)
        clock.advance(10L) // 让 updatedAt 严格递增，排序可重现
    }

    @Test
    fun `loopback prefers the client pushed config and its secret`() {
        pushConfig("cfg-1", "http://127.0.0.1:9999/v1", "deepseek-chat", key = "sk-from-client")
        val resolver = LlmConfigResolver(store, loopback = true)

        val config = resolver.resolve()

        assertEquals(LlmConfig.Source.CLIENT, config.source)
        assertEquals("cfg-1", config.configId)
        assertEquals("deepseek-chat", config.model)
        assertEquals("sk-from-client", config.apiKey)
        assertEquals("http://127.0.0.1:9999/v1/chat/completions", config.endpoint())
    }

    @Test
    fun `loopback picks the most recently updated usable config`() {
        pushConfig("cfg-old", "http://127.0.0.1:9001/v1", "old-model", key = "sk-old")
        pushConfig("cfg-new", "http://127.0.0.1:9002/v1", "new-model", key = "sk-new")

        val config = LlmConfigResolver(store, loopback = true).resolve()

        assertEquals("cfg-new", config.configId)
        assertEquals("new-model", config.model)
    }

    @Test
    fun `loopback ignores configs without base url or model and keeps looking`() {
        pushConfig("cfg-broken", "", "some-model", key = "sk-broken")
        pushConfig("cfg-good", "http://127.0.0.1:9003/v1", "good-model", key = "sk-good")

        assertEquals("cfg-good", LlmConfigResolver(store, loopback = true).resolve().configId)
    }

    @Test
    fun `loopback falls back to server config when the client pushed nothing`() {
        val resolver = LlmConfigResolver(
            store = store,
            loopback = true,
            server = ServerLlmConfig(baseUrl = "https://api.example.com/v1", apiKey = "sk-server", model = "server-model")
        )

        val config = resolver.resolve()

        assertEquals(LlmConfig.Source.SERVER, config.source)
        assertEquals("server-model", config.model)
        assertEquals("sk-server", config.apiKey)
        assertNull(config.configId)
    }

    @Test
    fun `remote deployment never reads the client pushed config`() {
        // 即使磁盘上有客户端配置（和 key），远程模式也只能用服务端自己的配置。
        pushConfig("cfg-client", "http://127.0.0.1:9004/v1", "client-model", key = "sk-client")
        val resolver = LlmConfigResolver(
            store = store,
            loopback = false,
            server = ServerLlmConfig(baseUrl = "https://server.example.com/v1", apiKey = "sk-server", model = "server-model")
        )

        val config = resolver.resolve()

        assertEquals(LlmConfig.Source.SERVER, config.source)
        assertEquals("server-model", config.model)
        assertEquals("https://server.example.com/v1/chat/completions", config.endpoint())
        assertTrue("客户端配置在远程模式里不可用", resolver.clientConfig() != null)
    }

    @Test
    fun `remote deployment without server config reports not configured`() {
        pushConfig("cfg-client", "http://127.0.0.1:9005/v1", "client-model", key = "sk-client")
        val resolver = LlmConfigResolver(store, loopback = false)

        val failure = runCatching { resolver.resolve() }.exceptionOrNull()

        assertTrue(failure is LlmException)
        val llm = failure as LlmException
        assertEquals(LlmErrorKind.NOT_CONFIGURED, llm.kind)
        assertEquals("llm_not_configured", llm.code)
        assertTrue("要说明远程部署不读客户端配置：${llm.userMessage()}", llm.userMessage().contains("不会读取客户端"))
    }

    @Test
    fun `loopback without any config explains the two ways to configure it`() {
        val failure = runCatching { LlmConfigResolver(store, loopback = true).resolve() }.exceptionOrNull()

        assertTrue(failure is LlmException)
        val message = (failure as LlmException).userMessage()
        assertTrue(message.contains("api_configs.json"))
        assertTrue(message.contains("--llm-base-url"))
    }

    @Test
    fun `incomplete explicit server config fails instead of being ignored`() {
        val resolver = LlmConfigResolver(
            store = store,
            loopback = false,
            server = ServerLlmConfig(baseUrl = "https://api.example.com/v1")
        )

        val failure = runCatching { resolver.resolve() }.exceptionOrNull()

        assertTrue(failure is LlmException)
        val llm = failure as LlmException
        assertEquals(LlmErrorKind.INVALID_CONFIG, llm.kind)
        assertTrue(llm.userMessage().contains("--llm-model"))
    }

    @Test
    fun `environment variables configure the server side`() {
        val resolver = LlmConfigResolver(
            store = store,
            loopback = false,
            env = mapOf(
                "YAP_LLM_BASE_URL" to "https://env.example.com/v1",
                "YAP_LLM_KEY" to "sk-env",
                "YAP_LLM_MODEL" to "env-model"
            )::get
        )

        val config = resolver.resolve()

        assertEquals("env-model", config.model)
        assertEquals("sk-env", config.apiKey)
        assertEquals("https://env.example.com/v1/chat/completions", config.endpoint())
    }

    @Test
    fun `endpoint accepts a base url that already includes chat completions`() {
        val config = LlmConfig(baseUrl = "https://api.example.com/v1/chat/completions/", model = "m")
        assertEquals("https://api.example.com/v1/chat/completions", config.endpoint())
    }

    @Test
    fun `redacted never contains the api key`() {
        val config = LlmConfig(
            baseUrl = "https://api.example.com/v1",
            model = "m",
            apiKey = "sk-super-secret",
            source = LlmConfig.Source.CLIENT
        )
        assertTrue(!config.redacted().contains("sk-super-secret"))
        assertTrue(config.redacted().contains("key 已配置"))
    }

    @Test
    fun `loopback detection matches the main option host`() {
        assertTrue(LlmConfigResolver.isLoopbackHost("127.0.0.1"))
        assertTrue(LlmConfigResolver.isLoopbackHost("localhost"))
        assertTrue(LlmConfigResolver.isLoopbackHost("127.0.0.2"))
        assertTrue(LlmConfigResolver.isLoopbackHost("::1"))
        assertTrue(!LlmConfigResolver.isLoopbackHost("0.0.0.0"))
        assertTrue(!LlmConfigResolver.isLoopbackHost("192.168.1.10"))
    }
}
