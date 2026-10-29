/**
 * 各服务商的语种代码映射。
 *
 * 我们内部统一用 LANGUAGES 里的代码（zh-Hans / en / fil …），
 * 每家平台的代码体系都不一样，翻译前必须过一次映射。
 *
 * 不在表里的语言 = 该平台不支持 → 降级链会跳过它。
 */

/** 百度：繁体是 cht，日文 jp，韩文 kor，法语 fra，西语 spa，阿拉伯 ara，越南 vie */
const BAIDU = {
  'zh-Hans': 'zh', 'zh-Hant': 'cht', en: 'en', es: 'spa', pt: 'pt', fr: 'fra', de: 'de', it: 'it',
  ru: 'ru', ar: 'ara', tr: 'tr', vi: 'vie', th: 'th', ja: 'jp', ko: 'kor'
};

/** 腾讯云：源语言可 auto；不支持繁体 */
const TENCENT = {
  'zh-Hans': 'zh', en: 'en', es: 'es', pt: 'pt', fr: 'fr', de: 'de', it: 'it', ru: 'ru', ar: 'ar',
  tr: 'tr', vi: 'vi', th: 'th', id: 'id', ms: 'ms', ja: 'ja', ko: 'ko'
};

/** Google：中文要 zh-CN / zh-TW，他加禄语是 tl */
const GOOGLE = {
  'zh-Hans': 'zh-CN', 'zh-Hant': 'zh-TW', en: 'en', es: 'es', pt: 'pt', fr: 'fr', de: 'de', it: 'it',
  nl: 'nl', ru: 'ru', ar: 'ar', tr: 'tr', vi: 'vi', th: 'th', id: 'id', ms: 'ms', hi: 'hi',
  ja: 'ja', ko: 'ko', pl: 'pl', he: 'he', fil: 'tl'
};

/** DeepL：全大写 */
const DEEPL = {
  'zh-Hans': 'ZH-HANS', 'zh-Hant': 'ZH-HANT', en: 'EN', es: 'ES', pt: 'PT', fr: 'FR', de: 'DE',
  it: 'IT', nl: 'NL', ru: 'RU', ar: 'AR', tr: 'TR', id: 'ID', ja: 'JA', ko: 'KO', pl: 'PL'
};

/** 火山引擎 */
const VOLC = {
  'zh-Hans': 'zh', en: 'en', es: 'es', pt: 'pt', fr: 'fr', de: 'de', it: 'it', ru: 'ru', ar: 'ar',
  vi: 'vi', th: 'th', ms: 'ms', id: 'id', tr: 'tr', ja: 'ja', ko: 'ko'
};

/** 阿里云 */
const ALIYUN = {
  'zh-Hans': 'zh', en: 'en', es: 'es', pt: 'pt', fr: 'fr', de: 'de', it: 'it', ru: 'ru',
  vi: 'vi', th: 'th', ms: 'ms', id: 'id', tr: 'tr', ja: 'ja', ko: 'ko'
};

/** Azure 微软翻译：与 Google 同体系，中文用 zh-Hans / zh-Hant，他加禄语是 tl */
const AZURE = {
  'zh-Hans': 'zh-Hans', 'zh-Hant': 'zh-Hant', en: 'en', es: 'es', pt: 'pt', fr: 'fr', de: 'de',
  it: 'it', nl: 'nl', ru: 'ru', ar: 'ar', tr: 'tr', vi: 'vi', th: 'th', id: 'id', ms: 'ms',
  hi: 'hi', ja: 'ja', ko: 'ko', pl: 'pl', he: 'he', fil: 'tl'
};

/** Yandex Cloud：中文只有 zh（不分简繁），他加禄语是 tl */
const YANDEX = {
  'zh-Hans': 'zh', en: 'en', es: 'es', pt: 'pt', fr: 'fr', de: 'de', it: 'it', nl: 'nl',
  ru: 'ru', ar: 'ar', tr: 'tr', vi: 'vi', th: 'th', id: 'id', ms: 'ms', hi: 'hi',
  ja: 'ja', ko: 'ko', pl: 'pl', he: 'he', fil: 'tl'
};

/** @type {Record<string, Record<string,string>>} */
export const PROVIDER_LANG_CODES = {
  baidu: BAIDU,
  tencent: TENCENT,
  google: GOOGLE,
  deepl: DEEPL,
  volc: VOLC,
  aliyun: ALIYUN,
  azure: AZURE,
  yandex: YANDEX
};

/**
 * 该平台是否支持目标语言。
 * @param {string} providerId
 * @param {string} code 我们的语言代码
 */
export function supportsTarget(providerId, code) {
  const map = PROVIDER_LANG_CODES[providerId];
  return Boolean(map && map[code]);
}

/**
 * 翻译成目标语言时用的平台代码。
 * @param {string} providerId
 * @param {string} code
 * @returns {string|null} 不支持则 null
 */
export function targetCode(providerId, code) {
  return PROVIDER_LANG_CODES[providerId]?.[code] ?? null;
}

/**
 * 平台返回的语言代码 → 我们的代码（用于「检测到源语言」）。
 * @param {string} providerId
 * @param {string} platformCode
 * @returns {string|null}
 */
export function fromPlatformCode(providerId, platformCode) {
  const map = PROVIDER_LANG_CODES[providerId];
  if (!map || !platformCode) return null;
  const lower = String(platformCode).toLowerCase();
  for (const [ours, theirs] of Object.entries(map)) {
    if (theirs.toLowerCase() === lower) return ours;
  }
  // 百度繁体回 'cht'、DeepL 回 'ZH-HANT' 这类变体
  if (platformCode === 'cht') return 'zh-Hant';
  if (platformCode === 'zh-TW' || platformCode === 'zh-Hant') return 'zh-Hant';
  if (platformCode === 'zh-CN' || platformCode === 'zh') return 'zh-Hans';
  return null;
}
