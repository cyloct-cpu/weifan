// swift-tools-version:5.9
// 微翻 iOS —— 协议层二进制框架包装包
//
// 为什么经 SwiftPM 而不是直接在 target 里挂 xcframework：
// Xcode 27 的 per-file 增量编译对该挂法解析不出 ObjC 模块（详见 docs/19 §2.12 排查），
// 而 SwiftPM binaryTarget 是官方标准姿势：artifact 会被拷进 DerivedData 并由
// SwiftPM 自己生成正确的 -F / module 引用。
import PackageDescription

let package = Package(
    name: "WeiFanProtocolPkg",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "WeiFanProtocolPkg", targets: ["WeifanProtocol"])
    ],
    targets: [
        .binaryTarget(
            name: "WeifanProtocol",
            path: "../Frameworks/WeifanProtocol.xcframework"
        )
    ]
)
