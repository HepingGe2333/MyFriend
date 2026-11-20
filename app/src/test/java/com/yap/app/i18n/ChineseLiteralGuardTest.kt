package com.yap.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「界面文案不许再硬编码中文」守卫。
 *
 * 0.10.0 只扫 `ui/` 与 `util/`；0.10.1 起**扩到 `data/` 与 `network/`**：
 * 那两层以前的 `error("人格不存在")` / `ApiException(401, "API Key 无效")` 会经 `Result` 直接上屏，
 * 英文界面因此在设置页、聊天错误气泡、备份导入结果里看到中文。
 *
 * 现在数据层与网络层只说「哪一种错误」（`domain/UserError.kt`），句子由 `ui/common/ErrorText.kt`
 * 映射资源。守卫扩到这里，是为了让中文错误**不再回流**。
 *
 * 允许出现中文的只有下面几类，改规则必须改这个文件：
 *
 * 1. 注释：`//`、`/* */`、KDoc —— 给开发者看的，保持中文（扫描时先剥掉）；
 * 2. 日志：`Log.d/w/i/e`、`println`、`Timber.*` —— 不进界面；
 * 3. 落库值：内置分类、会话默认标题这些**存进数据库的数据**；翻译它们会让老库里的值与新写的值对不上。
 *    见 [storedValues]，只放行精确匹配的字符串；
 * 4. 面向模型的提示词 / 工具结果 / 思考留痕：按 I18N 文档的约定，提示词脚手架不翻译。
 *    这类文件整份放行，但**每条必须写清为什么**（见 [fileWhitelist]）。
 *
 * 另外放行 `@Deprecated(...)` / `@Suppress(...)` 这类注解参数：那是给开发者的编译期说明。
 *
 * 其它任何 `Text("中文")`、`contentDescription = "中文"`、`placeholder = "中文"`，
 * 以及数据层新出现的 `error("中文")`，都会红。
 */
class ChineseLiteralGuardTest {

    /** 数据库里存着、并会被当成值再读回来的中文；翻译它们等于改数据。 */
    private val storedValues = setOf(
        // 人格/预设的内置分类（PresetRepository、PersonaRepository 的默认值与筛选都按这个字符串比对）
        "日常聊天", "角色扮演", "学习", "编程", "创意写作", "通用", "工作", "未分类",
        // 心情词表（domain/MoodLexicon）里的词：落库的标签，界面只是按它挑一条资源显示，
        // 模型也可能自己造一个新词（那种就原样显示，不翻）。
        "平静", "放松", "温柔", "雀跃", "开心", "兴奋", "期待", "想念", "有点累",
        "疲惫", "犯困", "低落", "难过", "心烦", "不安", "有点委屈", "愧疚"
    )

