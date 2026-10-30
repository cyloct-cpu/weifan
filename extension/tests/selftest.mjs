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
import { effectiveTheme, toMinutes, DEFAULT_SCHEDULE } from '../src/shared/theme.js';
import { md5 } from '../src/core/signing.js';
import { supportsTarget, targetCode, fromPlatformCode } from '../src/core/langs.js';
import { planCandidates, listProviders, credentialOf, AUTO_ORDER, PROVIDERS } from '../src/core/providers.js';
// 副作用引入：挂 globalThis.WeifanTts（TTS 语言映射）
import '../src/core/tts.js';
import { readFileSync } from 'node:fs';

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

// 百度官方文档《通用翻译API接入文档》给出的两组签名样例 —— 签名实现的回归护栏
// doc_bd/21：appid+q+salt+密钥 → MD5
check(
  md5('2015063000000001' + 'apple' + '1435660288' + '12345678') === 'f89f9594663708c1605f3d736d01d2d4',
  '百度签名样例一（官方文档）'
);
check(
  md5('2015063000000001' + 'apple' + '65478' + '1234567890') === 'a1a7461d92e5194c5cae3182b5b24de1',
  '百度签名样例二（官方文档）'
);

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
check(targetCode('azure', 'zh-Hans') === 'zh-Hans', 'Azure 简体 → zh-Hans');
check(targetCode('azure', 'zh-Hant') === 'zh-Hant', 'Azure 繁体 → zh-Hant');
check(targetCode('azure', 'fil') === 'tl', 'Azure 他加禄语 → tl');
check(targetCode('yandex', 'zh-Hans') === 'zh', 'Yandex 简体 → zh');
check(supportsTarget('azure', 'he') === true, 'Azure 支持希伯来语');
check(supportsTarget('yandex', 'zh-Hant') === false, 'Yandex 不支持繁体');
check(supportsTarget('yandex', 'fil') === true, 'Yandex 支持他加禄语（tl）');
check(targetCode('volcLLM', 'zh-Hans') === 'zh', '火山大模型 简体 → zh');
check(targetCode('volcLLM', 'fil') === 'tl', '火山大模型 他加禄语 → tl');
check(supportsTarget('volcLLM', 'he') === true, '火山大模型 支持希伯来语');
check(supportsTarget('volcLLM', 'zh-Hant') === true, '火山大模型 支持繁体');

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

// 用户显式选择 → 只用这一家，不降级（D-56）
const picked = planCandidates({ target: 'en', keys: twoProviders, selection: 'baidu' }).map((p) => p.id);
check(picked.length === 1 && picked[0] === 'baidu', '只用这家：单候选，不降级');
let threwSingle = '';
try {
  planCandidates({ target: 'zh-Hant', keys: { baidu: { appid: '1', secret: 'x' }, tencent: { secretId: 'a', secretKey: 'b' } }, selection: 'tencent' });
} catch (error) {
  threwSingle = error.message;
}
check(threwSingle.includes('用不了'), '选中的不支持该语言 → 显式报错（不再悄悄换链）');

// 按我的排序（D-56）：数组顺序即降级链，开关关掉的跳过
const chainKeys = { baidu: { appid: '1', secret: 'x' }, deepl: { key: 'k' }, tencent: { secretId: 'a', secretKey: 'b' } };
const chainOrder = planCandidates({
  target: 'en', keys: chainKeys, selection: 'priority',
  chainOrder: ['tencent', 'custom:missing', 'baidu', 'deepl'], chainOff: []
}).map((p) => p.id);
check(chainOrder.join(',') === 'tencent,baidu,deepl', '优先级链按数组顺序，不存在的 id 跳过');
const chainOff = planCandidates({
  target: 'en', keys: chainKeys, selection: 'priority',
  chainOrder: ['tencent', 'baidu', 'deepl'], chainOff: ['tencent', 'deepl']
}).map((p) => p.id);
check(chainOff.length === 1 && chainOff[0] === 'baidu', '关了开关的渠道被跳过');
let threwEmpty = '';
try {
  planCandidates({ target: 'en', keys: chainKeys, selection: 'priority', chainOrder: ['deepl'], chainOff: ['deepl'] });
} catch (error) {
  threwEmpty = error.message;
}
check(threwEmpty.includes('没有可用的服务'), '优先级链为空 → 显式报错');

