package com.yap.app.data.repo

import com.yap.app.data.ai.PersonaTranslationResult
import com.yap.app.data.ai.PersonaTranslator
import com.yap.app.data.local.PersonaDao
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.domain.PersonaLanguage
import com.yap.app.domain.PersonaPromptLocalization
import com.yap.app.domain.PersonaTranslationCodec
import com.yap.app.domain.PersonaTranslationSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** 一次「按需翻译」的结局；界面/日志只需要这个，不需要译文本身。 */
enum class PersonaTranslationOutcome {
    /** 设置里关掉了「自动翻译人设」且这次不是手动重译。 */
    DISABLED,

    /** 源语言与目标语言相同，不需要翻译。 */
    SAME_LANGUAGE,

    /** 缓存命中：零请求。 */
    CACHED,

    /** 这次真的调了模型并把结果写进了缓存。 */
    TRANSLATED,

    /** 调用失败 / 超时 / 坏 JSON：这一次用原文，下次再试。 */
    FAILED,

    /** 人格已经不在了。 */
    MISSING
}

/**
 * 协调器需要的最小依赖面。
 *
 * 单独抽出来只为一件事：并发去重、缓存命中、失败不写缓存这些规则能在**纯 JVM 单测**里
 * 用假实现跑（这台机器跑不了模拟器，也不该为了一个去重测试拖进 Room / DataStore）。
 */
interface PersonaTranslationGateway {
    suspend fun persona(id: String): PersonaEntity?
    suspend fun writeCache(personaId: String, translationJson: String?, lang: String?)
    suspend fun autoTranslateEnabled(): Boolean

    /** 调一次模型；没有配置 / 超时 / 坏 JSON 都返回 `ok = false`（不抛异常）。 */
    suspend fun translate(persona: PersonaEntity, targetLang: String): PersonaTranslationResult

    /** 当前软件语言（`zh` / `en` …）。 */
    fun appLanguage(): String
}

/**
 * 「开始人格之前按需翻译」的**唯一入口**（`spec/persona-translation/README.md` §2）。
 *
 * 三件事：
 * 1. 判定要不要翻（设置开关 / 源语言是否等于目标语言 / 缓存是否命中当前语言 + 源字段哈希）；
 * 2. 同一进程内按 `personaId + lang + sourceHash` 去重：并发进来的同一个请求只发一次；
 * 3. 写到 `personas.translationJson` / `translationLang`；失败不写缓存（否则下次「命中」的
 *    就是一份假的原文缓存，再也不会重试）。
 *
 * 刻意**不**在 App 启动时批量翻译：一开 App 就发 N 个请求既贵又没必要，规范 §2 明确禁止。
 * 触发点只有两个——打开会话前、生成主动消息前（外加人格页的手动「重新翻译」）。
 */
