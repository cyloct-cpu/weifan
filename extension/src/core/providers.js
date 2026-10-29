/**
 * 翻译服务商适配层。
 *
 * 七种通道，六家大厂免费额度 + 自定义 API：
 *   百度 / 腾讯云 / Google / DeepL / 火山引擎 / 阿里云  —— 用户自己申请免费额度，填 key 即可用
 *   自定义 API                                          —— 任意 OpenAI 兼容接口（key + baseURL + model）
 *
 * 全部在 background service worker 里跑（MV3 content script 受 CORS 限制，且密钥不能进页面上下文）。
 *
 * 降级规则：用户选的服务商优先；失败就换下一个「已配置 + 支持该目标语言 + 未超日限额」的服务。
 * 降级过程不静默 —— 结果里带上 degradedFrom，界面要明确告诉用户这次是谁翻的。
 */

import { md5, tencentTC3, volcV4, aliyunRpc } from './signing.js';
import { supportsTarget, targetCode, fromPlatformCode } from './langs.js';

const TIMEOUT_MS = { mt: 8000, llm: 20000 };

/**
 * @typedef {Object} Keys
 * @property {{key: string, endpoint?: string}} [google]
 * @property {{key: string, endpoint?: string}} [deepl]
 * @property {{appid: string, secret: string}} [baidu]
 * @property {{secretId: string, secretKey: string, region?: string}} [tencent]
 * @property {{accessKeyId: string, secretKey: string}} [volc]
 * @property {{accessKeyId: string, accessKeySecret: string}} [aliyun]
 * @property {Array<{id: string, name: string, key: string, baseURL: string, model: string}>} [customs]
 */

/**
 * @typedef {Object} Provider
 * @property {string} id
 * @property {string} label
 * @property {'free'|'custom'} mode
 * @property {'mt'|'llm'} kind
 * @property {string} [freeNote]
 * @property {string} [docs]
 * @property {Array<{id: string, label: string, placeholder: string, secret?: boolean}>} [fields]
 * @property {any} cred
 * @property {(keys: Keys) => boolean} configured
 * @property {(cred: any, payload: {text: string, source: string|null, target: string, style: string}) => Promise<{text: string, detectedSource: string|null}>} run
 */

/**
 * @typedef {Object} ProviderResult
 * @property {string} text
 * @property {string} provider
 * @property {string} providerId
 * @property {string|null} degradedFrom
 * @property {number} latency
 */

/** @param {number} ms */
async function withTimeout(ms) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), ms);
  return { signal: controller.signal, done: () => clearTimeout(timer) };
}

/** @param {Response} res */
async function readJson(res) {
  const text = await res.text();
  if (!text) return {};
  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`返回的不是 JSON：${text.slice(0, 120)}`);
  }
}

/* ── 各家实现 ─────────────────────────────────────── */

/** @param {any} cred @param {{text: string, source: string|null, target: string}} args */
async function baiduRun(cred, { text, source, target }) {
  const to = targetCode('baidu', target);
  if (!to) throw new Error('不支持该目标语言');
  const from = source && source !== 'auto' ? (targetCode('baidu', source) ?? 'auto') : 'auto';

  const salt = String(Math.floor(Math.random() * 1_000_000_000));
  const sign = md5(`${cred.appid}${text}${salt}${cred.secret}`);

  const body = new URLSearchParams({ q: text, from, to, appid: cred.appid, salt, sign });
  const { signal, done } = await withTimeout(TIMEOUT_MS.mt);
  try {
    const res = await fetch('https://fanyi-api.baidu.com/api/trans/vip/translate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
      signal
    });
    const data = await readJson(res);
    if (data?.error_code) throw new Error(data.error_msg || `错误 ${data.error_code}`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const dst = (data?.trans_result ?? []).map((item) => item.dst).join('\n');
    if (!dst) throw new Error('返回空结果');
    return { text: dst, detectedSource: fromPlatformCode('baidu', data.from) };
  } finally {
    done();
  }
}

