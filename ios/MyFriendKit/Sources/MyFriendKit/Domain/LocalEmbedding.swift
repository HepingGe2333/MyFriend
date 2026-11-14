// 来源：app/src/main/java/com/myfriend/app/domain/LocalEmbedding.kt
//
// 本地「语义」嵌入：把一段话压成 384 维向量，越像的句子向量越近。
//
// ## 先说清楚它是什么、不是什么
//
// 这**不是**神经网络 embedding（没有 transformer、没有预训练模型、不下载任何文件）。
// 它是「字 n-gram 的哈希嵌入」：把中文按 ChineseTerms 切出的字二元组、由二元组拼出的
// 字三元组、以及英文小写词，各自用带符号的哈希打进固定维度的桶里，再按 IDF 近似加权、L2 归一化。
//
// 所以它捕捉的是**字面近似的语义**：换一种说法时，只要字面上还有重叠（中文里绝大多数同义改写都
// 会保留一部分字，比如「项目压力大」/「工作上的压力」共享「压力」，「养了只猫」/「我在养猫」
// 共享「养猫」），向量就会靠得比较近；真正「一个字都不沾」的同义（「我哥」/「家兄」）它抓不到。
//
// ## 那为什么还是它
//
// - **离线**：飞机上、地铁里、第一次装上还没有网，都照样能想起事来；
// - **零依赖零成本**：不引第三方模型运行时，不打包 50～300MB 模型，不让用户在设置里等下载条；
// - **确定性**：同一段话永远得到同一个向量，单测能钉死，出问题能复现；
// - **够用**：这个 App 的「记忆」是几百条、每条几十字的中文短句，而且查询就是用户当下说的那句话，
//   和记忆原文几乎总共享一部分字词。真 embedding 的优势（认识从没见过的词）在这里换不来多少收益。
//
// ## 将来要换真模型怎么办
//
// `PROVIDER` 跟着每条向量一起入库（`memory_vectors.provider`），`DIM` 也跟着存。
// 换实现时只要改这个常量与 `embed`，旧向量因为 provider 对不上会被自然忽略，
// 不会出现「一半新向量一半旧向量」的余弦乱算。
//
// ## 特征与权重
//
// 1. **基础词元**：直接复用 ChineseTerms 的切词结果（中文按字二元组、英文按整词、虚词已删）。
//    不再写第二套分词：查询和记忆必须走同一套词元，否则会出现「上下文里挑中的记忆，回忆工具却翻不到」。
// 2. **字三元组**：由相邻的两个二元组拼出来（「项目」+「目压」→「项目压」），不重新扫一遍正文。
//    三元组更长、更具体，是中文里最接近「词」的碎片（「哈尔滨」「睡不着」）。
// 3. **IDF 近似**：这里没有语料库可统计 DF（记忆就几百条，为它建一张全局统计表、还要随写入失效，
//    得不偿失），所以用「长度当稀有度」做静态先验：单字最泛滥、二元组是基准、三元组更具体。
// 4. **亚线性 TF**：基础权重本身就是按出现次数归一的（0..1]，这里再压一次 `1 + ln(1 + w)`——
//    一个词在长记忆里出现十次不该让整条记忆的向量被它主导。
//
// 整个函数是纯的：不读时钟、不读数据库、不依赖随机数；空文本返回全零向量（调用方跳过它）。

import Foundation

/// 本地哈希嵌入（对应 Kotlin 的 `object LocalEmbedding`）。
public enum LocalEmbedding {

    /// 嵌入实现的名字。**改算法就必须改它**：这是「旧向量自然失效」的唯一开关。
    /// `v1` = 字 n-gram 哈希 + 静态 IDF 近似。
    public static let PROVIDER = "local-hash-v1"

    /// 维度：384。
    ///
    /// 中文二元组的数量级是「几百个常见字 × 几千个常见搭配」，384 维已经足够把
    /// 「这几条记忆」区分开（几百条记忆 ≈ 每条几十个特征，哈希碰撞是常态但带符号哈希
    /// 让碰撞的正负相消，不至于系统性高估相似度）。再大只是让 DB 里每条多占几 KB。
    public static let DIM = 384

    /// 单字：太泛滥（「猫」「难」「钱」到处都是），权重压到基准以下。
    static let WEIGHT_SINGLE_CHAR = 0.8

    /// 二元组：基准权重，也是 ChineseTerms 最主要的产物。
    static let WEIGHT_BIGRAM = 1.0

    /// 三元组：更长 = 更具体，给一点加成（「哈尔滨」比「哈尔」「尔滨」更像一个专名）。
    static let WEIGHT_TRIGRAM = 1.25

    /// 很短的英文词（ok、go、up）多半是口水词。
    static let WEIGHT_SHORT_LATIN = 0.85

    /// 正常长度的英文词（project、hangzhou）区分度不错。
    static let WEIGHT_LATIN = 1.15

    /// 归一化后范数小于它就当零向量：理论上不会发生，纯防御。
    static let MIN_NORM = 1e-6

