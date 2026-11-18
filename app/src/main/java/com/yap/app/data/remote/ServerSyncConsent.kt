package com.yap.app.data.remote

/**
 * 「把对话交给云端处理」的同意结果（0.13，`spec/sync-consent/README.md`）。
 *
 * 两个选项对应两种**历史处理口径**，而不是两个开关：
 * - [FULL]：全量元数据+文本同步，再逐项把历史媒体压缩上传，消息附件改写成 `/v1/media/<id>`；
 * - [NEW_ONLY]：不推历史（人格/会话元数据可以建，但消息为空），之后的新消息走服务端。
 *
 * 取消不是一个值：取消意味着**什么都不记**，开关回到关闭（见 [ServerModeSwitchCoordinator]）。
 */
enum class ServerSyncConsent(val storedValue: String) {
    FULL("FULL"),
    NEW_ONLY("NEW_ONLY");

    companion object {
        /**
         * 宽松解析：未知/空值一律当成「没有记录」（重新询问），
         * 绝不把未来的新枚举值猜成 [FULL]——那会偷偷上传历史。
         */
        fun parse(raw: String?): ServerSyncConsent? {
            val value = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.storedValue == value }
        }
    }
}

/** 同意状态的纯规则（不碰 IO，单测直接钉住）。 */
object ServerSyncConsentPolicy {

    /** 键/比较口径：与 bootstrap 标记、`ServerModeRepository` 的 baseUrl 同形（只去尾部 `/`）。 */
    fun normalize(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    /**
     * 这次「本地 → 远程」的切换要不要弹窗。
     *
     * 只有「本来关着、现在要打开、且这台地址还没有同意记录」才问：
     * - 关着→关着、开着→开着：没有切换，不弹；
     * - 开着→关着：走全量下载流程，不弹同意；
     * - 同一台地址曾经同意过：沿用记录，不重复打扰（换地址必然查不到记录，会重新问）。
     */
    fun needsPrompt(
        currentlyEnabled: Boolean,
        nextEnabled: Boolean,
        recorded: ServerSyncConsent?
    ): Boolean = nextEnabled && !currentlyEnabled && recorded == null

    /**
     * 没有同意记录时 bootstrap 应该怎么推。
     *
     * **必须按 [ServerSyncConsent.NEW_ONLY] 处理**：用户没点过「同意并同步」，
     * 任何路径（旧版本遗留配置、保存新地址后运行时自动对齐）都不能替他把历史传上去。
     */
    fun effective(recorded: ServerSyncConsent?): ServerSyncConsent =
        recorded ?: ServerSyncConsent.NEW_ONLY
}