/** @param {any} cred @param {{text: string, source: string|null, target: string}} args */
async function tencentRun(cred, { text, target }) {
  const to = targetCode('tencent', target);
  if (!to) throw new Error('不支持该目标语言');

  const payload = JSON.stringify({ SourceText: text, Source: 'auto', Target: to, ProjectId: 0 });
  const headers = await tencentTC3({
    secretId: cred.secretId,
    secretKey: cred.secretKey,
    action: 'TextTranslate',
    version: '2018-03-21',
    region: cred.region || 'ap-guangzhou',
    host: 'tmt.tencentcloudapi.com',
    payload
  });

  const { signal, done } = await withTimeout(TIMEOUT_MS.mt);
  try {
    const res = await fetch('https://tmt.tencentcloudapi.com', { method: 'POST', headers, body: payload, signal });
    const data = await readJson(res);
    const error = data?.Response?.Error;
    if (error) throw new Error(error.Message || `错误 ${error.Code}`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const dst = data?.Response?.TargetText;
    if (!dst) throw new Error('返回空结果');
    return { text: dst, detectedSource: fromPlatformCode('tencent', data?.Response?.Source) };
  } finally {
    done();
  }
}

/** @param {any} cred @param {{text: string, source: string|null, target: string}} args */
async function googleRun(cred, { text, source, target }) {
  const to = targetCode('google', target);
  if (!to) throw new Error('不支持该目标语言');
  const from = source && source !== 'auto' ? (targetCode('google', source) ?? null) : null;

  const url = `https://translation.googleapis.com/language/translate/v2?key=${encodeURIComponent(cred.key)}`;
  /** @type {any} */
  const body = { q: [text], target: to, format: 'text' };
  if (from) body.source = from;

  const { signal, done } = await withTimeout(TIMEOUT_MS.mt);
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal
    });
    const data = await readJson(res);
    if (!res.ok) throw new Error(`HTTP ${res.status}：${data?.error?.message ?? '请求失败'}`);
    const first = data?.data?.translations?.[0];
    if (!first?.translatedText) throw new Error('返回空结果');
    return {
      text: unescapeHTML(first.translatedText),
      detectedSource: fromPlatformCode('google', first.detectedSourceLanguage)
    };
  } finally {
    done();
  }
}

/** @param {any} cred @param {{text: string, source: string|null, target: string}} args */
async function deeplRun(cred, { text, source, target }) {
  const to = targetCode('deepl', target);
  if (!to) throw new Error('不支持该目标语言');
  const from = source && source !== 'auto' ? (targetCode('deepl', source) ?? null) : null;

  const params = new URLSearchParams();
  params.set('text', text);
  params.set('target_lang', to);
  if (from) params.set('source_lang', from);

  const { signal, done } = await withTimeout(TIMEOUT_MS.mt);
  try {
    const res = await fetch(cred.endpoint || 'https://api-free.deepl.com/v2/translate', {
      method: 'POST',
      headers: {
        Authorization: `DeepL-Auth-Key ${cred.key}`,
        'Content-Type': 'application/x-www-form-urlencoded'
      },
      body: params,
      signal
    });
    const data = await readJson(res);
    if (!res.ok) throw new Error(`HTTP ${res.status}：${data?.message ?? '请求失败'}`);
    const first = data?.translations?.[0];
    if (!first?.text) throw new Error('返回空结果');
    return { text: first.text, detectedSource: fromPlatformCode('deepl', first.detected_source_language) };
  } finally {
    done();
  }
}

/** @param {any} cred @param {{text: string, source: string|null, target: string}} args */
async function volcRun(cred, { text, target }) {
  const to = targetCode('volc', target);
  if (!to) throw new Error('不支持该目标语言');

  const query = { Action: 'TranslateText', Version: '2020-06-01' };
  const body = JSON.stringify({ TargetLanguage: to, SourceLanguage: 'auto', TextList: [text] });

  const headers = await volcV4({
    accessKeyId: cred.accessKeyId,
    secretKey: cred.secretKey,
    region: 'cn-north-1',
    service: 'translate',
    host: 'open.volcengineapi.com',
    body,
    query
  });

  const { signal, done } = await withTimeout(TIMEOUT_MS.mt);
  try {
    const res = await fetch(`https://open.volcengineapi.com/?${new URLSearchParams(query)}`, {
      method: 'POST',
      headers,
      body,
      signal
    });
    const data = await readJson(res);
    const error = data?.Response?.Error;
    if (error) throw new Error(error.Message || `错误 ${error.Code}`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const first = data?.Response?.TranslationList?.[0];
    if (!first?.Translation) throw new Error('返回空结果');
    return { text: first.Translation, detectedSource: fromPlatformCode('volc', first.SourceLanguage) };
  } finally {
    done();
  }
}

/** @param {any} cred @param {{text: string, source: string|null, target: string}} args */
async function aliyunRun(cred, { text, target }) {
  const to = targetCode('aliyun', target);
  if (!to) throw new Error('不支持该目标语言');

  const params = await aliyunRpc({
    accessKeyId: cred.accessKeyId,
    accessKeySecret: cred.accessKeySecret,
    params: {
      Action: 'TranslateGeneral',
      SourceLanguage: 'auto',
      TargetLanguage: to,
      SourceText: text,
      FormatType: 'text'
    }
  });

  const { signal, done } = await withTimeout(TIMEOUT_MS.mt);
  try {
    const res = await fetch('https://mt.cn-hangzhou.aliyuncs.com/', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(params),
      signal
    });
    const data = await readJson(res);
    if (data?.code && String(data.code) !== '200') throw new Error(data.message || `错误 ${data.code}`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const dst = data?.data?.translated;
    if (!dst) throw new Error('返回空结果');
    return { text: dst, detectedSource: fromPlatformCode('aliyun', data?.data?.detectedSourceLanguage) };
  } finally {
    done();
  }
}

/**
 * OpenAI 兼容的大模型（自定义 API）。
 * @param {{key: string, baseURL: string, model: string, label?: string}} cred
 * @param {{text: string, target: string, style: string}} args
 */
async function customRun(cred, { text, target, style }) {
  const messages = await buildPrompt(text, target, style);
  const base = (cred.baseURL || 'https://api.deepseek.com/v1').replace(/\/$/, '');
  const model = cred.model || 'deepseek-chat';

  const { signal, done } = await withTimeout(TIMEOUT_MS.llm);
  try {
    const res = await fetch(`${base}/chat/completions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${cred.key}` },
      body: JSON.stringify({ model, temperature: 0.2, max_tokens: 2048, messages }),
      signal
    });
    const data = await readJson(res);
    if (!res.ok) throw new Error(`HTTP ${res.status}：${data?.error?.message ?? '请求失败'}`);
    const content = data?.choices?.[0]?.message?.content?.trim();
    if (!content) throw new Error('返回空结果');
    return { text: content, detectedSource: null };
  } finally {
    done();
  }
}

