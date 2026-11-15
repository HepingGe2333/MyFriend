package com.myfriend.app.domain

import java.io.File
import java.util.UUID

/**
 * 备份格式 v3 的媒体部分：头像与聊天图片真的跟着备份走。
 *
 * v2 及以前，`personas.avatarUri` 只写一个「裸文件名」、`attachmentsJson` 只写路径：
 * 换台手机以后这些引用全都指不到文件，图片等于丢了。v3 在 JSON 里加一个顶层 `media`
 * 数组，把本机**当前还引用得到**的图片二进制用 base64 一起装进去。
 *
 * 三条边界写在这里，是为了以后没人能「顺手」放开：
 *
 * 1. **只带本机真的引用到的图片**：人格头像、用户自己的头像、预设头像、聊天消息里的附件。
 *    孤儿文件不扫描——备份不是磁盘镜像。
 * 2. **总量与单张都有上限**（[BackupMediaLimits]）：base64 会让体积涨约 1/3，
 *    而备份是一个要在手机上生成、传输、再读回来的 JSON 文件。超限就停，用户会看到
 *    「有 N 张图片没有随备份带走」——宁可少几张，也不要一份导不回去的备份。
 * 3. **导入时文件名一律重建**：备份里的 `fileName` 只用来做「消息/头像 ↔ 媒体」的关联，
 *    落盘永远是新的 UUID 文件名，并且必须过 [MediaOwnership] 的目录归属守卫。
 *    备份是外部输入，不能让它决定往哪个路径写字节。
 */
object BackupMediaLimits {

    /** 一份备份里所有图片的原始字节总量上限：128 MiB。 */
    const val MAX_MEDIA_BYTES: Long = 128L * 1024 * 1024

    /** 单张图片的原始字节上限：8 MiB。 */
    const val MAX_MEDIA_FILE_BYTES: Int = 8 * 1024 * 1024

    /**
     * 整份备份文件（含 base64 膨胀与文本）的读入上限：256 MiB。
     *
     * 旧的 64 MB 是按「只有文字」估的；v3 的 128 MiB 图片 base64 之后约 171 MiB，
     * 64 MB 的闸会把一份自己刚导出的备份挡在门外。
     */
    const val MAX_BACKUP_BYTES: Long = 256L * 1024 * 1024

    /** 解析前的字符数上限（与 [MAX_BACKUP_BYTES] 同值；base64 是 ASCII，字符数≈字节数）。 */
    const val MAX_BACKUP_CHARS: Int = 256 * 1024 * 1024

    fun isTooLarge(byteCount: Long): Boolean = byteCount > MAX_BACKUP_BYTES

    fun isTooManyChars(charCount: Int): Boolean = charCount > MAX_BACKUP_CHARS

    /** 给文案用的 MB 数（向上取整，避免出现「0MB」）。 */
    fun megabytes(bytes: Long = MAX_BACKUP_BYTES): Long {
        val mb = bytes / (1024 * 1024)
        return if (mb <= 0L) 1L else mb
    }

    /** 单张 base64 文本的长度上限（含 padding）：再长就不可能落在单张预算里。 */
    fun maxBase64Chars(perFileBytes: Int = MAX_MEDIA_FILE_BYTES): Int =
        ((perFileBytes + 2) / 3) * 4
}

/** `media[].kind` 的合法取值。 */
object BackupMediaKind {
    const val PERSONA_AVATAR = "PERSONA_AVATAR"
    const val USER_AVATAR = "USER_AVATAR"
    const val CHAT_IMAGE = "CHAT_IMAGE"

    val all = setOf(PERSONA_AVATAR, USER_AVATAR, CHAT_IMAGE)

    fun isAvatar(kind: String): Boolean = kind == PERSONA_AVATAR || kind == USER_AVATAR
}

/** v3 媒体相关的提醒：数据层只给计数与种类，句子由 UI 按语言拼。 */
sealed interface BackupMediaWarning {
    /** 导出时超限没带走的图片数。 */
    data class ExportSkipped(val count: Int) : BackupMediaWarning

    /** 导入时没能落盘的图片数（坏 base64 / 不支持的 mime / 文件名不安全 / 目录非法 / 写入失败）。 */
    data class ImportSkipped(val count: Int) : BackupMediaWarning

    /** 导入时因为总量或单张超限而跳过的图片数。 */
    data class ImportOverLimit(val count: Int) : BackupMediaWarning
}