    /**
     * 整份文件级别的白名单：**只允许「面向模型的提示词 / 工具协议 / 落库留痕」**。
     *
     * 这些文件里的中文不会被界面直接渲染：它们要么进 system prompt、要么进工具结果回灌给模型、
     * 要么是存进库里的「他自己想过什么」的留痕。按 `docs/I18N.md` 的约定，提示词脚手架不翻译
     * （翻译会改变模型行为）。除此之外的 data/network 文件仍然逐字面量扫描。
     */
    private val fileWhitelist = mapOf(
        // ---- 数据层：系统提示词 / 模型上下文 / 落库留痕 ----
        "src/main/java/com/yap/app/data/repo/AgentLoop.kt" to
            "常驻 agent 的处境描述与思考留痕：进 system prompt，或落库成「他最近在想什么」的原始记录",
        "src/main/java/com/yap/app/data/repo/AgentIntentRepository.kt" to
            "念头的提示词与落库说明：念头的 note 是模型给的原文",
        "src/main/java/com/yap/app/data/repo/ChatRepository.kt" to
            "system 提示词脚手架与工具回灌说明（会话标题/消息预览已在写入时取 R.string.chat_* 资源）",
        "src/main/java/com/yap/app/data/repo/EvolutionRepository.kt" to
            "人格进化提示词与模型给的默认摘要",
        "src/main/java/com/yap/app/data/repo/HeartbeatController.kt" to
            "心跳留痕（他自己的一举一动）与模型上下文说明",
        "src/main/java/com/yap/app/data/repo/HistoryCompressor.kt" to
            "长对话压缩提示词与内部状态说明（只进日志/摘要）",
        "src/main/java/com/yap/app/data/repo/IdleBrowsing.kt" to
            "中文停用词表（分词逻辑本身依赖这些词）+ 他自己上网看的提示词",
        "src/main/java/com/yap/app/data/repo/MemoryConsolidator.kt" to
            "记忆整理提示词：进模型上下文，界面只读整理结果里的数字",
        "src/main/java/com/yap/app/data/repo/MemoryReconsolidator.kt" to
            "记忆重构提示词（想起旧事时的措辞）",
        "src/main/java/com/yap/app/data/repo/MentalActivityRepository.kt" to
            "0.14 心理活动：私密念头的 system/user 提示词与模型回灌说明，不直接展示给用户",
        "src/main/java/com/yap/app/data/repo/SessionMemoryRepository.kt" to
            "0.14 会话备忘录：摘要提示词与模型上下文，不直接展示给用户",
        "src/main/java/com/yap/app/data/repo/MemoryRepository.kt" to
            "记忆抽取提示词与「你记得的事」上下文块",
        "src/main/java/com/yap/app/data/repo/PersonaEvolutionRepository.kt" to
            "人格进化评估的提示词角色指代（「对方/他」，只进模型上下文）与待审补丁的内部理由；" +
            "界面显示的是模型给的变化摘要，不直出这里的字符串",
        "src/main/java/com/yap/app/data/repo/PersonaCardRepository.kt" to
            "人设卡里的作息措辞：写进卡 JSON、渲染进提示词，模型也按它说话",
        "src/main/java/com/yap/app/data/repo/PresenceRepository.kt" to
            "「他正在…」给模型看的解释句；界面按 PresenceKind 取资源，不显示这句",
        "src/main/java/com/yap/app/data/repo/ProactiveProtocol.kt" to
            "主动开口协议的提示词与模型动作说明（规则的默认说明/指引已改由仓储层取资源）",
        "src/main/java/com/yap/app/data/repo/ProactiveRepository.kt" to
            "主动开口的 system 提示词（默认 note/hint 已改成 R.string.proactive_default_*）",
        "src/main/java/com/yap/app/data/repo/ToolExecutor.kt" to
            "只剩回灌给模型的工具结果/摘要与提示词脚手架；给用户看的标题、副标题、展开正文" +
            "已搬到 data/repo/ToolTraceText.kt 走 R.string.tool_trace_*（那个文件不在白名单里）",
        "src/main/java/com/yap/app/data/repo/WebSearchScorer.kt" to
            "网页打分/记笔记提示词与留痕",
        // ---- 网络层：回灌给模型的内容 / 日志 ----
        "src/main/java/com/yap/app/network/WebSearchClient.kt" to
            "抓回来的网页正文的截断标记与日志：正文回灌给模型，日志不进界面",
        // ---- 0.12 人设引导：话风/世界补全的提示词与本地兜底 ----
        "src/main/java/com/yap/app/data/ai/SpeechStyleSummarizer.kt" to
            "话风总结提示词（规范 §6.1 原文）与本地兜底说明：面向模型 / 落库的风格值，不是界面文案",
        "src/main/java/com/yap/app/data/ai/WorldPresetExpander.kt" to
            "世界预设补全提示词（规范 §6.2 原文）：面向模型，不进界面",
        "src/main/java/com/yap/app/data/ai/PersonaTranslator.kt" to
            "人设提示词翻译的提示词（spec/persona-translation §5 原文）与请求 JSON：面向模型，" +
            "只发用户自己写的设定，不进界面、不落库",
    )

    @Test
    fun `ui、util、data 与 network 里不再有硬编码的中文界面文案`() {
        val files = scanRoots()
        assertTrue("没扫到源文件，路径判断有问题（找到 ${files.size} 个）", files.size > 20)

        val violations = mutableListOf<String>()
        files.forEach fileLoop@{ file ->
            val relative = file.relativeTo(projectRoot()).invariantSeparatorsPath
            if (fileWhitelist.containsKey(relative)) return@fileLoop
            literals(file).forEach literalLoop@{ literal ->
                if (!CONTAINS_CJK.containsMatchIn(literal.text)) return@literalLoop
                if (literal.isLogArgument) return@literalLoop
                if (literal.isAnnotationArgument) return@literalLoop
                if (literal.text.trim() in storedValues) return@literalLoop
                violations += "$relative:${literal.line}  ${literal.text.trim()}"
            }
        }

        assertEquals(
            "以下位置还有硬编码的中文界面文案，请搬进 res/values/strings.xml（并补 values-en/）：\n" +
                violations.joinToString("\n"),
            emptyList<String>(),
            violations
        )
    }

