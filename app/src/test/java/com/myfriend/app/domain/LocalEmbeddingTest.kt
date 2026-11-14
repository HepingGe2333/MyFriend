package com.myfriend.app.domain

import com.myfriend.app.data.local.MemoryVectorDao
import com.myfriend.app.data.local.MemoryVectorEntity
import com.myfriend.app.data.repo.isUsableVector
import com.myfriend.app.data.repo.pruneForeignVectors
import com.myfriend.app.data.repo.searchVectors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地向量检索的纯逻辑单测：嵌入、余弦、topK、编解码、以及「换实现后旧向量自然失效」的约定。
 *
 * 这里刻意**不写死任何相似度数值**：向量只是「字面近似的语义」，具体分数会随哈希桶调整而变，
 * 钉死数字只会让以后每次改进都变成改测试。要钉的是**不等式**——同一个意思的两种说法，
 * 必须比一句无关的话更接近；以及各种边界（空、超长、坏 blob）不抛异常、给出安全值。
 */
class LocalEmbeddingTest {

    // ---------------------------------------------------------------- 嵌入：确定性与形状

    @Test
    fun `同一段文本两次编码完全一致`() {
        val text = "他妹妹最近失眠很严重，晚上总是睡不着"

        val first = LocalEmbedding.embed(text)
        val second = LocalEmbedding.embed(text)

        assertTrue("同样的文本必须得到同样的向量", first.contentEquals(second))
    }

    @Test
    fun `向量已做 L2 归一化`() {
        val vector = LocalEmbedding.embed("项目压力很大，晚上睡不着")

        assertEquals(LocalEmbedding.DIM, vector.size)
        assertEquals("归一化后范数应该是 1", 1.0, VectorMath.norm(vector), 0.001)
        assertTrue("不能出现 NaN/INF", vector.all { it.isFinite() })
    }

    @Test
    fun `维度与实现名是契约常量`() {
        // 这两个值跟着每条向量一起入库：改算法就必须改 PROVIDER，旧向量才会自然失效。
        assertEquals(384, LocalEmbedding.DIM)
        assertEquals("local-hash-v1", LocalEmbedding.PROVIDER)
    }

    @Test
    fun `空文本与纯空白返回全零向量`() {
        assertTrue(VectorMath.isZero(LocalEmbedding.embed("")))
        assertTrue(VectorMath.isZero(LocalEmbedding.embed("   \n\t ")))
    }

    @Test
    fun `纯标点与纯虚词返回全零向量`() {
        // 标点会被归一化掉，虚词会被 ChineseTerms 删掉：没有可检索的内容就不该有向量，
        // 否则一条纯标点的记忆会跟任何查询都算出「像」。
        assertTrue(VectorMath.isZero(LocalEmbedding.embed("。。。！？，、")))
        assertTrue(VectorMath.isZero(LocalEmbedding.embed("的了吗呢吧啊")))
    }

    @Test
    fun `超长文本不会爆掉且仍然归一化`() {
        val huge = buildString { repeat(20_000) { append("项目压力") } }

        val vector = LocalEmbedding.embed(huge)

        assertEquals(LocalEmbedding.DIM, vector.size)
        assertTrue(vector.all { it.isFinite() })
        assertEquals(1.0, VectorMath.norm(vector), 0.001)
    }

    @Test
    fun `单字记忆也有向量`() {
        // 「猫」这种一条一个字的记忆不该被丢掉：单字在中文里经常就是完整的词。
        val vector = LocalEmbedding.embed("猫")

        assertFalse(VectorMath.isZero(vector))
        assertEquals(1.0, VectorMath.norm(vector), 0.001)
        assertTrue(VectorMath.cosine(vector, LocalEmbedding.embed("猫")) > 0.99)
    }

    // ---------------------------------------------------------------- 嵌入：特征构成

