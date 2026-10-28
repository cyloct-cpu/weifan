/**
 * 语种表与本地语言检测。
 * 与 Swift 版 core/Sources/WeiFanCore/{Language,LanguageDetector}.swift 行为一致，
 * 改逻辑必须两边同改（断言集：core/Scripts/main.swift ↔ tests/selftest.mjs）。
 */

/** @typedef {{name: string, short: string, deepL: boolean, whisper?: string}} LangMeta */

/** @type {Record<string, LangMeta>} */
export const LANGUAGES = {
  'zh-Hans': { name: '简体中文', short: '中', deepL: true },
  'zh-Hant': { name: '繁体中文', short: '繁', deepL: true },
  en: { name: '英语', short: 'EN', deepL: true, whisper: 'en' },
  es: { name: '西班牙语', short: 'ES', deepL: true, whisper: 'es' },
  pt: { name: '葡萄牙语', short: 'PT', deepL: true, whisper: 'pt' },
  fr: { name: '法语', short: 'FR', deepL: true, whisper: 'fr' },
  de: { name: '德语', short: 'DE', deepL: true, whisper: 'de' },
  it: { name: '意大利语', short: 'IT', deepL: true, whisper: 'it' },
  nl: { name: '荷兰语', short: 'NL', deepL: true, whisper: 'nl' },
  ru: { name: '俄语', short: 'RU', deepL: true, whisper: 'ru' },
  ar: { name: '阿拉伯语', short: 'AR', deepL: true, whisper: 'ar' },
  tr: { name: '土耳其语', short: 'TR', deepL: true, whisper: 'tr' },
  vi: { name: '越南语', short: 'VI', deepL: false, whisper: 'vi' },
  th: { name: '泰语', short: 'TH', deepL: false, whisper: 'th' },
  id: { name: '印尼语', short: 'ID', deepL: true, whisper: 'id' },
  ms: { name: '马来语', short: 'MS', deepL: false, whisper: 'ms' },
  hi: { name: '印地语', short: 'HI', deepL: false, whisper: 'hi' },
  ja: { name: '日语', short: 'JA', deepL: true, whisper: 'ja' },
  ko: { name: '韩语', short: 'KO', deepL: true, whisper: 'ko' },
  pl: { name: '波兰语', short: 'PL', deepL: true, whisper: 'pl' },
  he: { name: '希伯来语', short: 'HE', deepL: false, whisper: 'he' },
  fil: { name: '菲律宾语', short: 'FIL', deepL: false, whisper: 'tl' }
};

/** 可作为发件目标的语言（去掉中文） */
export const SEND_TARGETS = Object.keys(LANGUAGES).filter((code) => !isChinese(code));

/** @param {string} code */
export function isChinese(code) {
  return code === 'zh-Hans' || code === 'zh-Hant';
}

// MARK: - 文字系统判定

/**
 * @param {number} cp
 * @returns {'latin'|'han'|'kana'|'hangul'|'arabic'|'cyrillic'|'devanagari'|'thai'|'hebrew'|null}
 */
function scriptOf(cp) {
  if ((cp >= 0x41 && cp <= 0x24f) || (cp >= 0x1e00 && cp <= 0x1eff)) return 'latin';
  if ((cp >= 0x4e00 && cp <= 0x9fff) || (cp >= 0x3400 && cp <= 0x4dbf) || (cp >= 0xf900 && cp <= 0xfaff)) return 'han';
  if (cp >= 0x3040 && cp <= 0x30ff) return 'kana';
  if ((cp >= 0xac00 && cp <= 0xd7af) || (cp >= 0x1100 && cp <= 0x11ff)) return 'hangul';
  if ((cp >= 0x600 && cp <= 0x6ff) || (cp >= 0x750 && cp <= 0x77f) || (cp >= 0xfb50 && cp <= 0xfdff)) return 'arabic';
  if (cp >= 0x400 && cp <= 0x4ff) return 'cyrillic';
  if (cp >= 0x900 && cp <= 0x97f) return 'devanagari';
  if (cp >= 0xe00 && cp <= 0xe7f) return 'thai';
  if (cp >= 0x590 && cp <= 0x5ff) return 'hebrew';
  return null;
}

const SCRIPT_TO_LANG = {
  arabic: 'ar',
  cyrillic: 'ru',
  devanagari: 'hi',
  thai: 'th',
  hebrew: 'he',
  hangul: 'ko',
  kana: 'ja'
};

