package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 备份导入的大小上限（安全审计 P2-3）。 */
class BackupLimitsTest {

    @Test
    fun `64MB 以内放行 超过拒绝`() {
        assertEquals(64L * 1024 * 1024, BackupLimits.MAX_BACKUP_BYTES)
        assertFalse(BackupLimits.isTooLarge(0L))
        assertFalse(BackupLimits.isTooLarge(64L * 1024 * 1024))
        assertTrue(BackupLimits.isTooLarge(64L * 1024 * 1024 + 1))
        assertTrue("一个 GB 级的文件必须拒绝", BackupLimits.isTooLarge(1024L * 1024 * 1024))
    }

    @Test
    fun `解析前的字符数也有限制`() {
        assertFalse(BackupLimits.isTooManyChars(1_000))
        assertFalse(BackupLimits.isTooManyChars(BackupLimits.MAX_BACKUP_CHARS))
        assertTrue(BackupLimits.isTooManyChars(BackupLimits.MAX_BACKUP_CHARS + 1))
    }

    @Test
    fun `文案里的 MB 数不会出现零`() {
        assertEquals(64L, BackupLimits.megabytes())
        assertEquals(1L, BackupLimits.megabytes(1024L))
        assertEquals(1L, BackupLimits.megabytes(0L))
        assertEquals(2L, BackupLimits.megabytes(2L * 1024 * 1024))
    }
}