/* ── 注册表 ───────────────────────────────────────── */

/** @type {Provider[]} */
export const PROVIDERS = [
  {
    id: 'deepl',
    label: 'DeepL',
    mode: 'free',
    kind: 'mt',
    freeNote: '每月 50 万字符免费（免费版 key 以 :fx 结尾）',
    docs: 'https://www.deepl.com/pro-api',
    fields: [
      { id: 'key', label: 'API Key', placeholder: 'xxxxxxxx-xxxx-…:fx', secret: true },
      { id: 'endpoint', label: '端点', placeholder: 'https://api-free.deepl.com/v2/translate' }
    ],
    configured: (keys) => Boolean(keys.deepl?.key),
    run: (cred, args) => deeplRun(cred, args)
  },
  {
    id: 'google',
    label: 'Google 翻译',
    mode: 'free',
    kind: 'mt',
    freeNote: '每月 50 万字符免费额度',
    docs: 'https://cloud.google.com/translate/docs/setup',
    fields: [{ id: 'key', label: 'API Key', placeholder: 'AIza…', secret: true }],
    configured: (keys) => Boolean(keys.google?.key),
    run: (cred, args) => googleRun(cred, args)
  },
  {
    id: 'baidu',
    label: '百度翻译',
    mode: 'free',
    kind: 'mt',
    freeNote: '每月 100 万字符免费（标准版，需实名）',
    docs: 'https://fanyi-api.baidu.com/doc/21',
    fields: [
      { id: 'appid', label: 'App ID', placeholder: '2024……' },
      { id: 'secret', label: 'Secret Key', placeholder: '……', secret: true }
    ],
    configured: (keys) => Boolean(keys.baidu?.appid && keys.baidu?.secret),
    run: (cred, args) => baiduRun(cred, args)
  },
  {
    id: 'tencent',
    label: '腾讯云翻译',
    mode: 'free',
    kind: 'mt',
    freeNote: '每月 500 万字符免费额度（需实名）',
    docs: 'https://cloud.tencent.com/document/product/551',
    fields: [
      { id: 'secretId', label: 'SecretId', placeholder: 'AKID……' },
      { id: 'secretKey', label: 'SecretKey', placeholder: '……', secret: true },
      { id: 'region', label: '地域', placeholder: 'ap-guangzhou' }
    ],
    configured: (keys) => Boolean(keys.tencent?.secretId && keys.tencent?.secretKey),
    run: (cred, args) => tencentRun(cred, args)
  },
  {
    id: 'volc',
    label: '火山引擎翻译',
    mode: 'free',
    kind: 'mt',
    freeNote: '每月 100 万字符免费额度',
    docs: 'https://www.volcengine.com/docs/4640',
    fields: [
      { id: 'accessKeyId', label: 'AccessKey ID', placeholder: 'AKLT……' },
      { id: 'secretKey', label: 'Secret Key', placeholder: '……', secret: true }
    ],
    configured: (keys) => Boolean(keys.volc?.accessKeyId && keys.volc?.secretKey),
    run: (cred, args) => volcRun(cred, args)
  },
  {
    id: 'aliyun',
    label: '阿里云翻译',
    mode: 'free',
    kind: 'mt',
    freeNote: '每月 100 万字符免费额度',
    docs: 'https://help.aliyun.com/zh/machine-translation/',
    fields: [
      { id: 'accessKeyId', label: 'AccessKey ID', placeholder: 'LTAI……' },
      { id: 'accessKeySecret', label: 'AccessKey Secret', placeholder: '……', secret: true }
    ],
    configured: (keys) => Boolean(keys.aliyun?.accessKeyId && keys.aliyun?.accessKeySecret),
    run: (cred, args) => aliyunRun(cred, args)
  }
];

