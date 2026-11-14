package com.myfriend.app.domain

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 「删文件之前先确认它在我自己的目录里」（安全审计 P2-2）。
 *
 * 头像 0.10.1 就有这道守卫，附件直到 0.10.2 才补：`attachmentsJson` 里的路径来自数据库，
 * 上游一旦被污染（手工改库、将来某条导入路径没丢掉附件字段），
 * 一个 `file:///.../databases/myfriend.db` 或 `../../shared_prefs/x.xml`
 * 就会被当成聊天图片删掉。这里用临时目录验真实路径语义。
 */
class MediaOwnershipTest {

    private val root: File = Files.createTempDirectory("media-ownership").toFile()
    private val filesDir = File(root, "files").apply { mkdirs() }
    private val mediaDir = File(filesDir, "media").apply { mkdirs() }
    private val avatarsDir = File(filesDir, "avatars").apply { mkdirs() }

    private val roots get() = listOf(mediaDir, avatarsDir)

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun `媒体目录里的文件允许删除`() {
        val chatImage = File(mediaDir, "chat-1.jpg").apply { writeText("x") }
        assertTrue(MediaOwnership.isWithin(chatImage, roots))
    }

    @Test
    fun `头像目录里的文件允许删除`() {
        val avatar = File(avatarsDir, "avatar-1.jpg").apply { writeText("x") }
        assertTrue(MediaOwnership.isWithin(avatar, roots))
    }

    @Test
    fun `用两点跳出目录的路径被拒绝`() {
        val escaped = File(mediaDir, "../../databases/myfriend.db")
        assertFalse("`..` 必须被 canonical path 解开后拒绝", MediaOwnership.isWithin(escaped, roots))
        assertFalse(
            MediaOwnership.isWithinPath(
                "${mediaDir.path}/../../../shared_prefs/x.xml",
                roots.map { it.path }
            )
        )
    }

    @Test
    fun `外部绝对路径被拒绝`() {
        val outside = File(root, "outside").apply { mkdirs() }
        val victim = File(outside, "keep.txt").apply { writeText("keep") }
        assertFalse(MediaOwnership.isWithin(victim, roots))
        assertFalse(MediaOwnership.isWithinPath("/etc/hosts", roots.map { it.path }))
    }

    @Test
    fun `同前缀但不是子目录的路径被拒绝`() {
        // /files/media2 不能被 /files/media 误判成子目录。
        val sibling = File(filesDir, "media2").apply { mkdirs() }
        val file = File(sibling, "chat-1.jpg").apply { writeText("x") }
        assertFalse(MediaOwnership.isWithin(file, roots))
    }

    @Test
    fun `应用根目录本身被拒绝`() {
        val databases = File(filesDir, "databases").apply { mkdirs() }
        val db = File(databases, "myfriend.db").apply { writeText("db") }
        assertFalse("数据库不是媒体文件", MediaOwnership.isWithin(db, roots))
    }

    @Test
    fun `目录本身也算在内 但空路径不算`() {
        assertTrue(MediaOwnership.isWithin(mediaDir, roots))
        assertFalse(MediaOwnership.isWithinPath("", roots.map { it.path }))
        assertFalse(MediaOwnership.isWithin(File(root, "nope"), emptyList()))
    }
}