const TRAD_ONLY = new Set([...'這個們麼說時間發貨價錢單還讓對請謝']);
const SIMP_ONLY = new Set([...'这们么说时间发货价钱单还让对请谢']);

/** @param {string} text */
function chineseVariant(text) {
  let t = 0;
  let s = 0;
  for (const ch of text) {
    if (TRAD_ONLY.has(ch)) t += 1;
    if (SIMP_ONLY.has(ch)) s += 1;
  }
  return t > s ? 'zh-Hant' : 'zh-Hans';
}

// MARK: - 拉丁语系打分表

/** @type {Record<string, {words: string[], markers: Record<string, number>}>} */
const LATIN_LEXICON = {
  en: {
    words: ['the', 'and', 'you', 'for', 'with', 'this', 'that', 'have', 'will', 'can', 'please', 'hello', 'thanks', 'when', 'what', 'how', 'ship', 'order', 'price', 'yes', 'not', 'are', 'is', 'to', 'of', 'it', 'we', 'my', 'your', 'good', 'morning', 'dear', 'shipment', 'payment'],
    markers: {}
  },
  es: {
    words: ['el', 'la', 'los', 'las', 'que', 'de', 'en', 'para', 'por', 'con', 'hola', 'gracias', 'cuando', 'puedes', 'pedido', 'precio', 'muy', 'pero', 'usted', 'nosotros', 'buenos', 'dias', 'si', 'no', 'es', 'un', 'una', 'enviar', 'pago'],
    markers: { 'ñ': 2, '¿': 3, '¡': 3, 'ción': 1 }
  },
  pt: {
    words: ['o', 'a', 'os', 'as', 'que', 'de', 'em', 'para', 'por', 'com', 'olá', 'obrigado', 'obrigada', 'quando', 'você', 'pedido', 'preço', 'muito', 'mas', 'não', 'sim', 'é', 'um', 'uma', 'enviar', 'bom', 'dia', 'pagamento'],
    markers: { 'ã': 2, 'õ': 2, 'ç': 1, 'ção': 2, 'não': 2 }
  },
  fr: {
    words: ['le', 'la', 'les', 'des', 'que', 'de', 'en', 'pour', 'par', 'avec', 'bonjour', 'merci', 'quand', 'vous', 'commande', 'prix', 'très', 'mais', 'oui', 'non', 'est', 'un', 'une', 'nous', 'pouvez', 'paiement'],
    markers: { 'è': 1, 'ê': 1, 'ç': 1, 'œ': 2, 'à': 1, 'ù': 2, "qu'": 2 }
  },
  de: {
    words: ['der', 'die', 'das', 'und', 'sie', 'für', 'mit', 'ist', 'nicht', 'hallo', 'danke', 'wann', 'können', 'bestellung', 'preis', 'sehr', 'aber', 'ja', 'nein', 'wir', 'ein', 'eine', 'bitte', 'guten', 'tag', 'zahlung'],
    markers: { 'ß': 3, 'ä': 1, 'ö': 1, 'ü': 1 }
  },
  it: {
    words: ['il', 'lo', 'la', 'gli', 'le', 'che', 'di', 'in', 'per', 'con', 'ciao', 'grazie', 'quando', 'puoi', 'ordine', 'prezzo', 'molto', 'ma', 'sì', 'no', 'è', 'un', 'una', 'noi', 'buongiorno', 'pagamento'],
    markers: { 'ì': 2, 'ò': 1, 'à': 1 }
  },
  nl: {
    words: ['de', 'het', 'een', 'en', 'van', 'voor', 'met', 'dat', 'niet', 'hallo', 'dank', 'wanneer', 'kunnen', 'bestelling', 'prijs', 'zeer', 'maar', 'ja', 'nee', 'wij', 'is', 'ik', 'jij'],
    markers: { ij: 2, aa: 1, oe: 1 }
  },
  tr: {
    words: ['ve', 'bir', 'bu', 'için', 'ile', 'merhaba', 'teşekkür', 'zaman', 'sipariş', 'fiyat', 'çok', 'ama', 'evet', 'hayır', 'değil', 'biz', 'siz'],
    markers: { 'ğ': 3, 'ş': 2, 'ı': 2 }
  },
  vi: {
    words: ['và', 'của', 'cho', 'với', 'chào', 'cảm', 'ơn', 'khi', 'nào', 'đơn', 'hàng', 'giá', 'rất', 'nhưng', 'có', 'không', 'chúng', 'tôi', 'bạn'],
    markers: { 'ạ': 2, 'ế': 1, 'ộ': 2, 'đ': 3, 'ơ': 1, 'ư': 2 }
  },
  id: {
    words: ['dan', 'yang', 'untuk', 'dengan', 'ini', 'itu', 'halo', 'terima', 'kasih', 'kapan', 'pesanan', 'harga', 'sangat', 'tapi', 'ya', 'tidak', 'kami', 'anda'],
    markers: {}
  },
  ms: {
    words: ['dan', 'yang', 'untuk', 'dengan', 'ini', 'itu', 'hai', 'terima', 'kasih', 'bila', 'pesanan', 'harga', 'sangat', 'tetapi', 'ya', 'tidak', 'kami', 'awak'],
    markers: {}
  },
  pl: {
    words: ['i', 'w', 'na', 'do', 'z', 'że', 'nie', 'jest', 'cześć', 'dziękuję', 'kiedy', 'zamówienie', 'cena', 'bardzo', 'ale', 'tak', 'my', 'wy'],
    markers: { 'ł': 2, 'ą': 2, 'ę': 2, 'ż': 2, 'ś': 1, 'ć': 1 }
  },
  fil: {
    words: ['ang', 'ng', 'sa', 'na', 'at', 'kumusta', 'salamat', 'kailan', 'order', 'presyo', 'sobrang', 'pero', 'oo', 'hindi', 'kami', 'ikaw'],
    markers: {}
  }
};