/** 自动优选时的优先级（也是下拉框里的顺序） */
export const AUTO_ORDER = ['deepl', 'google', 'baidu', 'tencent', 'volc', 'aliyun'];

/**
 * 取某个服务商的凭据。
 * @param {string} id
 * @param {Keys} keys
 */
export function credentialOf(id, keys) {
  const provider = PROVIDERS.find((p) => p.id === id);
  if (!provider) return null;

  const raw = keys[id];
  /** @type {any} */
  let credential = null;

  if (id === 'google') {
    // 兼容旧格式（key 直接是字符串）
    const key = typeof raw === 'string' ? raw : raw?.key;
    credential = key ? { key } : null;
  } else if (id === 'deepl') {
    if (typeof raw === 'string') credential = { key: raw, endpoint: keys.deeplEndpoint };
    else if (raw?.key) credential = { key: raw.key, endpoint: raw.endpoint ?? keys.deeplEndpoint };
  } else {
    credential = raw ?? null;
  }

  if (!credential) return null;

  // 必填字段缺一个就不算配好了（endpoint 是选填）
  const required = (provider.fields ?? []).filter((field) => field.id !== 'endpoint');
  if (!required.every((field) => credential[field.id])) return null;

  return credential;
}

/**
 * 已配置的自定义 API（可以有多个：DeepSeek、硅基流动、自建网关…）。
 * @param {Keys} keys
 * @returns {Provider[]}
 */
export function customProviders(keys) {
  return (keys.customs ?? [])
    .filter((item) => item?.key && item?.baseURL)
    .map((item) => ({
      id: `custom:${item.id}`,
      label: item.name || item.model || '自定义接口',
      mode: 'custom',
      kind: 'llm',
      cred: { key: item.key, baseURL: item.baseURL, model: item.model, label: item.name || '自定义接口' },
      configured: () => true,
      run: (cred, args) => customRun(cred, args)
    }));
}

/**
 * 已配置且可用的服务商列表。
 * @param {Keys} keys
 * @param {'free'|'custom'|undefined} [mode] 只看某一类
 * @returns {Provider[]}
 */
export function listProviders(keys, mode) {
  const free = PROVIDERS.filter((p) => p.configured(keys)).map((p) => ({ ...p, cred: credentialOf(p.id, keys) }));
  const custom = customProviders(keys);
  const all = [...free, ...custom];
  return mode ? all.filter((p) => p.mode === mode) : all;
}

/**
 * 排出候选顺序（降级链）。
 *
 * @param {Object} args
 * @param {string} args.target
 * @param {'mt'|'llm'} [args.route]
 * @param {Keys} args.keys
 * @param {string} [args.selection] 用户选的服务商 id；'auto' 或空 = 自动优选
 * @param {'free'|'custom'} [args.mode]
 * @param {string[]} [args.blocked] 已超日限额的服务商
 * @returns {Provider[]}
 */
export function planCandidates({ target, route, keys, selection, mode, blocked = [] }) {
  const all = listProviders(keys, mode).filter((p) => !blocked.includes(p.id));
  const eligible = all.filter((p) => (p.mode === 'custom' ? true : supportsTarget(p.id, target)));

  const explicit = selection && selection !== 'auto';
  if (explicit) {
    const chosen = eligible.find((p) => p.id === selection);
    if (chosen) return [chosen, ...eligible.filter((p) => p.id !== selection)];
    return eligible;
  }

  // 自动优选：route 决定先后（大模型优先还是机翻优先），同类内按注册顺序
  const wantLlm = route === 'llm';
  return [...eligible].sort((a, b) => {
    const aLlm = a.kind === 'llm';
    const bLlm = b.kind === 'llm';
    if (aLlm !== bLlm) return wantLlm ? (aLlm ? -1 : 1) : (aLlm ? 1 : -1);
    const ai = AUTO_ORDER.indexOf(a.id);
    const bi = AUTO_ORDER.indexOf(b.id);
    return (ai < 0 ? 99 : ai) - (bi < 0 ? 99 : bi);
  });
}

