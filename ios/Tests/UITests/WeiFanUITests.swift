import XCTest

/// iOS 端 UI 冒烟测试。
///
/// 存在的理由：本机 Xcode 是精简版（没有 Simulator.app 界面），无法用鼠标点，
/// 但 `xcodebuild test` 在 headless 模拟器上照样能驱动 UI。
/// 这个测试遍历四个 tab 并断言各页可达，同时把每一步截图存进 .xcresult，
/// 用 `xcrun xcresulttool export attachments` 可导出核对布局。
///
/// 断言口径经过一次实测校正：早期用页面正文文案断言会误判（客户页为空时
/// 「自动检测」根本不渲染），改用每个 tab 的 NavigationBar identifier，
/// 它由 navigationTitle 决定，稳定且与内容无关。
final class WeiFanUITests: XCTestCase {

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    func testAllTabsReachable() throws {
        let app = XCUIApplication()
        app.launchArguments = ["UITEST_FAKE_PAIRED"]
        app.launch()

        // ── 0. 聊天栈现在是第一 tab；本测试只验工具形态四页可达 → 切到翻译 tab
        let translateTab = app.tabBars.buttons["翻译"]
        XCTAssertTrue(translateTab.waitForExistence(timeout: 25), "TabBar 没起来（App 可能启动失败）")
        translateTab.tap()
        XCTAssertTrue(app.navigationBars["微翻"].waitForExistence(timeout: 25),
                      "翻译页没起来（App 可能启动失败）")
        snapshot(app, "01-翻译")

        // ── 2. 客户页
        try tapTab(app, "客户")
        XCTAssertTrue(app.navigationBars["客户"].waitForExistence(timeout: 10), "客户页没出现")
        snapshot(app, "02-客户")

        // ── 3. 术语页
        try tapTab(app, "术语")
        XCTAssertTrue(app.navigationBars["术语"].waitForExistence(timeout: 10), "术语页没出现")
        snapshot(app, "03-术语")

        // ── 4. 设置页
        try tapTab(app, "设置")
        XCTAssertTrue(app.navigationBars["设置"].waitForExistence(timeout: 10), "设置页没出现")
        snapshot(app, "04-设置")

        // ── 来回切换仍正常
        try tapTab(app, "翻译")
        XCTAssertTrue(app.navigationBars["微翻"].waitForExistence(timeout: 10), "切回翻译页失败")
    }

    /// 试译：输入中文 → 未配密钥时应给出可读提示，而不是静默失败或崩溃
    func testTranslateWithoutKeyShowsReadableError() throws {
        let app = XCUIApplication()
        app.launchArguments = ["UITEST_FAKE_PAIRED"]
        app.launch()
        // 聊天 tab 在前：切到翻译
        let tb = app.tabBars.buttons["翻译"]
        XCTAssertTrue(tb.waitForExistence(timeout: 25), "TabBar 没起来")
        tb.tap()

        let editor = app.textViews["sourceText"]
        XCTAssertTrue(editor.waitForExistence(timeout: 25), "输入框没找到")
        editor.tap()
        editor.typeText("这批货下周能发出吗")

        let translate = app.buttons["translateButton"]
        XCTAssertTrue(translate.waitForExistence(timeout: 5), "翻译按钮没找到")
        translate.tap()

        // 无密钥时的反馈：包含「密钥 / 服务 / 失败 / 未」之一即可
        let hint = app.staticTexts.containing(
            NSPredicate(format: "label CONTAINS '密钥' OR label CONTAINS '服务' "
                + "OR label CONTAINS '失败' OR label CONTAINS '配置'")
        ).firstMatch
        XCTAssertTrue(hint.waitForExistence(timeout: 20), "没看到可读的错误提示")
        XCTAssertEqual(app.state, .runningForeground, "App 不在前台（崩溃或被切走）")
        snapshot(app, "05-无密钥反馈")
    }

    // MARK: - helpers

    private func tapTab(_ app: XCUIApplication, _ title: String) throws {
        let bar = app.tabBars.firstMatch
        XCTAssertTrue(bar.waitForExistence(timeout: 10), "TabBar 不存在")
        let btn = bar.buttons[title]
        guard btn.waitForExistence(timeout: 6) else {
            let names = bar.buttons.allElementsBoundByIndex.map { $0.label }
            XCTFail("找不到 tab「\(title)」，现有：\(names)")
            return
        }
        btn.tap()
    }

    private func snapshot(_ app: XCUIApplication, _ name: String) {
        let att = XCTAttachment(screenshot: app.screenshot())
        att.name = name
        att.lifetime = .keepAlways
        add(att)
    }
}
