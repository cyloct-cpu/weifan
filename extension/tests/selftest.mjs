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
import { md5 } from '../src/core/signing.js';
import { supportsTarget, targetCode, fromPlatformCode } from '../src/core/langs.js';
import { planCandidates, listProviders, credentialOf, AUTO_ORDER } from '../src/core/providers.js';

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

// MARK: 签名与语言映射（新增的服务商层）

section('MD5 签名');

check(md5('') === 'd41d8cd98f00b204e9800998ecf8427e', '空串');
check(md5('abc') === '900150983cd24fb0d6963f7d28e17f72', 'abc');
check(md5('message digest') === 'f96b697d7cb7938d525a2f31aaf161d0', 'message digest');
check(md5('The quick brown fox jumps over the lazy dog') === '9e107d9d372bb6826bd81d3542a419d6', '绕口令');
check(md5('百度翻译签名测试') === md5('百度翻译签名测试'), '中文稳定');
check(md5('a') !== md5('b'), '不同输入不同结果');

section('语种映射');

check(targetCode('baidu', 'zh-Hans') === 'zh', '百度简体 → zh');
check(targetCode('baidu', 'zh-Hant') === 'cht', '百度繁体 → cht');
check(targetCode('google', 'zh-Hans') === 'zh-CN', 'Google 简体 → zh-CN');
check(targetCode('google', 'zh-Hant') === 'zh-TW', 'Google 繁体 → zh-TW');
check(targetCode('deepl', 'zh-Hans') === 'ZH-HANS', 'DeepL 全大写');
check(targetCode('google', 'fil') === 'tl', 'Google 他加禄语 → tl');
check(targetCode('baidu', 'nl') === null, '百度不支持荷兰语');
check(supportsTarget('baidu', 'nl') === false, '不支持时返回 false');
check(supportsTarget('google', 'hi') === true, 'Google 支持印地语');
check(supportsTarget('tencent', 'zh-Hant') === false, '腾讯云不支持繁体');
check(fromPlatformCode('google', 'zh-TW') === 'zh-Hant', '平台码 → 我们的码');
check(fromPlatformCode('baidu', 'cht') === 'zh-Hant', '百度繁体回译');
check(fromPlatformCode('deepl', 'EN') === 'en', 'DeepL 大小写回译');

section('服务商降级链');

// 一家已配置
const onlyBaidu = { baidu: { appid: '1', secret: 'x' } };
check(planCandidates({ target: 'en', keys: onlyBaidu }).length === 1, '只有一家可用');
check(planCandidates({ target: 'en', keys: onlyBaidu })[0].id === 'baidu', '就是那一家');

// 两家：自动优选按 AUTO_ORDER
const twoProviders = { baidu: { appid: '1', secret: 'x' }, deepl: { key: 'k' } };
const autoOrder = planCandidates({ target: 'en', keys: twoProviders }).map((p) => p.id);
check(autoOrder[0] === 'deepl', '自动优选：DeepL 优先');
check(autoOrder[1] === 'baidu', '自动优选：百度其次');

// 用户显式选择 → 排最前
const picked = planCandidates({ target: 'en', keys: twoProviders, selection: 'baidu' }).map((p) => p.id);
check(picked[0] === 'baidu', '用户选的服务商排最前');
check(picked[1] === 'deepl', '其余仍留在链上做降级');

// 选中的不支持该语言 → 退回自动优选
const fallback = planCandidates({ target: 'zh-Hant', keys: { baidu: { appid: '1', secret: 'x' }, tencent: { secretId: 'a', secretKey: 'b' } }, selection: 'tencent' }).map((p) => p.id);
check(fallback.length === 1 && fallback[0] === 'baidu', '腾讯云不支持繁体 → 只剩百度');

// 模式隔离
const withCustom = {
  baidu: { appid: '1', secret: 'x' },
  customs: [{ id: 'c1', name: 'DeepSeek', key: 'sk-x', baseURL: 'https://api.deepseek.com/v1', model: 'deepseek-chat' }]
};
check(planCandidates({ target: 'en', keys: withCustom, mode: 'free' }).every((p) => p.mode === 'free'), '免费模式不含自定义');
check(planCandidates({ target: 'en', keys: withCustom, mode: 'custom' }).every((p) => p.mode === 'custom'), '自定义模式不含免费');
check(planCandidates({ target: 'en', keys: withCustom, mode: 'custom' })[0].id === 'custom:c1', '自定义接口 id 带前缀');

// 超限的服务商被跳过
const blocked = planCandidates({ target: 'en', keys: twoProviders, blocked: ['deepl'] }).map((p) => p.id);
check(blocked.length === 1 && blocked[0] === 'baidu', '超限的服务商被跳过');

// 没配置任何一家 → 空链
check(planCandidates({ target: 'en', keys: {} }).length === 0, '没配置时没有候选');

// 只列出已配置的
check(listProviders(onlyBaidu).length === 1, 'listProviders 只返回已配置的');
check(listProviders(twoProviders).length === 2, '两家都配置时返回两家');

// 旧格式兼容
check(credentialOf('google', { google: 'AIza-x' })?.key === 'AIza-x', '兼容旧的字符串格式');
check(credentialOf('deepl', { deepl: 'k:fx' })?.key === 'k:fx', '兼容旧的 DeepL 字符串');
check(credentialOf('baidu', { baidu: { appid: '1' } }) === null, '缺字段不算已配置');
check(AUTO_ORDER.length === 6, '免费池六家');

console.log(`\n${checks - failures}/${checks} 通过`);
process.exit(failures === 0 ? 0 : 1);
