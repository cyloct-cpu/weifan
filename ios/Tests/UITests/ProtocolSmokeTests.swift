import XCTest

/// 协议层接入验证（iOS 阶段 0 的验收测试）。
///
/// 目的只有一个：证明 Go 编译出的 WeifanProtocol.xcframework 能在 iOS 运行时被 Swift 调用，
/// 且新建客户端不会崩。这是 iOS 客户端整条路线的前提——如果这步不通，后面所有聊天 UI 都是空谈。
///
/// 注意：不在这里做真实配对（要手机扫码），只验证「能创建当前目录下的客户端实例」。
final class ProtocolSmokeTests: XCTestCase {

    func testNewClientDoesNotCrash() throws {
        // 用临时目录，避免污染真实数据
        let tmp = NSTemporaryDirectory() + "weifan-protocol-smoke"
        try? FileManager.default.removeItem(atPath: tmp)
        try? FileManager.default.createDirectory(atPath: tmp,
                                                 withIntermediateDirectories: true)

        var err: NSError?
        let client = MobileNewClient(tmp, "", &err)

        // 全新目录应当能建出客户端（尚未配对）
        XCTAssertNotNil(client, "MobileNewClient 返回 nil，错误：\(err?.localizedDescription ?? "无")")

        if let client = client {
            // 未配对时应报 false 而不是崩溃
            XCTAssertFalse(client.isPaired(), "全新目录不应已被配对")
        }
    }
}
