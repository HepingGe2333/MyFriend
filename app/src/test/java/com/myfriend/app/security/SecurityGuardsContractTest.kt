package com.myfriend.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 安全修复的源码契约（安全审计 P1-0、P1-2、P2-1、P2-2）。
 *
 * 纯逻辑部分（明文策略、保留头、媒体目录归属、并发闸补偿）各有真单测；
 * 这里钉住「有没有接上线」与「配置有没有真的改掉」——这类问题最容易
 * 「测试全绿、发布包里还是错的」。
 */
class SecurityGuardsContractTest {

    private val mainManifest get() = source("src/main/AndroidManifest.xml")
    private val debugManifest get() = File(projectRoot(), "src/debug/AndroidManifest.xml")

    // ------------------------------------------------------------------ P1-2 明文 HTTP

    @Test
    fun `主 manifest 禁止明文 只有 debug manifest 打开`() {
        assertTrue(
            "主 manifest 必须写 usesCleartextTraffic=\"false\"（发布包系统层禁止明文）",
            mainManifest.contains("""android:usesCleartextTraffic="false"""")
        )
        assertFalse(
            "主 manifest 里不允许再出现 true",
            mainManifest.contains("""android:usesCleartextTraffic="true"""")
        )
        assertTrue("debug manifest 必须存在", debugManifest.isFile)
        val debug = debugManifest.readText()
        assertTrue(
            "debug manifest 要打开明文（本地自建服务常用 http://）",
            debug.contains("""android:usesCleartextTraffic="true"""")
        )
        assertTrue(
            "主 manifest 已经有 false，debug 需要 tools:replace 才能覆盖",
            debug.contains("""tools:replace="android:usesCleartextTraffic"""")
        )
    }

    @Test
    fun `地址校验按 debug 语义区分两种拒绝原因`() {
        val guard = source("src/main/java/com/myfriend/app/network/NetworkGuard.kt")
        assertTrue("要用共用的明文策略", guard.contains("CleartextHttpPolicy.rejection("))
        assertTrue(
            "默认取 BuildConfig.DEBUG：release 下拒绝 http://",
            guard.contains("debuggable: Boolean = BuildConfig.DEBUG")
        )
        assertTrue(
            "release 下 http:// 必须抛 CleartextDisabled（有专门文案）",
            source("src/main/java/com/myfriend/app/domain/CleartextHttpPolicy.kt")
                .contains("NetworkErrorKind.CleartextDisabled")
        )
    }

    // ------------------------------------------------------------------ P1-0 备份导入劫持

    @Test
    fun `备份合并保留本机地址与明文开关`() {
        val codec = source("src/main/java/com/myfriend/app/domain/BackupCodec.kt")
        val merge = between(codec, "fun BackupApiConfig.mergeInto(", "fun BackupApiConfig.toNewEntity(")
        assertTrue("baseUrl 必须保留本机值", merge.contains("baseUrl = existing.baseUrl"))
        assertTrue(
            "allowInsecureHttp 必须保留本机值",
            merge.contains("allowInsecureHttp = existing.allowInsecureHttp")
        )
        assertTrue("webToolsBaseUrl 也不能被备份改", merge.contains("webToolsBaseUrl = existing.webToolsBaseUrl"))
        assertTrue("要回报「地址被保留」", merge.contains("addressKept ="))
        assertTrue("要回报「明文被忽略」", merge.contains("insecureIgnored ="))
    }

    @Test
    fun `新配置也从不因备份打开明文`() {
        val codec = source("src/main/java/com/myfriend/app/domain/BackupCodec.kt")
        val newEntity = between(codec, "fun BackupApiConfig.toNewEntity(", "fun AppSettings.toBackupSettings(")
        assertTrue("新配置的明文开关一律从 false 开始", newEntity.contains("allowInsecureHttp = false"))
        assertTrue("备份写了 true 要记 warning", newEntity.contains("insecureIgnored = allowInsecureHttp"))
        assertTrue("新配置密钥留空", newEntity.contains("apiKeyCipher = null"))
    }

    @Test
    fun `导入路径复用与 save 相同的校验并写 warnings`() {
        val backup = source("src/main/java/com/myfriend/app/data/repo/BackupRepository.kt")
        assertTrue("导入的每条配置都要校验", backup.contains("apiConfigRepository.validateEntity(merged.entity)"))
        assertTrue(
            "地址被保留要进导入摘要",
            backup.contains("BackupWarning.ApiConfigAddressKept(addressKept)")
        )
        assertTrue(
            "明文被忽略要进导入摘要",
            backup.contains("BackupWarning.ApiConfigInsecureIgnored(insecureIgnored)")
        )
        assertTrue(
            "校验失败的配置要跳过并记 warning",
            backup.contains("BackupWarning.ApiConfigRejected(")
        )
        assertTrue(
            "save 与 validateEntity 共用同一套检查",
            source("src/main/java/com/myfriend/app/data/repo/ApiConfigRepository.kt")
                .contains("validateEntity(entity)")
        )
    }

    @Test
    fun `导入改地址与打开明文的提醒有中英资源`() {
        listOf(
            "backup_warn_api_config_address_kept",
            "backup_warn_api_config_insecure_ignored",
            "backup_warn_api_config_rejected"
        ).forEach { key ->
            val zh = File(projectRoot(), "src/main/res/values/strings.xml").readText()
            val en = File(projectRoot(), "src/main/res/values-en/strings.xml").readText()
            assertTrue("values/ 缺 $key", zh.contains("name=\"$key\""))
            assertTrue("values-en/ 缺 $key", en.contains("name=\"$key\""))
            // 0.10.2 起这几条里带计数的走 plurals（英文要区分 1 setup / 2 setups）。
            val backupText = source("src/main/java/com/myfriend/app/ui/common/BackupText.kt")
            assertTrue(
                "BackupText 没有映射 $key",
                backupText.contains("R.string.$key") || backupText.contains("R.plurals.$key")
            )
        }
    }

    // ------------------------------------------------------------------ P2-1 保留头

    @Test
    fun `保存时拦截保留头 请求组装处再兜一层`() {
        val config = source("src/main/java/com/myfriend/app/data/repo/ApiConfigRepository.kt")
        assertTrue("save 的 header 校验要拦保留头", config.contains("ApiConfigRules.reservedHeaderName(key)"))
        val adapter = source("src/main/java/com/myfriend/app/network/OpenAiAdapter.kt")
        assertTrue(
            "老库里可能还存着 0.10.2 之前写进去的保留头：组装请求时也要滤掉",
            adapter.contains("ApiConfigRules.isReservedHeader(name)")
        )
    }

    // ------------------------------------------------------------------ P2-2 附件所有权

    @Test
    fun `附件删除前校验落在应用媒体目录内`() {
        val media = source("src/main/java/com/myfriend/app/util/MediaStorage.kt")
        val deleteFiles = between(media, "fun deleteFiles(context: Context, uris: List<String>): Int", "fun clearChatImages(")
        assertTrue("附件删除要校验目录归属", deleteFiles.contains("MediaOwnership.isWithin(file, roots)"))
        assertTrue("越界要记日志", deleteFiles.contains("Log.w(TAG"))
        val deleteAvatar = between(media, "fun deleteAvatar(context: Context, uriString: String?): Int", "fun isMissingLocalFile(")
        assertTrue("头像删除也走同一套归属守卫", deleteAvatar.contains("MediaOwnership.isWithin("))
    }

    // ------------------------------------------------------------------ P2-3 / P2-4 并发

    @Test
    fun `并发闸的取消补偿不在已取消的协程里拿锁`() {
        val gate = source("src/main/java/com/myfriend/app/domain/LlmConcurrencyGate.kt")
        val compensation = between(gate, "catch (cancel: CancellationException)", "throw cancel")
        assertTrue(
            "补偿必须放在 NonCancellable 里：取消上下文里 Mutex.lock() 会立刻抛，名额永远还不上",
            compensation.contains("withContext(NonCancellable)")
        )
        assertTrue(compensation.contains("releaseLocked(lane)"))
    }

    @Test
    fun `AgentLoop tick 有非重入互斥`() {
        val loop = source("src/main/java/com/myfriend/app/data/repo/AgentLoop.kt")
        assertTrue("tick 要先抢锁", loop.contains("if (!tickLock.tryEnter())"))
        assertTrue("finally 里释放，失败不能让思考停摆", loop.contains("tickLock.exit()"))
        assertTrue("实际内容拆到 tickLocked", loop.contains("private suspend fun tickLocked(now: Long): Outcome"))
    }

    @Test
    fun `规则型主动消息在会话打开时不弹通知`() {
        val proactive = source("src/main/java/com/myfriend/app/data/repo/ProactiveRepository.kt")
        val notifyIndex = proactive.indexOf("ProactiveNotifier.notify(")
        val guardIndex = proactive.indexOf("ChatNotificationPolicy.shouldNotify(")
        assertTrue("通知调用前必须有前置判断", guardIndex >= 0 && guardIndex < notifyIndex)
    }

    // ------------------------------------------------------------------ 工具

    private fun projectRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/myfriend/app").isDirectory }
            ?: error("找不到工程根目录：${File(".").absolutePath}")
    }

    private fun source(relative: String): String = File(projectRoot(), relative).readText()

    private fun between(text: String, startMarker: String, endMarker: String): String {
        val start = text.indexOf(startMarker)
        require(start >= 0) { "找不到 $startMarker" }
        val end = text.indexOf(endMarker, start + startMarker.length)
        require(end >= 0) { "找不到 $endMarker" }
        return text.substring(start, end)
    }
}
