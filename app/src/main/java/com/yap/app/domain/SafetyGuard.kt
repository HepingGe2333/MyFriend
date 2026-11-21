package com.yap.app.domain

/**
 * 危机安全兜底（0.15）。
 *
 * 这是一层**只加一句话**的保险，不是心理干预，也不是内容审查：
 * - 只在这一轮用户的话里出现明确的危机语句（不想活、自杀、割腕、安眠药、消失比较好……）时启动；
 * - 只在模型这一轮的回复里**没有**任何「安全锚点」（我在 / 别一个人 / 身边有人 / 120 / 医院 /
 *   求助 / 家人 / 朋友……）时才追加一句很短的提示；
 * - 永远不覆盖、不改写模型原本说的话，也不改人设语气：只是在末尾补一行括号里的话。
 *
 * 为什么是纯函数：这条规则必须能在 JVM 单测里逐条钉住（命中/不命中/已有锚点不追加），
 * 而真正展示给用户的句子由调用点从 `R.string.safety_crisis_note` 取，按界面语言显示。
 *
 * 判断刻意保守：宁可多补一句，也不漏掉真正的求助；但明显是「想死你了」「笑死」这类
 * 想念/夸张说法不算危机（见 [CRISIS_PATTERNS] 里的负向先行断言）。
 */
object SafetyGuard {

    /**
     * 危机语句。中文为主，附几条明确的英文说法（英文界面/英文人设也会遇到）。
     *
     * 每条都尽量带上下文，避免把「想死你了」当成「想死」：
     * - `想死(?!你|我)`：排除「想死你了」「想死我了」；
     * - 英文只认明确的第一人称表达，不认 `kill` / `die` 这种会误伤玩笑的词。
     */
    private val CRISIS_PATTERNS: List<Regex> = listOf(
        Regex("不想活"),
        Regex("活不下去"),
        // 只认「想死」；「要死」在「累得要死」「要死机了」里太常见，会大面积误判。
        Regex("想死(?!你|我)"),
        Regex("自杀"),
        Regex("轻生"),
        Regex("自残|自伤"),
        Regex("结束(自己的)?生命|了结自己"),
        Regex("割腕|跳楼|上吊|烧炭"),
        Regex("安眠药"),
        Regex("一了百了"),
        Regex("(不如|干脆|还是)消失(掉)?(?!在)"),
        Regex("消失(比较|更)好"),
        Regex("没有我(会|你们会)?更好"),
        Regex("想不开"),
        Regex("遗书"),
        Regex("\\bkill(ing)? myself\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(want|wanna|wanting) to die\\b", RegexOption.IGNORE_CASE),
        Regex("\\bsuicid(e|al)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bend my life\\b|\\bend it all\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(hurt|cut|harm)(ting)? myself\\b", RegexOption.IGNORE_CASE),
        Regex("\\bself[- ]?harm\\b", RegexOption.IGNORE_CASE)
    )

    /**
     * 安全锚点：出现任何一条就认为模型已经把人往「有人陪着 / 能求助」的方向带，不再补话。
     *
     * `我在` 用先行断言限定成「我在这儿/我在你旁边/我在，」这类真正的陪伴，
     * 避免「我在忙」把安全提示挡掉；[朋友] 与 [家人] 按规范列为锚点（哪怕是在劝他找他们）。
     */
    private val ANCHOR_PATTERNS: List<Regex> = listOf(
        Regex("我在(?=[，。！？!?,、\\s]|这|你|旁|呢|啊|呀|陪|$)"),
        Regex("别一个人|不要一个人|一个人待着|身边有人|有人陪|陪着你|我陪你|我在你身边"),
        Regex("\\b(120|110|119|911|112)\\b"),
        Regex("医院|急诊|医生|心理(咨询|热线|医生)|热线|求助|报警"),
        Regex("家人|家里人|朋友"),
        Regex("\\bi'?m here\\b|\\bi am here\\b", RegexOption.IGNORE_CASE),
        Regex("\\bnot alone\\b|\\bsomeone (is )?with you\\b", RegexOption.IGNORE_CASE),
        Regex("\\bhospital\\b|\\bemergency\\b|\\bhotline\\b|\\btherapist\\b|\\bdoctor\\b", RegexOption.IGNORE_CASE),
        Regex("\\bfamily\\b|\\bfriends?\\b", RegexOption.IGNORE_CASE)
    )

    /** 这句话里有没有明确的危机语句。 */
    fun isCrisis(userMessage: String): Boolean {
        val text = userMessage.trim()
        if (text.isEmpty()) return false
        return CRISIS_PATTERNS.any { it.containsMatchIn(text) }
    }

    /** 这句回复里有没有安全锚点（把人往「有人陪 / 能求助」的方向带）。 */
    fun hasAnchor(reply: String): Boolean {
        val text = reply.trim()
        if (text.isEmpty()) return false
        return ANCHOR_PATTERNS.any { it.containsMatchIn(text) }
    }

    /**
     * 需要补的那句话；不需要补时返回 null。
     *
     * 三个条件同时成立才算「需要」：用户说了危机语句、他的回复非空、回复里没有安全锚点。
     * 回复为空（例如只有表情/工具）不补——那种情况由聊天流程自己决定要不要落一条消息，
     * 这里不替它造正文。
     */
    fun noteIfNeeded(userMessage: String, reply: String, note: String): String? {
        val safeNote = note.trim()
        if (safeNote.isEmpty()) return null
        if (reply.isBlank()) return null
        if (!isCrisis(userMessage)) return null
        if (hasAnchor(reply)) return null
        return safeNote
    }

    /**
     * 末尾追加（不覆盖原话）。已经有安全提示时不会重复追加：
     * 提示本身带「120」这类锚点，第二次调用会走 [hasAnchor] 直接返回原回复。
     */
    fun appendIfNeeded(userMessage: String, reply: String, note: String): String {
        val safeNote = noteIfNeeded(userMessage, reply, note) ?: return reply
        return reply.trimEnd() + "\n" + safeNote
    }
}