/**
 * 备份用的 base64：标准字母表、带 padding、**不换行**。
 *
 * 为什么不用 `android.util.Base64`：`BackupCodec` 与媒体策略要能在 JVM 单测里直接跑
 * （`app:testDebugUnitTest`），而 `android.util.Base64` 在本地单测里是 stub。
 * 实现按 RFC 4648 §4，解码对空白宽容（导入时允许别人手工排版过），但非法字符一律拒绝。
 */
object BackupBase64 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = CharArray(((bytes.size + 2) / 3) * 4)
        var source = 0
        var target = 0
        while (source + 2 < bytes.size) {
            val b0 = bytes[source].toInt() and 0xFF
            val b1 = bytes[source + 1].toInt() and 0xFF
            val b2 = bytes[source + 2].toInt() and 0xFF
            out[target] = ALPHABET[(b0 shr 2) and 0x3F]
            out[target + 1] = ALPHABET[((b0 shl 4) or (b1 shr 4)) and 0x3F]
            out[target + 2] = ALPHABET[((b1 shl 2) or (b2 shr 6)) and 0x3F]
            out[target + 3] = ALPHABET[b2 and 0x3F]
            source += 3
            target += 4
        }
        when (bytes.size - source) {
            1 -> {
                val b0 = bytes[source].toInt() and 0xFF
                out[target] = ALPHABET[(b0 shr 2) and 0x3F]
                out[target + 1] = ALPHABET[(b0 shl 4) and 0x3F]
                out[target + 2] = '='
                out[target + 3] = '='
            }
            2 -> {
                val b0 = bytes[source].toInt() and 0xFF
                val b1 = bytes[source + 1].toInt() and 0xFF
                out[target] = ALPHABET[(b0 shr 2) and 0x3F]
                out[target + 1] = ALPHABET[((b0 shl 4) or (b1 shr 4)) and 0x3F]
                out[target + 2] = ALPHABET[(b1 shl 2) and 0x3F]
                out[target + 3] = '='
            }
        }
        return String(out)
    }

    /** 解不出来返回 null（不抛）。空串解成空字节数组。 */
    fun decode(text: String): ByteArray? {
        val cleaned = StringBuilder(text.length)
        for (char in text) {
            when (char) {
                ' ', '\n', '\r', '\t' -> continue
                else -> cleaned.append(char)
            }
        }
        val value = cleaned.toString()
        if (value.isEmpty()) return ByteArray(0)
        if (value.length % 4 != 0) return null

        var padding = 0
        if (value.endsWith("==")) padding = 2 else if (value.endsWith("=")) padding = 1
        val out = ByteArray(value.length / 4 * 3 - padding)
        var target = 0
        var index = 0
        while (index < value.length) {
            val chunkEnd = index + 4
            val isLast = chunkEnd == value.length
            val c0 = index(value[index]) ?: return null
            val c1 = index(value[index + 1]) ?: return null
            val c0Char = value[index]
            val c1Char = value[index + 1]
            val c2Char = value[index + 2]
            val c3Char = value[index + 3]
            // 前两个字符永远不能是 padding。
            if (c0Char == '=' || c1Char == '=') return null
            val c2 = if (c2Char == '=') 0 else index(c2Char) ?: return null
            val c3 = if (c3Char == '=') 0 else index(c3Char) ?: return null
            // padding 只允许出现在最后四个字符里，而且必须是「尾部」：
            // `xx==` 合法，`xxx=` 合法，`xx=x` / `=xxx` 都是坏数据。
            if ((c2Char == '=' || c3Char == '=') && !isLast) return null
            if (c2Char == '=' && c3Char != '=') return null

            val combined = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
            if (target < out.size) out[target++] = ((combined shr 16) and 0xFF).toByte()
            if (target < out.size) out[target++] = ((combined shr 8) and 0xFF).toByte()
            if (target < out.size) out[target++] = (combined and 0xFF).toByte()
            index += 4
        }
        return out
    }

    private fun index(char: Char): Int? {
        val code = char.code
        return when (code) {
            in 'A'.code..'Z'.code -> code - 'A'.code
            in 'a'.code..'z'.code -> code - 'a'.code + 26
            in '0'.code..'9'.code -> code - '0'.code + 52
            '+'.code -> 62
            '/'.code -> 63
            else -> null
        }
    }
}

/** v3 支持的图片格式：只认这三种；GIF/视频先不带（见报告「没做与风险」）。 */
object BackupMediaFormats {

    const val JPEG = "image/jpeg"
    const val PNG = "image/png"
    const val WEBP = "image/webp"

    val supported: Set<String> = setOf(JPEG, PNG, WEBP)

    fun isSupported(mime: String): Boolean = mime.trim().lowercase() in supported

