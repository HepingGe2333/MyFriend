import XCTest
@testable import YapKit

/// 本地哈希嵌入（移植自 Android 的 LocalEmbeddingTest）。
///
/// 这里刻意**不写死任何相似度数值**：向量只是「字面近似的语义」，具体分数会随哈希桶调整而变，
/// 钉死数字只会让以后每次改进都变成改测试。要钉的是**不等式**——同一个意思的两种说法，
/// 必须比一句无关的话更接近；以及各种边界（空、超长、坏 blob）不抛异常、给出安全值。
final class LocalEmbeddingTests: XCTestCase {

    // MARK: 嵌入：确定性与形状

    func testSameTextEncodesIdentically() {
        let text = "他妹妹最近失眠很严重，晚上总是睡不着"
        XCTAssertEqual(LocalEmbedding.embed(text), LocalEmbedding.embed(text))
    }

    func testVectorIsL2Normalized() {
        let vector = LocalEmbedding.embed("项目压力很大，晚上睡不着")
        XCTAssertEqual(LocalEmbedding.DIM, vector.count)
        XCTAssertEqual(1.0, VectorMath.norm(vector), accuracy: 0.001, "归一化后范数应该是 1")
        XCTAssertTrue(vector.allSatisfy { $0.isFinite }, "不能出现 NaN/INF")
    }

    func testDimensionAndProviderAreContractConstants() {
        // 这两个值跟着每条向量一起入库：改算法就必须改 PROVIDER，旧向量才会自然失效。
        XCTAssertEqual(384, LocalEmbedding.DIM)
        XCTAssertEqual("local-hash-v1", LocalEmbedding.PROVIDER)
    }

    func testEmptyTextYieldsZeroVector() {
        XCTAssertTrue(VectorMath.isZero(LocalEmbedding.embed("")))
        XCTAssertTrue(VectorMath.isZero(LocalEmbedding.embed("   \n\t ")))
    }

    func testPunctuationAndStopWordsYieldZeroVector() {
        // 标点会被归一化掉，虚词会被 ChineseTerms 删掉：没有可检索的内容就不该有向量，
        // 否则一条纯标点的记忆会跟任何查询都算出「像」。
        XCTAssertTrue(VectorMath.isZero(LocalEmbedding.embed("。。。！？，、")))
        XCTAssertTrue(VectorMath.isZero(LocalEmbedding.embed("的了吗呢吧啊")))
    }

    func testHugeTextStaysFiniteAndNormalized() {
        var huge = ""
        huge.reserveCapacity(80_000)
        for _ in 0..<20_000 { huge += "项目压力" }
        let vector = LocalEmbedding.embed(huge)
        XCTAssertEqual(LocalEmbedding.DIM, vector.count)
        XCTAssertTrue(vector.allSatisfy { $0.isFinite })
        XCTAssertEqual(1.0, VectorMath.norm(vector), accuracy: 0.001)
    }

    func testSingleCharacterMemoryStillHasVector() {
        // 「猫」这种一条一个字的记忆不该被丢掉：单字在中文里经常就是完整的词。
        let vector = LocalEmbedding.embed("猫")
        XCTAssertFalse(VectorMath.isZero(vector))
        XCTAssertEqual(1.0, VectorMath.norm(vector), accuracy: 0.001)
        XCTAssertGreaterThan(VectorMath.cosine(vector, LocalEmbedding.embed("猫")), 0.99)
    }

    // MARK: 嵌入：特征构成

    func testTrigramsAreBuiltFromAdjacentBigrams() {
        let features = LocalEmbedding.features("项目压力")
        // 二元组来自 ChineseTerms，三元组不能靠第二套分词，只能由相邻二元组拼出来。
        XCTAssertTrue(features.contains("项目"), "二元组应该在，实际 \(features.pairs.map(\.term))")
        XCTAssertTrue(features.contains("目压"))
        XCTAssertTrue(features.contains("项目压"), "「项目压」应该是拼出来的三元组")
        XCTAssertTrue(features.contains("目压力"), "「目压力」应该是拼出来的三元组")
    }

    func testStopWordsNeverBecomeFeatures() {
        let features = LocalEmbedding.features("我觉得这个项目压力很大")
        let terms = features.pairs.map(\.term)
        XCTAssertTrue(features.contains("项目"), "实词应该留下，实际 \(terms)")
        XCTAssertFalse(terms.contains { $0.contains("的") }, "虚词不该成为特征，实际 \(terms)")
        XCTAssertFalse(terms.contains { $0.contains("很") })
        XCTAssertFalse(features.contains("觉得"), "「觉得」这种无信息词不该留下，实际 \(terms)")
    }