    /// FNV-1a 64 位的偏移基（0xcbf29ce484222325）。
    /// 用 FNV 而不是自带的字符串哈希：后者在 Swift 里每次进程启动都换种子，跨启动不可复现。
    static let FNV_OFFSET: Int64 = Int64(bitPattern: 0xcbf2_9ce4_8422_2325)

    static let FNV_PRIME: Int64 = 1_099_511_628_211

    /// 把一段文本编码成 L2 归一化向量（长度 `DIM`）。
    ///
    /// 空串 / 纯标点 / 全是虚词 → 全零向量。调用方应当用 `VectorMath.isZero` 判一下再跳过，
    /// 因为零向量和谁都不相似，混进排序里只会白白占一个候选位。
    public static func embed(_ text: String) -> [Float] {
        var vector = [Float](repeating: 0, count: DIM)
        if text.trimmed.isEmpty { return vector }
        let features = featureList(text)
        if features.isEmpty { return vector }
        for pair in features.pairs {
            let hash = hash64(pair.term)
            let bucket = Int(((hash % Int64(DIM)) + Int64(DIM)) % Int64(DIM))
            // 带符号哈希：用符号位决定正负号，碰撞时正负相消，而不是一律把相似度抬高。
            let sign = hash < 0 ? -1.0 : 1.0
            vector[bucket] += Float(sign * pair.weight)
        }
        let length = VectorMath.norm(vector)
        if !length.isFinite || length <= MIN_NORM {
            return [Float](repeating: 0, count: DIM)
        }
        let inverse = Float(1.0 / length)
        for i in vector.indices { vector[i] *= inverse }
        return vector
    }

    /// 一段文本 →（特征, 权重），顺序稳定（Kotlin 端是 LinkedHashMap 的插入顺序）。
    ///
    /// 只做三件事：拿 ChineseTerms 的词元当基底、把相邻二元组拼成三元组、按长度给静态 IDF 先验。
    /// 暴露出来是为了单测能单独钉住「三元组真的是拼出来的、虚词真的不在里面」，
    /// 而不是只能隔着余弦相似度去猜。
    public static func features(_ text: String) -> TermWeights { featureList(text) }

    static func featureList(_ text: String) -> TermWeights {
        let base = ChineseTerms.weights(text)
        if base.isEmpty { return TermWeights() }
        var out = TermWeights()

        // 1) 基础词元：二元组 / 单字 / 英文词
        for pair in base.pairs {
            out.set(pair.term, sublinear(pair.weight) * classWeight(pair.term))
        }

        // 2) 三元组：由相邻二元组拼出来（AB + BC → ABC）。
        //    先按首字建索引，避免 160 × 160 的两两比较。
        var byFirstChar: [Character: [(term: String, weight: Double)]] = [:]
        for pair in base.pairs {
            guard isCjkBigram(pair.term), let first = pair.term.first else { continue }
            byFirstChar[first, default: []].append((term: pair.term, weight: pair.weight))
        }
        if byFirstChar.isEmpty { return out }
        for first in base.pairs {
            guard isCjkBigram(first.term) else { continue }
            let chars = Array(first.term)
            guard let followers = byFirstChar[chars[1]] else { continue }
            for second in followers {
                let secondChars = Array(second.term)
                let trigram = first.term + String(secondChars[1])
                // 三元组的信息量不会超过它两个片段里更弱的那个。
                // 同一对片段可能来自不同的相邻位置，取更大的一份即可（重复不会额外加分）。
                let weight = min(first.weight, second.weight)
                out.set(trigram, sublinear(weight) * WEIGHT_TRIGRAM)
            }
        }
        return out
    }

    /// 静态 IDF 先验：用「长度」当「稀有度」的替身，见类注释第 3 条。
    static func classWeight(_ term: String) -> Double {
        if isCjkBigram(term) { return WEIGHT_BIGRAM }
        let chars = Array(term)
        if chars.count == 1 && ChineseTerms.isCjk(chars[0]) { return WEIGHT_SINGLE_CHAR }
        if chars.count < 4 { return WEIGHT_SHORT_LATIN }
        return WEIGHT_LATIN
    }

    /// 亚线性 TF：w ∈ (0,1] → [1, 1.693]，重复提到只轻微加分。
    static func sublinear(_ weight: Double) -> Double {
        if weight <= 0.0 || !weight.isFinite { return 1.0 }
        return 1.0 + log(1.0 + weight)
    }

    /// FNV-1a 64 位：跨平台、跨版本完全一致。
    ///
    /// 逐 UTF-16 码元入哈希（与 Kotlin 遍历 `Char` 一致），乘法用 `&*` 走二补数回绕，
    /// 与 Kotlin 的 `Long` 溢出行为逐位相同 —— 这样 Android 写下的向量在 iOS 上算出来一模一样。
    static func hash64(_ feature: String) -> Int64 {
        var hash = FNV_OFFSET
        for unit in feature.utf16 {
            hash ^= Int64(unit)
            hash = hash &* FNV_PRIME
        }
        return hash
    }

    static func isCjkBigram(_ term: String) -> Bool {
        let chars = Array(term)
        guard chars.count == 2 else { return false }
        return ChineseTerms.isCjk(chars[0]) && ChineseTerms.isCjk(chars[1])
    }
}