/**
 * @typedef {{language: string, confidence: number, basis: 'script'|'lexicon', runnerUp?: string}} Detection
 */

/**
 * 检测文本语言。
 * @param {string} text
 * @param {string[]} [candidates] 限定候选（默认全部发件目标语言）
 * @returns {Detection|null} 无法判断时返回 null（短句、纯数字、纯表情）
 */
export function detectLanguage(text, candidates = SEND_TARGETS) {
  const trimmed = (text ?? '').trim();
  if (!trimmed) return null;

  const byScript = detectByScript(trimmed);
  if (byScript) {
    if (!candidates.includes(byScript.language) && !isChinese(byScript.language)) return null;
    return byScript;
  }
  return detectLatin(trimmed, candidates);
}

/** @param {string} text @returns {Detection|null} */
function detectByScript(text) {
  /** @type {Record<string, number>} */
  const counts = {};
  for (const ch of text) {
    const cp = ch.codePointAt(0) ?? 0;
    const script = scriptOf(cp);
    if (script) counts[script] = (counts[script] ?? 0) + 1;
  }
  const entries = Object.entries(counts).sort((a, b) => b[1] - a[1]);
  if (!entries.length) return null;
  const dominant = entries[0][0];

  if (dominant === 'han') {
    return { language: chineseVariant(text), confidence: 0.9, basis: 'script' };
  }
  const mapped = SCRIPT_TO_LANG[dominant];
  if (!mapped) return null;
  return { language: mapped, confidence: dominant === 'cyrillic' ? 0.85 : 0.95, basis: 'script' };
}

/** @param {string} text @param {string[]} candidates @returns {Detection|null} */
function detectLatin(text, candidates) {
  const lowered = text.toLowerCase();
  const tokens = lowered.split(/[^\p{L}]+/u).filter(Boolean);
  if (tokens.length < 2) return null;

  const tokenSet = new Set(tokens);
  /** @type {Record<string, number>} */
  const scores = {};

  for (const code of candidates) {
    const lex = LATIN_LEXICON[code];
    if (!lex) continue;
    let score = 0;
    for (const token of tokenSet) if (lex.words.includes(token)) score += 1;
    for (const [marker, weight] of Object.entries(lex.markers)) {
      if (lowered.includes(marker)) score += weight;
    }
    if (score > 0) scores[code] = score;
  }

  const ranked = Object.entries(scores).sort((a, b) => b[1] - a[1]);
  if (!ranked.length) return null;

  const total = ranked.reduce((sum, [, v]) => sum + v, 0);
  const [bestCode, bestScore] = ranked[0];
  const runnerUp = ranked[1]?.[0];

  return {
    language: bestCode,
    confidence: Math.min(0.99, (bestScore / Math.max(total, 1)) * 0.9 + 0.1),
    basis: 'lexicon',
    runnerUp
  };
}
