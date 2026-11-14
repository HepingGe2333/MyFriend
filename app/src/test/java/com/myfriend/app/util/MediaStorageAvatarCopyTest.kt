package com.myfriend.app.util

import com.myfriend.app.domain.MediaOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 「复制人格时头像要复制成独立文件」的测试（审计 P2-11 / 0.11.1）。
 *
 * 背景：`PersonaRepository.duplicate` 以前把 `avatarUri` 原样复制，两个 `personas` 行指向同一个
 * `files/avatars/avatar-*.jpg`；删副本 / 给副本换头像都会 `deleteAvatar(路径)`，原人格的头像文件
 * 随之消失。
 *
 * `MediaStorage.copyAvatarFile` 是刻意抽出来的**纯文件**版本（不碰 `android.net.Uri`），
 * 单测用临时目录就能验：复制成功、名字不同、内容一致、目录外的源一律拒绝。
 */
class MediaStorageAvatarCopyTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

    @Test
    fun `复制头像生成独立文件 内容一致`() {
        val dir = tempDir("avatars")
        val source = File(dir, "avatar-source.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }

        val copy = MediaStorage.copyAvatarFile(source, dir, "avatar-copy.jpg")

        assertNotNull("同目录内的头像应当能复制", copy)
        assertEquals("avatar-copy.jpg", copy!!.name)
        assertFalse("文件名必须不同（不能共享同一个文件）", copy.name == source.name)
        assertTrue(copy.readBytes().contentEquals(source.readBytes()))
        assertTrue("源文件仍在（复制不是移动）", source.isFile)
    }

    @Test
    fun `默认新文件名带 avatar 前缀`() {
        val dir = tempDir("avatars")
        val source = File(dir, "avatar-source.jpg").apply { writeBytes(byteArrayOf(9)) }

        val copy = MediaStorage.copyAvatarFile(source, dir)

        assertNotNull(copy)
        assertTrue(copy!!.name.startsWith("avatar-"))
        assertTrue(copy.name.endsWith(".jpg"))
    }

    @Test
    fun `目录外的源文件一律拒绝`() {
        val dir = tempDir("avatars")
        val outside = tempDir("outside").let { File(it, "secret.jpg").apply { writeBytes(byteArrayOf(7)) } }

        assertNull("只允许复制 files/avatars 里的文件", MediaStorage.copyAvatarFile(outside, dir))
        assertTrue("目录外文件不该被复制进 avatars", dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `不安全的文件名与不存在的源都返回 null`() {
        val dir = tempDir("avatars")
        val source = File(dir, "avatar-source.jpg").apply { writeBytes(byteArrayOf(1)) }

        assertNull(MediaStorage.copyAvatarFile(source, dir, "../escape.jpg"))
        assertNull(MediaStorage.copyAvatarFile(source, dir, ""))
        assertNull(MediaStorage.copyAvatarFile(File(dir, "missing.jpg"), dir, "avatar-new.jpg"))
    }

    @Test
    fun `同前缀的兄弟目录不能被当成 avatars`() {
        val parent = tempDir("media")
        val avatars = File(parent, "avatars").apply { mkdirs() }
        val sibling = File(parent, "avatars2").apply { mkdirs() }
        val source = File(sibling, "avatar-x.jpg").apply { writeBytes(byteArrayOf(1)) }

        assertNull(
            "`avatars2` 与 `avatars` 前缀相同但不是子目录",
            MediaStorage.copyAvatarFile(source, avatars)
        )
        assertTrue(MediaOwnership.isWithin(source, listOf(sibling)))
    }

    // ------------------------------------------------------------------ 接线

    @Test
    fun `复制人格走文件复制 不再共享 avatarUri`() {
        val repo = source("src/main/java/com/myfriend/app/data/repo/PersonaRepository.kt")
        val body = bodyOf(repo, "suspend fun duplicate(")
        assertTrue(
            "复制人格必须把头像复制成独立文件",
            body.contains("MediaStorage.copyAvatar(appContext, source.avatarUri)")
        )
        assertTrue("复制件要用复制出来的路径", body.contains("avatarUri = copyAvatarUri"))
    }

    private fun projectRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/myfriend/app").isDirectory }
            ?: error("找不到 app 模块目录：${File(".").absolutePath}")
    }

    private fun source(relative: String): String = File(projectRoot(), relative).readText()

    private fun bodyOf(text: String, marker: String): String {
        val start = text.indexOf(marker)
        require(start >= 0) { "找不到 $marker" }
        val rest = text.substring(start + marker.length)
        val ends = listOf("\n    private suspend fun ", "\n    private fun ", "\n    suspend fun ", "\n    fun ")
        val end = ends.map { rest.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }
}
