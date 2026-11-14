import XCTest
@testable import MyFriendKit

/// 向量算术：余弦、topK 部分选择、编解码。
///
/// 坏输入（空数组、长度不一、NaN、INF、坏 blob）一律给安全值而不是抛异常 ——
/// 检索是「想起点什么」的辅助，它不该有机会让聊天崩掉。
final class VectorMathTests: XCTestCase {

    // MARK: 点积 / 范数 / 余弦

    func testDotAndNorm() {
        XCTAssertEqual(40.0, VectorMath.dot([1, 2, 3], [4, 6, 8]), accuracy: 1e-9)
        XCTAssertEqual(0.0, VectorMath.dot([1, 2], [1, 2, 3]), accuracy: 1e-9, "长度不一返回 0")
        XCTAssertEqual(5.0, VectorMath.norm([3, 4]), accuracy: 1e-9)
    }

    func testNormAndDotSkipNonFiniteValues() {
        XCTAssertEqual(0.0, VectorMath.norm([Float.nan, Float.infinity]), accuracy: 1e-9)
        XCTAssertEqual(5.0, VectorMath.norm([3, Float.nan, 4]), accuracy: 1e-9)
        XCTAssertEqual(6.0, VectorMath.dot([3, 4, Float.nan], [2, 0, 1]), accuracy: 1e-9)
    }

    func testCosineBoundaries() {
        let a: [Float] = [1, 0, 0]
        XCTAssertEqual(1.0, VectorMath.cosine(a, a), accuracy: 1e-9)
        XCTAssertEqual(-1.0, VectorMath.cosine(a, [-1, 0, 0]), accuracy: 1e-9)
        XCTAssertEqual(0.0, VectorMath.cosine(a, [0, 1, 0]), accuracy: 1e-9, "正交")
        XCTAssertEqual(0.0, VectorMath.cosine([], []), accuracy: 1e-9)
        XCTAssertEqual(0.0, VectorMath.cosine([1, 2], [1, 2, 3]), accuracy: 1e-9, "维度不同")
        XCTAssertEqual(0.0, VectorMath.cosine([0, 0], [1, 1]), accuracy: 1e-9, "零向量不相似")
        XCTAssertEqual(0.0, VectorMath.cosine([Float.nan, 0], [1, 1]), accuracy: 1e-9)
    }

    func testCosineIsSymmetricAndClamped() {
        let a: [Float] = [0.3, -0.2, 0.9]
        let b: [Float] = [0.8, 0.1, -0.4]
        XCTAssertEqual(VectorMath.cosine(a, b), VectorMath.cosine(b, a), accuracy: 1e-12)
        let value = VectorMath.cosine(a, b)
        XCTAssertTrue(value >= -1.0 && value <= 1.0)
    }

    func testIsZero() {
        XCTAssertTrue(VectorMath.isZero([]))
        XCTAssertTrue(VectorMath.isZero([0, 0, 0]))
        XCTAssertFalse(VectorMath.isZero([0, 0.001, 0]))
        XCTAssertTrue(VectorMath.isZero([Float.nan, 0]), "NaN 按 0 算")
    }

    // MARK: topK

    func testTopKReturnsHighestScoresDescending() {
        let items: [(name: String, score: Double)] = [
            ("d", 0.1), ("a", 0.9), ("c", 0.5), ("b", 0.7), ("e", 0.3)
        ]
        let top = VectorMath.topK(items, limit: 3) { $0.score }
        XCTAssertEqual(["a", "b", "c"], top.map(\.name))
    }

    func testTopKRejectsIllegalLimit() {
        let items: [(name: String, score: Double)] = [("a", 1.0), ("b", 2.0)]
        XCTAssertTrue(VectorMath.topK(items, limit: 0) { $0.score }.isEmpty)
        XCTAssertTrue(VectorMath.topK(items, limit: -3) { $0.score }.isEmpty)
        XCTAssertTrue(VectorMath.topK([(name: String, score: Double)](), limit: 5) { $0.score }.isEmpty)
    }

    func testTopKMatchesFullSort() {
        // 1000 条候选、K=7：部分选择（小顶堆）必须和「全量排序再截断」给出同样的前 7 名。
        let items: [(index: Int, score: Double)] = (0..<1000).map { index in
            (index, Double((index * 37) % 1000) / 1000.0)
        }
        let top = VectorMath.topK(items, limit: 7) { $0.score }
        let expected = items.sorted { $0.score > $1.score }.prefix(7)
        XCTAssertEqual(expected.map(\.index), top.map(\.index))
    }

    func testTopKKeepsInsertionOrderOnTies() {
        let items: [(name: String, score: Double)] = [("first", 0.5), ("second", 0.5), ("third", 0.5)]
        XCTAssertEqual(["first", "second"], VectorMath.topK(items, limit: 2) { $0.score }.map(\.name))
    }

    func testTopKDropsNanAndInfinity() {
        let items: [(name: String, score: Double)] = [
            ("nan", Double.nan), ("inf", Double.infinity), ("ok", 0.4), ("best", 0.8)
        ]
        XCTAssertEqual(["best", "ok"], VectorMath.topK(items, limit: 4) { $0.score }.map(\.name))
    }

    // MARK: 编解码

    func testEncodeIsLittleEndianFloat32() {
        let bytes = Array(VectorMath.encode([1.0]))
        XCTAssertEqual(4, bytes.count)
        XCTAssertEqual([0, 0, 0x80, 0x3F], bytes, "1.0f 的小端字节是 00 00 80 3F")
    }

    func testEncodeDecodeRoundTrip() {
        let vector = LocalEmbedding.embed("他妹妹最近失眠很严重")
        guard let decoded = VectorMath.decode(VectorMath.encode(vector), dim: LocalEmbedding.DIM) else {
            return XCTFail("往返解码不该失败")
        }
        XCTAssertEqual(vector, decoded)
    }

    func testEncodeWipesNaN() {
        let dirty: [Float] = [Float.nan, 1, Float.infinity, 3]
        guard let decoded = VectorMath.decode(VectorMath.encode(dirty), dim: 4) else {
            return XCTFail("解码不该失败")
        }
        XCTAssertEqual([0, 1, 0, 3], decoded)
        XCTAssertTrue(decoded.allSatisfy { $0.isFinite })
    }

    func testBadBlobDecodesToNil() {
        let good = VectorMath.encode([Float](repeating: 0.1, count: LocalEmbedding.DIM))
        XCTAssertNil(VectorMath.decode(Data(good.dropLast(2)), dim: LocalEmbedding.DIM), "少半个 float")
        var longer = Data(good)
        longer.append(0)
        XCTAssertNil(VectorMath.decode(longer, dim: LocalEmbedding.DIM), "多一个字节")
        XCTAssertNil(VectorMath.decode(Data(), dim: LocalEmbedding.DIM), "空数据")
        XCTAssertNil(VectorMath.decode(good, dim: 0), "维度非法")
        XCTAssertNil(VectorMath.decode(good, dim: LocalEmbedding.DIM * 2), "维度对不上（换了实现）")
        XCTAssertNil(VectorMath.decode(Data([1, 2, 3, 4, 5]), dim: LocalEmbedding.DIM), "乱码长度")
    }
}
