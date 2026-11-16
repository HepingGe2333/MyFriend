package com.yap.app.domain

import java.io.File

/**
 * 「这个文件是不是 App 自己媒体目录里的」——删文件之前的唯一守卫。
 *
 * 聊天图片与头像都落在应用私有目录（`files/media`、`files/avatars`），但它们的路径
 * 来自数据库里的 `attachmentsJson` / `avatarUri`，属于「数据即代码」的输入面：
 * 只要上游被污染（手工改库、将来某条导入路径没丢掉附件字段），一个
 * `file:///data/user/0/<pkg>/databases/yap.db` 或 `../../shared_prefs/x.xml`
 * 就会被当成「聊天图片」删掉——删消息/清空数据时顺手删掉数据库、prefs 甚至
 * 沙盒里任意可写文件。
 *
 * 所以删除前一律：解析成 canonical path（把 `..` 与符号链接解开），
 * 再确认它确实落在允许的目录里。头像早就这么做了（`MediaStorage.deleteAvatar`），
 * 附件 0.10.2 起补齐。
 *
 * 纯 JVM 路径逻辑，可用临时目录直接验。
 */
object MediaOwnership {

    /**
     * [candidate] 是否落在 [roots] 任意一个目录里面（含子目录）。
     *
     * 任何一边解析失败（路径非法、权限、根目录不存在）都返回 false：
     * 宁可少删一个文件，也不能删错。
     */
    fun isWithin(candidate: File, roots: List<File>): Boolean {
        val target = runCatching { candidate.canonicalFile }.getOrNull() ?: return false
        return roots.any { root ->
            val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return@any false
            isSameOrChild(target, canonicalRoot)
        }
    }

    /** 字符串版本：URI 解析出来的路径常用；同样按 canonical path 比较。 */
    fun isWithinPath(candidatePath: String, rootPaths: List<String>): Boolean {
        if (candidatePath.isBlank()) return false
        return isWithin(File(candidatePath), rootPaths.filter { it.isNotBlank() }.map { File(it) })
    }

    /**
     * target 是不是 root 本身或它的子孙。
     *
     * 用「分隔符结尾」比较，避免 `/files/media2` 被 `/files/media` 误判成子目录。
     */
    private fun isSameOrChild(target: File, root: File): Boolean {
        if (target == root) return true
        val rootPath = root.path.trimEnd(File.separatorChar)
        val targetPath = target.path
        return targetPath.length > rootPath.length &&
            targetPath.startsWith(rootPath) &&
            targetPath[rootPath.length] == File.separatorChar
    }
}
