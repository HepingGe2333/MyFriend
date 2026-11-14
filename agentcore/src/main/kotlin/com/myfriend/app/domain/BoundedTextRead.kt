package com.myfriend.app.domain

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 有界读文本：超过上限就停，绝不把一份 GB 级的「备份」读进内存（安全审计 P2-3）。
 *
 * 为什么不是 `reader().readText()`：那会先把整份文件读成字符串再判断，判断的时候
 * 内存已经没了。这里按字节流读到 `maxBytes + 1` 就抛 [TooLarge]，堆峰值只有上限那么多。
 *
 * 按**字节**计数（UTF-8 下一个汉字 3 字节），与文件大小、`OpenableColumns.SIZE` 同一口径。
 * 纯 JDK（`java.io`），普通 JVM 单测用 `ByteArrayInputStream` 就能钉住边界。
 */
object BoundedTextRead {

    /** 读到的内容超过上限。带上限值，好让界面说清「超过多少 MB」。 */
    class TooLarge(val limitBytes: Long) : Exception("text exceeds $limitBytes bytes")

    /**
     * 读到流末尾并返回 UTF-8 文本。
     *
     * @throws TooLarge 内容超过 [maxBytes]。
     */
    fun read(input: InputStream, maxBytes: Long = BackupLimits.MAX_BACKUP_BYTES): String {
        val limit = if (maxBytes <= 0L) BackupLimits.MAX_BACKUP_BYTES else maxBytes
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(DEFAULT_CHUNK)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            if (read == 0) continue
            if (buffer.size().toLong() + read > limit) throw TooLarge(limit)
            buffer.write(chunk, 0, read)
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    private const val DEFAULT_CHUNK = 8 * 1024
}