@Singleton
class PersonaTranslationCoordinator @Inject constructor(
    private val gateway: PersonaTranslationGateway
) {

    /** 去重键 → 这一趟的结果；同一个 key 的并发调用者一起等第一趟。 */
    private val inFlight = mutableMapOf<String, CompletableDeferred<PersonaTranslationOutcome>>()
    private val lock = Mutex()

    /**
     * 「开始这个人之前」调用一次。**永远不抛异常**：翻译失败最坏就是原文，
     * 不能因为它把聊天页/主动消息拦住（规范 §7）。
     *
     * @param force 手动「重新翻译」用：先清缓存、无视设置开关、立刻译一次。
     */
    suspend fun ensureTranslated(
        personaId: String,
        force: Boolean = false
    ): PersonaTranslationOutcome = runCatching {
        val persona = gateway.persona(personaId) ?: return@runCatching PersonaTranslationOutcome.MISSING
        if (!force && !gateway.autoTranslateEnabled()) {
            return@runCatching PersonaTranslationOutcome.DISABLED
        }
        val lang = gateway.appLanguage()
        // 手动重译：先清掉旧缓存，这样即便这次失败也不会留下一份「看起来有效」的旧译文。
        if (force) gateway.writeCache(personaId, null, null)

        val sourceHash = PersonaTranslationSource.hashOf(persona)
        val cached = PersonaTranslationCodec.decode(persona.translationJson)
        if (!force && cached != null && cached.matches(sourceHash, lang)) {
            return@runCatching PersonaTranslationOutcome.CACHED
        }
        if (!PersonaTranslationSource.needsTranslation(persona, lang)) {
            return@runCatching PersonaTranslationOutcome.SAME_LANGUAGE
        }

        deduped(persona, lang, sourceHash)
    }.getOrElse { PersonaTranslationOutcome.FAILED }

    /**
     * 去重：同一个 `personaId + lang + sourceHash` 同时只有一个请求在飞。
     *
     * 实现用的是「谁先登记谁执行，其余人 await 同一份结果」。
     * 不用「查一下 map 然后各自发请求」——那中间有挂起点，两个调用方都会以为自己是第一个。
     */
    private suspend fun deduped(
        persona: PersonaEntity,
        lang: String,
        sourceHash: String
    ): PersonaTranslationOutcome {
        val key = "${persona.id}|$lang|$sourceHash"
        var owner = false
        val gate = lock.withLock {
            inFlight[key] ?: CompletableDeferred<PersonaTranslationOutcome>().also {
                inFlight[key] = it
                owner = true
            }
        }
        if (!owner) return gate.await()
        var outcome = PersonaTranslationOutcome.FAILED
        try {
            outcome = translateAndStore(persona, lang, sourceHash)
            return outcome
        } finally {
            lock.withLock { inFlight.remove(key) }
            // 被取消时也要唤醒等在门口的人，否则他们的 await 会永远挂着。
            if (!gate.isCompleted) gate.complete(outcome)
        }
    }

    private suspend fun translateAndStore(
        persona: PersonaEntity,
        lang: String,
        sourceHash: String
    ): PersonaTranslationOutcome {
        val result = runCatching { gateway.translate(persona, lang) }
            .getOrElse { return PersonaTranslationOutcome.FAILED }
        if (!result.ok) {
            // 失败不写缓存：写一份「原文缓存」会让下次误判成功，永远不会重试。
            return PersonaTranslationOutcome.FAILED
        }
        val translation = result.translation
        if (translation.isEmpty()) return PersonaTranslationOutcome.SAME_LANGUAGE
        gateway.writeCache(persona.id, PersonaTranslationCodec.encode(translation), lang)
        return if (translation.matches(sourceHash, lang)) {
            PersonaTranslationOutcome.TRANSLATED
        } else {
            // 翻译器理论上会填对哈希/语言；万一没有，按失败处理，下次再来。
            PersonaTranslationOutcome.FAILED
        }
    }

    /**
     * 后台触发：调用方只是「打开页面前顺手准备一下」，不该等它、也不该因为它失败。
     *
     * 这里不吞 `CancellationException`（协程被取消就是该停），只吞真正的失败。
     */
    suspend fun prefetch(personaId: String) {
        try {
            ensureTranslated(personaId)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // 翻译是旁路：失败就当没发生过（界面用原文）。
        }
    }

    /** 当前语言（给界面显示「已翻译成 xx」用）。 */
    fun appLanguage(): String = gateway.appLanguage()

    /** 语言标签的可读名字（界面用）。 */
    fun languageLabel(lang: String): String = PersonaLanguage.displayName(lang)
}

/** 生产实现：Room 里读人格与缓存，设置里读开关，[PersonaTranslator] 发请求。 */
@Singleton
class DefaultPersonaTranslationGateway @Inject constructor(
    private val personaDao: PersonaDao,
    private val personaRepository: PersonaRepository,
    private val translator: PersonaTranslator,
    private val settingsRepository: SettingsRepository
) : PersonaTranslationGateway {

    override suspend fun persona(id: String): PersonaEntity? = personaDao.getById(id)

    override suspend fun writeCache(personaId: String, translationJson: String?, lang: String?) {
        personaRepository.updateTranslationCache(personaId, translationJson, lang)
    }

    override suspend fun autoTranslateEnabled(): Boolean =
        settingsRepository.settings.first().autoTranslatePersona

    override suspend fun translate(
        persona: PersonaEntity,
        targetLang: String
    ): PersonaTranslationResult = translator.translate(persona, targetLang)

    override fun appLanguage(): String = PersonaPromptLocalization.currentAppLanguage()
}