    @Test
    fun `白名单里的理由必须写得出来`() {
        assertEquals("白名单要写理由，不许留空", emptyList<String>(), fileWhitelist.filterValues { it.isBlank() }.keys.toList())
    }

    // ------------------------------------------------------------- 扫描

    private data class Literal(
        val line: Int,
        val text: String,
        /** 字面量在同一行里的前缀代码，用来判断它是不是日志/注解的参数。 */
        val linePrefix: String
    ) {
        val isLogArgument: Boolean
            get() = LOG_CALL.containsMatchIn(linePrefix)

        val isAnnotationArgument: Boolean
            get() = linePrefix.trimEnd().let { it.endsWith("(") && ANNOTATION.containsMatchIn(it) }
    }

    private fun projectRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到工程根目录，当前工作目录：${File(".").absolutePath}")
    }

    private fun scanRoots(): List<File> {
        val root = projectRoot()
        return listOf(
            "src/main/java/com/yap/app/ui",
            "src/main/java/com/yap/app/util",
            "src/main/java/com/yap/app/data",
            "src/main/java/com/yap/app/network"
        )
            .map { File(root, it) }
            .flatMap { dir ->
                dir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".kt") }
                    .toList()
            }
            .sortedBy { it.path }
    }

    /**
     * 一个够用的 Kotlin 词法扫描：剥注释，取出字符串字面量（含 `"""…"""`）。
     *
     * 注释里的中文不参与判断，字符串里的 `//`（URL）也不会被误当注释。
     */
    private fun literals(file: File): List<Literal> {
        val source = file.readText()
        val result = mutableListOf<Literal>()
        var i = 0
        var line = 1
        val n = source.length

        fun lineStartOf(index: Int): Int = source.lastIndexOf('\n', index - 1).let { if (it < 0) 0 else it + 1 }

        while (i < n) {
            when {
                source.startsWith("//", i) -> {
                    val end = source.indexOf('\n', i)
                    i = if (end < 0) n else end
                }
                source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2)
                    val stop = if (end < 0) n else end + 2
                    line += source.substring(i, stop).count { it == '\n' }
                    i = stop
                }
                source.startsWith("\"\"\"", i) -> {
                    val start = i
                    val startLine = line
                    var j = i + 3
                    while (j < n && !source.startsWith("\"\"\"", j)) {
                        if (source[j] == '\n') line++
                        j++
                    }
                    val text = source.substring(start + 3, minOf(j, n))
                    result += Literal(startLine, text, source.substring(lineStartOf(start), start))
                    i = minOf(j + 3, n)
                }
                source[i] == '"' -> {
                    val start = i
                    val startLine = line
                    var j = i + 1
                    val text = StringBuilder()
                    while (j < n) {
                        val c = source[j]
                        if (c == '\\' && j + 1 < n) {
                            text.append(c).append(source[j + 1])
                            j += 2
                            continue
                        }
                        if (c == '"') break
                        if (c == '\n') line++
                        text.append(c)
                        j++
                    }
                    result += Literal(startLine, text.toString(), source.substring(lineStartOf(start), start))
                    i = minOf(j + 1, n)
                }
                source[i] == '\'' -> {
                    // 字符字面量：直接跳过，别把 ' 当成字符串开头
                    var j = i + 1
                    while (j < n && source[j] != '\'') {
                        if (source[j] == '\\') j++
                        j++
                    }
                    i = minOf(j + 1, n)
                }
                else -> {
                    if (source[i] == '\n') line++
                    i++
                }
            }
        }
        return result
    }

    private companion object {
        val CONTAINS_CJK = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]")
        val LOG_CALL = Regex("""\b(Log\.[dviwe]|println|printStackTrace|System\.(out|err)|Timber\.)""")
        val ANNOTATION = Regex("""@(Deprecated|Suppress\w*)\(""")
    }
}
