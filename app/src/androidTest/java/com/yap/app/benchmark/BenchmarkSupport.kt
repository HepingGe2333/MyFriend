package com.yap.app.benchmark

import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * 基准测试的公共设施：计时、内存读数、结果落盘。
 *
 * ## 为什么单独一份
 *
 * 这组测试**不改生产代码**，也不进 CI 的常跑集：它们的价值是给出「10 万条历史 / 500 张图」
 * 这两个极端场景下的真实数字，好让「要不要加倒排索引 / 要不要改流式备份」有依据，而不是靠感觉。
 * 计时代码和业务代码混在一起会让两边都难以信任，所以这里只放「怎么量」。
 *
 * ## 一句提醒
 *
 * 所有耗时都是**单次进程内**测量，含 JIT 预热差异、GC 抖动、模拟器 CPU 与真机的差距。
 * 报告里必须把「怎么量的」和「误差可能有多大」一起写出来，否则这些数字会被当成承诺。
 */
internal object BenchLog {

    const val TAG = "YapBench"

    /** 同时写 logcat 与 stdout：`adb logcat -s YapBench` 与 Gradle 输出都能看到。 */
    fun line(message: String) {
        Log.i(TAG, message)
        println("$TAG| $message")
    }
}

/** 一次测量的 min / median / max（毫秒）。样本数一起带上，避免「1 次也叫中位数」。 */
internal data class Stats(
    val samples: Int,
    val minMs: Double,
    val medianMs: Double,
    val maxMs: Double
) {
    companion object {
        fun of(valuesMs: List<Double>): Stats {
            require(valuesMs.isNotEmpty())
            val sorted = valuesMs.sorted()
            val median = if (sorted.size % 2 == 1) {
                sorted[sorted.size / 2]
            } else {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            }
            return Stats(sorted.size, sorted.first(), median, sorted.last())
        }
    }
}

/** 保留两位小数：文档里不需要更多精度，多出来的位数只会假装精确。 */
internal fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

internal fun elapsedMsSince(startNanos: Long): Double =
    (System.nanoTime() - startNanos) / 1_000_000.0

/**
 * 当前**进程**累计消耗的 CPU 时间（毫秒，含所有线程）。
 *
 * 为什么除了墙钟还要记这个：模拟器跑在共享的宿主上，别的构建一压上来，墙钟会成倍膨胀
 * （本仓库实测同一段检索在宿主 load=14 时 2.9s、load=240 时 13.9s）。CPU 时间只统计
 * 「真的被调度执行」的时间，能大体还原「这段活本身有多重」。
 * 少数 ROM 上这个 API 可能不可用，拿不到就返回 -1（报告里会写「CPU 口径不可用」）。
 */
internal fun processCpuMs(): Long = runCatching { android.os.Process.getElapsedCpuTime() }.getOrDefault(-1L)

internal fun kbToMb(kb: Long): Double = round2(kb / 1024.0)

internal fun bytesToMb(bytes: Long): Double = round2(bytes / 1_048_576.0)

/**
 * 内存读数。
 *
 * 三个口径都给，是因为它们回答的是不同问题：
 * - `javaUsedMb`：`Runtime.totalMemory - freeMemory`，**当前活着的** Java 对象量（GC 之后才有意义）；
 * - `totalPssMb`：整个进程的物理内存（含 native、SQLite 页缓存、dex），任务管理器看到的量级；
 * - `javaHeapPssMb`：只有 Java 堆那部分，用来对比「Java 对象涨了多少」与「系统看到多少」。
 */
internal data class MemoryReading(
    val javaUsedBytes: Long,
    val javaMaxBytes: Long,
    val totalPssKb: Long,
    val javaHeapPssKb: Long,
    val nativeHeapPssKb: Long
) {
    val javaUsedMb: Double get() = bytesToMb(javaUsedBytes)
    val javaMaxMb: Double get() = bytesToMb(javaMaxBytes)
    val totalPssMb: Double get() = kbToMb(totalPssKb)
    val javaHeapPssMb: Double get() = kbToMb(javaHeapPssKb)
    val nativeHeapPssMb: Double get() = kbToMb(nativeHeapPssKb)
}

internal fun readMemory(context: Context): MemoryReading {
    val runtime = Runtime.getRuntime()
    val info = Debug.MemoryInfo()
    runCatching { Debug.getMemoryInfo(info) }
    return MemoryReading(
        javaUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
        javaMaxBytes = runtime.maxMemory(),
        totalPssKb = info.totalPss.toLong(),
        javaHeapPssKb = info.dalvikPss.toLong(),
        nativeHeapPssKb = info.nativePss.toLong()
    )
}

/**
 * 把「还能回收的都回收掉」再读内存。
 *
 * 单次 `System.gc()` 在 ART 上不保证立即完成（并发 GC + 引用处理），所以跑几轮并留出间隔。
 * 这是测量手段，不是性能优化：报告里的「GC 后内存」都经过这一手。
 */
internal fun forceGc(rounds: Int = 4) {
    repeat(rounds) {
        System.gc()
        runCatching { Thread.sleep(60) }
    }
}