// 渠道混排：自定义接口与免费服务商可同链（不再按 mode 隔离）
const withCustom = {
  baidu: { appid: '1', secret: 'x' },
  customs: [{ id: 'c1', name: 'DeepSeek', key: 'sk-x', baseURL: 'https://api.deepseek.com/v1', model: 'deepseek-chat' }]
};
const mixed = planCandidates({
  target: 'en', keys: withCustom, selection: 'priority',
  chainOrder: ['custom:c1', 'baidu'], chainOff: []
}).map((p) => p.id);
check(mixed.join(',') === 'custom:c1,baidu', '自定义接口与免费服务商混排（custom 在前）');
// 百度已是大模型端点（kind=llm），用纯机翻的 DeepL 验证 route 排序
const mtAndLlm = { deepl: { key: 'k' }, customs: [{ id: 'c1', name: 'DeepSeek', key: 'sk-x', baseURL: 'https://api.deepseek.com/v1', model: 'deepseek-chat' }] };
check(planCandidates({ target: 'en', keys: mtAndLlm, route: 'llm' })[0].id === 'custom:c1', '自动优选 llm 路由：自定义大模型优先');
check(planCandidates({ target: 'en', keys: mtAndLlm, route: 'mt' })[0].id === 'deepl', '自动优选 mt 路由：机翻优先');

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

// 选填字段不影响"已配置"判定
check(credentialOf('azure', { azure: { key: 'k' } })?.key === 'k', 'Azure 只有密钥也算已配置');
check(credentialOf('azure', { azure: { key: 'k' } })?.region === undefined, 'Azure 区域留空也能用');
check(credentialOf('tencent', { tencent: { secretId: 'a', secretKey: 'b' } }) !== null, '腾讯地域留空也算已配置');
check(credentialOf('yandex', { yandex: { key: 'k' } }) === null, 'Yandex 缺 folderId 不算已配置');
check(credentialOf('yandex', { yandex: { key: 'k', folderId: 'f' } }) !== null, 'Yandex 填齐即算已配置');
check(AUTO_ORDER.length === 9, '免费池九家');

// 端点：固定端点必须写进注册表，用户才只需填密钥
check(PROVIDERS.every((p) => typeof p.endpoint === 'string' && p.endpoint.startsWith('https://')), '每家都有 https 端点');
check(PROVIDERS.filter((p) => p.endpointEditable).map((p) => p.id).sort().join(',') === 'azure,deepl', '只有 Azure 与 DeepL 的端点可改');
check(PROVIDERS.find((p) => p.id === 'volc').endpoint === 'https://translate.volcengineapi.com', '火山文本翻译用官方域名');
check(PROVIDERS.find((p) => p.id === 'volcLLM').endpoint.includes('matx_translate'), '火山大模型翻译端点正确');
check(PROVIDERS.find((p) => p.id === 'baidu').kind === 'llm', '百度走大模型端点，标记为 llm 档');

// ── 主题：昼 / 夜 / 跟随系统 + 自定义时段 ─────────────

check(toMinutes('22:00') === 1320, '时段解析 22:00');
check(toMinutes('07:30') === 450, '时段解析 07:30');
check(toMinutes('24:00') === null, '时段解析拒绝非法小时');
check(toMinutes('12:60') === null, '时段解析拒绝非法分钟');
check(toMinutes('abc') === null, '时段解析拒绝非时间串');

check(effectiveTheme('light', null, false) === 'light', '昼模式恒为浅色（哪怕系统是暗色）');
check(effectiveTheme('light', null, true) === 'light', '昼模式恒为浅色（系统是暗色）');
check(effectiveTheme('dark', null, false) === 'dark', '夜模式恒为暗色（哪怕系统是浅色）');
check(effectiveTheme('dark', null, true) === 'dark', '夜模式恒为暗色（系统是浅色）');

