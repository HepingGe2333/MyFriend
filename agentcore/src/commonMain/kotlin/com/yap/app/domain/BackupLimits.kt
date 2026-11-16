package com.yap.app.domain

/**
 * 备份导入的大小上限（安全审计 P2-3）。
 *
 * 以前导入是 `openInputStream(uri).bufferedReader().readText()`：不分大小先整份读成字符串，
 * 再交给 `BackupCodec.decode`。用户选中一个几百 MB～GB 的「备份」时，OOM 发生在 UI 附近，
 * 表现是「点了一下就闪退」，没有任何可解释的提示。
 *
 * 现在两道闸：
 * - **读文件前**：查大小（`OpenableColumns.SIZE` / `AssetFileDescriptor.length`），超限直接拒绝；
 * - **读文件时**：按字节流读到上限再多读 1 字节就停，绝不把整份文件读进内存；
 * - **解析前**：`BackupCodec.decode` 再按字符数兜一次（防别的调用方绕过上面的闸）。
 *
 * 64MB 的依据：真实的备份是「几十段对话 + 几百条记忆」的量级，正常最大也就几 MB；
 * 给 64MB 已经是一个数量级的余量，再大只可能是拿错了文件。
 */
object BackupLimits {

    /** 单份备份文件 / 字符串的上限：64MB。 */
    const val MAX_BACKUP_BYTES: Long = 64L * 1024 * 1024

    /** 解析前的字符数上限：UTF-16 字符串 2 字节/字符，这里与字节上限同值（严格不弱于上面的闸）。 */
    const val MAX_BACKUP_CHARS: Int = 64 * 1024 * 1024

    /** 文件大小超限了吗。 */
    fun isTooLarge(byteCount: Long): Boolean = byteCount > MAX_BACKUP_BYTES

    /** 解析前的字符数超限了吗。 */
    fun isTooManyChars(charCount: Int): Boolean = charCount > MAX_BACKUP_CHARS

    /** 给文案用的 MB 数（向上取整，避免出现「0MB」）。 */
    fun megabytes(bytes: Long = MAX_BACKUP_BYTES): Long {
        val mb = bytes / (1024 * 1024)
        return if (mb <= 0L) 1L else mb
    }
}
