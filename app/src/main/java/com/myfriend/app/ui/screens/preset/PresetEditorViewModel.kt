package com.myfriend.app.ui.screens.preset

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myfriend.app.ui.common.ErrorText
import com.myfriend.app.R
import com.myfriend.app.data.ai.SpeechStyleResult
import com.myfriend.app.data.ai.SpeechStyleSource
import com.myfriend.app.data.ai.SpeechStyleSummarizer
import com.myfriend.app.data.ai.WorldPresetExpander
import com.myfriend.app.data.repo.PresetDraft
import com.myfriend.app.data.repo.PresetRepository
import com.myfriend.app.domain.SpeechStyle
import com.myfriend.app.domain.SpeechStyleCodec
import com.myfriend.app.domain.WorldPreset
import com.myfriend.app.domain.WorldPresetCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PresetForm(
    val name: String = "",
    val description: String = "",
    val prompt: String = "",
    val category: String = "通用",
    val avatarSeed: Int = 0,
    // ---- 世界设定（v13） ----------------------------------------------------
    val worldName: String = "",
    val era: String = "",
    val worldDescription: String = "",
    val scheduleJson: String = "",
    val relationshipsJson: String = "",
    val obligationsText: String = "",
    // ---- 话风（v13） --------------------------------------------------------
    val speechTone: String = "",
    val speechPhrases: List<String> = emptyList(),
    val speechHabits: String = "",
    val speechAvoid: String = "",
    /** 粘贴的聊天记录原文只活在内存里，保存时不会写库（规范 §3.3）。 */
    val speechSample: String = "",
    /** `llm` / `heuristic`；为空表示还没总结过。 */
    val speechSource: String = ""
)

data class PresetEditorUiState(
    val form: PresetForm = PresetForm(),
    val saving: Boolean = false,
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val worldFilling: Boolean = false,
    val speechSummarizing: Boolean = false
)

sealed interface PresetEditorEvent {
    data class Saved(val presetId: String) : PresetEditorEvent
    data class Message(val text: String) : PresetEditorEvent
}

