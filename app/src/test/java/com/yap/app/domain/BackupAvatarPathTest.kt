package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份里的头像路径（安全审计 P2-5）。
 *
 * 导出只写文件名、不写设备绝对路径；导入把「文件名 / 老备份的 file:// 路径」都归一成文件名，
 * 再由调用方按「本机有没有这个文件」决定恢复还是清空。
 */
class BackupAvatarPathTest {

    private val absolute =
        "file:///data/user/0/com.yap.app/files/avatars/avatar-1234-abcd.jpg"

    @Test
    fun `导出把绝对路径收成文件名`() {
        assertEquals("avatar-1234-abcd.jpg", BackupAvatarPath.portable(absolute))
        assertNull("非 file:// 的值不写进备份", BackupAvatarPath.portable("content://media/external/images/1"))
        assertNull(BackupAvatarPath.portable(null))
        assertNull(BackupAvatarPath.portable(""))
    }

    @Test
    fun `导入能吃裸文件名 也能吃老备份的绝对路径`() {
        assertEquals("avatar-1234-abcd.jpg", BackupAvatarPath.fileName("avatar-1234-abcd.jpg"))
        assertEquals("avatar-1234-abcd.jpg", BackupAvatarPath.fileName(absolute))
        assertNull(BackupAvatarPath.fileName(null))
    }

    @Test
    fun `目录穿越与奇怪文件名一律拒绝`() {
        assertFalse(BackupAvatarPath.isSafeName("../yap.db"))
        assertFalse(BackupAvatarPath.isSafeName("a/b.jpg"))
        assertFalse(BackupAvatarPath.isSafeName("a\\b.jpg"))
        assertFalse(BackupAvatarPath.isSafeName(".."))
        assertFalse(BackupAvatarPath.isSafeName(""))
        assertNull(BackupAvatarPath.fileName("file:///data/x/avatars/../../databases/yap.db"))
        assertTrue(BackupAvatarPath.isSafeName("avatar-1.jpg"))
    }

    @Test
    fun `本机路径按头像目录拼出来`() {
        assertEquals(
            "file:///data/user/0/x/files/avatars/avatar-1.jpg",
            BackupAvatarPath.localUri("/data/user/0/x/files/avatars", "avatar-1.jpg")
        )
        assertEquals(
            "file:///data/user/0/x/files/avatars/avatar-1.jpg",
            BackupAvatarPath.localUri("/data/user/0/x/files/avatars/", "avatar-1.jpg")
        )
    }
}
