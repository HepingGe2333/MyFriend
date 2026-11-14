package com.myfriend.app.domain

import kotlin.math.ln

/**
 * 本地「语义」嵌入：把一段话压成 384 维向量，越像的句子向量越近。
 *
 * ## 先说清楚它是什么、不是什么
 *
 * 这**不是**神经网络 embedding（没有 transformer、没有预训练模型、不下载任何文件）。
 * 它是「字 n-gram 的哈希嵌入」：把中文按 [ChineseTerms] 切出的字二元组、由二元组拼出的
 * 字三元组、以及英文小写词，各自用带符号的哈希打进固定维度的桶里，再按 IDF 近似加权、L2 归一化。
 *
 * 所以它捕捉的是**字面近似的语义**：换一种说法时，只要字面上还有重叠（中文里绝大多数同义改写都
 * 会保留一部分字，比如「项目压力大」/「工作上的压力」共享「压力」，「养了只猫」/「我在养猫」
 * 共享「养猫」），向量就会靠得比较近；真正「一个字都不沾」的同义（「我哥」/「家兄」）它抓不到。
 *
 * ## 那为什么还是它
 *
 * - **离线**：飞机上、地铁里、第一次装上还没有网，都照样能想起事来；
 * - **零依赖零成本**：不引 ONNX/TFLite/MLKit，不打包 50～300MB 模型，不让用户在设置里等下载条；
 * - **确定性**：同一段话永远得到同一个向量，单测能钉死，出问题能复现；
 * - **够用**：这个 App 的「记忆」是几百条、每条几十字的中文短句，而且查询就是用户当下说的那句话，
 *   和记忆原文几乎总共享一部分字词。真 embedding 的优势（认识从没见过的词）在这里换不来多少收益。
 *
 * ## 将来要换真模型怎么办
 *
 * [PROVIDER] 跟着每条向量一起入库（`memory_vectors.provider`），`dim` 也跟着存。
 * 换实现时只要改这个常量与 [embed]，旧向量因为 provider 对不上会被自然忽略
 * （`MemoryVectorDao.deleteOtherProviders` 负责清掉），不会出现「一半新向量一半旧向量」的余弦乱算。
 * 调用方（[com.myfriend.app.data.repo.MemoryVectorIndex]）只依赖 `embed(text): FloatArray` 这一个签名。
 *
 * ## 特征与权重
 *
 * 1. **基础词元**：直接复用 [ChineseTerms] 的切词结果（中文按字二元组、英文按整词、虚词已删）。
 *    不再写第二套分词：查询和记忆必须走同一套词元，否则会出现「上下文里挑中的记忆，回忆工具却翻不到」。
 * 2. **字三元组**：由相邻的两个二元组拼出来（「项目」+「目压」→「项目压」），不重新扫一遍正文。
 *    三元组更长、更具体，是中文里最接近「词」的碎片（「哈尔滨」「睡不着」）。
 * 3. **IDF 近似**：这里没有语料库可统计 DF（记忆就几百条，为它建一张全局统计表、还要随写入失效，
 *    得不偿失），所以用「长度当稀有度」做静态先验：单字最泛滥、二元组是基准、三元组更具体。
 *    同一个效果：常见碎片权重低，稀有长碎片权重高。
 * 4. **亚线性 TF**：基础权重本身就是按出现次数归一的（0..1]，这里再压一次 `1 + ln(1 + w)`——
 *    一个词在长记忆里出现十次不该让整条记忆的向量被它主导。
 *
 * 整个函数是纯的：不读时钟、不读数据库、不依赖随机数；空文本返回全零向量（调用方跳过它）。
 */
object LocalEmbedding {

    /**
     * 嵌入实现的名字。**改算法就必须改它**：这是「旧向量自然失效」的唯一开关。
     * `v1` = 字 n-gram 哈希 + 静态 IDF 近似。
     */
    const val PROVIDER: String = "local-hash-v1"

    /**
     * 维度：384。
     *
     * 中文二元组的数量级是「几百个常见字 × 几千个常见搭配」，384 维已经足够把
     * 「这几条记忆」区分开（几百条记忆 ≈ 每条几十个特征，哈希碰撞是常态但带符号哈希
     * 让碰撞的正负相消，不至于系统性高估相似度）。再大只是让 DB 里每条多占几 KB，
     * 而记忆只有几千条上限，收益是可以忽略的。
     */
    const val DIM: Int = 384

    /** 单字：太泛滥（「猫」「难」「钱」到处都是），权重压到基准以下。 */
    private const val WEIGHT_SINGLE_CHAR = 0.8

    /** 二元组：基准权重，也是 [ChineseTerms] 最主要的产物。 */
    private const val WEIGHT_BIGRAM = 1.0

    /** 三元组：更长 = 更具体，给一点加成（「哈尔滨」比「哈尔」「尔滨」更像一个专名）。 */
    private const val WEIGHT_TRIGRAM = 1.25

    /** 很短的英文词（ok、go、up）多半是口水词。 */
    private const val WEIGHT_SHORT_LATIN = 0.85