    fun extensionOf(mime: String): String? = when (mime.trim().lowercase()) {
        JPEG -> "jpg"
        PNG -> "png"
        WEBP -> "webp"
        else -> null
    }

    fun mimeOfExtension(extension: String): String? = when (extension.trim().lowercase()) {
        "jpg", "jpeg" -> JPEG
        "png" -> PNG
        "webp" -> WEBP
        else -> null
    }

    /** 按文件名后缀猜 mime；猜不到返回 null（调用方跳过这张图）。 */
    fun mimeOfFile(fileName: String): String? {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        if (extension.isEmpty() || extension == fileName) return null
        return mimeOfExtension(extension)
    }
}

/**
 * 导出时的一个候选图片：已经被解析到真实的、属于 App 的本地文件。
 *
 * `fileName` 是**源文件名**（裸名），它同时会写进 `personas.avatarUri` /
 * `messages.attachmentsJson`，作为导入时「引用 ↔ media」的关联键。
 */
data class BackupMediaCandidate(
    val kind: String,
    val personaId: String?,
    val fileName: String,
    val file: File,
    val mime: String
)

/** 打包结果：写进 JSON 的 media 列表 + 因为超限没带走的张数。 */
data class BackupMediaPackResult(
    val media: List<BackupMedia>,
    val skippedByLimit: Int
)

/**
 * 导出时的媒体打包：**只读**文件系统，不写任何东西（单测可直接喂临时目录）。
 *
 * 规则：
 * - 同一张文件（同一路径 + 同一 kind）只装一次；
 * - 单张 > [perFileLimit] 直接跳过并计入超限；
 * - 原始字节累计 > [totalBudget] 就停止收集，剩下的全部计入超限（不写半张图）；
 * - 读不出来 / 文件不在了 → 跳过，但**不计**进「超限」提醒（那不是上限问题）。
 */
object BackupMediaPacker {

    fun pack(
        candidates: List<BackupMediaCandidate>,
        totalBudget: Long = BackupMediaLimits.MAX_MEDIA_BYTES,
        perFileLimit: Int = BackupMediaLimits.MAX_MEDIA_FILE_BYTES
    ): BackupMediaPackResult {
        val packed = mutableListOf<BackupMedia>()
        val seen = mutableSetOf<String>()
        var total = 0L
        var skipped = 0

        val unique = candidates.filter { candidate ->
            val key = candidate.kind + "|" + runCatching { candidate.file.canonicalPath }.getOrDefault(candidate.file.path)
            seen.add(key)
        }

        for ((position, candidate) in unique.withIndex()) {
            val file = candidate.file
            if (!file.isFile || !file.canRead()) continue
            val mime = candidate.mime.takeIf { BackupMediaFormats.isSupported(it) }
                ?: BackupMediaFormats.mimeOfFile(candidate.fileName)
                ?: continue
            if (!BackupAvatarPath.isSafeName(candidate.fileName)) continue
            val length = file.length()
            if (length <= 0L) continue
            if (length > perFileLimit) {
                skipped++
                continue
            }
            if (total + length > totalBudget) {
                // 停止收集：剩下的（含当前这张）都没有随备份带走。
                skipped += unique.size - position
                break
            }
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            total += bytes.size
            packed += BackupMedia(
                id = UUID.randomUUID().toString(),
                kind = candidate.kind,
                personaId = candidate.personaId,
                fileName = candidate.fileName,
                mime = mime,
                bytes = bytes.size,
                data = BackupBase64.encode(bytes)
            )
        }
        return BackupMediaPackResult(packed, skipped)
    }
}

/** 导入时落盘的一张图片：新文件 + 它在备份里的源文件名 + 新的 `file://` 地址。 */
data class RestoredBackupMedia(
    val kind: String,
    val fileName: String,
    val file: File,
    val uri: String,
    val mime: String
)

/** 导入媒体阶段的结果：关联表 + 提醒 + 已写文件（失败时要能回滚删掉）。 */
data class BackupMediaRestoreResult(
    val avatarsByFileName: Map<String, RestoredBackupMedia> = emptyMap(),
    val avatarsByPersonaId: Map<String, RestoredBackupMedia> = emptyMap(),
    val chatImagesByFileName: Map<String, RestoredBackupMedia> = emptyMap(),
    val userAvatar: RestoredBackupMedia? = null,
    val warnings: List<BackupMediaWarning> = emptyList(),
    val writtenFiles: List<File> = emptyList()
)

