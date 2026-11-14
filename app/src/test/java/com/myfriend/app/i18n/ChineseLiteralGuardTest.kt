package com.myfriend.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「界面文案不许再硬编码中文」守卫。
 *
 * 扫 `ui/` 与 `util/` 下所有 `.kt`，把每一处**字符串字面量**里的中文找出来。
 * 允许出现中文的只有三类，且都写在这里，改规则必须改这个文件：
 *
 * 1. 注释：`//`、`/* */`、KDoc —— 给开发者看的，保持中文（扫描时先剥掉）；
 * 2. 日志：`Log.d/w/i/e`、`println`、`Timber.*` —— 不进界面；
 * 3. 落库值：内置人格分类、版本记录里的一句话。这些是**存进数据库的数据**，
 *    不是某次渲染时的界面文案，翻译它们会让老库里的值和新写的值对不上。
 *    见 [STORED_VALUES]，只放行这几个精确的字符串。
 *
 * 另外放行 `@Deprecated(...)` / `@Suppress(...)` 这类注解参数：那是给开发者的编译期说明。
 *
 * 其它任何 `Text("中文")`、`contentDescription = "中文"`、`placeholder = "中文"` 都会红。
 */
class ChineseLiteralGuardTest {

    /** 数据库里存着、并会被当成值再读回来的中文；翻译它们等于改数据。 */
    private val storedValues = setOf(
        // 人格/预设的内置分类（PresetRepository、PersonaRepository 的默认值与筛选都按这个字符串比对）
        "日常聊天", "角色扮演", "学习", "编程", "创意写作", "通用", "工作",
        // 版本历史里的 reason，写入 persona_revisions 后由「成长」页原样显示
        "创建人格卡", "编辑人格卡",
        // 心情词表（domain/MoodLexicon）里的词：落库的标签，界面只是按它挑一条资源显示，
        // 模型也可能自己造一个新词（那种就原样显示，不翻）。
        "平静", "放松", "温柔", "雀跃", "开心", "兴奋", "期待", "想念", "有点累",
        "疲惫", "犯困", "低落", "难过", "心烦", "不安", "有点委屈", "愧疚"
    )

    /**
     * 整份文件级别的白名单：**目前为空**。
     * 若以后必须新增，这里要写清楚为什么这个文件里的中文不算界面文案（例如「模型面向的提示词」）。
     */
    private val fileWhitelist = emptyMap<String, String>()

    @Test
    fun `ui 与 util 里不再有硬编码的中文界面文案`() {
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
        return candidates.firstOrNull { File(it, "src/main/java/com/myfriend/app").isDirectory }
            ?: error("找不到工程根目录，当前工作目录：${File(".").absolutePath}")
    }

    private fun scanRoots(): List<File> {
        val root = projectRoot()
        return listOf("src/main/java/com/myfriend/app/ui", "src/main/java/com/myfriend/app/util")
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