    @Test
    fun `三元组由相邻二元组拼出`() {
        val features = LocalEmbedding.features("项目压力")

        // 二元组来自 ChineseTerms，三元组不能靠第二套分词，只能由相邻二元组拼出来。
        assertTrue("二元组应该在，实际 $features", "项目" in features.keys)
        assertTrue("二元组应该在，实际 $features", "目压" in features.keys)
        assertTrue("「项目压」应该是拼出来的三元组，实际 $features", "项目压" in features.keys)
        assertTrue("「目压力」应该是拼出来的三元组，实际 $features", "目压力" in features.keys)
    }

    @Test
    fun `虚词不会进入特征`() {
        val features = LocalEmbedding.features("我觉得这个项目压力很大")

        assertTrue("实词应该留下，实际 ${features.keys}", "项目" in features.keys)
        assertFalse("虚词不该成为特征，实际 ${features.keys}", features.keys.any { it.contains('的') })
        assertFalse("虚词不该成为特征，实际 ${features.keys}", features.keys.any { it.contains('很') })
        assertFalse("「觉得」这种无信息词不该留下，实际 ${features.keys}", "觉得" in features.keys)
    }

    // ---------------------------------------------------------------- 嵌入：同义改写 > 无关文本

    /** 断言「同一件事换个说法」比「一句无关的话」更接近查询。 */
    private fun assertParaphraseCloser(query: String, paraphrase: String, unrelated: String) {
        val q = LocalEmbedding.embed(query)
        val near = VectorMath.cosine(q, LocalEmbedding.embed(paraphrase))
        val far = VectorMath.cosine(q, LocalEmbedding.embed(unrelated))

        assertTrue(
            "「$paraphrase」($near) 应该比「$unrelated」($far) 更接近「$query」",
            near > far
        )
        assertTrue("同义改写至少得有一点相似度（$near）", near > 0.0)
    }

    @Test
    fun `同义改写比无关文本更接近：项目压力`() {
        assertParaphraseCloser(
            query = "最近项目压力很大睡不着",
            paraphrase = "工作压力太大，晚上总是睡不着",
            unrelated = "周末去公园散步拍照"
        )
    }

    @Test
    fun `同义改写比无关文本更接近：妹妹失眠`() {
        assertParaphraseCloser(
            query = "我妹妹最近一直失眠",
            paraphrase = "妹妹失眠很严重，晚上睡不好",
            unrelated = "楼下便利店的猫砂该换了"
        )
    }

    @Test
    fun `同义改写比无关文本更接近：哈尔滨滑雪`() {
        // 查询里的「去年」会被归一化剥掉，靠「哈尔滨」「滑雪」这些实词对上。
        assertParaphraseCloser(
            query = "去年冬天去哈尔滨滑雪",
            paraphrase = "冬天去了趟哈尔滨，滑了一次雪",
            unrelated = "这周末要不要去看场电影"
        )
    }

    @Test
    fun `同义改写比无关文本更接近：英文`() {
        assertParaphraseCloser(
            query = "project deadline pressure",
            paraphrase = "the project deadline is killing me",
            unrelated = "周末去爬山看日出"
        )
    }

    @Test
    fun `共享字词越多越接近`() {
        val query = LocalEmbedding.embed("他养的是一只橘猫")
        val sameSentence = VectorMath.cosine(query, LocalEmbedding.embed("养了一只橘猫"))
        val sharesOneWord = VectorMath.cosine(query, LocalEmbedding.embed("橘猫很胖"))
        val sharesNothing = VectorMath.cosine(query, LocalEmbedding.embed("明天开会记得带电脑"))

        assertTrue("几乎同一句话应该最接近：$sameSentence vs $sharesOneWord", sameSentence > sharesOneWord)
        assertTrue(
            "共享一个词应该比一个都不共享更接近：$sharesOneWord vs $sharesNothing",
            sharesOneWord > sharesNothing
        )
    }

