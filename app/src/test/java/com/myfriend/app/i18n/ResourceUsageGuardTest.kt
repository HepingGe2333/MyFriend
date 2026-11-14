package com.myfriend.app.i18n

import org.junit.Assert.assertEquals
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

    // ---------------------------------------------------------------- 扫描

    private fun projectRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/myfriend/app").isDirectory }
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
                    .forEach { add(it.readText()) }
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

    private companion object {
        val R_STRING = Regex("""R\.string\.(\w+)""")
        val R_PLURALS = Regex("""R\.plurals\.(\w+)""")
        val AT_STRING = Regex("""@string/(\w+)""")
    }
}
