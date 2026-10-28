// 无 Xcode 环境下的冒烟自测：swiftc Sources/WeiFanCore/*.swift Scripts/main.swift && ./main
// 有 Xcode 后请改用 `swift test`（core/Tests 下有完整的 XCTest 用例）。

import Foundation

var failures = 0
var checks = 0

func check(_ condition: Bool, _ message: String) {
    checks += 1
    if condition {
        print("  ok   \(message)")
    } else {
        failures += 1
        print("  FAIL \(message)")
    }
}

func section(_ title: String) { print("\n[\(title)]") }

// MARK: 语言检测

section("语言检测")
let detector = HeuristicLanguageDetector()

check(detector.detect("Hello, when can you ship the order?")?.language == .english, "英语")
check(detector.detect("Hola, ¿cuándo puedes enviar el pedido?")?.language == .spanish, "西班牙语")
check(detector.detect("Olá, obrigado! Você pode enviar o pedido?")?.language == .portuguese, "葡萄牙语")
check(detector.detect("مرحبا، متى يمكنك شحن الطلب؟")?.language == .arabic, "阿拉伯语（脚本判定）")
check(detector.detect("Здравствуйте, когда вы отправите заказ?")?.language == .russian, "俄语")
check(detector.detect("你好，什么时候可以发货？")?.language == .chineseSimplified, "简体中文")
check(detector.detect("こんにちは、いつ発送できますか？")?.language == .japanese, "日语（假名）")
check(detector.detect("안녕하세요, 언제 발송할 수 있나요?")?.language == .korean, "韩语")
check(detector.detect("ok") == nil, "过短内容判不出，交给兜底")
check(detector.detect("12345 6789") == nil, "纯数字判不出")
check(detector.detect("Hello, when can you ship the order?", candidates: []) == nil, "候选语言为空时不误判")

// MARK: 术语保护

section("术语保护")
let glossary = Glossary(entries: [
    GlossaryEntry(term: "轻舟 Pro", policy: .keepOriginal),
    GlossaryEntry(term: "Chan", policy: .keepOriginal),
    GlossaryEntry(term: "定金", policy: .fixed, translations: ["es": "depósito"])
])
let protected = glossary.protect("轻舟 Pro 的定金是 500 美元，请联系 Chan。", target: .spanish)
check(protected.units.count == 3, "命中 3 个词条")
check(Set(protected.hits) == Set(["轻舟 Pro", "Chan", "定金"]), "命中词集合正确")
let restored = glossary.restore("El ⟦2⟧ de ⟦0⟧ es 500 USD, contacte a ⟦1⟧.", units: protected.units)
check(restored == "El depósito de 轻舟 Pro es 500 USD, contacte a Chan.", "还原后与预期一致：\(restored)")
let mangled = glossary.restore("Please ship to 【 0 】.", units: glossary.protect("请寄给 Chan", target: .english).units)
check(mangled == "Please ship to Chan.", "容忍全角方括号与空格（编号从 0 开始）")
check(glossary.restore("hello ⟦9⟧ world", units: [:]) == "hello ⟦9⟧ world", "无法还原时保留占位符，不静默出错译")
let longFirst = Glossary(entries: [
    GlossaryEntry(term: "轻舟", policy: .keepOriginal),
    GlossaryEntry(term: "轻舟 Pro Max", policy: .keepOriginal)
])
check(longFirst.protect("轻舟 Pro Max 到货了", target: .english).units.count == 1, "长词优先匹配")

// MARK: 路由

section("混合路由")
let router = TranslationRouter()
check(router.route("好的，谢谢").route == .fastMT, "短句走机翻")
check(router.route("总价是 1200").route == .languageModel, "含数字升级到大模型")
let longText = String(repeating: "这个订单需要提前确认包装方式并且核对收货地址", count: 4)
check(router.route(longText).route == .languageModel, "长文本升级到大模型")
let medium = String(repeating: "包装", count: 16)
check(router.route(medium, style: .business).route == .languageModel, "商务语气更早升级")
check(router.route(medium, style: .casual).route == .fastMT, "日常语气保持机翻")

// MARK: 风险闸门

section("发送前风险闸门")
var gate = RiskGate()
gate.longSentenceThreshold = 60
let money = gate.assess(text: "报价 1200 美元", detection: nil, glossaryHits: [],
                        rememberedLanguage: nil, userAllowsDirectSend: true)
check(money.shouldPreview && money.reasons.contains(.containsMoney), "含金额即使开了直发也要预览")
let date = gate.assess(text: "下周发货", detection: nil, glossaryHits: [],
                       rememberedLanguage: nil, userAllowsDirectSend: true)
check(!date.shouldPreview, "直发模式下普通日期不拦截")
let strictDate = gate.assess(text: "下周发货", detection: nil, glossaryHits: [],
                             rememberedLanguage: nil, userAllowsDirectSend: false)
check(strictDate.reasons.contains(.containsDate), "严格模式下日期需要预览")
let switched = gate.assess(text: "早上好",
                           detection: LanguageDetection(language: .spanish, confidence: 0.8, basis: .lexicon),
                           glossaryHits: [], rememberedLanguage: .english, userAllowsDirectSend: false)
check(switched.reasons.contains(.switchedLanguage), "对方换语言会提示")

// MARK: 设置兼容

section("设置解码兼容")
let oldJSON = #"{"allowsDirectSend":false}"#
if let decoded = try? JSONDecoder().decode(AppSettings.self, from: Data(oldJSON.utf8)) {
    check(decoded.allowsDirectSend == false, "旧字段读出来")
    check(decoded.defaultTargetLanguage == .english, "缺失字段补默认值：兜底英语")
    check(decoded.longSentenceThreshold == 60, "阈值默认值正确")
} else {
    check(false, "旧数据解码不应失败")
}
var roundTrip = AppSettings()
roundTrip.defaultTargetLanguage = .spanish
roundTrip.pinnedLanguages = [.english, .arabic]
if let data = try? JSONEncoder().encode(roundTrip),
   let decoded = try? JSONDecoder().decode(AppSettings.self, from: data) {
    check(decoded.defaultTargetLanguage == .spanish && decoded.pinnedLanguages == [.english, .arabic], "编码往返一致")
} else {
    check(false, "编码往返失败")
}

print("\n\(checks - failures)/\(checks) 通过")
exit(failures == 0 ? 0 : 1)