    @Test
    fun `余弦是对称的`() {
        val a = LocalEmbedding.embed("项目压力很大睡不着")
        val b = LocalEmbedding.embed("工作上的压力让我睡不好")

        assertEquals(VectorMath.cosine(a, b), VectorMath.cosine(b, a), 1e-9)
    }

    // ---------------------------------------------------------------- 余弦

    @Test
    fun `余弦：自己是最高、零向量谁都不像`() {
        val a = LocalEmbedding.embed("他说下个月要搬家")

        assertEquals(1.0, VectorMath.cosine(a, a), 1e-6)
        assertEquals(0.0, VectorMath.cosine(a, FloatArray(LocalEmbedding.DIM)), 1e-9)
        assertEquals(0.0, VectorMath.cosine(FloatArray(LocalEmbedding.DIM), a), 1e-9)
    }

    @Test
    fun `余弦：维度不同或空数组返回零`() {
        val a = LocalEmbedding.embed("记忆")
        val shorter = FloatArray(8) { 1f }

        assertEquals(0.0, VectorMath.cosine(a, shorter), 1e-9)
        assertEquals(0.0, VectorMath.cosine(FloatArray(0), FloatArray(0)), 1e-9)
        assertEquals(0.0, VectorMath.dot(a, shorter), 1e-9)
    }

    // ---------------------------------------------------------------- topK 部分选择

    @Test
    fun `topK 取分数最高的前 K 个并降序`() {
        val items = listOf("d" to 0.1, "a" to 0.9, "c" to 0.5, "b" to 0.7, "e" to 0.3)

        val top = VectorMath.topK(items, 3) { it.second }

        assertEquals(listOf("a", "b", "c"), top.map { it.first })
    }

    @Test
    fun `topK 在 limit 非法时返回空`() {
        val items = listOf("a" to 1.0, "b" to 2.0)

        assertTrue(VectorMath.topK(items, 0) { it.second }.isEmpty())
        assertTrue(VectorMath.topK(items, -3) { it.second }.isEmpty())
        assertTrue(VectorMath.topK(emptyList<Pair<String, Double>>(), 5) { it.second }.isEmpty())
    }

    @Test
    fun `topK 与全量排序结果一致`() {
        // 1000 条候选、K=7：部分选择（小顶堆）必须和「全量排序再截断」给出同样的前 7 名。
        val items = (0 until 1000).map { index ->
            // 用一条确定性的伪随机分数，避免 flaky。
            val score = ((index * 37) % 1000) / 1000.0
            index to score
        }

        val top = VectorMath.topK(items, 7) { it.second }
        val expected = items.sortedByDescending { it.second }.take(7)

        assertEquals(expected.map { it.first }, top.map { it.first })
    }

    @Test
    fun `topK 丢掉 NaN 与负数之外的坏分数`() {
        val items = listOf(
            "nan" to Double.NaN,
            "inf" to Double.POSITIVE_INFINITY,
            "ok" to 0.4,
            "best" to 0.8
        )

        val top = VectorMath.topK(items, 4) { it.second }

        assertEquals(listOf("best", "ok"), top.map { it.first })
    }

    // ---------------------------------------------------------------- 编解码

    @Test
    fun `编码是小端 float32`() {
        val bytes = VectorMath.encode(floatArrayOf(1.0f))

        assertEquals(4, bytes.size)
        assertEquals("1.0f 的小端字节是 00 00 80 3F", listOf(0, 0, -128, 63), bytes.map { it.toInt() })
    }

    @Test
    fun `编码解码往返一致`() {
        val vector = LocalEmbedding.embed("他妹妹最近失眠很严重")

        val decoded = VectorMath.decode(VectorMath.encode(vector), LocalEmbedding.DIM)

        assertNotNull(decoded)
        assertTrue(vector.contentEquals(decoded!!))
    }

