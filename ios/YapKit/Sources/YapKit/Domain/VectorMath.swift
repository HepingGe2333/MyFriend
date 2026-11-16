// 来源：app/src/main/java/com/yap/app/domain/VectorMath.kt
//
// 向量算术：余弦、topK 部分选择、以及入库用的定点编码。
//
// 全是纯函数，不碰数据库 / 时钟；坏输入（空数组、长度不一、NaN、INF、坏 blob）
// 一律给出「安全值」而不是抛异常 —— 检索是「想起点什么」的辅助，它不该有机会让聊天崩掉。
//
// ## 为什么单独拎出来
//
// LocalEmbedding 决定「一段话变成什么向量」，这里只决定「两个向量有多像、怎么取前 K 个」。
// 将来如果真换成神经网络 embedding（哪怕是下载的小模型），换的也只是 LocalEmbedding，
// 余弦、topK、编码格式这些都不用动。

import Foundation

/// 向量算术的命名空间（对应 Kotlin 的 `object VectorMath`）。
public enum VectorMath {

    /// 小于这个范数就认为向量是零向量：空文本、纯标点、纯虚词都会走到这里。
    static let ZERO_NORM = 1e-6

    // MARK: 相似度

    /// 点积。长度不一返回 0（不同实现、不同维度的向量不该被比较）。
    public static func dot(_ a: [Float], _ b: [Float]) -> Double {
        if a.isEmpty || a.count != b.count { return 0.0 }
        var sum = 0.0
        for i in 0..<a.count {
            let x = a[i]
            let y = b[i]
            if !x.isFinite || !y.isFinite { continue }
            sum += Double(x) * Double(y)
        }
        return sum.isFinite ? sum : 0.0
    }

    /// L2 范数。含 NaN/INF 的元素按 0 算，返回值一定是有限数。
    public static func norm(_ vector: [Float]) -> Double {
        if vector.isEmpty { return 0.0 }
        var sum = 0.0
        for value in vector {
            if !value.isFinite { continue }
            sum += Double(value) * Double(value)
        }
        if !sum.isFinite || sum <= 0.0 { return 0.0 }
        return sum.squareRoot()
    }

    /// 余弦相似度，落在 -1..1。
    ///
    /// LocalEmbedding 产出的向量已经做过 L2 归一化，此时它等价于点积；
    /// 但库里存的向量是「上一个版本写进去的」—— 可能因为代码回滚、字节损坏而没归一化，
    /// 所以这里还是老老实实除一次范数：多算 768 次乘法，换「坏数据算出来仍然是合法相似度」。
    ///
    /// 任一为空、维度不同、含零范数 → 0.0（= 不相似，而不是报错）。
    public static func cosine(_ a: [Float], _ b: [Float]) -> Double {
        if a.isEmpty || a.count != b.count { return 0.0 }
        let product = dot(a, b)
        if product == 0.0 { return 0.0 }
        let normA = norm(a)
        let normB = norm(b)
        if normA <= ZERO_NORM || normB <= ZERO_NORM { return 0.0 }
        let value = product / (normA * normB)
        if !value.isFinite { return 0.0 }
        return min(max(value, -1.0), 1.0)
    }

    /// 是不是零向量（空文本的嵌入）：调用方应当跳过它，别拿它去算相似度。
    public static func isZero(_ vector: [Float]) -> Bool {
        if vector.isEmpty { return true }
        for value in vector {
            if !value.isFinite { continue }
            if value != 0 { return false }
        }
        return true
    }

    // MARK: 部分选择

    /// 取分数最高的前 `limit` 个，按分数降序返回。
    ///
    /// 用大小为 limit 的小顶堆做部分选择，而不是「先全量排序再截断」：
    /// 一次搜索可能扫过上千条记忆，全量排序会为整张表建一个临时列表，在低端机上正好是内存尖峰。
    /// 这里内存只跟 limit 有关（默认 16），跟候选多少无关。
    ///
    /// 同分时**先到的先留下**（按数组的遍历顺序），保证同一个库同样的查询每次排出来一样。
    /// 分数是 NaN / INF 的条目直接丢掉：宁可少一条候选，也不要把排序搅乱。
    public static func topK<T>(_ items: [T], limit: Int, score: (T) -> Double) -> [T] {
        if limit <= 0 { return [] }
        var heap: [Entry<T>] = []
        heap.reserveCapacity(limit + 1)
        var seq = 0
        for item in items {
            let value = score(item)
            if value.isFinite {
                if heap.count < limit {
                    push(&heap, Entry(item: item, score: value, seq: seq))
                } else if let worst = heap.first, value > worst.score {
                    _ = pop(&heap)
                    push(&heap, Entry(item: item, score: value, seq: seq))
                }
            }
            seq += 1
        }
        return heap
            .sorted { a, b in
                if a.score != b.score { return a.score > b.score }
                return a.seq < b.seq
            }
            .map(\.item)
    }