/**
 * 导入时的媒体落盘：把 `media[].data` 解码、写进本机媒体目录、返回关联表。
 *
 * 安全边界：
 * - 备份里的 `fileName` 只做关联键，**不参与拼路径**；落盘名一律是新 UUID；
 * - 目标路径必须过 [MediaOwnership.isWithin]（目录归属守卫），目录非法就整类跳过；
 * - 坏 base64 / 不支持的 mime / 字节数与声明不符 / 超限：跳过并计数，绝不抛。
 *
 * 返回的 [BackupMediaRestoreResult.writtenFiles] 用于「媒体写完、落库失败」时回滚，
 * 避免留下一批没人引用的图片。
 */
object BackupMediaRestorer {

    fun restore(
        entries: List<BackupMedia>,
        avatarDir: File,
        chatDir: File,
        totalBudget: Long = BackupMediaLimits.MAX_MEDIA_BYTES,
        perFileLimit: Int = BackupMediaLimits.MAX_MEDIA_FILE_BYTES,
        newName: (prefix: String, extension: String) -> String = ::defaultNewName
    ): BackupMediaRestoreResult {
        if (entries.isEmpty()) return BackupMediaRestoreResult()

        val avatarReady = ensureDirectory(avatarDir)
        val chatReady = ensureDirectory(chatDir)
        val avatarsByFileName = linkedMapOf<String, RestoredBackupMedia>()
        val avatarsByPersonaId = linkedMapOf<String, RestoredBackupMedia>()
        val chatImages = linkedMapOf<String, RestoredBackupMedia>()
        val written = mutableListOf<File>()
        var userAvatar: RestoredBackupMedia? = null
        var invalid = 0
        var overLimit = 0
        var total = 0L

        entries.forEach { entry ->
            val kind = entry.kind.trim()
            if (kind !in BackupMediaKind.all) {
                invalid++
                return@forEach
            }
            // 备份里的源文件名只用来关联：不合法就整条拒绝（绝对路径 / ../ 一律不认）。
            if (!BackupAvatarPath.isSafeName(entry.fileName)) {
                invalid++
                return@forEach
            }
            val mime = entry.mime.trim().lowercase()
            if (!BackupMediaFormats.isSupported(mime)) {
                invalid++
                return@forEach
            }
            val extension = BackupMediaFormats.extensionOf(mime) ?: run { invalid++; return@forEach }
            if (entry.bytes < 0 || entry.bytes > perFileLimit ||
                entry.data.length > BackupMediaLimits.maxBase64Chars(perFileLimit)
            ) {
                overLimit++
                return@forEach
            }
            val bytes = BackupBase64.decode(entry.data)
            if (bytes == null || bytes.isEmpty() || bytes.size != entry.bytes) {
                invalid++
                return@forEach
            }
            if (bytes.size > perFileLimit || total + bytes.size > totalBudget) {
                overLimit++
                return@forEach
            }

            val isAvatar = BackupMediaKind.isAvatar(kind)
            if (isAvatar && !avatarReady) {
                invalid++
                return@forEach
            }
            if (!isAvatar && !chatReady) {
                invalid++
                return@forEach
            }
            val dir = if (isAvatar) avatarDir else chatDir
            val prefix = if (isAvatar) AvatarPrefix else ChatPrefix
            val target = File(dir, newName(prefix, extension))
            // 目录归属守卫：写之前确认目标确实落在我们自己的媒体目录里。
            if (!BackupAvatarPath.isSafeName(target.name) || !MediaOwnership.isWithin(target, listOf(dir))) {
                invalid++
                return@forEach
            }
            if (target.exists()) {
                invalid++
                return@forEach
            }
            val writtenOk = runCatching {
                target.writeBytes(bytes)
                target.isFile && target.length() == bytes.size.toLong()
            }.getOrDefault(false)
            if (!writtenOk) {
                runCatching { target.delete() }
                invalid++
                return@forEach
            }
            total += bytes.size
            written += target
            val restored = RestoredBackupMedia(
                kind = kind,
                fileName = entry.fileName,
                file = target,
                uri = fileUri(target),
                mime = mime
            )
            when (kind) {
                BackupMediaKind.PERSONA_AVATAR -> {
                    // 同一个源文件名出现多次时以第一条为准（文件内容不同的「同名」是坏数据）。
                    if (avatarsByFileName.putIfAbsent(restored.fileName, restored) == null) {
                        val personaId = entry.personaId?.trim().orEmpty()
                        if (personaId.isNotEmpty()) avatarsByPersonaId.putIfAbsent(personaId, restored)
                    } else {
                        written -= target
                        runCatching { target.delete() }
                        invalid++
                    }
                }
                BackupMediaKind.USER_AVATAR -> {
                    if (userAvatar == null) userAvatar = restored else {
                        written -= target
                        runCatching { target.delete() }
                        invalid++
                    }
                }
                else -> {
                    if (chatImages.putIfAbsent(restored.fileName, restored) != null) {
                        written -= target
                        runCatching { target.delete() }
                        invalid++
                    }
                }
            }
        }

        val warnings = buildList {
            if (invalid > 0) add(BackupMediaWarning.ImportSkipped(invalid))
            if (overLimit > 0) add(BackupMediaWarning.ImportOverLimit(overLimit))
        }
        return BackupMediaRestoreResult(
            avatarsByFileName = avatarsByFileName,
            avatarsByPersonaId = avatarsByPersonaId,
            chatImagesByFileName = chatImages,
            userAvatar = userAvatar,
            warnings = warnings,
            writtenFiles = written
        )
    }

