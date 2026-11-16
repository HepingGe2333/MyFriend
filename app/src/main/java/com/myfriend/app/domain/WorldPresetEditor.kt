package com.myfriend.app.domain

/**
 * 世界预设的**编辑器状态**（`spec/persona-guidance/README.md` §2.3）。
 *
 * 规范要求世界设定必须用可视化编辑器填，不再让用户写 JSON；编辑器的状态与全部
 * 增删改/校验逻辑都放在这里（纯 JVM、不依赖 Compose、不读资源），界面只负责画：
 *
 * - 日程 / 关系是**行式列表**：增删、上下移动、逐行改；
 * - 义务是「一行一条」的短列表；
 * - 其他背景（notes）是多行文本，最多 [WorldPreset.MAX_NOTES_CHARS] 字；
 * - 保存前调 [issues] 做结构校验，问题自带「哪一段的第几行」，界面能定位到具体行。
 *
 * 为什么不把行状态塞进 Compose 的 `remember`：编辑器要能被单测逐条驱动
 * （加一行、移一行、写坏时间、跨午夜……），而 Compose UI 测试在这台机器上跑不了。
 * 界面因此是 `state + onChange` 的纯函数形状。
 *
 * 出错时**不静默丢行**：坏行留在列表里、[issues] 指出来，用户自己改或删，
 * 保存按钮据此拒绝保存——把用户写了一半的日程悄悄扔掉，比报错更糟。
 */
