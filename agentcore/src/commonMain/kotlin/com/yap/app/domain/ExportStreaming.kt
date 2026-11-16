package com.yap.app.domain

/**
 * 把「一次导出」分批写成 JSON 文本。
 *
 * 导出一整库对话可能是几 MB～几十 MB：`json.encodeToString(整份 bundle)` 会先在内存里
 * 拼出一个巨字符串，再整体写文件，主线程/堆都会有明显峰值。这里改成「每条会话单独
 * 序列化 → 攒够一批就交给调用方写出去」，峰值只跟**单条会话**和批大小有关。
 *
 * 输出与 `ExportBundle` 的字段顺序一致（`schemaVersion` / `exportedAt` / `sessions`），
 * 读旧文件的实现不需要任何改动。`sessions` 里的每条由调用方用同一个 `Json`
 * 实例序列化，因此转义规则与以前完全一致。
 *
 * 纯 Kotlin + suspend lambda：可用 `runTest` 直接验分批、顺序与失败传播。
 */
class ExportJsonStream(
    private val batchChars: Int = DEFAULT_BATCH_CHARS,
    private val emit: suspend (String) -> Unit
) {

    private val buffer = StringBuilder()
    private var sessions = 0
    private var started = false
    private var finished = false

    init {
        require(batchChars > 0) { "批大小必须为正数" }
    }

    /** 写开头。必须在 [writeSession] 之前调用一次。 */
    suspend fun start(schemaVersion: Int, exportedAt: Long) {
        check(!started) { "导出流已经开始了" }
        check(!finished) { "导出流已经结束了" }
        started = true
        buffer.append("{\"schemaVersion\":")
        buffer.append(schemaVersion)
        buffer.append(",\"exportedAt\":")
        buffer.append(exportedAt)
        buffer.append(",\"sessions\":[")
        // 开头只有几十字节，不急着 flush；等第一条会话进来时一起走。
    }

    /** 追加一条已经序列化好的会话 JSON。 */
    suspend fun writeSession(encoded: String) {
        check(started) { "先调用 start()" }
        check(!finished) { "导出流已经结束了" }
        if (sessions > 0) buffer.append(',')
        buffer.append(encoded)
        sessions++
        if (buffer.length >= batchChars) flush()
    }

    /** 收尾（`]}`），并把最后一批强制写出去。 */
    suspend fun finish() {
        check(started) { "先调用 start()" }
        if (finished) return
        buffer.append("]}")
        finished = true
        flush(force = true)
    }

    /** 已经写出去的会话条数（测试/日志用）。 */
    val sessionCount: Int get() = sessions

    private suspend fun flush(force: Boolean = false) {
        if (buffer.isEmpty()) return
        if (!force && buffer.length < batchChars) return
        val chunk = buffer.toString()
        buffer.setLength(0)
        // 故意不 catch：写文件失败必须让整次导出失败，而不是「一半文件看着像成功」。
        emit(chunk)
    }

    companion object {
        /**
         * 一批最多攒多少字符再写盘。
         *
         * 256KB 对闪存是一次很小的写、对内存也远低于任何一台设备的压力线；
         * 太小（比如 4KB）会让一次导出变成几千次系统调用。
         */
        const val DEFAULT_BATCH_CHARS = 256 * 1024
    }
}
