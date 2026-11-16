package com.yap.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「资源键有没有人用」守卫。
 *
 * `StringResourceParityTest` 只保证中英两边 key 一致：一条文案如果**两边都没人用**，
 * 它仍然是「一致」的，于是会一直留在包里（上一轮实测就有 `search_section_sessions` /
 * `search_section_messages` 两个已被 `search_tab_*` 取代的死键）。这里补上最后一块：
 *
 * 1. `values/` 里每一条 string / plurals 都必须能在 `src/main` 里找到引用
 *    （`R.string.<key>`、`R.plurals.<key>`，或 manifest / XML 里的 `@string/<key>`）；
 * 2. 确实无法被静态引用的（例如只被反射/系统按名字读取），必须写进 [whitelist] 并说明原因；
 * 3. 白名单里不允许留空说明，也不允许留「已经没人用」的过期条目。
 *
 * 扫不到引用的后果不只是包里多几 KB：删文案时没人知道它还在不在用，
 * 改 key 时也不会有人发现另一处已经断了。
 */
class ResourceUsageGuardTest {

    /**
     * 静态扫描不到、但确实在用的键。
     *
     * 目前为空：`app_name` 由 manifest 的 `@string/app_name` 引用（扫描包含 manifest），
     * 错误文案由 `ErrorText` 直接写 `R.string.*`，不需要反射。
     */
    private val whitelist = mapOf<String, String>()

    @Test
    fun `values 里的每条文案都被源码或 manifest 引用`() {
        val referenced = references()
        val strings = TestResources.strings("values").keys
        val plurals = TestResources.plurals("values").keys
        // plurals 的 key 与 string 的 key 是不同命名空间：分别检查。
        val problems = buildList {
            strings.forEach { key ->
                if (key !in referenced && key !in whitelist) add("R.string.$key")
            }
            plurals.forEach { key ->
                if (key !in referenced && key !in whitelist) add("R.plurals.$key")
            }
        }
        assertEquals(
            "以下资源键在 values/ 里存在但没有任何引用（删掉它，或加进 whitelist 并说明原因）：\n" +
                problems.joinToString("\n"),
            emptyList<String>(),
            problems
        )
    }

    @Test
    fun `白名单必须写清原因且不能有过期条目`() {
        val stale = whitelist.filterValues { it.isBlank() }.keys.toList()
        assertEquals("白名单条目要写原因", emptyList<String>(), stale)

        val referenced = references()
        val unused = whitelist.keys.filter { it in referenced }
        assertEquals(
            "以下键已经被引用，不该再留在白名单里：$unused",
            emptyList<String>(),
            unused
        )
    }

    /**
     * 注释里出现的 `R.string.x` **不算引用**（安全审计 P2-10 的第三个缺口）。
     *
     * 0.10.x 的扫描是「整份文件 raw text 里找 `R.string.(\w+)`」，于是
     * `// R.string.foo 已经不用了` 这样的注释会让一个死键永远绿。这里在扫描前先剥注释：
     * 把注释换成空白（保留行号/长度），字符串字面量照常保留（里面的 `//` 不是注释）。
     */
    @Test
    fun `注释里出现的资源键不算引用`() {
        val kt = """
            // R.string.commented_out_key
            /* R.string.block_commented_key */
            val real = R.string.really_used_key
            val text = "https://example.com" // R.string.after_url_key 仍是注释
            val quoted = "R.string.inside_a_string"
        """.trimIndent()
        val stripped = stripComments(kt, "Sample.kt")
        assertFalse("行注释里的键不算引用", stripped.contains("commented_out_key"))
        assertFalse("块注释里的键不算引用", stripped.contains("block_commented_key"))
        assertFalse("行尾注释里的键不算引用", stripped.contains("after_url_key"))
        assertTrue("真实引用要保留", stripped.contains("R.string.really_used_key"))
        assertTrue("字符串字面量里的 `//` 不是注释，后面的内容要保留", stripped.contains("https://example.com"))
        assertTrue("字符串字面量按原文保留", stripped.contains("R.string.inside_a_string"))
    }

    @Test
    fun `XML 注释里出现的资源键不算引用`() {
        val xml = """
            <!-- @string/commented_out_key and R.string.another_comment -->
            <TextView android:text="@string/really_used_key" />
        """.trimIndent()
        val stripped = stripComments(xml, "layout.xml")
        assertFalse("XML 注释不算引用", stripped.contains("commented_out_key"))
        assertFalse("XML 注释不算引用", stripped.contains("another_comment"))
        assertTrue("真实 @string 引用要保留", stripped.contains("@string/really_used_key"))
    }

    // ---------------------------------------------------------------- 扫描

    private fun projectRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到工程根目录，当前工作目录：${File(".").absolutePath}")
    }

    private fun references(): Set<String> {
        val root = projectRoot()
        val texts = buildList {
            add(File(root, "src/main/AndroidManifest.xml").takeIf { it.isFile }?.readText().orEmpty())
            add(File(root, "src/debug/AndroidManifest.xml").takeIf { it.isFile }?.readText().orEmpty())
            listOf(
                File(root, "src/main/java"),
                File(root, "src/main/res"),
                File(root, "src/debug/res")
            ).filter { it.isDirectory }.forEach { dir ->
                dir.walkTopDown()
                    .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".xml")) }
                    // 只认真实引用：注释里的 `R.string.x` / `@string/x` 不是引用（安全审计 P2-10）。
                    .forEach { add(stripComments(it.readText(), it.name)) }
            }
        }
        assertTrue("一个源文件都没读到，路径判断有问题", texts.any { it.isNotBlank() })
        val joined = texts.joinToString("\n")
        val refs = mutableSetOf<String>()
        R_STRING.findAll(joined).forEach { refs += it.groupValues[1] }
        R_PLURALS.findAll(joined).forEach { refs += it.groupValues[1] }
        AT_STRING.findAll(joined).forEach { refs += it.groupValues[1] }
        return refs
    }

    /**
     * 剥掉注释（换成空格，保留换行，方便报错时还能看到原来的行）。
     *
     * Kotlin：`//` 行注释、`/* */` 块注释；能识别 `"…"`、`"""…"""`、`'c'`
     * （三引号字符串里的 `//`、URL 里的 `//` 都不是注释）。
     * XML：`<!-- -->`。
     */
    private fun stripComments(text: String, fileName: String): String {
        if (fileName.endsWith(".xml")) return text.replace(XML_COMMENT, "")
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    out.append("  ")
                    i += 2
                    while (i < text.length) {
                        if (text[i] == '*' && i + 1 < text.length && text[i + 1] == '/') {
                            out.append("  ")
                            i += 2
                            break
                        }
                        out.append(if (text[i] == '\n') '\n' else ' ')
                        i++
                    }
                }
                text.startsWith("\"\"\"", i) -> {
                    out.append("\"\"\"")
                    i += 3
                    while (i < text.length) {
                        if (text.startsWith("\"\"\"", i)) {
                            out.append("\"\"\"")
                            i += 3
                            break
                        }
                        out.append(text[i])
                        i++
                    }
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    out.append(quote)
                    i++
                    while (i < text.length) {
                        val ch = text[i]
                        out.append(ch)
                        i++
                        if (ch == '\\' && i < text.length) {
                            out.append(text[i])
                            i++
                        } else if (ch == quote) {
                            break
                        }
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private companion object {
        val R_STRING = Regex("""R\.string\.(\w+)""")
        val R_PLURALS = Regex("""R\.plurals\.(\w+)""")
        val AT_STRING = Regex("""@string/(\w+)""")
        val XML_COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    }
}
