package com.yap.app.domain

import com.yap.app.data.local.ApiConfigEntity
import com.yap.app.data.local.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份导入不改写「这台设备对这个服务商的信任决定」（安全审计 P1-0）。
 *
 * 旧行为：`mergeInto` 无条件覆盖 `baseUrl` / `allowInsecureHttp`，却保留本机 Key。
 * 攻击者只要知道本机某条配置的 id（任何导出过的备份里都明文带着），拼一份备份
 * 把地址改成自己的主机、`allowInsecureHttp=true`，用户「合并导入」之后下一次聊天
 * 就把真实 Key 与全部上下文发过去，或被静默降级成明文。
 */
class BackupApiConfigMergeTest {

    private fun local() = ApiConfigEntity(
        id = "config-1",
        name = "DeepSeek 官方",
        provider = ProviderType.OPENAI,
        baseUrl = "https://api.deepseek.com",
        apiKeyCipher = "LOCAL-CIPHER",
        model = "deepseek-chat",
        customHeadersJson = """{"X-Team":"local"}""",
        allowInsecureHttp = false,
        temperature = 0.8,
        topP = 0.95,
        maxOutputTokens = 2048,
        frequencyPenalty = 0.1,
        presencePenalty = 0.2,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_100_000L,
        webToolsEnabled = true,
        webToolsBaseUrl = "https://api.deepseek.com",
        webToolsApiKeyCipher = "LOCAL-WEB-CIPHER"
    )

    @Test
    fun `备份里的地址不会被采纳`() {
        val incoming = local().toBackupApiConfig().copy(
            baseUrl = "https://attacker.example.com",
            allowInsecureHttp = true,
            model = "attacker-model"
        )
        val merged = incoming.mergeInto(local())

        assertEquals(
            "地址必须保留本机值：否则下一次请求就把真实 Key 发到攻击者主机",
            "https://api.deepseek.com",
            merged.entity.baseUrl
        )
        assertTrue("要告诉用户「地址没被改」", merged.addressKept)
        assertFalse("明文开关不能被备份打开", merged.entity.allowInsecureHttp)
        assertTrue("要告诉用户「明文没被打开」", merged.insecureIgnored)
        assertEquals("非敏感字段（模型）仍然跟着备份走", "attacker-model", merged.entity.model)
        assertEquals("本机 Key 不能被备份抹掉", "LOCAL-CIPHER", merged.entity.apiKeyCipher)
        assertEquals("本机自定义 Headers 不能被备份抹掉", """{"X-Team":"local"}""", merged.entity.customHeadersJson)
    }

    @Test
    fun `地址与明文都没变时不产生告警`() {
        val incoming = local().toBackupApiConfig().copy(model = "deepseek-reasoner")
        val merged = incoming.mergeInto(local())

        assertFalse(merged.addressKept)
        assertFalse(merged.insecureIgnored)
        assertEquals("deepseek-reasoner", merged.entity.model)
    }

    @Test
    fun `备份想关掉本机的明文开关 也不采纳`() {
        val insecureLocal = local().copy(allowInsecureHttp = true)
        val incoming = insecureLocal.toBackupApiConfig().copy(allowInsecureHttp = false)
        val merged = incoming.mergeInto(insecureLocal)

        assertTrue("明文是用户在本机的信任决定，备份不能替用户关掉/打开", merged.entity.allowInsecureHttp)
        assertFalse(merged.insecureIgnored)
    }

    @Test
    fun `联网工具地址同样不被备份改写`() {
        val incoming = local().toBackupApiConfig().copy(
            webToolsBaseUrl = "https://attacker.example.com/tools"
        )
        val merged = incoming.mergeInto(local())

        assertEquals("https://api.deepseek.com", merged.entity.webToolsBaseUrl)
        assertTrue("联网工具地址也是一个「会被送 Key 的主机」", merged.addressKept)
        assertEquals("LOCAL-WEB-CIPHER", merged.entity.webToolsApiKeyCipher)
    }

    @Test
    fun `新配置正常导入 但不带 Key 也不打开明文`() {
        val incoming = local().toBackupApiConfig().copy(
            id = "config-new",
            baseUrl = "https://api.other.example.com",
            allowInsecureHttp = true
        )
        val merged = incoming.toNewEntity()

        assertEquals("新 id 的配置正常导入", "https://api.other.example.com", merged.entity.baseUrl)
        assertNull("密钥为空，等用户在这台手机上重新填", merged.entity.apiKeyCipher)
        assertNull(merged.entity.webToolsApiKeyCipher)
        assertEquals("{}", merged.entity.customHeadersJson)
        assertFalse("新配置也不能因备份打开明文", merged.entity.allowInsecureHttp)
        assertTrue("要提示用户「备份要开明文但没采纳」", merged.insecureIgnored)
    }

    @Test
    fun `备份里的空地址不会把本机地址清掉`() {
        val incoming = local().toBackupApiConfig().copy(baseUrl = "")
        val merged = incoming.mergeInto(local())
        assertEquals("https://api.deepseek.com", merged.entity.baseUrl)
        assertFalse("空值不算「改了地址」", merged.addressKept)
    }

    @Test
    fun `尾部斜杠的差异不算改地址`() {
        val incoming = local().toBackupApiConfig().copy(baseUrl = "https://api.deepseek.com/")
        val merged = incoming.mergeInto(local())
        assertFalse(merged.addressKept)
        assertEquals("https://api.deepseek.com", merged.entity.baseUrl)
    }
}
