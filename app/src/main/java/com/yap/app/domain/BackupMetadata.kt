package com.yap.app.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 备份文件的「版本信息」。
 *
 * 这些字段回答的是「这份文件是谁、什么时候、用哪一版 App / 哪一版数据库写出来的」，
 * 不是「他」的一部分——所以它们**不**参与 [BackupBundle] 的往返比较，
 * 只在导出时写进 JSON、导入时读出来给用户看（以及校验文件有没有被改坏）。
 *
 * 字段来源：
 * - [formatVersion]：备份格式版本（[BACKUP_FORMAT_VERSION]，与 iOS 的 v7 互为契约）；
 * - [appVersion]：导出时 `BuildConfig.VERSION_NAME`；
 * - [schemaVersion]：导出时 Room 的 `@Database(version = …)`（本机数据库结构版本）；
 * - [createdAt]：导出时刻（毫秒）。
 */
data class BackupMetadata(
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val appVersion: String = "",
    val schemaVersion: Int = 0,
    val createdAt: Long = 0L,
    /** 文件自带的校验和（小写十六进制），没有则为 null（旧备份 / 服务端备份）。 */
    val checksum: String? = null,
    /** 校验和是否真的对上了（文件里没有校验和时为 false）。 */
    val checksumVerified: Boolean = false,
    /**
     * 文件里的 checksum 不是本机算法写的（例如 iOS 端导出的）。
     *
     * 这种校验和**不在本机复核**：两端的 JSON 编码器（Kotlin/serialization 与 Swift/JSONEncoder）
     * 对浮点数、Unicode 转义的写法并不逐字节相同，硬比只会把一份正常备份判成损坏。
     * 界面显示「校验和来自其他客户端，未在本机复核」，仍然允许导入。
     */
    val checksumForeign: Boolean = false
) {
    val hasChecksum: Boolean get() = !checksum.isNullOrBlank()

    /** 用一份没有校验和的备份构造（v1–v7 旧样本、iOS 导出、服务端导出）。 */
    companion object {
        fun of(bundle: BackupBundle, schemaVersion: Int = 0, createdAt: Long = 0L): BackupMetadata =
            BackupMetadata(
                formatVersion = bundle.formatVersion,
                appVersion = bundle.appVersion,
                schemaVersion = schemaVersion,
                createdAt = if (createdAt > 0L) createdAt else bundle.exportedAt
            )
    }
}

/** 文件内容校验失败的原因（与 [BackupFailure] 分开：那条在 agentcore 里，跨端共用）。 */
sealed interface BackupCorruption {
    /** 文件里写了 checksum，但对不上：被截断 / 被改过 / 读写坏掉了。 */
    data class ChecksumMismatch(val declared: String, val computed: String) : BackupCorruption

    /** 文件里写了 checksum，但不是合法的 SHA-256（64 位十六进制）。 */
    data class ChecksumMalformed(val declared: String) : BackupCorruption
}

/**
 * 备份文件的封条：对「除 checksum 外的整份 JSON」做规范化后的 SHA-256。
 *
 * ## 为什么不是对某个 `data` 字段
 *
 * 现有 v7 备份是**扁平结构**（顶层直接是 `personas[]` / `messages[]` / …），没有 `data` 包一层；
 * 把结构改成 `{data: …}` 会直接破坏与 iOS 的 v7 契约。所以这里的「data」定义为
 * **整份备份负载**：解析后的顶层对象去掉 `checksum` 键之后的部分。
 *
 * ## 为什么要「规范化」
 *
 * 用户可能用编辑器重新格式化文件（缩进/换行变化），也可能经由会重排键的云盘/剪贴板。
 * 直接对原始文本做哈希会因为纯排版差异误报「损坏」。因此：
 * - 对象键按字典序排序；
 * - 数组顺序保持不变（数组顺序本身是契约：消息、人格顺序有意义）；
 * - 不做任何空白/缩进；
 * - 数字/布尔/字符串按 JSON 字面量原样输出（字符串由 kotlinx 负责转义）。
 *
 * 这样「同一份内容、不同排版」得到同一个哈希，而「改了一个字」一定不同。
 */
object BackupChecksum {

    /** JSON 里校验和的键名。 */
    const val KEY: String = "checksum"

    /** 校验和算法标记：只有带着这个标记的 checksum 才由本机复核（见 [BackupMetadata.checksumForeign]）。 */
    const val ALGORITHM_KEY: String = "checksumAlgorithm"

    /** 本机算法：整份负载（去掉 checksum/checksumAlgorithm）规范化 JSON 的 SHA-256。 */
    const val ALGORITHM: String = "yap-canonical-json-sha256-v1"

    /** 对「除 checksum / checksumAlgorithm 外的整份 JSON」计算 SHA-256（小写十六进制）。 */
    fun of(root: JsonObject): String {
        val stripped = JsonObject(root.filterKeys { it != KEY && it != ALGORITHM_KEY })
        return MediaHashes.sha256Hex(canonical(stripped).toByteArray(Charsets.UTF_8))
    }

    /** 规范化 JSON 文本（键有序、无空白）。 */
    fun canonical(element: JsonElement): String = buildString { appendCanonical(element, this) }

    private fun appendCanonical(element: JsonElement, out: StringBuilder) {
        when (element) {
            is JsonObject -> {
                out.append('{')
                val keys = element.keys.sorted()
                keys.forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    // 键用 JSON 字符串字面量（含转义），与值同一套规则。
                    out.append(JsonPrimitive(key).toString())
                    out.append(':')
                    appendCanonical(element.getValue(key), out)
                }
                out.append('}')
            }

            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    appendCanonical(item, out)
                }
                out.append(']')
            }

            is JsonNull -> out.append("null")

            is JsonPrimitive -> out.append(element.toString())

            // kotlinx 目前只有上面三种 JsonElement；兜底保持确定性而不是抛异常。
            else -> out.append(element.toString())
        }
    }
}
