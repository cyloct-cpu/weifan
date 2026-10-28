import XCTest
@testable import WeiFanCore

final class LanguageDetectorTests: XCTestCase {

    let detector = HeuristicLanguageDetector()

    func testEnglish() {
        let result = detector.detect("Hello, when can you ship the order?")
        XCTAssertEqual(result?.language, .english)
        XCTAssertEqual(result?.basis, .lexicon)
    }

    func testSpanish() {
        let result = detector.detect("Hola, ¿cuándo puedes enviar el pedido?")
        XCTAssertEqual(result?.language, .spanish)
    }

    func testPortuguese() {
        let result = detector.detect("Olá, obrigado! Você pode enviar o pedido?")
        XCTAssertEqual(result?.language, .portuguese)
    }

    func testArabicByScript() {
        let result = detector.detect("مرحبا، متى يمكنك شحن الطلب؟")
        XCTAssertEqual(result?.language, .arabic)
        XCTAssertEqual(result?.basis, .script)
        XCTAssertGreaterThan(result!.confidence, 0.9)
    }

    func testRussianByScript() {
        let result = detector.detect("Здравствуйте, когда вы отправите заказ?")
        XCTAssertEqual(result?.language, .russian)
    }

    func testChineseSimplified() {
        let result = detector.detect("你好，什么时候可以发货？")
        XCTAssertEqual(result?.language, .chineseSimplified)
    }

    func testJapaneseByKana() {
        let result = detector.detect("こんにちは、いつ発送できますか？")
        XCTAssertEqual(result?.language, .japanese)
    }

    func testKoreanByHangul() {
        let result = detector.detect("안녕하세요, 언제 발송할 수 있나요?")
        XCTAssertEqual(result?.language, .korean)
    }

    func testTooShortReturnsNil() {
        // "ok" 这种词任何语言都可能用，宁可判不出来交给兜底
        XCTAssertNil(detector.detect("ok"))
        XCTAssertNil(detector.detect("👍"))
        XCTAssertNil(detector.detect("   "))
    }

    func testNumbersOnlyReturnsNil() {
        XCTAssertNil(detector.detect("12345 6789"))
    }

    func testEmptyCandidates() {
        XCTAssertNil(detector.detect("Hello, when can you ship the order?", candidates: []))
    }
}

final class GlossaryTests: XCTestCase {

    private func makeGlossary() -> Glossary {
        Glossary(entries: [
            GlossaryEntry(term: "轻舟 Pro", policy: .keepOriginal),
            GlossaryEntry(term: "Chan", policy: .keepOriginal),
            GlossaryEntry(term: "定金", policy: .fixed, translations: ["es": "depósito"])
        ])
    }

    func testProtectThenRestoreRoundTrip() {
        let glossary = makeGlossary()
        let source = "轻舟 Pro 的定金是 500 美元，请联系 Chan。"
        let protected = glossary.protect(source, target: .spanish)

        XCTAssertTrue(protected.masked.contains("⟦0⟧"))
        XCTAssertEqual(protected.units.count, 3)
        XCTAssertEqual(Set(protected.hits), Set(["轻舟 Pro", "Chan", "定金"]))

        // 模拟引擎返回：占位符位置被打乱也无所谓，只要编号还在
        let fakeTranslation = "El ⟦2⟧ de ⟦0⟧ es 500 USD, contacte a ⟦1⟧."
        let restored = glossary.restore(fakeTranslation, units: protected.units)
        XCTAssertEqual(restored, "El depósito de 轻舟 Pro es 500 USD, contacte a Chan.")
    }

    func testRestoreToleratesSpacingAndFullWidthBrackets() {
        let glossary = makeGlossary()
        let protected = glossary.protect("请寄给 Chan", target: .english)
        // DeepL/Google 有时会塞空格，有时会换成全角
        let mangled = "Please ship to 【 1 】."
        let restored = glossary.restore(mangled, units: protected.units)
        XCTAssertEqual(restored, "Please ship to Chan.")
    }

    func testRestoreLeavesUnknownPlaceholderVisible() {
        let glossary = makeGlossary()
        let restored = glossary.restore("hello ⟦9⟧ world", units: [:])
        // 还原不了就原样留着，让用户看到异常，而不是静默给出错译
        XCTAssertEqual(restored, "hello ⟦9⟧ world")
    }

    func testLongestTermWins() {
        let glossary = Glossary(entries: [
            GlossaryEntry(term: "轻舟", policy: .keepOriginal),
            GlossaryEntry(term: "轻舟 Pro Max", policy: .keepOriginal)
        ])
        let protected = glossary.protect("轻舟 Pro Max 到货了", target: .english)
        XCTAssertEqual(protected.units.count, 1)
        XCTAssertEqual(protected.hits, ["轻舟 Pro Max"])
    }

