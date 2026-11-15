package com.myfriend.app.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * ## 为什么还要一把进程级锁（0.12 遗留缺陷 2）
 *
 * 「查引用 → 删文件」是两步。另一边 `persistChatImage` 对同一张内容同名文件做
 * 「写文件 → 返回引用」，如果两步之间被插进来，就会出现：
 * 删除刚查完「没人引用」、另一个协程把**同一个文件名**写好并返回给一条新消息、
 * 删除随后把文件删掉 —— 新消息指向一个已经消失的文件。
 *
 * 所以 [ioMutex] 是**进程级**（object 单例）临界区：
 * - 引用查询与删除在同一个临界区里完成（[deleteIfUnreferencedWithReferrer]）；
 * - 写文件与「返回引用」在同一个临界区里完成（`MediaStorage.persistChatImage` / `persistAvatar`）。
 * 这样两边不可能交错到「查到无引用之后、真正删除之前又有人写回同一个文件名」。
 *
 * 这里只放不依赖 Android 的部分（`java.io.File` + 一个挂起的引用判定回调），
 * 所以「两条消息共享一张图」这类场景可以直接在纯 JVM 单测里用临时目录验。
 */
object MediaFileGc {

    /**
     * 媒体文件的写/删临界区（进程级单例）。
     *
     * 用 `Mutex` 而不是 `ReentrantLock`：临界区里要 `await` 真正的引用查询（Room / DataStore），
     * 挂起期间线程不能被一把跟线程绑定的锁占住。**不要嵌套获取**（`Mutex` 不可重入）。
     */
    val ioMutex: Mutex = Mutex()

    /** 在媒体文件临界区里执行 [block]；写文件、查引用、删文件都必须走这里。 */
    suspend fun <T> withFileIoLock(block: suspend () -> T): T = ioMutex.withLock { block() }

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
     * 查询与删除在同一个 [ioMutex] 临界区里：调用方传进来的 [isReferenced] 必须是真正的
     * 引用查询（Room / DataStore），而不是「提前查好、这里只剩一个集合包含判断」。
     * 新代码优先用 [deleteIfUnreferencedWithReferrer]，那个签名能把批量查询也收进临界区。
     *
     * @param file 已经过目录归属校验的目标文件（属于 `files/media` / `files/avatars`）
     * @param isReferenced 传文件名，回答「库里还有没有行引用它」
     * @return 真的删掉了没有
     */
    suspend fun deleteIfUnreferenced(file: File, isReferenced: suspend (String) -> Boolean): Boolean =
        withFileIoLock {
            if (!file.isFile) return@withFileIoLock false
            if (isReferenced(file.name)) return@withFileIoLock false
            runCatching { file.delete() }.getOrDefault(false)
        }

    /**
     * 批量版（旧签名）：逐个问引用、逐个删；每次都在 [ioMutex] 里完成「查询 + 删除」。
     *
     * 批量场景优先用 [deleteUnreferencedFilesWithReferrer]：它只查一次，而且整批都在一个临界区里。
     */
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

    /**
     * **引用查询 + 删除的原子入口**（0.12 遗留缺陷 2 的修法）。
     *
     * [referrer] 在 [ioMutex] 临界区内被调用：它查库的这段时间里，
     * `persistChatImage` / `persistAvatar` 不可能把同一个文件写出来并返回引用。
     *
     * @param file 已过归属校验的候选文件
     * @param referrer 传候选文件名集合，回答「其中哪些还有引用」
     */
    suspend fun deleteIfUnreferencedWithReferrer(
        file: File,
        referrer: suspend (Collection<String>) -> Set<String>
    ): Boolean = withFileIoLock {
        if (!file.isFile) return@withFileIoLock false
        val referenced = referrer(listOf(file.name))
        if (file.name in referenced) return@withFileIoLock false
        runCatching { file.delete() }.getOrDefault(false)
    }

    /**
     * 批量版的原子入口：去重 → 一次性查引用 → 逐个删，全部在同一个 [ioMutex] 临界区里。
     *
     * @return 真正删掉的文件数
     */
    suspend fun deleteUnreferencedFilesWithReferrer(
        files: Iterable<File>,
        referrer: suspend (Collection<String>) -> Set<String>
    ): Int = withFileIoLock {
        val candidates = files.filter { it.isFile }.distinctBy { it.name }
        if (candidates.isEmpty()) return@withFileIoLock 0
        val referenced = referrer(candidates.map { it.name }.toSet())
        candidates.count { file ->
            file.name !in referenced && runCatching { file.delete() }.getOrDefault(false)
        }
    }
}