    /// 堆里的一个候选：带上遍历序号，用来把同分的顺序钉死。
    struct Entry<T> {
        let item: T
        let score: Double
        let seq: Int
    }

    /// 小顶堆的「最差」：分数小的在上；同分时后到的（序号大的）在上。
    static func isWorse<T>(_ a: Entry<T>, _ b: Entry<T>) -> Bool {
        if a.score != b.score { return a.score < b.score }
        return a.seq > b.seq
    }

    static func push<T>(_ heap: inout [Entry<T>], _ entry: Entry<T>) {
        heap.append(entry)
        var child = heap.count - 1
        while child > 0 {
            let parent = (child - 1) / 2
            if isWorse(heap[child], heap[parent]) {
                heap.swapAt(child, parent)
                child = parent
            } else {
                break
            }
        }
    }

    static func pop<T>(_ heap: inout [Entry<T>]) -> Entry<T>? {
        if heap.isEmpty { return nil }
        let top = heap[0]
        let last = heap.removeLast()
        if heap.isEmpty { return top }
        heap[0] = last
        var parent = 0
        while true {
            let left = parent * 2 + 1
            let right = left + 1
            var worst = parent
            if left < heap.count && isWorse(heap[left], heap[worst]) { worst = left }
            if right < heap.count && isWorse(heap[right], heap[worst]) { worst = right }
            if worst == parent { break }
            heap.swapAt(parent, worst)
            parent = worst
        }
        return top
    }

    // MARK: 编解码

    /// `[Float]` → 二进制（小端 float32，每条 4 字节）。
    ///
    /// 小端是因为 Android 设备（ARM）本来就是小端，读写都不用转字节序；
    /// 编码时把 NaN/INF 抹成 0：坏分数一旦落库，之后每次召回都要重新防它一遍。
    ///
    /// 用 `Data` 而不是 `[UInt8]`：iOS 端 BLOB 列直接写 `Data`，少一层来回转换。
    public static func encode(_ vector: [Float]) -> Data {
        var bytes = [UInt8]()
        bytes.reserveCapacity(vector.count * 4)
        for value in vector {
            let safe: Float = value.isFinite ? value : 0
            var bits = safe.bitPattern.littleEndian
            withUnsafeBytes(of: &bits) { bytes.append(contentsOf: $0) }
        }
        return Data(bytes)
    }

    /// 二进制 → `[Float]`（小端 float32），并校验长度。
    ///
    /// 长度对不上（半个 float、维度变了、字段被写坏）返回 **nil** 而不是抛异常，
    /// 调用方看到 nil 就当这条记忆没有向量：检索安静地少一条，比整次召回失败强。
    /// 单个元素是 NaN/INF 时抹成 0 —— 长度对说明整条 blob 不是乱码，只是有个坏数。
    public static func decode(_ raw: Data, dim: Int) -> [Float]? {
        if dim <= 0 { return nil }
        if raw.isEmpty { return nil }
        // 先挡住 dim * 4 的溢出，避免一个荒唐的维度把长度判断绕过去。
        if dim > Int.max / 4 { return nil }
        if raw.count != dim * 4 { return nil }
        let bytes = [UInt8](raw)
        var out = [Float]()
        out.reserveCapacity(dim)
        for i in 0..<dim {
            let base = i * 4
            let bits = UInt32(bytes[base])
                | (UInt32(bytes[base + 1]) << 8)
                | (UInt32(bytes[base + 2]) << 16)
                | (UInt32(bytes[base + 3]) << 24)
            let value = Float(bitPattern: bits)
            out.append(value.isFinite ? value : 0)
        }
        return out
    }
}
