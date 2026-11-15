package com.myfriend.app.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 媒体删除的 TOCTOU（0.12 遗留缺陷 2）。
 *
 * 旧实现是两步：`referrer()` 查完引用 → 再 `file.delete()`。中间如果另一个协程
 * `persistChatImage` 把**同一个内容寻址文件名**写出来并返回给一条新消息，删除就会把它删掉。
 *
 * 这里不依赖 Android Bitmap（纯 JVM 跑不了真选图），而是直接钉住修法的契约：
 * - [MediaFileGc.deleteIfUnreferencedWithReferrer] 的「查询 + 删除」在 [MediaFileGc.ioMutex] 内；
 * - 写文件的一方也用同一个 `withFileIoLock`；删除先拿到锁时，写方必须等删除完成才写，
 *   所以最终文件一定存在（而不是「查询时无引用 → 写回 → 被删」）。
 *
 * 另加源码契约：`MediaStorage.persistChatImage` / `persistAvatar` 与三条删除路径都必须走同一把锁。
 */
class MediaConcurrencyLockTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `delete holding the lock blocks the writer until the file is gone`() = runBlocking {
        val file = temp.newFile("chat-0123456789abcdef.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val queryEntered = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<Unit>()
        val writerStarted = CompletableDeferred<Unit>()
        var writerReturned = false

        val deleter = launch(Dispatchers.Default) {
            MediaFileGc.deleteIfUnreferencedWithReferrer(file) {
                // 已经持锁，并且真正走到了「引用查询」这一步：这正是旧实现最危险的窗口。
                queryEntered.complete(Unit)
                releaseQuery.await()
                emptySet()
            }
        }

        queryEntered.await()
        assertTrue("删除路径必须真的持有进程级媒体锁", MediaFileGc.ioMutex.isLocked)

        val writer = launch(Dispatchers.Default) {
            writerStarted.complete(Unit)
            MediaFileGc.withFileIoLock {
                // 与 persistChatImage 同构：在锁内把同名文件写出来并「返回引用」。
                file.writeBytes(byteArrayOf(9, 8, 7))
                writerReturned = true
            }
        }

        writerStarted.await()
        delay(150)
        assertFalse("删除还持有锁时，写文件的一方不能插进「查完引用、还没删」的窗口", writerReturned)

        releaseQuery.complete(Unit)
        deleter.join()
        writer.join()

        assertTrue("写入发生在删除之后：文件必须仍然存在", file.isFile)
        assertEquals("文件内容必须是写方最后写进去的", 3, file.readBytes().size)
        assertTrue(writerReturned)
        assertFalse(MediaFileGc.ioMutex.isLocked)
    }

    @Test
    fun `a referenced file is kept and an unreferenced file is deleted under the same guard`() = runBlocking {
        val kept = temp.newFile("avatar-aaaaaaaaaaaaaaaa-1.jpg")
        val removed = temp.newFile("avatar-bbbbbbbbbbbbbbbb-1.jpg")
        val referenced = setOf(kept.name)
        val deleted = MediaFileGc.deleteUnreferencedFilesWithReferrer(listOf(kept, removed)) { names ->
            assertEquals("批量查询只问一次，并且拿到的是候选文件名", setOf(kept.name, removed.name), names.toSet())
            names.filterTo(mutableSetOf()) { it in referenced }
        }

        assertEquals(1, deleted)
        assertTrue("还有引用：文件不能删", kept.isFile)
        assertFalse("零引用：文件必须删", removed.exists())
    }

    @Test
    fun `media storage writes and guarded deletes share the same process lock`() {
        val gc = File("src/main/java/com/myfriend/app/util/MediaFileGc.kt").readText()
        assertTrue("锁必须是进程级单例（object 里的 Mutex）", gc.contains("val ioMutex: Mutex = Mutex()"))
        assertTrue("写文件/删文件统一入口", gc.contains("suspend fun <T> withFileIoLock("))
        assertTrue(
            "引用查询与删除在同一个临界区",
            gc.contains("suspend fun deleteUnreferencedFilesWithReferrer(") &&
                gc.contains("withFileIoLock {") &&
                gc.contains("referrer(candidates.map { it.name }.toSet())")
        )

        val storage = File("src/main/java/com/myfriend/app/util/MediaStorage.kt").readText()
        val persistChat = storage.substringAfter("suspend fun persistChatImage(")
            .substringBefore("suspend fun toContextDataUrl(")
        val persistAvatar = storage.substringAfter("suspend fun persistAvatar(")
            .substringBefore("suspend fun copyAvatar(")
        assertTrue("persistChatImage 的「写文件 + 返回引用」必须在锁内", persistChat.contains("MediaFileGc.withFileIoLock"))
        assertTrue("persistAvatar 的「写文件 + 返回引用」必须在锁内", persistAvatar.contains("MediaFileGc.withFileIoLock"))

        // 三条带引用守卫的批量删除都必须走「查询 + 删除」同一个临界区，不能提前算 referenced。
        val deleteFunctions = listOf(
            "deleteFilesIfUnreferenced",
            "clearChatImagesExceptIfUnreferenced",
            "clearUnreferencedAvatarsIfUnreferenced"
        )
        deleteFunctions.forEach { name ->
            val body = storage.substringAfter("suspend fun $name(").take(1600).substringBefore("    /**")
            assertTrue("$name 必须走带锁的批量删除", body.contains("deleteUnreferencedFilesWithReferrer"))
            assertFalse("$name 不能提前查引用再传集合", body.contains("val referenced = referrer("))
        }
    }
}