/**
 * 翻译（带自动降级）。
 *
 * @param {Object} req
 * @param {string} req.text
 * @param {string|null} req.source
 * @param {string} req.target
 * @param {'business'|'casual'|'literal'} req.style
 * @param {'mt'|'llm'} req.route
 * @param {Keys} [req.keys]
 * @param {Object} [options]
 * @param {string} [options.selection]
 * @param {'free'|'custom'} [options.mode]
 * @param {string[]} [options.blocked]
 * @returns {Promise<ProviderResult>}
 */
export async function translate(req, options = {}) {
  const { text, source, target, style, route } = req;
  const keys = req.keys ?? {};
  const started = performance.now();

  const candidates = planCandidates({
    target,
    route,
    keys,
    selection: options.selection,
    mode: options.mode,
    blocked: options.blocked
  });

  if (!candidates.length) {
    const hint = options.mode === 'custom'
      ? '去设置里填一个自定义 API（key + 接口地址 + 模型名）'
      : '去设置里填一个服务商的免费额度密钥';
    throw new Error(`没有可用的翻译服务：${hint}`);
  }

  const preferred = candidates[0];
  const failures = [];

  for (const provider of candidates) {
    try {
      const result = await provider.run(provider.cred ?? {}, { text, source, target, style });
      return {
        text: result.text,
        provider: provider.label,
        providerId: provider.id,
        degradedFrom: provider.id === preferred.id ? null : preferred.label,
        latency: Math.round(performance.now() - started)
      };
    } catch (error) {
      failures.push(`${provider.label}：${error?.message ?? error}`);
    }
  }

  throw new Error(`翻译失败（已尝试 ${candidates.length} 个服务）→ ${failures.join('；')}`);
}

/**
 * 构造大模型提示词。
 * @param {string} text
 * @param {string} target
 * @param {string} style
 */
export async function buildPrompt(text, target, style) {
  const tone = style === 'business'
    ? '正式商务语气，简洁专业，不使用缩略语和表情'
    : style === 'literal'
      ? '忠实直译，保留原文结构与数字格式，不润色'
      : '自然口语，像熟人聊天，可以简短';

  return [
    {
      role: 'system',
      content: [
        `你是专业的中外互译引擎，服务于跨境贸易沟通。把用户输入翻译成${languageName(target)}（${target}）。`,
        '要求：',
        '1. 只输出译文，不要解释、不要引号、不要加任何前后缀。',
        `2. 语气：${tone}。`,
        '3. 文本中的 ⟦数字⟧ 是占位符，必须原样保留在译文中的对应位置，不得翻译、不得改写、不得移动。',
        '4. 金额、数量、日期、型号必须逐字对应，不许换算、不许四舍五入。',
        '5. 人名、品牌名、产品型号保留原文，不要音译。',
        '6. 如果原文已是目标语言，原样返回。'
      ].join('\n')
    },
    { role: 'user', content: text }
  ];
}

/** @param {string} code */
export function languageName(code) {
  const map = {
    en: '英语', es: '西班牙语', pt: '葡萄牙语', fr: '法语', de: '德语', it: '意大利语',
    nl: '荷兰语', ru: '俄语', ar: '阿拉伯语', tr: '土耳其语', vi: '越南语', th: '泰语',
    id: '印尼语', ms: '马来语', hi: '印地语', ja: '日语', ko: '韩语', pl: '波兰语',
    he: '希伯来语', fil: '菲律宾语', 'zh-Hans': '简体中文', 'zh-Hant': '繁体中文'
  };
  return map[code] ?? code;
}

/** @param {string} s */
function unescapeHTML(s) {
  return s
    .replaceAll('&#39;', "'")
    .replaceAll('&quot;', '"')
    .replaceAll('&gt;', '>')
    .replaceAll('&lt;', '<')
    .replaceAll('&amp;', '&');
}

/**
 * 兼容旧调用：只判断"能不能翻"，不实际请求。
 * @param {'mt'|'llm'} route
 * @param {string} target
 * @param {Keys} keys
 * @returns {{kind: string, reason: string}}
 */
export function pickProvider(route, target, keys) {
  const candidates = planCandidates({ target, route, keys });
  if (!candidates.length) throw new Error('没有可用的翻译服务：请在设置里填入至少一个密钥');
  const first = candidates[0];
  return { kind: first.kind, reason: first.label };
}
