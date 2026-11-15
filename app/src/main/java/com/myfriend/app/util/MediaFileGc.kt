package com.myfriend.app.util

import java.io.File

/**
 * 媒体文件「引用计数」的纯逻辑：**还有没有人引用这个文件**决定它能不能删。
 *
 * ## 为什么需要它（0.12 的缺陷）
 *
 * 聊天图片按内容寻址命名（`chat-<sha256前16>.<ext>`），同一张图只落一份。
 * 删除一条引用它的消息 / 一段会话 / 一个人格时，旧实现直接把附件路径对应的文件删掉，
 * 于是**另一条消息**（甚至另一个人格的会话）里那张同样的图就变成「图片已不在本机」。
 *
 * 现在所有删文件的地方都先问一句「还有引用吗」，没有才删；而这个判断必须**在数据库行
 * 删掉之后**再做（顺序反了就会把「本次要删的那条消息自己」算进去，永远删不掉）。
 *
 * 这里只放不依赖 Android 的部分（`java.io.File` + 一个挂起的引用判定回调），
 * 所以「两条消息共享一张图」这类场景可以直接在纯 JVM 单测里用临时目录验。
 */
object MediaFileGc {

    /** SQLite `LIKE` 的转义字符，必须与 [com.myfriend.app.data.local.MediaReferenceDao] 里的 `ESCAPE` 一致。 */
    const val LIKE_ESCAPE: Char = '\\'

    /**
     * 文件名 → `LIKE '%<escaped>%'` 的模式串。
     *
     * 文件名理论上只有哈希 / UUID（不含通配符），但这里是「数据即代码」的输入面：
     * 一份被改过的库或备份可以把 `attachmentsJson` 写成 `chat-_-%-...`，不转义的话
     * `_` 会匹配任意一个字符、`%` 会匹配任意串——引用计数会**偏高**（宁可不删），
     * 更糟的是反过来的写法可能让一条无关记录把文件「认领」走。一律转义，语义就只剩字面匹配。
     */
    fun likePattern(fileName: String): String = "%" + escapeLike(fileName) + "%"

    /** 转义 `LIKE` 的三个元字符：`\` 自己、`%`、`_`（顺序不能反，先转义转义符）。 */
    fun escapeLike(raw: String): String = buildString(raw.length + 8) {
        raw.forEach { ch ->
            when (ch) {
                LIKE_ESCAPE, '%', '_' -> append(LIKE_ESCAPE).append(ch)
                else -> append(ch)
            }
        }
    }

    /**
     * 把数据库里的 `file://` 路径（或裸文件名）收成文件名。
     *
     * 引用计数比较的是「文件名」：库里的 `avatarUri` 是绝对路径，而 `attachmentsJson`
     * 也是绝对路径——同一个文件在库里可能有两种写法，比文件名才不会漏。
     * 不是 `file://` 也不是裸文件名的值（`content://`、`https://`）一律返回 null：
     * 那些不是本机媒体文件，谈不上引用。
     */
    fun fileNameOf(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        // 带目录穿越的整条路径直接不认（与 MediaOwnership / BackupAvatarPath 同一套口径）。
        if (value.contains("..")) return null
        val path = when {
            value.startsWith("file://") -> value.removePrefix("file://")
            value.contains("://") -> return null
            else -> value
        }
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        return name.takeIf { it.isNotBlank() && it != "." && it != ".." }
    }

    /**
     * **单点守卫**：只有 [isReferenced] 说「没被引用」时才删这个文件。
     *
     * @param file 已经过目录归属校验的目标文件（属于 `files/media` / `files/avatars`）
     * @param isReferenced 传文件名，回答「库里还有没有行引用它」
     * @return 真的删掉了没有
     */
    suspend fun deleteIfUnreferenced(file: File, isReferenced: suspend (String) -> Boolean): Boolean {
        if (!file.isFile) return false
        if (isReferenced(file.name)) return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    /** 批量版：逐个问引用、逐个删，返回真的删掉的个数。 */
    suspend fun deleteUnreferencedFiles(
        files: Iterable<File>,
        isReferenced: suspend (String) -> Boolean
    ): Int {
        var deleted = 0
        files.forEach { file ->
            if (deleteIfUnreferenced(file, isReferenced)) deleted++
        }
        return deleted
    }
}
