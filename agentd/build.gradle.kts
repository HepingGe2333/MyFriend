plugins {
    kotlin("jvm")
    application
}

// `agentd` 是一个「零第三方依赖」的独立 JVM 服务：
// 只用 JDK 自带的 `com.sun.net.httpserver`（JDK 18+ 的标准 API，JDK 17 已在 jdk.httpserver 模块里）
// 和自己的 JSON 解析器（app/ 的纯领域代码不依赖 kotlinx-serialization）。
//
// 它**复用 Android 同一批源文件**（不是复制）：`../app/src/main/java/com/myfriend/app/domain`
// 里那些不碰 Android/Room/Hilt/kotlinx-serialization 的纯 Kotlin 文件直接进 main 源集。
// 这样两端的行为只有一个实现，改一处 agentd 立刻跟着变。
val domainDir = file("../app/src/main/java/com/myfriend/app/domain")

kotlin {
    sourceSets {
        main {
            kotlin.srcDir(domainDir)
            // 逐个确认过、确实无法脱离 Android/串行化库编译的文件（原因见 agentd/README.md）。
            // 这里用 exclude（而不是 include 白名单）：新加一个纯领域文件会自动被 agentd 编译，
            // 如果它偷偷依赖了 Android，构建会在 CI 上直接红，而不是悄悄少一份实现。
            kotlin.exclude(
                "**/AgentActions.kt", // kotlinx-serialization（模型动作协议）
                "**/ApiCost.kt", // kotlinx-serialization + java.util 定价/用量
                "**/Attachments.kt", // kotlinx-serialization（附件编解码）
                "**/BackupCodec.kt", // Room 实体（AgentIntentEntity 等）
                "**/ImportanceScoring.kt", // kotlinx-serialization（模型输出解析）
                "**/LlmConcurrencyGate.kt", // kotlinx-coroutines
                "**/MemoryScreening.kt", // kotlinx-serialization（MemoryTags）
                "**/Models.kt", // Room 实体（ChatSessionEntity / MessageEntity）
                "**/OpenSessionTracker.kt", // javax.inject（Hilt）
                "**/PersonaCard.kt", // kotlinx-serialization
                "**/PersonaProfile.kt", // Room 实体 PersonaEntity
                "**/Recall.kt", // 依赖 MemoryScreening 的 MemoryRow
                "**/ToolProtocol.kt", // kotlinx-serialization（工具协议）
                "**/ToolTrace.kt", // kotlinx-serialization（工具痕迹）
            )
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.myfriend.agentd.MainKt")
}

dependencies {
    // 运行时零第三方依赖；JUnit 只进测试源集（而且它已经在本机 Gradle 缓存里）。
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
    }
}

/**
 * 自包含可执行 jar：`java -jar agentd/build/libs/agentd-0.10.0-all.jar --port 8788`。
 *
 * 为什么需要它：普通 `jar` 任务只打自己的 class，Kotlin 标准库不在里面，`java -jar` 会
 * `NoClassDefFoundError: kotlin/...`。这里不用任何第三方打包插件（保持零依赖），
 * 直接把 runtimeClasspath 解进同一个 jar，并写上 Main-Class。
 */
tasks.register<Jar>("fatJar") {
    group = "distribution"
    description = "Build a self-contained runnable jar (Main-Class + Kotlin stdlib)."
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.myfriend.agentd.MainKt"
    }
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get().map { file ->
            if (file.isDirectory) file else zipTree(file)
        }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}
