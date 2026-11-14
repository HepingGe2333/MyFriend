// swift-tools-version:5.9
import PackageDescription

// 与 Android 端共享的那一层：数据模型、SQLite 存储、备份编解码、网络、提示词、
// 以及所有纯算法（心跳、向量、链接、Markdown、历史压缩……）。
// 放在 SPM 里是为了能在 macOS 上直接 `swift test` 跑逻辑测试，不必每次都起模拟器。
// 应用层（SwiftUI）在 ../MyFriend，只依赖这个包。
let package = Package(
    name: "MyFriendKit",
    platforms: [
        // 部署目标：iOS 16（Android 端 minSdk 26 的对照线）。
        .iOS(.v16),
        .macOS(.v13)
    ],
    products: [
        .library(name: "MyFriendKit", targets: ["MyFriendKit"])
    ],
    targets: [
        .target(
            name: "MyFriendKit",
            path: "Sources/MyFriendKit",
            resources: []
        ),
        .testTarget(
            name: "MyFriendKitTests",
            dependencies: ["MyFriendKit"],
            path: "Tests/MyFriendKitTests"
        )
    ]
)