@HiltViewModel
class PresetEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val presetRepository: PresetRepository,
    private val speechStyleSummarizer: SpeechStyleSummarizer,
    private val worldPresetExpander: WorldPresetExpander,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val presetId: String? = savedStateHandle.get<String>("presetId")?.takeIf { it.isNotBlank() }
    private val _state = MutableStateFlow(
        PresetEditorUiState(loaded = presetId == null, isNew = presetId == null)
    )
    val state: StateFlow<PresetEditorUiState> = _state

    private val events = Channel<PresetEditorEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    init {
        if (presetId != null) {
            viewModelScope.launch {
                val preset = presetRepository.get(presetId)
                if (preset != null) {
                    val world = WorldPresetCodec.decode(preset.worldJson)
                    val speech = SpeechStyleCodec.decode(preset.speechStyleJson)
                    _state.update {
                        it.copy(
                            form = PresetForm(
                                name = preset.name,
                                description = preset.description,
                                prompt = preset.prompt,
                                category = preset.category,
                                avatarSeed = preset.avatarSeed,
                                worldName = world?.worldName.orEmpty(),
                                era = world?.era.orEmpty(),
                                worldDescription = world?.description.orEmpty(),
                                scheduleJson = WorldPresetCodec.encodeSchedule(world?.schedule.orEmpty()),
                                relationshipsJson = WorldPresetCodec.encodeRelationships(
                                    world?.relationships.orEmpty()
                                ),
                                obligationsText = world?.obligations.orEmpty().joinToString("\n"),
                                speechTone = speech?.tone.orEmpty(),
                                speechPhrases = speech?.phrases.orEmpty(),
                                speechHabits = speech?.habits.orEmpty().joinToString("\n"),
                                speechAvoid = speech?.avoid.orEmpty().joinToString("\n"),
                                speechSource = ""
                            ),
                            loaded = true
                        )
                    }
                } else {
                    _state.update { it.copy(loaded = true) }
                    events.send(PresetEditorEvent.Message(context.getString(R.string.preset_editor_not_found)))
                }
            }
        }
    }

    fun update(transform: (PresetForm) -> PresetForm) {
        _state.update { it.copy(form = transform(it.form)) }
    }

    /** 「AI 补全」：角色简介（没有就用提示词/名字）→ 世界预设 JSON。 */
    fun fillWorldWithAi() {
        val current = _state.value
        if (current.worldFilling) return
        val form = current.form
        val brief = listOf(form.description, form.prompt).firstOrNull { it.isNotBlank() }.orEmpty()
        if (brief.isBlank() && form.name.isBlank()) {
            viewModelScope.launch {
                events.send(PresetEditorEvent.Message(context.getString(R.string.preset_editor_name_required)))
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(worldFilling = true) }
            val result = runCatching {
                worldPresetExpander.expand(brief = brief, name = form.name)
            }.getOrNull()
            _state.update { it.copy(worldFilling = false) }
            if (result == null) {
                events.send(PresetEditorEvent.Message(context.getString(R.string.guidance_world_ai_fallback)))
                return@launch
            }
            _state.update { state ->
                state.copy(
                    form = state.form.copy(
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
                PresetEditorEvent.Message(
                    context.getString(
                        if (result.isFallback) R.string.guidance_world_ai_fallback else R.string.guidance_world_ai_llm
                    )
                )
            )
        }
    }

    /** 「总结」：聊天记录 → 话风（模型优先，失败走本地兜底）。 */
    fun summarizeSpeech() {
        val current = _state.value
        if (current.speechSummarizing) return
        val sample = current.form.speechSample.trim()
        if (sample.isBlank()) {
            viewModelScope.launch {
                events.send(PresetEditorEvent.Message(context.getString(R.string.guidance_speech_sample_required)))
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
                events.send(PresetEditorEvent.Message(context.getString(R.string.guidance_speech_summarize_failed)))
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

    fun save() {
        val current = _state.value
        if (current.saving) return
        val form = current.form
        if (form.name.isBlank()) {
            viewModelScope.launch { events.send(PresetEditorEvent.Message(context.getString(R.string.preset_editor_name_required))) }
            return
        }
        if (form.prompt.isBlank()) {
            viewModelScope.launch { events.send(PresetEditorEvent.Message(context.getString(R.string.preset_editor_prompt_required))) }
            return
        }
        // 日程 / 关系是用户手写的 JSON：格式不对就明确报错，不当场吞掉这些条目。
        val schedule = WorldPresetCodec.decodeSchedule(form.scheduleJson)
        if (schedule == null) {
            viewModelScope.launch { events.send(PresetEditorEvent.Message(context.getString(R.string.guidance_world_schedule_invalid))) }
            return
        }
        val relationships = WorldPresetCodec.decodeRelationships(form.relationshipsJson)
        if (relationships == null) {
            viewModelScope.launch { events.send(PresetEditorEvent.Message(context.getString(R.string.guidance_world_relationships_invalid))) }
            return
        }
        val world = WorldPreset(
            worldName = form.worldName,
            era = form.era,
            description = form.worldDescription,
            schedule = schedule,
            relationships = relationships,
            obligations = form.obligationsText.lineSequence().map { it.trim() }
                .filter { it.isNotBlank() }.toList()
        )
        val speech = SpeechStyle(
            tone = form.speechTone,
            phrases = form.speechPhrases,
            habits = form.speechHabits.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList(),
            avoid = form.speechAvoid.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        )
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val result = presetRepository.save(
                PresetDraft(
                    id = presetId,
                    name = form.name,
                    description = form.description,
                    prompt = form.prompt,
                    category = form.category,
                    avatarUri = null,
                    avatarSeed = form.avatarSeed,
                    worldJson = world.takeIf { !it.isEmpty() && it.worldName.isNotBlank() }
                        ?.let(WorldPresetCodec::encode),
                    speechStyleJson = speech.takeIf { !it.isEmpty() }?.let(SpeechStyleCodec::encode)
                )
            )
            _state.update { it.copy(saving = false) }
            result.onSuccess { events.send(PresetEditorEvent.Saved(it)) }
                .onFailure { events.send(PresetEditorEvent.Message(ErrorText.of(context, it, R.string.preset_editor_save_failed))) }
        }
    }
}
