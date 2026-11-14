package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「Key 到底配好了没有」的三态（安全审计 P2-6）。
 *
 * 只看密文非空会把「Keystore 失效」显示成「已保存」，用户发消息只会收到 401。
 */
class ApiKeyStateTest {

    @Test
    fun `没有密文就是未配置`() {
        assertEquals(ApiKeyState.MISSING, ApiKeyPolicy.evaluate(null, null))
        assertEquals(ApiKeyState.MISSING, ApiKeyPolicy.evaluate("", "sk-x"))
        assertEquals(ApiKeyState.MISSING, ApiKeyPolicy.evaluate("   ", null))
    }

    @Test
    fun `解得出来才算已配置`() {
        assertEquals(ApiKeyState.USABLE, ApiKeyPolicy.evaluate("cipher", "sk-123"))
        assertTrue(ApiKeyPolicy.isUsable("cipher", "sk-123"))
    }

    /**
     * 安全 P2-6 / 审计 P2-5：界面必须接三态。
     *
     * 0.11.0 只修了数据层：`ApiKeyState` / `keyState()` 全仓没有调用点，配置页仍用
     * `hasApiKey`（密文非空）显示「密钥已加密保存」。Keystore 失效时用户看到「已保存」，
     * 发消息才收到「无法解密」——得先知道要重新填一次。
     *
     * 这里用源码断言钉住接线（本项目没有 Compose UI 测试环境）；文案资源化由
     * `StringResourceParityTest` 保证中英都有。
     */
    @Test
    fun `界面用三态而不是密文非空`() {
        val vm = source("src/main/java/com/myfriend/app/ui/screens/apiconfig/ApiEditorViewModel.kt")
        assertTrue("编辑器状态要带三态", vm.contains("val existingKeyState: ApiKeyState"))
        assertTrue("从仓库取 keyState", vm.contains("existingKeyState = repository.keyState(config)"))
        assertFalse("不能再只看密文非空", vm.contains("hasApiKey(config)"))

        val screen = source("src/main/java/com/myfriend/app/ui/screens/apiconfig/ApiEditorScreen.kt")
        assertTrue("界面按三态分支", screen.contains("when (state.existingKeyState)"))
        assertTrue("解不出来时给明确文案", screen.contains("R.string.api_key_unrecoverable_hint"))
        assertTrue("占位符也要说明", screen.contains("R.string.api_key_unrecoverable_placeholder"))
        assertTrue("标成错误态", screen.contains("isError = state.existingKeyState == ApiKeyState.UNRECOVERABLE"))
        assertFalse("旧字段不该还在用", screen.contains("state.hasExistingKey"))

        val repo = source("src/main/java/com/myfriend/app/data/repo/ApiConfigRepository.kt")
        assertTrue(
            "hasApiKey 只能作为三态的转发（删掉也可以，但绝不能回到「密文非空」）",
            repo.contains("fun hasApiKey(config: ApiConfigEntity): Boolean = keyState(config) == ApiKeyState.USABLE")
        )
    }

    @Test
    fun `有密文但解不出来是 无法恢复`() {
        assertEquals(ApiKeyState.UNRECOVERABLE, ApiKeyPolicy.evaluate("cipher", null))
        assertEquals(ApiKeyState.UNRECOVERABLE, ApiKeyPolicy.evaluate("cipher", ""))
        assertEquals(ApiKeyState.UNRECOVERABLE, ApiKeyPolicy.evaluate("cipher", "   "))
        assertFalse("解不出来不能算已配置", ApiKeyPolicy.isUsable("cipher", null))
    }

    private fun projectRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/myfriend/app").isDirectory }
            ?: error("找不到 app 模块目录：${File(".").absolutePath}")
    }

    private fun source(relative: String): String = File(projectRoot(), relative).readText()
}
