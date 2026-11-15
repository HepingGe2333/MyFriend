package com.myfriend.app.ui.screens.persona

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myfriend.app.domain.PersonaRevisionReason
import com.myfriend.app.ui.common.ErrorText
import com.myfriend.app.R
import com.myfriend.app.data.local.ApiConfigEntity
import com.myfriend.app.data.repo.ApiConfigRepository
import com.myfriend.app.data.repo.PersonaCardRepository
import com.myfriend.app.data.repo.PersonaRepository
import com.myfriend.app.data.ai.SpeechStyleResult
import com.myfriend.app.data.ai.SpeechStyleSource
import com.myfriend.app.data.ai.SpeechStyleSummarizer
import com.myfriend.app.data.ai.WorldPresetExpander
import com.myfriend.app.domain.AvatarDraftFiles
import com.myfriend.app.domain.PersonaActivity
import com.myfriend.app.domain.PersonaActivityCodec
import com.myfriend.app.domain.PersonaCard
import com.myfriend.app.domain.PersonaProfile
import com.myfriend.app.domain.ProactiveLevel
import com.myfriend.app.domain.SleepPolicy
import com.myfriend.app.domain.SpeechStyle
import com.myfriend.app.domain.SpeechStyleCodec
import com.myfriend.app.domain.WorldPreset
import com.myfriend.app.domain.WorldPresetCodec
import com.myfriend.app.domain.PersonaDraft
import com.myfriend.app.domain.PersonaTrait
import com.myfriend.app.util.MediaStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

/**
 * 联系人编辑表单。
 *
 * 1.9 把它从「名字 + 大段提示词」扩展成可一眼看懂的分节：
 * 性格 / 喜欢 / 习惯 / 过去 / 设定 + 主动程度 / 记性 / 抽记忆频率 / 睡觉 / 唤醒阈值。
 * 列表字段用「逗号分隔」的字符串编辑，落库前再拆成数组——这是界面上最不容易写错的形式。
 */
