/**
 * 无依赖的冒烟测试：node tests/selftest.mjs
 *
 * 这份断言集与 Swift 版 core/Scripts/main.swift **必须保持一致**——
 * 它是四端行为一致性的唯一保障。改逻辑时两边同改。
 */

import { detectLanguage } from '../src/core/language.js';
import { protect, restore } from '../src/core/glossary.js';
import { route, assessRisk, signals } from '../src/core/routing.js';
import { resolveTarget, DEFAULT_SETTINGS } from '../src/core/core.js';

let failures = 0;
let checks = 0;

function check(condition, message) {
  checks += 1;
  if (condition) {
    console.log(`  ok   ${message}`);
  } else {
    failures += 1;
    console.log(`  FAIL ${message}`);
  }
}

const section = (title) => console.log(`\n[${title}]`);

// MARK: 语言检测

section('语言检测');
check(detectLanguage('Hello, when can you ship the order?')?.language === 'en', '英语');
check(detectLanguage('Hola, ¿cuándo puedes enviar el pedido?')?.language === 'es', '西班牙语');
check(detectLanguage('Olá, obrigado! Você pode enviar o pedido?')?.language === 'pt', '葡萄牙语');
check(detectLanguage('مرحبا، متى يمكنك شحن الطلب؟')?.language === 'ar', '阿拉伯语（脚本判定）');
check(detectLanguage('Здравствуйте, когда вы отправите заказ?')?.language === 'ru', '俄语');
check(detectLanguage('你好，什么时候可以发货？')?.language === 'zh-Hans', '简体中文');
check(detectLanguage('こんにちは、いつ発送できますか？')?.language === 'ja', '日语（假名）');
check(detectLanguage('안녕하세요, 언제 발송할 수 있나요?')?.language === 'ko', '韩语');
check(detectLanguage('ok') === null, '过短内容判不出');
check(detectLanguage('12345 6789') === null, '纯数字判不出');
check(detectLanguage('Hello, when can you ship the order?', []) === null, '候选为空时不误判');

// MARK: 术语保护

section('术语保护');
const entries = [
  { term: '轻舟 Pro', policy: 'keepOriginal' },
  { term: 'Chan', policy: 'keepOriginal' },
  { term: '定金', policy: 'fixed', translations: { es: 'depósito' } }
];
const protectedText = protect('轻舟 Pro 的定金是 500 美元，请联系 Chan。', entries, 'es');
check(Object.keys(protectedText.units).length === 3, '命中 3 个词条');
check(protectedText.hits.length === 3, '命中词记录正确');
const restored = restore('El ⟦2⟧ de ⟦0⟧ es 500 USD, contacte a ⟦1⟧.', protectedText.units);
check(restored === 'El depósito de 轻舟 Pro es 500 USD, contacte a Chan.', `还原正确：${restored}`);
const singleHit = protect('请寄给 Chan', entries, 'en');
check(
  restore('Please ship to 【 0 】.', singleHit.units) === 'Please ship to Chan.',
  '容忍全角方括号与空格'
);
check(restore('hello ⟦9⟧ world', {}) === 'hello ⟦9⟧ world', '无法还原时保留占位符');
check(
  Object.keys(protect('轻舟 Pro Max 到货了', [
    { term: '轻舟', policy: 'keepOriginal' },
    { term: '轻舟 Pro Max', policy: 'keepOriginal' }
  ], 'en').units).length === 1,
  '长词优先'
);

// MARK: 路由

section('混合路由');
check(route('好的，谢谢').route === 'mt', '短句走机翻');
check(route('总价是 1200').route === 'llm', '含数字升级到大模型');
const longText = '这个订单需要提前确认包装方式并且核对收货地址'.repeat(4);
check(route(longText).route === 'llm', '长文本升级到大模型');
const medium = '包装'.repeat(16);
check(route(medium, 'business').route === 'llm', '商务语气更早升级');
check(route(medium, 'casual').route === 'mt', '日常语气保持机翻');

// MARK: 风险闸门

section('发送前风险闸门');
check(signals.hasMoney('报价 1200 美元'), '识别金额');
check(signals.hasDate('下周发货'), '识别日期');

const money = assessRisk({ text: '报价 1200 美元', detection: null, allowDirectSend: true });
check(money.shouldPreview && money.reasons.includes('containsMoney'), '含金额即使开直发也要预览');

const date = assessRisk({ text: '下周发货', detection: null, allowDirectSend: true });
check(!date.shouldPreview, '直发模式下普通日期不拦截');

const strictDate = assessRisk({ text: '下周发货', detection: null, allowDirectSend: false });
check(strictDate.reasons.includes('containsDate'), '严格模式下日期要预览');

const switched = assessRisk({
  text: '早上好',
  detection: { language: 'es', confidence: 0.8, basis: 'lexicon' },
  rememberedLanguage: 'en',
  allowDirectSend: false
});
check(switched.reasons.includes('switchedLanguage'), '对方换语言会提示');

// MARK: 语言解析顺序

section('目标语言解析');
check(resolveTarget('es', { language: 'en', confidence: 0.9 }, 'fr', DEFAULT_SETTINGS).target === 'es', '手动优先');
check(resolveTarget(null, { language: 'es', confidence: 0.9, basis: 'lexicon' }, 'fr', DEFAULT_SETTINGS).target === 'es', '检测次之');
check(resolveTarget(null, null, 'fr', DEFAULT_SETTINGS).target === 'fr', '会话记忆再次之');
check(resolveTarget(null, null, null, DEFAULT_SETTINGS).target === 'en', '兜底英语');
check(
  resolveTarget(null, { language: 'es', confidence: 0.2, basis: 'lexicon' }, 'fr', DEFAULT_SETTINGS).target === 'fr',
  '置信度不足时不用检测结果'
);

console.log(`\n${checks - failures}/${checks} 通过`);
process.exit(failures === 0 ? 0 : 1);