// 跟随系统：没自定义时段就跟着系统
check(effectiveTheme('auto', null, false) === 'light', '跟随系统 · 系统浅色');
check(effectiveTheme('auto', null, true) === 'dark', '跟随系统 · 系统暗色');
check(effectiveTheme('auto', { enabled: false }, true) === 'dark', '跟随系统 · 时段未启用则仍跟系统');

// 跟随系统 + 自定义时段：按时刻判定（跨零点也要认）
const at = (h, m) => new Date(2026, 0, 1, h, m);
const night = { enabled: true, start: '22:00', end: '07:00' };
check(effectiveTheme('auto', night, false, at(23, 0)) === 'dark', '时段内 23:00 判为夜间');
check(effectiveTheme('auto', night, false, at(3, 0)) === 'dark', '跨零点后 03:00 仍判为夜间');
check(effectiveTheme('auto', night, false, at(12, 0)) === 'light', '时段外 12:00 判为昼');
check(effectiveTheme('auto', night, true, at(12, 0)) === 'light', '自定义时段优先于系统');
check(effectiveTheme('auto', night, false, at(22, 0)) === 'dark', '起点时刻算夜间（闭区间）');
check(effectiveTheme('auto', night, false, at(7, 0)) === 'light', '终点时刻算昼（半开区间）');

// 不跨零点的时段
const day = { enabled: true, start: '09:00', end: '18:00' };
check(effectiveTheme('auto', day, false, at(12, 0)) === 'dark', '同日时段内 12:00 判为夜间');
check(effectiveTheme('auto', day, false, at(20, 0)) === 'light', '同日时段外 20:00 判为昼');

// 设置项默认值
check(DEFAULT_SETTINGS.themeMode === 'auto', '默认主题是跟随系统');
check(DEFAULT_SETTINGS.themeSchedule?.start === DEFAULT_SCHEDULE.start, '默认夜间起点');
check(DEFAULT_SETTINGS.themeSchedule?.end === DEFAULT_SCHEDULE.end, '默认夜间终点');

// MARK: 右键菜单与快捷键（D-55）

section('右键菜单 / 快捷键');
const manifest = JSON.parse(readFileSync(new URL('../manifest.base.json', import.meta.url), 'utf8'));
check(manifest.permissions.includes('contextMenus'), 'manifest 带 contextMenus 权限');
check(manifest.commands?.['translate-selection']?.suggested_key?.default === 'Alt+Shift+T', '快捷键：翻选区 Alt+Shift+T');
check(manifest.commands?.['toggle-hover']?.suggested_key?.default === 'Alt+Shift+H', '快捷键：悬停开关 Alt+Shift+H');
check(manifest.commands?.['open-text-page']?.suggested_key?.default === 'Alt+Shift+X', '快捷键：打开文本页 Alt+Shift+X');
check(
  manifest.content_scripts?.some((cs) => (cs.js ?? []).includes('src/core/tts.js')),
  'content script 注入 tts.js（划词气泡朗读用）'
);

// MARK: TTS 语言映射（与 android .../core/Tts.kt 同表）

section('TTS 语言映射');
const { ttsTag } = globalThis.WeifanTts ?? {};
check(typeof ttsTag === 'function', 'tts.js 挂载了 WeifanTts');
check(ttsTag?.('zh-Hans') === 'zh-CN', '简体中文 → zh-CN');
check(ttsTag?.('zh-Hant') === 'zh-TW', '繁体中文 → zh-TW');
check(ttsTag?.('en') === 'en-US', '英语 → en-US');
check(ttsTag?.('ja') === 'ja-JP', '日语 → ja-JP');
check(ttsTag?.('fil') === 'fil-PH', '菲律宾语 → fil-PH');
check(ttsTag?.('xx') === null, '没有映射的语言返回 null');
check(ttsTag?.('') === null, '空代码返回 null');
check(ttsTag?.(null) === null, 'null 返回 null');

console.log(`\n${checks - failures}/${checks} 通过`);
process.exit(failures === 0 ? 0 : 1);