    func testTrigramWeightIsHigherThanBigram() {
        let features = LocalEmbedding.features("项目压力")
        guard let bigram = features["项目"], let trigram = features["项目压"] else {
            return XCTFail("二元组与三元组都该在")
        }
        XCTAssertGreaterThan(trigram, bigram * 1.1, "三元组更长更具体，权重应当更高")
    }

    // MARK: 嵌入：同义改写 > 无关文本

    func assertParaphraseCloser(query: String, paraphrase: String, unrelated: String,
                                file: StaticString = #filePath, line: UInt = #line) {
        let q = LocalEmbedding.embed(query)
        let near = VectorMath.cosine(q, LocalEmbedding.embed(paraphrase))
        let far = VectorMath.cosine(q, LocalEmbedding.embed(unrelated))
        XCTAssertGreaterThan(near, far,
                             "「\(paraphrase)」(\(near)) 应该比「\(unrelated)」(\(far)) 更接近「\(query)」",
                             file: file, line: line)
        XCTAssertGreaterThan(near, 0.0, "同义改写至少得有一点相似度（\(near)）", file: file, line: line)
    }

    func testParaphraseCloserProjectPressure() {
        assertParaphraseCloser(query: "最近项目压力很大睡不着",
                               paraphrase: "工作压力太大，晚上总是睡不着",
                               unrelated: "周末去公园散步拍照")
    }

    func testParaphraseCloserSisterInsomnia() {
        assertParaphraseCloser(query: "我妹妹最近一直失眠",
                               paraphrase: "妹妹失眠很严重，晚上睡不好",
                               unrelated: "楼下便利店的猫砂该换了")
    }

    func testParaphraseCloserHarbinSkiing() {
        // 查询里的「去年」会被归一化剥掉，靠「哈尔滨」「滑雪」这些实词对上。
        assertParaphraseCloser(query: "去年冬天去哈尔滨滑雪",
                               paraphrase: "冬天去了趟哈尔滨，滑了一次雪",
                               unrelated: "这周末要不要去看场电影")
    }

    func testParaphraseCloserEnglish() {
        assertParaphraseCloser(query: "project deadline pressure",
                               paraphrase: "the project deadline is killing me",
                               unrelated: "周末去爬山看日出")
    }

    func testMoreSharedWordsMeansCloser() {
        let query = LocalEmbedding.embed("他养的是一只橘猫")
        let sameSentence = VectorMath.cosine(query, LocalEmbedding.embed("养了一只橘猫"))
        let sharesOneWord = VectorMath.cosine(query, LocalEmbedding.embed("橘猫很胖"))
        let sharesNothing = VectorMath.cosine(query, LocalEmbedding.embed("明天开会记得带电脑"))

        XCTAssertGreaterThan(sameSentence, sharesOneWord,
                             "几乎同一句话应该最接近：\(sameSentence) vs \(sharesOneWord)")
        XCTAssertGreaterThan(sharesOneWord, sharesNothing,
                             "共享一个词应该比一个都不共享更接近：\(sharesOneWord) vs \(sharesNothing)")
    }

    func testCosineIsSymmetric() {
        let a = LocalEmbedding.embed("项目压力很大睡不着")
        let b = LocalEmbedding.embed("工作上的压力让我睡不好")
        XCTAssertEqual(VectorMath.cosine(a, b), VectorMath.cosine(b, a), accuracy: 1e-12)
    }

    func testSelfSimilarityIsHighest() {
        let a = LocalEmbedding.embed("他说下个月要搬家")
        XCTAssertEqual(1.0, VectorMath.cosine(a, a), accuracy: 1e-6)
        XCTAssertEqual(0.0, VectorMath.cosine(a, [Float](repeating: 0, count: LocalEmbedding.DIM)),
                       accuracy: 1e-12)
    }

    // MARK: 与 Android 端逐位一致

    func testHashMatchesKotlinFNV() {
        // FNV-1a 64 位的已知值：跨平台必须完全一致，否则两端写下的向量互相认不出。
        XCTAssertEqual(-3750763034362895579, LocalEmbedding.hash64(""))
        let expected = "项目".utf16.reduce(LocalEmbedding.FNV_OFFSET) { hash, unit in
            (hash ^ Int64(unit)) &* LocalEmbedding.FNV_PRIME
        }
        XCTAssertEqual(expected, LocalEmbedding.hash64("项目"))
    }
}