    /** 正常长度的英文词（project、hangzhou）区分度不错。 */
    private const val WEIGHT_LATIN = 1.15

    /** 归一化后范数小于它就当零向量：理论上不会发生，纯防御。 */
    private const val MIN_NORM = 1e-6

    /** FNV-1a 64 位的偏移基（0xcbf29ce484222325）。用 FNV 而不是 String.hashCode：后者只有 32 位且质量差。 */
    private const val FNV_OFFSET = -3750763034362895579L

    private const val FNV_PRIME = 1099511628211L

    /**
     * 把一段文本编码成 L2 归一化向量（长度 [DIM]）。
     *
     * 空串 / 纯标点 / 全是虚词 → 全零向量。调用方应当用 [VectorMath.isZero] 判一下再跳过，
     * 因为零向量和谁都不相似，混进排序里只会白白占一个候选位。
     */
    fun embed(text: String): FloatArray {
        val vector = FloatArray(DIM)
        if (text.isBlank()) return vector
        val features = features(text)
        if (features.isEmpty()) return vector
        features.forEach { (feature, weight) ->
            val hash = hash64(feature)
            val bucket = (hash % DIM + DIM).toInt() % DIM
            // 带符号哈希：用另一个位决定正负号，碰撞时正负相消，而不是一律把相似度抬高。
            val sign = if ((hash ushr 63) and 1L == 0L) 1.0 else -1.0
            vector[bucket] += (sign * weight).toFloat()
        }
        val norm = VectorMath.norm(vector)
        if (!norm.isFinite() || norm <= MIN_NORM) return FloatArray(DIM)
        val inverse = (1.0 / norm).toFloat()
        for (i in vector.indices) vector[i] *= inverse
        return vector
    }

    /**
     * 一段文本 →（特征, 权重）。
     *
     * 只做三件事：拿 [ChineseTerms] 的词元当基底、把相邻二元组拼成三元组、按长度给静态 IDF 先验。
     * 暴露成 internal 是为了单测能单独钉住「三元组真的是拼出来的、虚词真的不在里面」，
     * 而不是只能隔着余弦相似度去猜。
     */
    internal fun features(text: String): Map<String, Double> {
        val base = ChineseTerms.weights(text)
        if (base.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Double>(base.size * 2)

        // 1) 基础词元：二元组 / 单字 / 英文词
        base.forEach { (term, weight) ->
            out[term] = sublinear(weight) * classWeight(term)
        }

        // 2) 三元组：由相邻二元组拼出来（AB + BC → ABC）。
        //    先按首字建索引，避免 160 × 160 的两两比较。
        val byFirstChar = HashMap<Char, MutableList<Pair<String, Double>>>(16)
        base.forEach { (term, weight) ->
            if (isCjkBigram(term)) {
                byFirstChar.getOrPut(term[0]) { mutableListOf() }.add(term to weight)
            }
        }
        if (byFirstChar.isEmpty()) return out
        base.forEach { (first, firstWeight) ->
            if (!isCjkBigram(first)) return@forEach
            val followers = byFirstChar[first[1]] ?: return@forEach
            followers.forEach { (second, secondWeight) ->
                val trigram = first + second[1]
                // 三元组的信息量不会超过它两个片段里更弱的那个
                // 同一对片段可能来自不同的相邻位置，取更大的一份即可（重复不会额外加分）。
                val weight = minOf(firstWeight, secondWeight)
                out[trigram] = sublinear(weight) * WEIGHT_TRIGRAM
            }
        }
        return out
    }

    /** 静态 IDF 先验：用「长度」当「稀有度」的替身，见类注释第 3 条。 */
    private fun classWeight(term: String): Double = when {
        isCjkBigram(term) -> WEIGHT_BIGRAM
        term.length == 1 && isCjk(term[0]) -> WEIGHT_SINGLE_CHAR
        term.length < 4 -> WEIGHT_SHORT_LATIN
        else -> WEIGHT_LATIN
    }

    /** 亚线性 TF：w ∈ (0,1] → [1, 1.693]，重复提到只轻微加分。 */
    private fun sublinear(weight: Double): Double {
        if (weight <= 0.0 || !weight.isFinite()) return 1.0
        return 1.0 + ln(1.0 + weight)
    }

    /** FNV-1a 64 位：跨平台、跨版本完全一致（String.hashCode 也是，但位数太少）。 */
    private fun hash64(feature: String): Long {
        var hash = FNV_OFFSET
        for (ch in feature) {
            hash = hash xor ch.code.toLong()
            hash *= FNV_PRIME
        }
        return hash
    }

    private fun isCjkBigram(term: String): Boolean =
        term.length == 2 && isCjk(term[0]) && isCjk(term[1])

    /** 和 [ChineseTerms] / [MemoryScreening] 里同一套汉字范围，避免「同一个字在两处一个算中文一个不算」。 */
    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF ||
            code in 0x3040..0x30FF || code in 0xAC00..0xD7AF ||
            code in 0xF900..0xFAFF
    }
}
