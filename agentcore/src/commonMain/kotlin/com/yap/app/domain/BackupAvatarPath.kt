package com.yap.app.domain

/**
 * 备份里的人格头像该怎么写、导入时怎么读回（安全审计 P2-5）。
 *
 * 以前导出直接把本机绝对路径写进 JSON：
 * `"avatarUri": "file:///data/user/0/com.yap.app/files/avatars/avatar-<uuid>.jpg"`。
 * 这属于「设备路径」外泄（沙盒布局 + 文件名 UUID），而且对导入方毫无用处——那个文件
 * 从来不在对方的手机上。
 *
 * 现在：
 * - **导出**：只写文件名（`avatar-<uuid>.jpg`），不带 scheme、不带目录；不是 `file://` 的值
 *   （历史遗留的 `content://`）直接不写；
 * - **导入**：把「文件名」或老备份里的 `file://` 都归一成文件名，再看本机
 *   `files/avatars/<文件名>` 在不在：在就用本机路径恢复，不在就清空并记一条 warning。
 *
 * 这样两端行为仍然一致（iOS 的导入逻辑本来就是「文件不存在 → 清空 + warning」，
 * 裸文件名对它同样是不存在的文件）。iOS 侧导出仍是绝对路径，需要下一轮同步，见报告。
 */
object BackupAvatarPath {

    /** 头像文件名前缀（`MediaStorage.persistAvatar` 与 `AvatarImagePolicy` 共用这个前缀）。 */
    const val FILE_PREFIX = "avatar-"

    /** 文件名允许的最大长度（比 UUID + 扩展名宽松一点，但不接受超长垃圾）。 */
    const val MAX_NAME_LENGTH = 200

    /**
     * 导出用：把本机 `file://` 路径收成裸文件名。
     *
     * 不是 `file://`、或者文件名不像我们自己的头像文件、或者带目录穿越 → 返回 null（不写）。
     */
    fun portable(avatarUri: String?): String? {
        val raw = avatarUri?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (!raw.startsWith("file://")) return null
        val name = raw.substringAfterLast('/')
        return name.takeIf { isSafeName(it) }
    }

    /**
     * 导入用：从备份里的值取出文件名。
     *
     * 兼容三种形态：新备份的裸文件名、老备份的 `file://` 绝对路径、以及带其它目录的路径。
     * 取不出安全文件名时返回 null（调用方按「文件不存在」处理）。
     */
    fun fileName(avatarUri: String?): String? {
        val raw = avatarUri?.trim().orEmpty()
        if (raw.isEmpty()) return null
        // 带 `..` 的整条路径直接不认（不能只看最后一段：`../../databases/yap.db`
        // 的最后一段看起来完全无害）。
        if (raw.contains("..")) return null
        val name = raw.substringAfterLast('/')
        return name.takeIf { isSafeName(it) }
    }

    /** 文件名是不是「我们自己的头像文件、且没有目录穿越」。 */
    fun isSafeName(name: String): Boolean {
        if (name.isBlank() || name.length > MAX_NAME_LENGTH) return false
        if (name.contains('/') || name.contains('\\')) return false
        if (name == "." || name == ".." || name.contains("..")) return false
        return true
    }

    /** 本机头像目录 + 文件名 → `file://` 路径（不检查存在性；存在性由调用方查）。 */
    fun localUri(avatarDirPath: String, fileName: String): String {
        val dir = avatarDirPath.trimEnd('/')
        return "file://$dir/$fileName"
    }
}
