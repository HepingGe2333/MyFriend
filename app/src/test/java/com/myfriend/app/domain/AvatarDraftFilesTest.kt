package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 头像草稿文件的回收（审计 P2-2）。
 *
 * 三种场景：连选两张（删前一张）、取消编辑（删本次新建的全部）、保存（留下被采纳的那张）。
 */
class AvatarDraftFilesTest {

    private val first = "file:///files/avatars/avatar-a.jpg"
    private val second = "file:///files/avatars/avatar-b.jpg"
    private val stored = "file:///files/avatars/avatar-old.jpg"

    @Test
    fun `连选两张时删掉前一张草稿`() {
        val pending = setOf(first)
        assertEquals(listOf(first), AvatarDraftFiles.onPicked(first, pending))
    }

    @Test
    fun `前一张不是本次草稿时不动它`() {
        assertTrue("已经保存过的头像不能在这里删（由仓库层负责）", AvatarDraftFiles.onPicked(stored, setOf(first)).isEmpty())
        assertTrue(AvatarDraftFiles.onPicked(null, setOf(first)).isEmpty())
    }

    @Test
    fun `取消编辑删掉本次新建的全部草稿`() {
        val pending = setOf(first, second)
        assertEquals(listOf(first, second), AvatarDraftFiles.onDiscard(stored, pending))
    }

    @Test
    fun `取消时保留原有那张`() {
        assertTrue(AvatarDraftFiles.onDiscard(first, setOf(first)).isEmpty())
        assertTrue(AvatarDraftFiles.onDiscard(null, emptySet()).isEmpty())
    }

    @Test
    fun `保存成功只留被采纳的那张`() {
        val pending = setOf(first, second)
        assertEquals(listOf(first), AvatarDraftFiles.onSaved(second, pending))
        assertTrue(AvatarDraftFiles.onSaved(second, setOf(second)).isEmpty())
    }

    @Test
    fun `空串不会被当成文件删`() {
        assertTrue(AvatarDraftFiles.onDiscard("", setOf("", "   ")).isEmpty())
    }
}
