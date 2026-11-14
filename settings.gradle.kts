pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "MyFriend"
include(":app")
// 两端共用的 agent 决策核心（纯 Kotlin/JVM、零第三方依赖）。
// `:app` 与 `:agentd` 都依赖它：decisions 只有一份实现，不再靠 `kotlin.srcDir` + exclude 复用源码。
include(":agentcore")
// 独立可运行的 agent 核心服务（Kotlin/JVM，零第三方依赖），对外只暴露 4 个 `/v1/core/*` 端点。
// 刻意**不**让 :app 依赖 :agentd：Android 构建完全不受影响；两者只共享 :agentcore。
include(":agentd")