    func testDisabledEntryIgnored() {
        let glossary = Glossary(entries: [
            GlossaryEntry(term: "Chan", policy: .keepOriginal, isEnabled: false)
        ])
        XCTAssertTrue(glossary.protect("hi Chan", target: .english).units.isEmpty)
    }
}

final class RoutingTests: XCTestCase {

    let router = TranslationRouter()

    func testShortSimpleSentenceUsesMT() {
        XCTAssertEqual(router.route("好的，谢谢").route, .fastMT)
        XCTAssertEqual(router.route("Please send the invoice").route, .fastMT)
    }

    func testNumberEscalatesToModel() {
        XCTAssertEqual(router.route("总价是 1200").route, .languageModel)
    }

    func testLongTextEscalates() {
        let long = String(repeating: "这个订单需要提前确认包装方式并且核对收货地址", count: 4)
        XCTAssertEqual(router.route(long).route, .languageModel)
    }

    func testBusinessStyleEscalatesEarlier() {
        let text = String(repeating: "包装", count: 16) // 32 字
        XCTAssertEqual(router.route(text, style: .business).route, .languageModel)
        XCTAssertEqual(router.route(text, style: .casual).route, .fastMT)
    }
}

final class RiskGateTests: XCTestCase {

    private func gate() -> RiskGate {
        var gate = RiskGate()
        gate.longSentenceThreshold = 60
        return gate
    }

    func testMoneyAlwaysNeedsPreview() {
        let assessment = gate().assess(text: "报价 1200 美元", detection: nil, glossaryHits: [],
                                       rememberedLanguage: nil, userAllowsDirectSend: true)
        XCTAssertTrue(assessment.shouldPreview)
        XCTAssertTrue(assessment.reasons.contains(.containsMoney))
    }

    func testDateIsFilteredWhenUserAllowsDirectSend() {
        let assessment = gate().assess(text: "下周发货", detection: nil, glossaryHits: [],
                                       rememberedLanguage: nil, userAllowsDirectSend: true)
        XCTAssertFalse(assessment.shouldPreview)
    }

    func testDateNeedsPreviewWhenStrict() {
        let assessment = gate().assess(text: "下周发货", detection: nil, glossaryHits: [],
                                       rememberedLanguage: nil, userAllowsDirectSend: false)
        XCTAssertTrue(assessment.reasons.contains(.containsDate))
    }

    func testLanguageSwitchDetected() {
        let detection = LanguageDetection(language: .spanish, confidence: 0.8, basis: .lexicon)
        let assessment = gate().assess(text: "早上好", detection: detection, glossaryHits: [],
                                       rememberedLanguage: .english, userAllowsDirectSend: false)
        XCTAssertTrue(assessment.reasons.contains(.switchedLanguage))
    }

    func testLowConfidenceFlagged() {
        let detection = LanguageDetection(language: .spanish, confidence: 0.3, basis: .lexicon)
        let assessment = gate().assess(text: "早上好", detection: detection, glossaryHits: [],
                                       rememberedLanguage: nil, userAllowsDirectSend: false)
        XCTAssertTrue(assessment.reasons.contains(.lowDetectionConfidence))
    }
}

final class SettingsDecodingTests: XCTestCase {

    func testDecodingOldPayloadKeepsDefaults() throws {
        // 老版本只存了一个字段，新字段必须能补默认值而不是整体解码失败
        let json = #"{"allowsDirectSend":false}"#
        let settings = try JSONDecoder().decode(AppSettings.self, from: Data(json.utf8))
        XCTAssertFalse(settings.allowsDirectSend)
        XCTAssertEqual(settings.defaultTargetLanguage, .english)
        XCTAssertEqual(settings.longSentenceThreshold, 60)
    }

    func testRoundTrip() throws {
        var settings = AppSettings()
        settings.defaultTargetLanguage = .spanish
        settings.pinnedLanguages = [.english, .arabic]
        settings.glossary = [GlossaryEntry(term: "轻舟", policy: .keepOriginal)]

        let data = try JSONEncoder().encode(settings)
        let decoded = try JSONDecoder().decode(AppSettings.self, from: data)
        XCTAssertEqual(decoded.defaultTargetLanguage, .spanish)
        XCTAssertEqual(decoded.pinnedLanguages, [.english, .arabic])
        XCTAssertEqual(decoded.glossary.first?.term, "轻舟")
    }
}