data class PersonaForm(
    val name: String = "",
    val description: String = "",
    val avatarUri: String? = null,
    val avatarSeed: Int = 0,
    val prompt: String = "",
    val category: String = "日常聊天",
    val defaultApiConfigId: String? = null,
    val modelOverride: String = "",
    val traitsText: String = "",
    val likesText: String = "",
    val behaviorsText: String = "",
    val backstory: String = "",
    val quirks: String = "",
    val proactiveLevel: Int = ProactiveLevel.DEFAULT,
    val memoryRetention: Int = 3,
    val extractionEvery: Int = 6,
    val sleepEnabled: Boolean = true,
    /** 睡觉 / 醒来的 HH:mm；留空用默认 23:30–08:00。 */
    val sleepAt: String = "",
    val wakeAt: String = "",
    val wakeThreshold: Int = 6,
    // ---- 0.12 人设引导：世界 / 话风 / 当前活动（v13） ------------------------
    val worldName: String = "",
    val era: String = "",
    val worldDescription: String = "",
    val scheduleJson: String = "",
    val relationshipsJson: String = "",
    val obligationsText: String = "",
    val speechTone: String = "",
    val speechPhrases: List<String> = emptyList(),
    val speechHabits: String = "",
    val speechAvoid: String = "",
    val speechSample: String = "",
    val speechSource: String = ""
) {
    val traits: List<String> get() = parseList(traitsText)
    val likes: List<String> get() = parseList(likesText)
    val behaviors: List<String> get() = parseList(behaviorsText)

    companion object {
        /** 中英文逗号、顿号、换行都当分隔符；去空、去重、保序。 */
        fun parseList(raw: String): List<String> =
            raw.split(',', '，', '、', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

        /** [separator] 是界面上回显用的分隔符（中英文不同），解析时两种都认。 */
        fun joinList(items: List<String>, separator: String): String = items.joinToString(separator)
    }
}

data class PersonaEditorUiState(
    val personaId: String? = null,
    val form: PersonaForm = PersonaForm(),
    val initialPrompt: String = "",
    val initialForm: PersonaForm = PersonaForm(),
    /** 结构化人设卡（可在提示词下方展开编辑）。 */
    val card: PersonaCard = PersonaCard(),
    val initialCard: PersonaCard = PersonaCard(),
    val configs: List<ApiConfigEntity> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val isNew: Boolean = true,
    val isDefault: Boolean = false,
    /** 他此刻在做什么（只读展示；到点自动按「没在忙什么」显示）。 */
    val currentActivity: PersonaActivity? = null,
    val worldFilling: Boolean = false,
    val speechSummarizing: Boolean = false
) {
    val isDirty: Boolean get() = form != initialForm || card != initialCard
    val boundConfig: ApiConfigEntity? get() = configs.firstOrNull { it.id == form.defaultApiConfigId }
}

sealed interface PersonaEditorEvent {
    data class Saved(val personaId: String) : PersonaEditorEvent
    data class Message(val text: String) : PersonaEditorEvent
}

@HiltViewModel
class PersonaEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val personaRepository: PersonaRepository,
    private val personaCardRepository: PersonaCardRepository,
    private val speechStyleSummarizer: SpeechStyleSummarizer,
    private val worldPresetExpander: WorldPresetExpander,
    apiConfigRepository: ApiConfigRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val personaId: String? = savedStateHandle.get<String>("personaId")?.takeIf { it.isNotBlank() }
    private val _state = MutableStateFlow(PersonaEditorUiState(personaId = personaId, isNew = personaId == null))
    val state: StateFlow<PersonaEditorUiState> = _state.asStateFlow()
    private val events = Channel<PersonaEditorEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    init {
        viewModelScope.launch {
            if (personaId != null) {
                val persona = personaRepository.getById(personaId)
                savedAvatarUri = persona?.avatarUri
                if (persona == null) {
                    events.send(PersonaEditorEvent.Message(context.getString(R.string.persona_editor_persona_missing)))
                    _state.update { it.copy(loading = false) }
                } else {
                    val world = WorldPresetCodec.decode(persona.worldJson)
                    val speech = SpeechStyleCodec.decode(persona.speechStyleJson)
                    val form = PersonaForm(
                        name = persona.name,
                        description = persona.description,
                        avatarUri = persona.avatarUri,
                        avatarSeed = persona.avatarSeed,
                        prompt = persona.prompt,
                        category = persona.category,
                        defaultApiConfigId = persona.defaultApiConfigId,
                        modelOverride = persona.modelOverride.orEmpty(),
                        traitsText = PersonaForm.joinList(
                            PersonaProfile.decodeList(persona.traits),
                            context.getString(R.string.persona_editor_list_separator)
                        ),
                        likesText = PersonaForm.joinList(
                            PersonaProfile.decodeList(persona.likes),
                            context.getString(R.string.persona_editor_list_separator)
                        ),
                        behaviorsText = PersonaForm.joinList(
                            PersonaProfile.decodeList(persona.behaviors),
                            context.getString(R.string.persona_editor_list_separator)
                        ),
                        backstory = persona.backstory.orEmpty(),
                        quirks = persona.quirks.orEmpty(),
                        proactiveLevel = ProactiveLevel.normalize(persona.proactiveLevel),
                        memoryRetention = persona.memoryRetention.coerceIn(1, 5),
                        extractionEvery = persona.extractionEvery.coerceIn(3, 12),
                        sleepEnabled = persona.sleepEnabled,
                        sleepAt = persona.quietSleepMinutes?.let { formatClock(it) }.orEmpty(),
                        wakeAt = persona.quietWakeMinutes?.let { formatClock(it) }.orEmpty(),
                        wakeThreshold = SleepPolicy.normalizeThreshold(persona.wakeThreshold),
                        worldName = world?.worldName.orEmpty(),
                        era = world?.era.orEmpty(),
                        worldDescription = world?.description.orEmpty(),
                        scheduleJson = WorldPresetCodec.encodeSchedule(world?.schedule.orEmpty()),
                        relationshipsJson = WorldPresetCodec.encodeRelationships(world?.relationships.orEmpty()),
                        obligationsText = world?.obligations.orEmpty().joinToString("\n"),
                        speechTone = speech?.tone.orEmpty(),
                        speechPhrases = speech?.phrases.orEmpty(),
                        speechHabits = speech?.habits.orEmpty().joinToString("\n"),
                        speechAvoid = speech?.avoid.orEmpty().joinToString("\n")
                    )
                    // 还没有卡时，这里会从旧的一整段提示词里尽力拆一份出来（只读，不写库）。
                    val card = runCatching { personaCardRepository.cardOf(persona.id) }
                        .getOrDefault(PersonaCard())
                    _state.update {
                        it.copy(
                            form = form,
                            initialForm = form,
                            initialPrompt = persona.prompt,
                            card = card,
                            initialCard = card,
                            isDefault = persona.isDefault,
                            currentActivity = PersonaActivityCodec.decode(persona.activityJson),
                            loading = false
                        )
                    }
                }
            } else {
                val initial = PersonaForm(avatarSeed = Random.nextInt(0, 13))
                _state.update {
                    it.copy(
                        form = initial,
                        initialForm = initial.copy(name = "", prompt = "", description = ""),
                        initialPrompt = "",
                        loading = false
                    )
                }
            }
            apiConfigRepository.observeAll().collect { configs ->
                _state.update { it.copy(configs = configs) }
            }
        }
    }

    fun update(transform: (PersonaForm) -> PersonaForm) {
        _state.update { it.copy(form = transform(it.form)) }
    }

    /**
     * 本次编辑过程中新建、但还没保存的头像文件（审计 P2-2）。
     *
     * 选图是「立刻落盘」的；不记着它们，取消编辑或连选两张就会在 files/avatars 里留孤儿。
     * 只有「本次新建的」才进这个集合：数据库里已经保存过的那张由仓库层负责删。
     *
     * 这里直接用 `MediaStorage.deleteAvatar`（不查引用）是安全的：集合里只会出现在本页
     * `persistAvatar` 刚写出来的**全新文件**（每次都是新 UUID，数据库里不可能有行引用它）。
     * 凡是「库里已经引用过的文件」，删除一律走 `MediaJanitor` 的带守卫版本。
     */
    private val pendingAvatarUris = mutableSetOf<String>()

    /** ViewModel 是否已经销毁：选图协程晚到的登记据此改成「直接删文件」（审计 P2-12）。 */
    @Volatile
    private var draftWindowClosed = false

    /** 数据库里当前保存的头像（编辑开始时的那张）；取消编辑时它不能被删。 */
    private var savedAvatarUri: String? = null

    fun setAvatarUri(uri: String?) {
        // 审计 P2-12（0.11.1）：页面已经销毁时才落盘的头像，没人会在草稿集合里登记它；
        // 这里直接删掉刚写的文件（否则就是一个永远没人回收的孤儿文件）。
        if (draftWindowClosed) {
            val orphan = uri?.trim().orEmpty().takeIf { it.isNotEmpty() }
            if (orphan != null) MediaStorage.deleteAvatar(context, orphan)
            return
        }
        val previous = _state.value.form.avatarUri
        // 连选两张：前一张如果是本次草稿，立刻删掉（不留中间文件）。
        val stale = AvatarDraftFiles.onPicked(previous, pendingAvatarUris)
        stale.forEach { MediaStorage.deleteAvatar(context, it) }
        pendingAvatarUris.removeAll(stale.toSet())
        val next = uri?.trim().orEmpty()
        if (next.isNotEmpty() && next != savedAvatarUri) pendingAvatarUris += next
        update { it.copy(avatarUri = uri) }
    }

    /**
     * 取消编辑 / 返回 / ViewModel 销毁时调用：删掉本次新建但没被保存的头像文件。
     *
     * 幂等：保存成功后 pending 已经清空，再调一次什么都不做。
     */
    fun discardDraftFiles() {
        val stale = AvatarDraftFiles.onDiscard(savedAvatarUri, pendingAvatarUris)
        stale.forEach { MediaStorage.deleteAvatar(context, it) }
        pendingAvatarUris.removeAll(stale.toSet())
    }
    fun setAvatarSeed(seed: Int) = update { it.copy(avatarSeed = seed) }
    fun setPrompt(prompt: String) = update { it.copy(prompt = prompt) }
    fun resetPromptToSaved() = update { it.copy(prompt = _state.value.initialPrompt) }

    /** 修改结构化人设卡（身份/声音/性格/价值观/边界/生活/你们之间）。 */
    fun updateCard(transform: (PersonaCard) -> PersonaCard) {
        _state.update { it.copy(card = transform(it.card)) }
    }

    fun addTrait() {
        _state.update {
            it.copy(card = it.card.copy(traits = it.card.traits + PersonaTrait()))
        }
    }

    fun updateTrait(index: Int, transform: (PersonaTrait) -> PersonaTrait) {
        _state.update { state ->
            val traits = state.card.traits.toMutableList()
            if (index !in traits.indices) return@update state
            traits[index] = transform(traits[index])
            state.copy(card = state.card.copy(traits = traits))
        }
    }

    fun removeTrait(index: Int) {
        _state.update { state ->
            val traits = state.card.traits.toMutableList()
            if (index !in traits.indices) return@update state
            traits.removeAt(index)
            state.copy(card = state.card.copy(traits = traits))
        }
    }

    /** 「AI 补全」：用简介/提示词补一个世界预设（失败时保留角色名 + 空数组）。 */
    fun fillWorldWithAi() {
        val current = _state.value
        if (current.worldFilling) return
        val form = current.form
        val brief = listOf(form.description, form.prompt).firstOrNull { it.isNotBlank() }.orEmpty()
        viewModelScope.launch {
            _state.update { it.copy(worldFilling = true) }
            val result = runCatching { worldPresetExpander.expand(brief = brief, name = form.name) }.getOrNull()
            _state.update { state ->
                state.copy(
                    worldFilling = false,
                    form = if (result == null) state.form else state.form.copy(
                        worldName = result.preset.worldName,
                        era = result.preset.era,
                        worldDescription = result.preset.description,
                        scheduleJson = WorldPresetCodec.encodeSchedule(result.preset.schedule),
                        relationshipsJson = WorldPresetCodec.encodeRelationships(result.preset.relationships),
                        obligationsText = result.preset.obligations.joinToString("\n")
                    )
                )
            }
            events.send(
                PersonaEditorEvent.Message(
                    context.getString(
                        if (result == null || result.isFallback) {
                            R.string.guidance_world_ai_fallback
                        } else {
                            R.string.guidance_world_ai_llm
                        }
                    )
                )
            )
        }
    }

    /** 「总结」：粘贴的聊天记录 → 话风；失败走本地兜底并明确标注。 */
    fun summarizeSpeech() {
        val current = _state.value
        if (current.speechSummarizing) return
        val sample = current.form.speechSample.trim()
        if (sample.isBlank()) {
            viewModelScope.launch {
                events.send(PersonaEditorEvent.Message(context.getString(R.string.guidance_speech_sample_required)))
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(speechSummarizing = true) }
            val result = runCatching { speechStyleSummarizer.summarize(sample) }
                .getOrElse { SpeechStyleResult(SpeechStyle(), SpeechStyleSource.HEURISTIC) }
            _state.update { state ->
                state.copy(
                    speechSummarizing = false,
                    form = state.form.copy(
                        speechTone = result.style.tone,
                        speechPhrases = result.style.phrases,
                        speechHabits = result.style.habits.joinToString("\n"),
                        speechAvoid = result.style.avoid.joinToString("\n"),
                        speechSource = result.source
                    )
                )
            }
            if (result.isHeuristic) {
                events.send(PersonaEditorEvent.Message(context.getString(R.string.guidance_speech_summarize_failed)))
            }
        }
    }

    fun clearSpeech() {
        _state.update {
            it.copy(
                form = it.form.copy(
                    speechTone = "",
                    speechPhrases = emptyList(),
                    speechHabits = "",
                    speechAvoid = "",
                    speechSample = "",
                    speechSource = ""
                )
            )
        }
    }

    private fun buildWorldJson(form: PersonaForm): String? {
        val schedule = WorldPresetCodec.decodeSchedule(form.scheduleJson) ?: return null
        val relationships = WorldPresetCodec.decodeRelationships(form.relationshipsJson) ?: return null
        val world = WorldPreset(
            worldName = form.worldName,
            era = form.era,
            description = form.worldDescription,
            schedule = schedule,
            relationships = relationships,
            obligations = form.obligationsText.lineSequence().map { it.trim() }
                .filter { it.isNotBlank() }.toList()
        )
        return if (world.isEmpty() || world.worldName.isBlank()) null else WorldPresetCodec.encode(world)
    }

    private fun buildSpeechJson(form: PersonaForm): String? {
        val speech = SpeechStyle(
            tone = form.speechTone,
            phrases = form.speechPhrases,
            habits = form.speechHabits.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList(),
            avoid = form.speechAvoid.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        )
        return if (speech.isEmpty()) null else SpeechStyleCodec.encode(speech)
    }

    fun save() {
        val current = _state.value
        if (current.saving) return
        val form = current.form
        if (form.name.isBlank()) {
            viewModelScope.launch {
                events.send(PersonaEditorEvent.Message(context.getString(R.string.persona_editor_name_required)))
            }
            return
        }
        if (form.prompt.isBlank()) {
            viewModelScope.launch {
                events.send(PersonaEditorEvent.Message(context.getString(R.string.persona_editor_prompt_required)))
            }
            return
        }
        if (WorldPresetCodec.decodeSchedule(form.scheduleJson) == null) {
            viewModelScope.launch {
                events.send(PersonaEditorEvent.Message(context.getString(R.string.guidance_world_schedule_invalid)))
            }
            return
        }
        if (WorldPresetCodec.decodeRelationships(form.relationshipsJson) == null) {
            viewModelScope.launch {
                events.send(PersonaEditorEvent.Message(context.getString(R.string.guidance_world_relationships_invalid)))
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val result = personaRepository.save(
                PersonaDraft(
                    id = personaId,
                    name = form.name,
                    description = form.description,
                    avatarUri = form.avatarUri,
                    avatarSeed = form.avatarSeed,
                    prompt = form.prompt,
                    category = form.category,
                    defaultApiConfigId = form.defaultApiConfigId,
                    modelOverride = form.modelOverride.ifBlank { null },
                    traits = form.traits,
                    likes = form.likes,
                    behaviors = form.behaviors,
                    backstory = form.backstory.ifBlank { null },
                    quirks = form.quirks.ifBlank { null },
                    proactiveLevel = form.proactiveLevel,
                    memoryRetention = form.memoryRetention,
                    extractionEvery = form.extractionEvery,
                    sleepEnabled = form.sleepEnabled,
                    wakeThreshold = form.wakeThreshold,
                    quietSleepMinutes = parseClock(form.sleepAt),
                    quietWakeMinutes = parseClock(form.wakeAt),
                    worldJson = buildWorldJson(form)?.takeIf { it.isNotBlank() },
                    speechStyleJson = buildSpeechJson(form)?.takeIf { it.isNotBlank() }
                )
            )
            if (result.isFailure) {
                _state.update { it.copy(saving = false) }
                events.send(
                    PersonaEditorEvent.Message(
                        ErrorText.of(context, result.exceptionOrNull(), R.string.persona_editor_save_failed)
                    )
                )
                return@launch
            }
            val id = result.getOrThrow()
            // 保存成功：只有被采纳的那张头像该留下（审计 P2-2：连选两张时前一张是孤儿）。
            val adoptedAvatar = _state.value.form.avatarUri
            AvatarDraftFiles.onSaved(adoptedAvatar, pendingAvatarUris)
                .forEach { MediaStorage.deleteAvatar(context, it) }
            pendingAvatarUris.clear()
            savedAvatarUri = adoptedAvatar
            // 只有用户真的动过卡、且卡不是空的才写回：
            // 空卡代表「留空沿用提示词原文」，不能拿占位渲染去覆盖旧设定。
            val card = current.card
            if (card != current.initialCard && !card.isEmpty()) {
                val reason = if (current.personaId == null) {
                    PersonaRevisionReason.CARD_CREATED
                } else {
                    PersonaRevisionReason.CARD_EDITED
                }
                val cardResult = personaCardRepository.saveCard(id, card, reason)
                if (cardResult.isFailure) {
                    _state.update { it.copy(saving = false) }
                    events.send(
                        PersonaEditorEvent.Message(
                            context.getString(
                                R.string.persona_editor_card_save_failed,
                                ErrorText.of(
                                    context,
                                    cardResult.exceptionOrNull(),
                                    R.string.persona_editor_unknown_error
                                )
                            )
                        )
                    )
                    events.send(PersonaEditorEvent.Saved(id))
                    return@launch
                }
            }
            _state.update { it.copy(saving = false) }
            events.send(PersonaEditorEvent.Saved(id))
        }
    }

    fun getSavedPrompt(): String = _state.value.initialPrompt

    override fun onCleared() {
        // 审计 P2-12（0.11.1）：先立旗子，之后到达的 setAvatarUri 会直接删文件而不是登记。
        draftWindowClosed = true
        // 兜底（审计 P2-2）：用户直接按返回键 / 进程回收时，界面可能没机会调 discardDraftFiles。
        discardDraftFiles()
        super.onCleared()
    }

    private companion object {
        /** 空的睡眠时间 = 不自定义，交给默认 23:30–08:00。 */
        fun parseClock(text: String): Int? {
            val match = Regex("""(\d{1,2})\s*[:：]\s*(\d{1,2})""").find(text.trim()) ?: return null
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return hour * 60 + minute
        }

        fun formatClock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
    }
}
