package com.yap.app.util

/**
 * 通知 id 的分配规则。纯函数、不碰 Android，方便当普通单测跑。
 *
 * 为什么不继续用 `sessionId.hashCode()`：String.hashCode 只有 32 位、分布也糙，
 * 两个会话撞到同一个 id 时，后到的通知会把前一条**覆盖**掉。用户看到的不是「多了一条消息」，
 * 而是「他没回过我」——这种背后出错用户既看不到也管不了，却直接毁掉「他一直在那儿」的可信感。
 * 所以这里改用 64 位 FNV-1a，再折叠进一个固定区段里当下标。
 *
 * 区段是刻意分开的，任何两类通知都不会互相顶掉：
 * - 会话通知：0x0C00_0000 ~ 0x0CFF_FFFF
 * - 每个 persona 的聚合摘要：0x0D00_0000 ~ 0x0DFF_FFFF
 * - 常驻「他还在」：0x001F_2E3D（AgentNotifier.NOTIFICATION_ID，不在这两个区段内）
 */
object NotificationIds {

    /** 会话通知区段的起点。 */
    const val CHAT_ID_BASE = 0x0C00_0000

    /** 聚合摘要通知区段的起点。 */
    const val CHAT_SUMMARY_ID_BASE = 0x0D00_0000

    /** 每个区段的大小（2^24）。会话量级下 64 位 hash 撞进同一格的概率可以忽略。 */
    const val ID_SPAN = 0x0100_0000

    /** 某个会话的通知 id：同一个 sessionId 永远得到同一个值，重启 App 也不会变。 */
    fun chatId(sessionId: String): Int = CHAT_ID_BASE + bucket(sessionId)

    /** 某个 persona 的聚合摘要通知 id。 */
    fun chatSummaryId(personaId: String): Int = CHAT_SUMMARY_ID_BASE + bucket(personaId)

    /** 同一 persona 的会话共用一组，同组多会话会被系统折叠成一条摘要，不刷屏。 */
    fun chatGroup(personaId: String): String = "chat-$personaId"

    /** 把任意字符串稳定地折叠到 `[0, ID_SPAN)`。跨进程、跨版本都一致。 */
    fun bucket(key: String): Int {
        val hash = fnv1a64(key)
        // 高 32 位低位混进来，避免只取尾部字节导致相似字符串挤在一起。
        val folded = (hash xor (hash ushr 32)).toInt()
        return folded and (ID_SPAN - 1)
    }

    private fun fnv1a64(value: String): Long {
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64 位偏移基
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= 0x100000001b3L // FNV-1a 64 位质数
        }
        return hash
    }
}
