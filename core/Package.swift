// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "WeiFanCore",
    platforms: [
        .iOS(.v16),
        .macOS(.v13)
    ],
    products: [
        .library(name: "WeiFanCore", targets: ["WeiFanCore"])
    ],
    targets: [
        .target(name: "WeiFanCore", path: "Sources/WeiFanCore"),
        .testTarget(name: "WeiFanCoreTests", dependencies: ["WeiFanCore"], path: "Tests/WeiFanCoreTests")
    ]
)
