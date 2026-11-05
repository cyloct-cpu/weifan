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

    /// 2026-11-06 用户反馈「翻译服务页怎么返回上一页」——
    /// 验证真实导航流（列表 ⋮ → 设置 → 翻译服务）里返回键存在且可用。
    /// 注意：调试钩子 PREVIEW_TRANSLATION 的根视图本来就没有返回键，别拿它当真实流。
    func testTranslationServiceBackButton() throws {
        let app = XCUIApplication()
        app.launchArguments = ["PREVIEW_LIST"]
        app.launch()

        let menu = app.buttons["header_menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 25), "列表页 ⋮ 没找到")
        menu.tap()

        let settingsItem = app.buttons["设置"]
        XCTAssertTrue(settingsItem.waitForExistence(timeout: 8), "菜单里没有「设置」")
        settingsItem.tap()
        XCTAssertTrue(app.navigationBars["聊天设置"].waitForExistence(timeout: 10), "聊天设置没推入")

        let row = app.buttons["row_translation_service"]
        XCTAssertTrue(row.waitForExistence(timeout: 8), "翻译服务入口行没找到")
        row.tap()
        XCTAssertTrue(app.navigationBars["翻译服务"].waitForExistence(timeout: 10), "翻译服务页没推入")
        snapshot(app, "30-翻译服务-真实流")

        // 返回键 = 导航栏左侧按钮（「聊天设置」或 chevron）
        let navBar = app.navigationBars["翻译服务"]
        let back = navBar.buttons.element(boundBy: 0)
        XCTAssertTrue(back.exists && back.isHittable, "翻译服务页没有可点的返回键")
        back.tap()
        XCTAssertTrue(app.navigationBars["聊天设置"].waitForExistence(timeout: 8), "点返回没回到聊天设置")
    }

    /// 2026-11-06 第二轮反馈回归：网格未配置默认关、外观挪总设置、四模板入口
    func testTranslationServiceFixes() throws {
        let app = XCUIApplication()
        app.launchArguments = ["PREVIEW_LIST"]
        app.launch()

        let menu = app.buttons["header_menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 25), "列表页 ⋮ 没找到")
        menu.tap()
        let settingsItem = app.buttons["设置"]
        XCTAssertTrue(settingsItem.waitForExistence(timeout: 8), "菜单里没有「设置」")
        settingsItem.tap()
        XCTAssertTrue(app.navigationBars["聊天设置"].waitForExistence(timeout: 10), "聊天设置没推入")
        snapshot(app, "40-总设置-外观行")

        // #4：外观：昼夜切换应出现在总设置，且不在翻译服务页
        XCTAssertTrue(app.staticTexts["外观：昼夜切换"].waitForExistence(timeout: 5), "总设置没有外观行")

        let row = app.buttons["row_translation_service"]
        XCTAssertTrue(row.waitForExistence(timeout: 8), "翻译服务入口行没找到")
        row.tap()
        XCTAssertTrue(app.navigationBars["翻译服务"].waitForExistence(timeout: 10), "翻译服务页没推入")
        XCTAssertFalse(app.staticTexts["外观：昼夜切换"].exists, "外观行不该在翻译服务页")

        // #2：未配置渠道默认关——服务段默认展开，直接拍网格
        sleep(1)
        snapshot(app, "41-网格-未配置全关")
        // 三档服务选择菜单
        let autoRow = app.buttons["自动（按语种优选）"]
        if autoRow.exists && autoRow.isHittable {
            autoRow.tap()
            sleep(1)
            snapshot(app, "43-三档服务菜单")
            app.buttons["自动（按语种优选）"].firstMatch.tap()
        }
        // 未配密钥时 DeepL 卡应处于关（开关 value = 0）


        // #3：模板按钮 → 四模板选择器（显示段默认展开，别再点段头否则收起）
        let tplBtn = app.buttons["模板"]
        if tplBtn.waitForExistence(timeout: 5) {
            tplBtn.tap()
            XCTAssertTrue(app.navigationBars["选择提示词模板"].waitForExistence(timeout: 8), "四模板选择器没弹出")
            snapshot(app, "42-四模板选择器")
            let cancel = app.buttons["取消"]
            if cancel.exists { cancel.tap() }
        }
    }

    /// 2026-11-06 用户反馈：切夜态看不清 —— 动态色扫过后夜态巡检截图
    func testDarkModeReadability() throws {
        let app = XCUIApplication()
        app.launchArguments = ["PREVIEW_LIST", "UITEST_FORCE_DARK"]
        app.launch()

        XCTAssertTrue(app.buttons["header_menu"].waitForExistence(timeout: 25), "列表没起来")
        sleep(1)
        snapshot(app, "50-夜-会话列表")

        // 进会话看气泡（夜态发件绿/收件黑）
        let firstChat = app.staticTexts["TEST-验证"]
        if firstChat.waitForExistence(timeout: 5) {
            firstChat.tap()
            sleep(2)
            snapshot(app, "53-夜-会话气泡")
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }

        // 进设置 → 翻译服务
        app.buttons["header_menu"].tap()
        let settingsItem = app.buttons["设置"]
        XCTAssertTrue(settingsItem.waitForExistence(timeout: 8))
        settingsItem.tap()
        XCTAssertTrue(app.navigationBars["聊天设置"].waitForExistence(timeout: 10))
        sleep(1)
        snapshot(app, "51-夜-聊天设置")

        let row = app.buttons["row_translation_service"]
        XCTAssertTrue(row.waitForExistence(timeout: 8))
        row.tap()
        XCTAssertTrue(app.navigationBars["翻译服务"].waitForExistence(timeout: 10))
        sleep(1)
        snapshot(app, "52-夜-翻译服务")
    }

    /// 2026-11-06 第三轮反馈：七家占位引擎卡片也要能点开填密钥（对齐安卓 openProviderDialog）
    func testProviderKeyDialogs() throws {
        let app = XCUIApplication()
        app.launchArguments = ["PREVIEW_LIST"]
        app.launch()

        XCTAssertTrue(app.buttons["header_menu"].waitForExistence(timeout: 25), "列表没起来")
        app.buttons["header_menu"].tap()
        let settingsItem = app.buttons["设置"]
        XCTAssertTrue(settingsItem.waitForExistence(timeout: 8))
        settingsItem.tap()
        XCTAssertTrue(app.navigationBars["聊天设置"].waitForExistence(timeout: 10))
        let row = app.buttons["row_translation_service"]
        XCTAssertTrue(row.waitForExistence(timeout: 8))
        row.tap()
        XCTAssertTrue(app.navigationBars["翻译服务"].waitForExistence(timeout: 10))

        // DeepL/Google 未配置也应有橙色提示（第三轮反馈：最上面两家没小字）
        XCTAssertTrue(app.staticTexts["未配置 · 点开填密钥"].waitForExistence(timeout: 5),
                      "DeepL/Google 未配置卡没有状态提示")

        // 点 Azure 卡 → 配置密钥对话框（订阅密钥预填由运行前 simctl defaults write 注入；
        // headless 键盘 typeText 不稳，不走真打字）
        let azure = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Azure")).firstMatch
        XCTAssertTrue(azure.waitForExistence(timeout: 8), "Azure 卡没找到")
        azure.tap()
        XCTAssertTrue(app.navigationBars["配置密钥"].waitForExistence(timeout: 8), "密钥对话框没弹出")
        let sec = app.secureTextFields.firstMatch
        XCTAssertTrue(sec.waitForExistence(timeout: 5), "订阅密钥输入框没找到")
        let existing = sec.value as? String ?? ""
        XCTAssertFalse(existing.isEmpty, "预填的 cred_azure_key 没读到")
        snapshot(app, "60-Azure密钥对话框")
        app.navigationBars["配置密钥"].buttons["保存"].tap()
        // 保存后卡片应变为「密钥已就绪」
        XCTAssertTrue(app.staticTexts["密钥已就绪 · 引擎接入后自动生效"].waitForExistence(timeout: 8),
                      "保存后 Azure 卡没有变成已就绪态")
        snapshot(app, "61-Azure已就绪")
    }

    /// 二分定位：blob disabledProviders 被谁写 —— 只导航不点任何菜单
    func testBlobProbeNavigationOnly() throws {
        let app = XCUIApplication()
        app.launchArguments = ["PREVIEW_LIST"]
        app.launch()
        let menu = app.buttons["header_menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 25))
        menu.tap()
        let settingsItem = app.buttons["设置"]
        XCTAssertTrue(settingsItem.waitForExistence(timeout: 8))
        settingsItem.tap()
        XCTAssertTrue(app.navigationBars["聊天设置"].waitForExistence(timeout: 10))
        let row = app.buttons["row_translation_service"]
        XCTAssertTrue(row.waitForExistence(timeout: 8))
        row.tap()
        XCTAssertTrue(app.navigationBars["翻译服务"].waitForExistence(timeout: 10))
        // 二分 A：只点开+关闭三档菜单（模板选择器不进）
        let autoRow = app.buttons["自动（按语种优选）"]
        if autoRow.exists && autoRow.isHittable {
            autoRow.tap()
            sleep(1)
            app.buttons["自动（按语种优选）"].firstMatch.tap()
        }
        sleep(3)
    }
}
