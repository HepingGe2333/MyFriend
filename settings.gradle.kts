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
// 独立可运行的 agent 核心服务（Kotlin/JVM，零第三方依赖）。
// 刻意**不**让 :app 依赖 :agentd：Android 构建完全不受影响，两者只共享 domain 源文件。
include(":agentd")
