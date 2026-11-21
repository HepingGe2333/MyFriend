package com.yap.agentd.safety

/**
 * 危机安全兜底（0.15）：用户说「不想活 / 自杀 / 安眠药 / 割腕 / 消失比较好」时，
 * 回复里必须有至少一个**安全锚点**；没有就在末尾补 1–2 句。
 *
 * 为什么放在服务端而不是只靠提示词：提示词能让模型「通常」处理得好，但评测和真实对话里
 * 总会有模型回一句「别想太多，早点睡」就过去的情况。这不是一句普通的话聊坏了，是安全底线，
 * 所以用确定性代码兜住：**角色人格、心情、连发、延迟都不影响它**。
 *
 * 这里是**纯 Kotlin**（无 IO、无存储、无 HTTP），输入输出都是字符串，便于逐条单测：
 * - [isCrisis]：用户原句是否在表达自伤/轻生风险（带一点否定判断，避免「我没有自杀」误报）；
 * - [hasSafetyAnchor]：回复里是否已经有「我在 / 别一个人 / 身边有人 / 120 / 110 / 医院 /
 *   急诊 / 求助 / 家人 / 朋友」这类「把对方拉回安全」的话；
 * - [guard]：需要时把提示追加到最后一条回复的末尾，并返回真正追加的文本
 *   （调用方据此补一个 SSE delta，让在线客户端也能立刻看到）。
 */
object SafetyGuard {

    /** 用户侧的危机信号。命中任意一个就按危机处理（否定式除外）。 */
    val CRISIS_PHRASES: List<String> = listOf(
        "不想活",
        "自杀",
        "安眠药",
        "割腕",
        "消失比较好"
    )

    /**
     * 回复里的安全锚点。要求给的 10 个词一个不少；另外补上一个**同义的自然说法**，
     * 否则模型明明问了「你身边有没有人」却因为少一个「有」字被判定为「没说」。
     */
    val SAFETY_ANCHORS: List<String> = listOf(
        "我在",
        "别一个人",
        "身边有人",
        "身边有没有人",
        "120",
        "110",
        "医院",
        "急诊",
        "求助",
        "家人",
        "朋友"
    )

    /** 情绪危机（想消失/不想活）的兜底：先接住情绪，再把人往身边人与求助电话上引。 */
    const val EMOTIONAL_NOTE =
        "我在，别一个人扛着。你身边有人吗？可以打 120 或 110，也可以找信任的家人朋友陪着你。"

    /** 医疗危机（安眠药/割腕）的兜底：时间更紧，先让人叫急救/去急诊。 */
    const val MEDICAL_NOTE =
        "我在，先别一个人扛着，尽快打 120 或去急诊。身边有人的话让他陪着你，也可以联系家人朋友。"

    /** 一次兜底的结果：`messages` 是改写后的回复；`appended` 是真正追加的文本（没追加为 null）。 */
    data class GuardResult(
        val messages: List<String>,
        val appended: String?
    ) {
        val changed: Boolean get() = appended != null
    }

    /** 用户原句里是否有危机信号（允许「我不想自杀」这类否定句不算）。 */
    fun isCrisis(message: String): Boolean {
        val text = message.trim()
        if (text.isEmpty()) return false
        for (phrase in CRISIS_PHRASES) {
            var index = text.indexOf(phrase)
            while (index >= 0) {
                if (!isNegated(text, index)) return true
                index = text.indexOf(phrase, index + phrase.length)
            }
        }
        return false
    }

    /** 回复里是否已经有安全锚点。数字锚点按「完整数字」匹配，避免把 1200 当成 120。 */
    fun hasSafetyAnchor(reply: String): Boolean {
        if (reply.isBlank()) return false
        for (anchor in SAFETY_ANCHORS) {
            if (anchor.all { it.isDigit() }) {
                if (Regex("(?<!\\d)$anchor(?!\\d)").containsMatchIn(reply)) return true
            } else if (reply.contains(anchor)) {
                return true
            }
        }
        return false
    }

    /**
     * 需要时给最后一句话追加安全提示。
     *
     * 只改**最后一条**：连发时前面几条保持原样，提示落在最后一条气泡里，
     * 不会变成一条突兀的独立消息。没有回复（`messages` 为空）时原样返回、不追加——
     * 「这一轮没说话」由 `stay_silent` 的严格语义处理，这里不擅自替模型开口。
     */
    fun guard(userMessage: String, messages: List<String>): GuardResult {
        if (messages.isEmpty()) return GuardResult(messages, null)
        if (!isCrisis(userMessage)) return GuardResult(messages, null)
        val combined = messages.joinToString("\n")
        if (hasSafetyAnchor(combined)) return GuardResult(messages, null)
        val note = if (isMedical(userMessage)) MEDICAL_NOTE else EMOTIONAL_NOTE
        val last = messages.last()
        val separator = if (last.endsWith("\n")) "" else "\n\n"
        val appended = separator + note
        val updated = messages.toMutableList()
        updated[updated.lastIndex] = last + appended
        return GuardResult(updated, appended)
    }

    /** 医疗类（药物过量/割腕）优先用「120/急诊」的提示；其余用情绪危机的提示。 */
    private fun isMedical(message: String): Boolean =
        message.contains("安眠药") || message.contains("割腕")

    /** 危机短语前面紧邻否定词时不算（「我没有自杀的念头」）。 */
    private fun isNegated(text: String, index: Int): Boolean {
        if (index <= 0) return false
        val prefix = text.substring(maxOf(0, index - 8), index).trimEnd()
        return NEGATION_SUFFIX.containsMatchIn(prefix)
    }

    private val NEGATION_SUFFIX =
        Regex("(?:没有|没|不|别|不是|不会|并不|没想|没打算|从未|从没|不想)(?:任何|什么)?$")
}
