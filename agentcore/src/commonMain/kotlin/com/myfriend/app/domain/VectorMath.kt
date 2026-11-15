package com.myfriend.app.domain

import kotlin.math.sqrt

/**
 * 向量算术：余弦、topK 部分选择、以及入库用的定点编码。
 *
 * 全是纯函数，不碰 Room / Android / 时钟；坏输入（空数组、长度不一、NaN、INF、坏 blob）
 * 一律给出「安全值」而不是抛异常 —— 检索是「想起点什么」的辅助，它不该有机会让聊天崩掉。
 *
 * ## 为什么单独拎出来
 *
 * [LocalEmbedding] 决定「一段话变成什么向量」，这里只决定「两个向量有多像、怎么取前 K 个」。
 * 将来如果真换成神经网络 embedding（哪怕是下载的小模型），换的也只是 [LocalEmbedding]，
 * 余弦、topK、编码格式这些都不用动。
 */
object VectorMath {

    /** 小于这个范数就认为向量是零向量：空文本、纯标点、纯虚词都会走到这里。 */
    private const val ZERO_NORM = 1e-6

    // ---------------------------------------------------------------- 相似度

    /** 点积。长度不一返回 0（不同实现、不同维度的向量不该被比较）。 */
    fun dot(a: FloatArray, b: FloatArray): Double {
        if (a.isEmpty() || a.size != b.size) return 0.0
        var sum = 0.0
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            if (!x.isFinite() || !y.isFinite()) continue
            sum += x.toDouble() * y.toDouble()
        }
        return if (sum.isFinite()) sum else 0.0
    }

    /** L2 范数。含 NaN/INF 的元素按 0 算，返回值一定是有限数。 */
    fun norm(vector: FloatArray): Double {
        if (vector.isEmpty()) return 0.0
        var sum = 0.0
        for (value in vector) {
            if (!value.isFinite()) continue
            sum += value.toDouble() * value.toDouble()
        }
        if (!sum.isFinite() || sum <= 0.0) return 0.0
        return sqrt(sum)
    }

    /**
     * 余弦相似度，落在 -1..1。
     *
     * [LocalEmbedding] 产出的向量已经做过 L2 归一化，此时它等价于点积；
     * 但库里存的向量是「上一个版本写进去的」——可能因为代码回滚、字节损坏而没归一化，
     * 所以这里还是老老实实除一次范数：多算 768 次乘法，换「坏数据算出来仍然是合法相似度」。
     *
     * 任一为空、维度不同、含零范数 → 0.0（= 不相似，而不是报错）。
     */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.isEmpty() || a.size != b.size) return 0.0
        val dot = dot(a, b)
        if (dot == 0.0) return 0.0
        val normA = norm(a)
        val normB = norm(b)
        if (normA <= ZERO_NORM || normB <= ZERO_NORM) return 0.0
        val value = dot / (normA * normB)
        if (!value.isFinite()) return 0.0
        return value.coerceIn(-1.0, 1.0)
    }

    /** 是不是零向量（空文本的嵌入）：调用方应当跳过它，别拿它去算相似度。 */
    fun isZero(vector: FloatArray): Boolean {
        if (vector.isEmpty()) return true
        for (value in vector) {
            if (!value.isFinite()) continue
            if (value != 0f) return false
        }
        return true
    }

    // ---------------------------------------------------------------- 部分选择

    /**
     * 取分数最高的前 [limit] 个，按分数降序返回。
     *
     * 用大小为 limit 的小顶堆做部分选择，而不是「先全量排序再截断」：
     * 一次搜索可能扫过上千条记忆，`sortedBy` 那种写法会为整张表建一个临时列表，
     * 在低端机上正好是内存尖峰。这里内存只跟 limit 有关（默认 16），跟候选多少无关。
     *
     * 同分时**先到的先留下**（按 [Iterable] 的遍历顺序），保证同一个库同样的查询每次排出来一样。
     * 分数是 NaN / INF 的条目直接丢掉：宁可少一条候选，也不要把排序搅乱。
     */
    fun <T> topK(items: Iterable<T>, limit: Int, score: (T) -> Double): List<T> {
        if (limit <= 0) return emptyList()
        val heap = MinHeap<Entry<T>>(limit + 1, WORST_FIRST)
        var seq = 0
        for (item in items) {
            val value = score(item)
            if (value.isFinite()) {
                if (heap.size < limit) {
                    heap.add(Entry(item, value, seq))
                } else {
                    val worst = heap.peek()
                    // 新来的同分一定是「后到的」，按同分先到先留的约定它更差，所以只用严格大于。
                    if (worst != null && value > worst.score) {
                        heap.poll()
                        heap.add(Entry(item, value, seq))
                    }
                }
            }
            seq++
        }
        return heap.toList()
            .sortedWith(compareByDescending<Entry<T>> { it.score }.thenBy { it.seq })
            .map { it.item }
    }

    /** 堆里的一个候选：带上遍历序号，用来把同分的顺序钉死。 */
    private data class Entry<T>(val item: T, val score: Double, val seq: Int)

    /** 小顶堆的「最差」：分数小的在上；同分时后到的（序号大的）在上。 */
    private val WORST_FIRST: Comparator<Entry<*>> =
        compareBy<Entry<*>> { it.score }.thenByDescending { it.seq }

    /**
     * 极简二叉小顶堆（commonMain 里没有 `java.util.PriorityQueue`）。
     *
     * 只实现 [topK] 用到的四个操作；比较器把「最差」放在堆顶，容量只有 limit，
     * 和原来的 JDK 实现一样是 O(n log limit)、内存只跟 limit 有关。
     */
    private class MinHeap<T>(initialCapacity: Int, private val comparator: Comparator<in T>) {
        private val items = ArrayList<T>(initialCapacity.coerceAtLeast(1))

        val size: Int get() = items.size

        fun peek(): T? = items.firstOrNull()

        fun add(value: T) {
            items.add(value)
            siftUp(items.size - 1)
        }

        fun poll(): T? {
            if (items.isEmpty()) return null
            val root = items[0]
            val last = items.removeAt(items.size - 1)
            if (items.isNotEmpty()) {
                items[0] = last
                siftDown(0)
            }
            return root
        }

        fun toList(): List<T> = items.toList()

        private fun siftUp(start: Int) {
            var index = start
            while (index > 0) {
                val parent = (index - 1) / 2
                if (comparator.compare(items[index], items[parent]) >= 0) return
                swap(index, parent)
                index = parent
            }
        }

        private fun siftDown(start: Int) {
            var index = start
            while (true) {
                val left = index * 2 + 1
                if (left >= items.size) return
                val right = left + 1
                var smallest = left
                if (right < items.size && comparator.compare(items[right], items[left]) < 0) {
                    smallest = right
                }
                if (comparator.compare(items[smallest], items[index]) >= 0) return
                swap(index, smallest)
                index = smallest
            }
        }

        private fun swap(a: Int, b: Int) {
            val tmp = items[a]
            items[a] = items[b]
            items[b] = tmp
        }
    }

    // ---------------------------------------------------------------- 编解码

    /**
     * FloatArray → ByteArray（小端 float32，每条 4 字节）。
     *
     * 小端是因为 Android 设备（ARM）本来就是小端，读写都不用转字节序；
     * 编码时把 NaN/INF 抹成 0：坏分数一旦落库，之后每次召回都要重新防它一遍。
     */
    fun encode(vector: FloatArray): ByteArray {
        // 小端 float32：commonMain 里没有 ByteBuffer，这里手写字节序（IEEE-754 的
        // Float.toBits / Float.fromBits 是跨平台一致的）。格式与旧版 ByteBuffer 逐字节相同。
        val bytes = ByteArray(vector.size * 4)
        for (index in vector.indices) {
            val raw = if (vector[index].isFinite()) vector[index] else 0f
            val bits = raw.toBits()
            bytes[index * 4] = (bits and 0xFF).toByte()
            bytes[index * 4 + 1] = ((bits ushr 8) and 0xFF).toByte()
            bytes[index * 4 + 2] = ((bits ushr 16) and 0xFF).toByte()
            bytes[index * 4 + 3] = ((bits ushr 24) and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * ByteArray → FloatArray（小端 float32），并校验长度。
     *
     * 长度对不上（半个 float、维度变了、字段被写坏）返回 **null** 而不是抛异常，
     * 调用方看到 null 就当这条记忆没有向量：检索安静地少一条，比整次召回失败强。
     * 单个元素是 NaN/INF 时抹成 0 —— 长度对说明整条 blob 不是乱码，只是有个坏数。
     */
    fun decode(raw: ByteArray, dim: Int): FloatArray? {
        if (dim <= 0) return null
        if (raw.isEmpty() || raw.size != dim * 4) return null
        return FloatArray(dim) { index ->
            val offset = index * 4
            val bits = (raw[offset].toInt() and 0xFF) or
                ((raw[offset + 1].toInt() and 0xFF) shl 8) or
                ((raw[offset + 2].toInt() and 0xFF) shl 16) or
                ((raw[offset + 3].toInt() and 0xFF) shl 24)
            val value = Float.fromBits(bits)
            if (value.isFinite()) value else 0f
        }
    }
}