    @Test
    fun `编码把 NaN 抹成零`() {
        val dirty = FloatArray(4) { index -> if (index % 2 == 0) Float.NaN else index.toFloat() }

        val decoded = VectorMath.decode(VectorMath.encode(dirty), 4)

        assertNotNull(decoded)
        assertEquals(0f, decoded!![0], 0f)
        assertEquals(1f, decoded[1], 0f)
        assertEquals(0f, decoded[2], 0f)
        assertEquals(3f, decoded[3], 0f)
        assertTrue("解码结果必须是有限数", decoded.all { it.isFinite() })
    }

    @Test
    fun `坏 blob 解码返回 null`() {
        val good = VectorMath.encode(FloatArray(LocalEmbedding.DIM) { 0.1f })

        assertNull("少半个 float", VectorMath.decode(good.copyOf(good.size - 2), LocalEmbedding.DIM))
        assertNull("多一个字节", VectorMath.decode(good.copyOf(good.size + 1), LocalEmbedding.DIM))
        assertNull("空数组", VectorMath.decode(ByteArray(0), LocalEmbedding.DIM))
        assertNull("维度非法", VectorMath.decode(good, 0))
        assertNull("维度对不上（换了实现）", VectorMath.decode(good, LocalEmbedding.DIM * 2))
        assertNull("乱码长度", VectorMath.decode(byteArrayOf(1, 2, 3, 4, 5), LocalEmbedding.DIM))
    }

    @Test
    fun `维度对不上时这段向量就当没有`() {
        // provider 与 dim 是「这条向量还算不算数」的两个开关：换实现后旧向量必须自然失效。
        assertTrue(isUsableVector(LocalEmbedding.PROVIDER, LocalEmbedding.DIM))
        assertFalse(isUsableVector(LocalEmbedding.PROVIDER, LocalEmbedding.DIM * 2))
        assertFalse(isUsableVector("local-hash-v0", LocalEmbedding.DIM))
        assertFalse(isUsableVector("", LocalEmbedding.DIM))
    }

    // ---------------------------------------------------------------- 向量表：检索与换实现

    @Test
    fun `向量检索按余弦取前 K 条`() = runBlocking {
        val dao = FakeMemoryVectorDao()
        dao.insertAll(
            listOf(
                vectorRow("hit", "工作压力太大，晚上总是睡不着"),
                vectorRow("near", "项目的事压得他喘不过气"),
                vectorRow("far", "周末一起去公园散步拍照吧")
            )
        )

        val hits = searchVectors(
            dao = dao,
            memoryIds = listOf("hit", "near", "far"),
            queryVector = LocalEmbedding.embed("最近项目压力很大睡不着"),
            limit = 2
        )

        assertEquals(2, hits.size)
        assertTrue("无关那条不该进前二，实际 ${hits.map { it.memoryId }}", hits.none { it.memoryId == "far" })
        assertTrue(hits[0].similarity >= hits[1].similarity)
        assertTrue(hits.all { it.similarity > 0.0 })
    }

    @Test
    fun `跨块的向量检索也能取到全局前 K`() = runBlocking {
        val dao = FakeMemoryVectorDao()
        // 130 条无关记忆先把第一块（128 条）填满，两条真正相关的放在最后一块：
        // 分块扫如果忘了合并各块的 topK，就会把答案漏在第二块里。
        val fillerIds = (1..130).map { "filler-$it" }
        dao.insertAll(fillerIds.map { id -> vectorRow(id, "无关条目${id}号") })
        dao.insertAll(
            listOf(
                vectorRow("late-hit", "工作压力太大睡不着"),
                vectorRow("late-near", "项目的压力让他喘不过气")
            )
        )

        val hits = searchVectors(
            dao = dao,
            memoryIds = fillerIds + listOf("late-hit", "late-near"),
            queryVector = LocalEmbedding.embed("最近项目压力很大睡不着"),
            limit = 2
        )

        assertEquals(2, hits.size)
        assertEquals(
            setOf("late-hit", "late-near"),
            hits.map { it.memoryId }.toSet()
        )
    }