    /** 头像目录里的文件前缀（与 `MediaStorage.persistAvatar` 保持一致）。 */
    const val AvatarPrefix = "avatar-"

    /** 聊天图片前缀（`MediaStorage.clearChatImages` 只清这一类，所以恢复时也必须用它）。 */
    const val ChatPrefix = "chat-"

    /**
     * `File.toURI()` 给的是 `file:/path`（单斜杠），而 App 其它地方（`Uri.fromFile`）写的是
     * `file:///path`。统一成后者，免得同一个头像在库里出现两种写法的 `file://` 引用。
     */
    private fun fileUri(file: File): String = "file://" + file.toURI().toString().removePrefix("file:")

    private fun ensureDirectory(dir: File): Boolean =
        runCatching { (dir.isDirectory || dir.mkdirs()) && dir.isDirectory }.getOrDefault(false)

    private fun defaultNewName(prefix: String, extension: String): String =
        "$prefix${UUID.randomUUID()}.$extension"
}

/**
 * `messages.attachmentsJson` 在 v3 里的两条转换。
 *
 * 导出：把每条的 `uri` 从设备路径换成**裸文件名**（换机后路径必然失效；裸名用来在
 * `media` 里找到同一张图）。文件不存在 / 名字不安全 / 格式不支持的附件直接丢掉。
 *
 * 导入：只有能在 `media`（CHAT_IMAGE + 同名 fileName）里找到的附件才重建成本机新路径；
 * 找不到的丢掉并计数——这正是 v1/v2 备份「图片没有跟着备份走，只剩文字」的老行为。
 */
object BackupAttachmentsPortable {

    /** 导入一条消息的附件结果：[json] 为 null 表示没有（可用的）附件了。 */
    data class Restored(val json: String?, val dropped: Int)

    /** 导出：`allowedNames` 是本次真的装进 media 的聊天图片文件名集合。 */
    fun portable(raw: String?, allowedNames: Set<String>): String? {
        val attachments = AttachmentsCodec.decode(raw)
        if (attachments.isEmpty() || allowedNames.isEmpty()) return null
        val portable = attachments.mapNotNull { attachment ->
            val name = BackupAvatarPath.portable(attachment.uri) ?: return@mapNotNull null
            if (name !in allowedNames) return@mapNotNull null
            if (!BackupMediaFormats.isSupported(attachment.mime)) {
                // mime 字段可能是历史遗留的奇怪值：按文件名再认一次。
                if (BackupMediaFormats.mimeOfFile(name) == null) return@mapNotNull null
            }
            attachment.copy(uri = name)
        }
        return AttachmentsCodec.encode(portable)
    }

    /** 导入：按 `media` 里的 CHAT_IMAGE 重建本机路径。 */
    fun restore(
        raw: String?,
        chatImagesByFileName: Map<String, RestoredBackupMedia>
    ): Restored {
        val attachments = AttachmentsCodec.decode(raw)
        if (attachments.isEmpty()) return Restored(null, 0)
        var dropped = 0
        val rebuilt = attachments.mapNotNull { attachment ->
            // v3 的附件 uri 是裸文件名；v1/v2 里的设备路径一律不认（换机后本来也指不到文件）。
            val bare = attachment.uri.trim()
            if (bare.contains('/') || bare.contains('\\') || bare.contains("..")) {
                dropped++
                return@mapNotNull null
            }
            val media = chatImagesByFileName[bare] ?: run { dropped++; return@mapNotNull null }
            attachment.copy(uri = media.uri, mime = media.mime)
        }
        return Restored(AttachmentsCodec.encode(rebuilt), dropped)
    }
}
