package com.yap.app.data.repo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 备份版本管理（0.15）的**接线契约**：元数据/checksum 真的写进导出管线、导入真的会拒绝坏文件。
 *
 * 纯逻辑在 `BackupMetadataTest`；这里盯源码，因为「逻辑对但没接上」在发布包里
 * 表现为「文件照样没有 checksum / 坏了照样导入」——那是用户丢数据的场景。
 */
class BackupVersionWiringContractTest {

    private fun projectRoot(): File =
        listOf(File("."), File(".."))
            .firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到工程根目录，当前工作目录：${File(".").absolutePath}")

    private fun source(path: String): String = File(projectRoot(), path).readText()

    private val repo get() = source("src/main/java/com/yap/app/data/repo/BackupRepository.kt")
    private val codec get() = source("src/main/java/com/yap/app/domain/BackupCodec.kt")
    private val metadata get() = source("src/main/java/com/yap/app/domain/BackupMetadata.kt")
    private val settingsViewModel get() = source("src/main/java/com/yap/app/ui/screens/settings/SettingsViewModel.kt")
    private val backupText get() = source("src/main/java/com/yap/app/ui/common/BackupText.kt")
    private val backupSection get() = source("src/main/java/com/yap/app/ui/screens/settings/BackupSection.kt")

    @Test
    fun `导出真的写 schemaVersion 与 checksum`() {
        assertTrue(
            "导出要走带元数据的编码入口",
            repo.contains("BackupCodec.encodeWithMetadata(bundle, schemaVersion = schemaVersion)")
        )
        assertTrue(
            "schema 版本取运行时的 Room 版本，而不是再抄一份常量",
            repo.contains("database.openHelper.readableDatabase.version")
        )
        assertTrue("编码器真的写了 schemaVersion", codec.contains("put(\"schemaVersion\", JsonPrimitive(schemaVersion))"))
        assertTrue("编码器真的写了 createdAt", codec.contains("put(\"createdAt\", JsonPrimitive("))
        assertTrue(
            "checksum 与算法标记成对写（没有标记的 checksum 来自别的客户端，本机不复核）",
            codec.contains("put(BackupChecksum.ALGORITHM_KEY, JsonPrimitive(BackupChecksum.ALGORITHM))") &&
                codec.contains("put(BackupChecksum.KEY, JsonPrimitive(BackupChecksum.of(withMetadata)))")
        )
        assertTrue("规范化 JSON 的 SHA-256", metadata.contains("object BackupChecksum"))
        assertTrue(
            "对整份负载（去掉 checksum 与算法标记）做哈希",
            metadata.contains("root.filterKeys { it != KEY && it != ALGORITHM_KEY }")
        )
    }

    @Test
    fun `解码时校验 checksum 并整份拒绝`() {
        assertTrue(
            "有 checksum 才校验（没有键时返回 null = 放行）",
            codec.contains("if (declared.isNullOrBlank() || !isOwnChecksum(root)) return null")
        )
        assertTrue("对不上 → Corrupted", codec.contains("BackupCorruption.ChecksumMismatch(expected, computed)"))
        assertTrue("不是 64 位 hex → ChecksumMalformed", codec.contains("BackupCorruption.ChecksumMalformed"))
        assertTrue("结果类型里有 Corrupted 分支", codec.contains("data class Corrupted(val reason: BackupCorruption)"))
        assertTrue(
            "服务端导入前也要预检封条（服务端不认识 checksum）",
            repo.contains("BackupCodec.verifyChecksum(raw)") &&
                codec.contains("fun verifyChecksum(")
        )
    }

    @Test
    fun `导入遇到损坏文件要抛出去 而不是继续写库`() {
        assertTrue(
            "importJson 遇到 Corrupted 要停止",
            repo.contains("is BackupDecodeResult.Corrupted ->") &&
                repo.contains("throw BackupImportException(corruption = decoded.reason)")
        )
        assertTrue(
            "预览也把损坏直接报给用户",
            settingsViewModel.contains("BackupText.corruption(context, decoded.reason)")
        )
        assertTrue("损坏有专门的文案（不像「不是备份」那么含糊）", backupText.contains("R.string.backup_fail_checksum"))
    }

    @Test
    fun `导入预览展示版本信息`() {
        assertTrue(
            "预览带上解码出来的元数据",
            settingsViewModel.contains("BackupPreview.from(decoded.bundle, decoded.metadata)")
        )
        assertTrue("对话框真的显示了格式/schema/校验和", backupSection.contains("BackupText.format(context, preview)"))
        assertTrue(
            "预览模型里有这三项",
            codec.contains("val formatVersion: Int = BACKUP_FORMAT_VERSION") &&
                codec.contains("val schemaVersion: Int = 0") &&
                codec.contains("val checksumVerified: Boolean = false")
        )
    }

    @Test
    fun `未来版本先拒绝 旧版本照旧迁移`() {
        // 顺序在 BackupCodecTest / BackupMetadataTest 里已经钉过行为；这里只确认代码顺序没被调换：
        // formatVersion 检查必须在 checksum 校验之前（「先升级 App」比「文件坏了」更可行动）。
        val versionCheck = codec.indexOf("BackupFailure.NewerFormat(version, BACKUP_FORMAT_VERSION)")
        val checksumCheck = codec.indexOf("checksumCorruption(root)?.let { return BackupDecodeResult.Corrupted(it) }")
        assertTrue("版本检查要在校验和之前", versionCheck in 0 until checksumCheck)
        assertTrue(
            "旧版本不是拒绝，而是一条提醒",
            codec.contains("warnings += BackupWarning.OlderFormat(version, BACKUP_FORMAT_VERSION)")
        )
    }
}