/**
 * 峰值内存采样器：后台线程每几毫秒读一次 `Runtime` 的已用堆，记录最高点。
 *
 * 为什么需要它：整个导出流程里最胖的那一刻（`BackupCodec.encode` 正在拼一个上百 MB 的
 * JSON 字符串）在两次「前后读数」之间根本看不见——前后都回收过以后，增量可能是 0 或者负数。
 * 采样不是精确的（间隔内可能还有一个更高的瞬时点），但足以区分「20MB 量级」和「200MB 量级」，
 * 而这正是「要不要改流式」要回答的问题。报告里会写明它是采样值。
 */
internal class HeapPeakSampler(private val intervalMs: Long = 5) {

    data class Result(val baselineBytes: Long, val peakBytes: Long) {
        val baselineMb: Double get() = bytesToMb(baselineBytes)
        val peakMb: Double get() = bytesToMb(peakBytes)
        val deltaMb: Double get() = bytesToMb(peakBytes - baselineBytes)
    }

    private val runtime = Runtime.getRuntime()
    private val baseline = runtime.totalMemory() - runtime.freeMemory()

    @Volatile
    private var running = false

    @Volatile
    private var peak = baseline

    private var thread: Thread? = null

    fun start(): HeapPeakSampler {
        running = true
        peak = runtime.totalMemory() - runtime.freeMemory()
        val worker = Thread {
            while (running) {
                val used = runtime.totalMemory() - runtime.freeMemory()
                if (used > peak) peak = used
                runCatching { Thread.sleep(intervalMs) }
            }
        }
        worker.isDaemon = true
        worker.name = "bench-heap-sampler"
        thread = worker
        worker.start()
        return this
    }

    fun stop(): Result {
        running = false
        runCatching { thread?.join(2_000) }
        val used = runtime.totalMemory() - runtime.freeMemory()
        if (used > peak) peak = used
        return Result(baseline, peak)
    }
}

/**
 * 结果记录器：键值对 + 落盘成一个 JSON。
 *
 * 落盘位置是 App 的外部私有目录（`/sdcard/Android/data/<包名>/files/bench-*.json`），
 * 这样 `adb pull` 不需要 `run-as`，也不会把测试产物塞进仓库。
 */
internal class BenchRecorder(private val suite: String) {

    private val entries = LinkedHashMap<String, String>()

    fun put(key: String, value: Any?) {
        val text = value?.toString() ?: "null"
        entries[key] = text
        BenchLog.line("$suite.$key=$text")
    }

    fun putStats(prefix: String, stats: Stats) {
        put("$prefix.samples", stats.samples)
        put("$prefix.minMs", round2(stats.minMs))
        put("$prefix.medianMs", round2(stats.medianMs))
        put("$prefix.maxMs", round2(stats.maxMs))
    }

    /** 当前进程/设备的元信息：文档里的「设备与构建信息」一节直接抄这里。 */
    fun putEnvironment(context: Context) {
        put("device.model", Build.MODEL)
        put("device.manufacturer", Build.MANUFACTURER)
        put("device.sdkInt", Build.VERSION.SDK_INT)
        put("device.release", Build.VERSION.RELEASE)
        put("device.abis", Build.SUPPORTED_ABIS.joinToString(","))
        put("device.hardware", Build.HARDWARE)
        put("device.isEmulator", isProbablyEmulator())
        put("vm.heapGrowthLimitMb", growthLimitMb(context))
        put("vm.runtimeMaxMemoryMb", bytesToMb(Runtime.getRuntime().maxMemory()))
        put("app.debuggable", (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        put("app.versionName", appVersion(context))
    }

    fun writeJsonFile(context: Context): File? {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "bench-$suite.json")
        val body = buildString {
            append("{\n")
            entries.entries.forEachIndexed { index, (key, value) ->
                append("  ")
                append(quote(key))
                append(": ")
                append(quote(value))
                if (index != entries.size - 1) append(",")
                append("\n")
            }
            append("}\n")
        }
        return runCatching {
            file.writeText(body)
            BenchLog.line("results written to ${file.absolutePath}")
            file
        }.getOrNull()
    }

    fun keys(): Map<String, String> = entries.toMap()

    private fun quote(text: String): String {
        val out = StringBuilder(text.length + 2)
        out.append('"')
        text.forEach { ch ->
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) out.append(String.format(Locale.US, "\\u%04x", ch.code)) else out.append(ch)
            }
        }
        out.append('"')
        return out.toString()
    }

    private fun isProbablyEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.MODEL.contains("sdk_gphone") ||
            Build.HARDWARE.contains("ranchu") ||
            Build.HARDWARE.contains("goldfish")

    private fun growthLimitMb(context: Context): Double {
        // 读不到就报 -1：这个值只用于解释「为什么 128MiB 的图片会 OOM」，不是测量结论。
        val fromProp = runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java)
            (get.invoke(null, "dalvik.vm.heapgrowthlimit") as? String)?.trim()
        }.getOrNull()
        val mb = parseHeapSizeMb(fromProp)
        if (mb > 0) return mb
        return bytesToMb(Runtime.getRuntime().maxMemory())
    }

    private fun parseHeapSizeMb(text: String?): Double {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return -1.0
        val value = raw.dropLast(1).toDoubleOrNull() ?: return -1.0
        return when (raw.last().lowercaseChar()) {
            'g' -> value * 1024
            'm' -> value
            'k' -> value / 1024
            else -> value / 1_048_576
        }
    }

    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("unknown")
}
