package com.yap.app.data.prefs

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class BubbleStyle { SOFT, COMPACT, OUTLINED }

/**
 * 本机偏好。
 *
 * 1.9 把「调机器人」的那些旋钮从产品里拿掉了：
 * 记忆四开关、读消息节奏、月度额度 / 单价、每天主动几次，全都不再存在。
 * 记忆是内置行为；节奏与主动程度由人格（`proactiveLevel` 等）和模型自己的
 * `defer_reply` 决定；用量页只统计 token，不拦截、不显示钱。
 *
 * 这里剩下的每一项用户都能在设置页看到（[heartbeatLastResetAt] 除外，那是内部状态）。
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentColor: Long = 0xFFC76F4E,
    val fontScale: Float = 1f,
    val bubbleStyle: BubbleStyle = BubbleStyle.SOFT,
    val animationsEnabled: Boolean = true,
    val chatBackgroundColor: Long? = null,
    val chatBackgroundImageUri: String? = null,
    /**
     * 「我的头像」：App 私有目录里的 `file://` 路径，null = 默认（一枚安静的人形图标）。
     *
     * 它**不进数据库**（不需要为新功能加迁移），也**不进备份**：图片文件本来就不跟着
     * 备份走，换手机之后重新选一张就好。
     */
    val userAvatarUri: String? = null,
    /**
     * 「我的头像」的**本机缓存副本**（服务端模式才有值）。
     *
     * 服务端模式下 [userAvatarUri] 存 `/v1/media/<id>`，渲染走带鉴权的媒体组件；
     * 但服务端不可达（离线/关掉服务端模式）时那张图取不到，所以上传成功后再留一份本地文件，
     * 渲染层用它兜底。本地模式下它恒为 null（此时 [userAvatarUri] 本身就是本地文件）。
     */
    val userAvatarLocalUri: String? = null,
    val activePersonaId: String? = null,
    val hapticsEnabled: Boolean = true,
    /** 让他常驻后台继续「活着」（前台服务 + 自主循环）。 */
    val agentLoopEnabled: Boolean = true,
    /**
     * 打开聊天 / 生成主动消息前，按需把人格提示词翻译成软件当前语言
     * （`spec/persona-translation/README.md` §6，默认开）。
     *
     * 关掉之后一律用原文：对「我就是要用中文设定配英文界面」的人，翻译只是多余的一次请求。
     */
    val autoTranslatePersona: Boolean = true,
    /** 是否还显示「把他留在后台」的引导卡片。 */
    val backgroundTipDismissed: Boolean = false,
    /**
     * 他的冲动曲线走到哪儿了：概率时钟上一次被重置的时刻。
     *
     * 这是**内部状态**，不是设置项：界面里没有它，也不该有。它存在的唯一理由，
     * 是让 App 重启之后曲线接着走 —— 不然每次冷启动他都从头「很想找你」，像换了个人。
     */
    val heartbeatLastResetAt: Long = 0L,
    /** 上一次他真的因为这股冲动想了一回的时刻；null = 还没发生过。同样是内部状态。 */
    val heartbeatLastFiredAt: Long? = null
)
