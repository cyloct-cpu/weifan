import XCTest

/// 阶段 1 UI 验收（docs/17 §11.6 第 8 条）：
///  1) 首启语言选择 → 点选后进配对页
///  2) 聊天 Tab 可达（fake-paired 模式下会话列表 + 会话页渲染）
/// 配对成功本身需要手机扫码，不在模拟器验收范围（记录于 docs/19）。
final class Phase1UITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    /// 语言选择页渲染并可点选 → 进入配对页
    func test01OnboardingFlow() throws {
        let app = XCUIApplication()
        app.launchArguments = ["UITEST_RESET_ONBOARDING"]
        app.launch()

        // 首启：语言选择页
        let spanish = app.buttons["lang_es"].firstMatch
        if !spanish.waitForExistence(timeout: 10) {
            let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            shot.name = "onboarding-not-shown"
            shot.lifetime = .keepAlways
            add(shot)
        }
        XCTAssertTrue(spanish.exists, "语言选择页未出现")
        app.buttons["lang_en"].firstMatch.tap()

        // 点选后：配对页（标题「关联 WhatsApp」）
        let pairTitle = app.staticTexts["关联 WhatsApp"]
        if !pairTitle.waitForExistence(timeout: 10) {
            let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            shot.name = "after-language-tap"
            shot.lifetime = .keepAlways
            add(shot)
        }
        XCTAssertTrue(pairTitle.exists, "点选语言后未进入配对页")
    }

    /// fake-paired：会话列表渲染种子会话 → 进入会话页看到气泡与输入区
    func test02ChatStackRenders() throws {
        let app = XCUIApplication()
        app.launchArguments = ["UITEST_FAKE_PAIRED"]
        app.launch()

        // 会话列表：种子会话 TEST-验证
        let row = app.staticTexts["TEST-验证"].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10), "会话列表未显示种子会话")

        // 进入会话页（导航行由 NavigationLink 承载，点整行）
        row.tap()
        let bubble = app.staticTexts["Hola, ¿cómo estás? Es un placer conocerte."]
        let nav = app.navigationBars["TEST-验证"]
        let opened = bubble.waitForExistence(timeout: 10) || nav.waitForExistence(timeout: 2)
        XCTAssertTrue(opened, "会话页没打开（点了行没反应或气泡未渲染）")
        let translated = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "谢谢你的帮助")).firstMatch
        if !translated.exists {
            // 失败取证：把当前屏截下来（xcresult 里可取）
            let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            shot.name = "chatview-no-translated"
            shot.lifetime = .keepAlways
            add(shot)
        }
        XCTAssertTrue(translated.exists, "译文行未渲染")
        XCTAssertTrue(app.textFields.firstMatch.exists || app.textViews.firstMatch.exists, "输入区未渲染")
        let evidence = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        evidence.name = "phase2-chat-media"
        evidence.lifetime = .keepAlways
        add(evidence)
    }

}
