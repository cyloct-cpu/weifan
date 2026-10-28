/**
 * 翻译服务商适配。对应 Swift 版 core/Sources/WeiFanCore/Providers.swift。
 * 全部在 background service worker 里跑（MV3 content script 受 CORS 限制）。
 */

const TIMEOUT_MS = { mt: 8000, llm: 20000 };

/**
 * @typedef {Object} Keys
 * @property {string} [google]
 * @property {string} [deepl]
 * @property {string} [deeplEndpoint]
 * @property {string} [groq]
 * @property {string} [llm]
 * @property {string} [llmBaseURL]
 * @property {string} [llmModel]
 */

/** @typedef {{text: string, provider: string, detectedSource: string|null, latency: number}} ProviderResult */

/** @param {AbortSignal} signal */
async function withTimeout(ms) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), ms);
  return { signal: controller.signal, done: () => clearTimeout(timer) };
}

/**
 * 选服务商：llm 路线优先 Groq（实测更快），机翻路线优先 DeepL（中→欧语更稳）。
 * @param {'mt'|'llm'} route
 * @param {string} target
 * @param {Keys} keys
 * @returns {{kind: 'google'|'deepl'|'groq'|'llm', reason: string}}
 */
export function pickProvider(route, target, keys) {
  const deeplOK = ['en', 'es', 'pt', 'fr', 'de', 'it', 'nl', 'ru', 'ar', 'tr', 'id', 'ja', 'ko', 'pl', 'zh-Hans', 'zh-Hant'].includes(target);

  if (route === 'llm') {
    if (keys.groq) return { kind: 'groq', reason: 'Groq（低延迟大模型）' };
    if (keys.llm) return { kind: 'llm', reason: 'OpenAI 兼容大模型' };
    if (keys.google) return { kind: 'google', reason: '大模型未配置，回落 Google' };
    if (keys.deepl && deeplOK) return { kind: 'deepl', reason: '大模型未配置，回落 DeepL' };
    throw new Error('没有可用的翻译服务：请在扩展设置里填入至少一个密钥');
  }

  if (keys.deepl && deeplOK) return { kind: 'deepl', reason: 'DeepL' };
  if (keys.google) return { kind: 'google', reason: 'Google' };
  if (keys.groq) return { kind: 'groq', reason: '机翻未配置，回落 Groq' };
  if (keys.llm) return { kind: 'llm', reason: '机翻未配置，回落大模型' };
  throw new Error('没有可用的翻译服务：请在扩展设置里填入至少一个密钥');
}

/**
 * @param {Object} req
 * @param {string} req.text
 * @param {string|null} req.source
 * @param {string} req.target
 * @param {'business'|'casual'|'literal'} req.style
 * @param {'mt'|'llm'} req.route
 * @param {Keys} keys
 * @returns {Promise<ProviderResult>}
 */
export async function translate({ text, source, target, style, route }, keys) {
  const { kind, reason } = pickProvider(route, target, keys);
  const started = performance.now();

  switch (kind) {
    case 'google': {
      const result = await googleTranslate(text, source, target, keys.google);
      return { ...result, provider: `Google（${reason}）`, latency: performance.now() - started };
    }
    case 'deepl': {
      const result = await deeplTranslate(text, source, target, keys.deepl, keys.deeplEndpoint);
      return { ...result, provider: `DeepL（${reason}）`, latency: performance.now() - started };
    }
    case 'groq': {
      const result = await llmTranslate(text, target, style, {
        key: keys.groq,
        baseURL: 'https://api.groq.com/openai/v1',
        model: keys.groqModel || 'llama-3.3-70b-versatile',
        label: 'Groq'
      });
      return { ...result, provider: `Groq（${reason}）`, latency: performance.now() - started };
    }
    case 'llm':
    default: {
      const result = await llmTranslate(text, target, style, {
        key: keys.llm,
        baseURL: keys.llmBaseURL || 'https://api.deepseek.com/v1',
        model: keys.llmModel || 'deepseek-chat',
        label: '大模型'
      });
      return { ...result, provider: `${result.providerLabel || '大模型'}（${reason}）`, latency: performance.now() - started };
    }
  }
}