data class WorldEditorState(
    val worldName: String = "",
    val era: String = "",
    val description: String = "",
    val schedule: List<WorldScheduleEntry> = emptyList(),
    val relationships: List<WorldRelationship> = emptyList(),
    val obligations: List<String> = emptyList(),
    val notes: String = ""
) {

    fun isEmpty(): Boolean =
        worldName.isBlank() && era.isBlank() && description.isBlank() &&
            schedule.isEmpty() && relationships.isEmpty() && obligations.isEmpty() &&
            notes.isBlank()

    // ------------------------------------------------------------------ 日程

    /** 加一行日程：默认「工作日 09:00-18:00」，用户改哪项都行。 */
    fun addSchedule(
        entry: WorldScheduleEntry = WorldScheduleEntry(
            day = "工作日",
            start = "09:00",
            end = "18:00",
            activity = ""
        )
    ): WorldEditorState = copy(schedule = schedule + entry)

    fun updateSchedule(index: Int, transform: (WorldScheduleEntry) -> WorldScheduleEntry): WorldEditorState =
        copy(schedule = schedule.mapIndexed { i, row -> if (i == index) transform(row) else row })

    fun removeSchedule(index: Int): WorldEditorState =
        if (index !in schedule.indices) this else copy(schedule = schedule.filterIndexed { i, _ -> i != index })

    /** 上下移动一行（[delta] = -1 上移，+1 下移）；到头了就原样返回，不抛异常。 */
    fun moveSchedule(index: Int, delta: Int): WorldEditorState = copy(schedule = schedule.moved(index, delta))

    // -------------------------------------------------------------- 人际关系

    fun addRelationship(
        entry: WorldRelationship = WorldRelationship(name = "", relation = "", note = "")
    ): WorldEditorState = copy(relationships = relationships + entry)

    fun updateRelationship(
        index: Int,
        transform: (WorldRelationship) -> WorldRelationship
    ): WorldEditorState =
        copy(relationships = relationships.mapIndexed { i, row -> if (i == index) transform(row) else row })

    fun removeRelationship(index: Int): WorldEditorState =
        if (index !in relationships.indices) {
            this
        } else {
            copy(relationships = relationships.filterIndexed { i, _ -> i != index })
        }

    fun moveRelationship(index: Int, delta: Int): WorldEditorState =
        copy(relationships = relationships.moved(index, delta))

    // ------------------------------------------------------------------ 义务

    fun addObligation(text: String = ""): WorldEditorState = copy(obligations = obligations + text)

    fun updateObligation(index: Int, text: String): WorldEditorState =
        copy(obligations = obligations.mapIndexed { i, row -> if (i == index) text else row })

    fun removeObligation(index: Int): WorldEditorState =
        if (index !in obligations.indices) {
            this
        } else {
            copy(obligations = obligations.filterIndexed { i, _ -> i != index })
        }

    // -------------------------------------------------------------- 其他背景

    /** notes 输入时就截断：界面不显示一个永远写不进去的第 1201 个字。 */
    fun withNotes(text: String): WorldEditorState =
        copy(notes = text.take(WorldPreset.MAX_NOTES_CHARS))

    val notesTooLong: Boolean get() = notes.length > WorldPreset.MAX_NOTES_CHARS

    // ------------------------------------------------------------ 校验与落库

    /**
     * 保存前的结构校验：每一条都指到「第几行」。
     *
     * 只校验用户真的写错的东西：空行（还没填）算错，是因为空日程/空关系没有意义，
     * 保存下来只会渲染出一行空白；要么填完，要么删掉。
     */
    fun issues(): List<WorldEditorIssue> = buildList {
        schedule.forEachIndexed { index, entry ->
            if (entry.day !in WorldPreset.ALLOWED_DAYS) {
                add(WorldEditorIssue(WorldEditorField.SCHEDULE_DAY, index))
            }
            if (WorldPreset.minuteOfDay(entry.start) == null) {
                add(WorldEditorIssue(WorldEditorField.SCHEDULE_START, index))
            }
            if (WorldPreset.minuteOfDay(entry.end) == null) {
                add(WorldEditorIssue(WorldEditorField.SCHEDULE_END, index))
            }
            if (entry.activity.isBlank()) {
                add(WorldEditorIssue(WorldEditorField.SCHEDULE_ACTIVITY, index))
            }
        }
        relationships.forEachIndexed { index, relation ->
            if (relation.name.isBlank() && relation.relation.isBlank()) {
                add(WorldEditorIssue(WorldEditorField.RELATION_NAME, index))
            }
            if (relation.intimacy != null && relation.intimacy !in 1..5) {
                add(WorldEditorIssue(WorldEditorField.RELATION_INTIMACY, index))
            }
        }
        if (notesTooLong) add(WorldEditorIssue(WorldEditorField.NOTES))
    }

    /**
     * 「AI 补全」的合并规则（规范 §2.3：用户改过的字段不被覆盖）。
     *
     * 只填**还空着**的地方：已经写了的日程/关系/义务列表、已经写了的名字与描述都原样保留。
     * 想整体换掉，用户自己清空对应那一段再补全一次即可——比弹一个「覆盖？」对话框更不容易误操作。
     */
    fun mergedWith(generated: WorldEditorState): WorldEditorState = copy(
        worldName = worldName.ifBlank { generated.worldName },
        era = era.ifBlank { generated.era },
        description = description.ifBlank { generated.description },
        schedule = if (schedule.isEmpty()) generated.schedule else schedule,
        relationships = if (relationships.isEmpty()) generated.relationships else relationships,
        obligations = if (obligations.filter { it.isNotBlank() }.isEmpty()) {
            generated.obligations
        } else {
            obligations
        },
        notes = notes.ifBlank { generated.notes }
    )

    /** 编辑器状态 → 落库结构；空白义务/关系行在这里被过滤（保存前已经校验过）。 */
    fun toPreset(): WorldPreset = WorldPreset(
        worldName = worldName.trim(),
        era = era.trim(),
        description = description.trim(),
        schedule = schedule.map { entry ->
            entry.copy(
                day = entry.day.trim(),
                start = entry.start.trim(),
                end = entry.end.trim(),
                activity = entry.activity.trim()
            )
        },
        relationships = relationships
            .filter { it.name.isNotBlank() || it.relation.isNotBlank() }
            .map { relation ->
                relation.copy(
                    name = relation.name.trim(),
                    relation = relation.relation.trim(),
                    note = relation.note.trim()
                )
            },
        obligations = obligations.map { it.trim() }.filter { it.isNotBlank() },
        notes = notes.trim()
    )

    companion object {

        /** 规范 §2.1 的 day 下拉顺序：每天 / 工作日 / 周末 / 周一…周日。 */
        val DAY_OPTIONS: List<String> = listOf(
            "每天", "工作日", "周末",
            "周一", "周二", "周三", "周四", "周五", "周六", "周日"
        )

        /** 关系下拉的常用值；不在列表里的值由「自定义」兜住（不丢用户写的字）。 */
        val RELATION_OPTIONS: List<String> = listOf("家人", "朋友", "同事", "上级", "同学", "其他")

        /** 从落库结构读回编辑器状态（缺字段就是空行，不做任何补全）。 */
        fun from(preset: WorldPreset?): WorldEditorState {
            if (preset == null) return WorldEditorState()
            return WorldEditorState(
                worldName = preset.worldName,
                era = preset.era,
                description = preset.description,
                schedule = preset.schedule,
                relationships = preset.relationships,
                obligations = preset.obligations,
                notes = preset.notes
            )
        }

        /**
         * 空状态用的示例：直接来自 `spec/persona-guidance/cases/world-preset-linshen.json`
         * 的 `expect` 段（临港新城的建筑师）。
         *
         * 内联成常量而不是运行时读文件：示例是产品行为的一部分（空状态长什么样），
         * 不能因为打包时少了一个 test fixture 就让用户看到一个空白的编辑区。
         * `WorldPresetEditorTest` 逐字段对照那份案例文件，防止两边漂移。
         */
        fun example(): WorldEditorState = WorldEditorState(
            worldName = "临港新城·云筑建筑事务所",
            era = "当代，中国沿海新一线城市，2026 年",
            description = "林深所在的城市正经历大规模住宅开发，事务所竞争激烈、甲方频繁改图。" +
                "他带领六人小组负责一个高层住宅项目，正处于方案深化到施工图的关键阶段，加班是常态。" +
                "他租住在事务所附近的老小区，母亲住在同城另一端，父亲早年去世，母子关系亲近但见面不多。",
            schedule = listOf(
                WorldScheduleEntry("工作日", "09:00", "12:00", "主持项目组晨会，分配楼栋深化任务"),
                WorldScheduleEntry("工作日", "13:00", "18:30", "画图、审图，与结构、机电对接"),
                WorldScheduleEntry("工作日", "19:30", "22:30", "赶项目节点，改图并准备次日汇报"),
                WorldScheduleEntry("周三", "20:00", "20:30", "接母亲电话"),
                WorldScheduleEntry("周末", "10:00", "16:00", "视进度加班或补觉")
            ),
            relationships = listOf(
                WorldRelationship("母亲", "母亲", "每周三晚八点准时打电话，总问他有没有好好吃饭"),
                WorldRelationship("老周", "事务所合伙人", "把控节点，催图直接但认可他的能力")
            ),
            obligations = listOf(
                "按甲方节点提交方案深化图与施工图",
                "协调建筑、结构、机电各专业",
                "每周三晚接母亲电话"
            )
        )
    }
}

/** 编辑器校验出来的一个具体问题（界面据此定位到「第几行」）。 */
data class WorldEditorIssue(
    val field: WorldEditorField,
    /** 行号（0 起）；[WorldEditorField.NOTES] 没有行号，给 -1。 */
    val rowIndex: Int = -1
)

/** 出问题的那一段/那一项。界面把这个映射成资源文案，不在状态层拼句子。 */
enum class WorldEditorField {
    SCHEDULE_DAY,
    SCHEDULE_START,
    SCHEDULE_END,
    SCHEDULE_ACTIVITY,
    RELATION_NAME,
    RELATION_INTIMACY,
    NOTES
}

/** 上下移动的公共实现：越界就原样返回，绝不因为按了「上移」把行弄丢。 */
private fun <T> List<T>.moved(index: Int, delta: Int): List<T> {
    val target = index + delta
    if (index !in indices || target !in indices || delta == 0) return this
    val mutable = toMutableList()
    val item = mutable.removeAt(index)
    mutable.add(target, item)
    return mutable
}