    @Test
    fun `坏 blob 与别的实现的向量都不参与检索`() = runBlocking {
        val dao = FakeMemoryVectorDao()
        dao.insertAll(
            listOf(
                vectorRow("good", "工作压力太大，晚上总是睡不着"),
                // 长度对不上的坏行：解码失败就该跳过，不能把整次检索拖垮。
                MemoryVectorEntity(
                    memoryId = "broken",
                    provider = LocalEmbedding.PROVIDER,
                    dim = LocalEmbedding.DIM,
                    vector = byteArrayOf(1, 2, 3),
                    updatedAt = 0L
                ),
                // 上一代实现写的向量：provider 对不上，一律当没有。
                vectorRow("old", "项目的压力让他喘不过气", provider = "local-hash-v0")
            )
        )

        val hits = searchVectors(
            dao = dao,
            memoryIds = listOf("good", "broken", "old"),
            queryVector = LocalEmbedding.embed("最近项目压力很大睡不着"),
            limit = 5
        )

        assertEquals(listOf("good"), hits.map { it.memoryId })
        assertTrue(hits[0].similarity > 0.0)
    }

    @Test
    fun `换实现后旧 provider 的向量被清掉`() = runBlocking {
        val dao = FakeMemoryVectorDao()
        dao.insertAll(
            listOf(
                vectorRow("current", "项目压力很大", provider = LocalEmbedding.PROVIDER),
                vectorRow("old-1", "项目压力很大", provider = "local-hash-v0"),
                vectorRow("old-2", "项目压力很大", provider = "onnx-mini")
            )
        )

        val removed = pruneForeignVectors(dao)

        assertEquals("两条上一代的向量应该被清掉", 2, removed)
        assertEquals("只剩下当前实现的那条", 1, dao.allForProvider(LocalEmbedding.PROVIDER).size)
        assertTrue(dao.allForProvider("local-hash-v0").isEmpty())
        assertTrue(dao.allForProvider("onnx-mini").isEmpty())
    }

    // ---------------------------------------------------------------- 测试替身

    /** 只认「provider + 长度」两个约束的假向量表，语义按 MemoryVectorDao 的 SQL 对齐。 */
    private class FakeMemoryVectorDao : MemoryVectorDao {
        private val rows = LinkedHashMap<Pair<String, String>, MemoryVectorEntity>()

        override suspend fun allForProvider(provider: String): List<MemoryVectorEntity> =
            rows.values.filter { it.provider == provider }

        override suspend fun forMemories(ids: List<String>, provider: String): List<MemoryVectorEntity> =
            rows.values.filter { it.provider == provider && it.memoryId in ids }

        override suspend fun countForProvider(provider: String): Int =
            rows.values.count { it.provider == provider }

        override suspend fun insertAll(vectors: List<MemoryVectorEntity>) {
            vectors.forEach { rows[it.memoryId to it.provider] = it }
        }

        override suspend fun deleteByMemory(memoryId: String) {
            rows.keys.removeAll { it.first == memoryId }
        }

        override suspend fun deleteOrphans(): Int {
            // 假表里「记忆表」是空的：所有行都算孤儿。这条只在真实 Room 上才有意义。
            val before = rows.size
            rows.clear()
            return before
        }

        override suspend fun deleteOtherProviders(provider: String): Int {
            val before = rows.size
            rows.keys.removeAll { it.second != provider }
            return before - rows.size
        }

        override suspend fun deleteAll() {
            rows.clear()
        }
    }

    private fun vectorRow(
        memoryId: String,
        content: String,
        provider: String = LocalEmbedding.PROVIDER
    ): MemoryVectorEntity = MemoryVectorEntity(
        memoryId = memoryId,
        provider = provider,
        dim = LocalEmbedding.DIM,
        vector = VectorMath.encode(LocalEmbedding.embed(content)),
        updatedAt = 0L
    )
}