/** @param {string} text @param {string|null} source @param {string} target @param {string} key */
async function googleTranslate(text, source, target, key) {
  const url = `https://translation.googleapis.com/language/translate/v2?key=${encodeURIComponent(key)}`;
  const body = { q: [text], target, format: 'text' };
  if (source) body.source = source;

  const { signal, done } = await withTimeout(TIMEOUT_MS.mt);
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal
    });
    const data = await res.json();
    if (!res.ok) throw new Error(`Google ${res.status}：${data?.error?.message ?? '请求失败'}`);
    const first = data?.data?.translations?.[0];
    if (!first?.translatedText) throw new Error('Google 返回空结果');
    return {
      text: unescapeHTML(first.translatedText),
      detectedSource: first.detectedSourceLanguage ?? null
    };
  } finally {
    done();
  }
}

/** @param {string} text @param {string|null} source @param {string} target @param {string} key @param {string} [endpoint] */
async function deeplTranslate(text, source, target, key, endpoint) {
  const params = new URLSearchParams();
  params.set('text', text);
  params.set('target_lang', toDeepLCode(target));
  if (source) params.set('source_lang', toDeepLCode(source));

  const { signal, done } = await withTimeout(TIMEOUT_MS.mt);
  try {
    const res = await fetch(endpoint || 'https://api-free.deepl.com/v2/translate', {
      method: 'POST',
      headers: {
        Authorization: `DeepL-Auth-Key ${key}`,
        'Content-Type': 'application/x-www-form-urlencoded'
      },
      body: params.toString(),
      signal
    });
    const data = await res.json();
    if (!res.ok) throw new Error(`DeepL ${res.status}：${data?.message ?? '请求失败'}`);
    const first = data?.translations?.[0];
    if (!first?.text) throw new Error('DeepL 返回空结果');
    return { text: first.text, detectedSource: (first.detected_source_language ?? '').toLowerCase() || null };
  } finally {
    done();
  }
}

/** @param {string} code */
function toDeepLCode(code) {
  if (code === 'zh-Hans') return 'ZH-HANS';
  if (code === 'zh-Hant') return 'ZH-HANT';
  return code.toUpperCase();
}

/** @param {string} text @param {string} target @param {string} style */
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
function languageName(code) {
  const map = {
    en: '英语', es: '西班牙语', pt: '葡萄牙语', fr: '法语', de: '德语', it: '意大利语',
    nl: '荷兰语', ru: '俄语', ar: '阿拉伯语', tr: '土耳其语', vi: '越南语', th: '泰语',
    id: '印尼语', ms: '马来语', hi: '印地语', ja: '日语', ko: '韩语', pl: '波兰语',
    he: '希伯来语', fil: '菲律宾语', 'zh-Hans': '简体中文', 'zh-Hant': '繁体中文'
  };
  return map[code] ?? code;
}

/** @param {Object} args @returns {Promise<{text: string, detectedSource: null, providerLabel: string}>} */
async function llmTranslate(text, target, style, { key, baseURL, model, label }) {
  const messages = await buildPrompt(text, target, style);
  const { signal, done } = await withTimeout(TIMEOUT_MS.llm);
  try {
    const res = await fetch(`${baseURL.replace(/\/$/, '')}/chat/completions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${key}`
      },
      body: JSON.stringify({ model, temperature: 0.2, max_tokens: 1024, messages }),
      signal
    });
    const data = await res.json();
    if (!res.ok) throw new Error(`${label} ${res.status}：${data?.error?.message ?? '请求失败'}`);
    const content = data?.choices?.[0]?.message?.content?.trim();
    if (!content) throw new Error(`${label} 返回空结果`);
    return { text: content, detectedSource: null, providerLabel: label };
  } finally {
    done();
  }
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
