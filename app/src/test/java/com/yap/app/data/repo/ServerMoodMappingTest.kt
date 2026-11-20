package com.yap.app.data.repo

import com.yap.app.data.remote.ServerMood
import kotlinx.serialization.json.Json
import com.yap.app.domain.MoodDimensions
import com.yap.app.domain.MoodReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端多维心情 → 本地缓存的映射（0.13，任务 2）。
 *
 * 服务端已经夹过一次，客户端仍要按 `MoodDimensions` 再规整：
 * 旧服务端可能只给 label/intensity，或者给一个越界值；本地提示词依赖这三个维度连续合法。
 */
class ServerMoodMappingTest {

    private fun previous(
        label: String = "平静",
        intensity: Int = 2,
        valence: Int = 0,
        arousal: Int = 2,
        dominance: Int = 3
    ) = MoodReading(
        label = label,
        intensity = intensity,
        valence = valence,
        arousal = arousal,
        dominance = dominance,
        updatedAt = 100L
    )

    @Test
    fun `三维与标签原样写进本地`() {
        val reading = ServerMoodMapping.toReading(
            server = ServerMood(
                personaId = "p1",
                label = "有点累",
                intensity = 3,
                valence = -1,
                arousal = 2,
                dominance = 3,
                reason = "加班",
                updatedAt = 999L
            ),
            previous = previous(),
            now = 1_000L
        )
        assertEquals("有点累", reading?.label)
        assertEquals(3, reading?.intensity)
        assertEquals(-1, reading?.valence)
        assertEquals(2, reading?.arousal)
        assertEquals(3, reading?.dominance)
        assertEquals("加班", reading?.reason)
        assertEquals("写库时间用本机 now，服务端 updatedAt 只作参考", 1_000L, reading?.updatedAt)
    }

    @Test
    fun `越界值按规范夹取`() {
        val reading = ServerMoodMapping.toReading(
            server = ServerMood(
                label = "很开心",
                intensity = 99,
                valence = 9,
                arousal = -3,
                dominance = 42
            ),
            previous = previous(),
            now = 2_000L
        )
        assertTrue(reading != null)
        assertTrue(reading!!.valence in MoodDimensions.VALENCE_MIN..MoodDimensions.VALENCE_MAX)
        assertTrue(reading.arousal in MoodDimensions.AROUSAL_MIN..MoodDimensions.AROUSAL_MAX)
        assertTrue(reading.dominance in MoodDimensions.DOMINANCE_MIN..MoodDimensions.DOMINANCE_MAX)
        assertEquals("强度只夹到 1..5，不再受单次 ±2 限制", 5, reading.intensity)
    }

    @Test
    fun `取消单次幅度限制 服务端心情原样落地`() {
        val reading = ServerMoodMapping.toReading(
            server = ServerMood(label = "烦", intensity = 5, valence = 2, arousal = 5, dominance = 5),
            previous = previous(label = "累", intensity = 1, valence = -2, arousal = 0, dominance = 0),
            now = 3_000L
        )
        assertTrue(reading != null)
        // 0.15：服务端算出来的心情就是他的真实反应，客户端不再二次压平。
        assertEquals(2, reading!!.valence)
        assertEquals(5, reading.arousal)
        assertEquals(5, reading.dominance)
        assertEquals(5, reading.intensity)
    }

    @Test
    fun `state 与事件里的心情三维可以解析`() {
        val mood = Json.decodeFromString<ServerMood>(
            """{"personaId":"p1","label":"平静","intensity":2,"updatedAt":9,
               "valence":1,"arousal":3,"dominance":4,"reason":"晒了会太阳"}"""
        )
        assertEquals(1, mood.valence)
        assertEquals(3, mood.arousal)
        assertEquals(4, mood.dominance)
        assertEquals("晒了会太阳", mood.reason)
    }

    @Test
    fun `旧服务端缺三维时按基准值读出`() {
        val mood = Json.decodeFromString<ServerMood>(
            """{"personaId":"p1","label":"平静","intensity":2,"updatedAt":9}"""
        )
        assertEquals(0, mood.valence)
        assertEquals(2, mood.arousal)
        assertEquals(3, mood.dominance)
    }

    @Test
    fun `服务端没给标签时不写库`() {
        assertNull(ServerMoodMapping.toReading(ServerMood(label = ""), previous(), now = 4_000L))
        assertNull(ServerMoodMapping.toReading(ServerMood(label = "   "), previous(), now = 4_000L))
    }

    @Test
    fun `强度缺失沿用上一轮`() {
        val reading = ServerMoodMapping.toReading(
            server = ServerMood(label = "平静", intensity = 0, valence = 0, arousal = 2, dominance = 3),
            previous = previous(intensity = 4),
            now = 5_000L
        )
        assertEquals(4, reading?.intensity)
    }
}
