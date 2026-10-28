/**
 * 术语保护：把"不能乱译"的词替换成占位符，翻译后还原。
 * 对应 Swift 版 core/Sources/WeiFanCore/Glossary.swift。
 *
 * 已知风险（V-03）：部分引擎会吞掉或改写占位符。还原用宽松正则，
 * 还原失败时保留占位符原文（让异常可见），不静默返回错译。
 */

/**
 * @typedef {Object} GlossaryEntry
 * @property {string} term
 * @property {'keepOriginal'|'fixed'} policy
 * @property {Record<string, string>} [translations] 形如 { es: 'depósito' }
 * @property {boolean} [isEnabled]
 */

const TOKEN_OPEN = '⟦';
const TOKEN_CLOSE = '⟧';

/** @param {number} n */
const token = (n) => `${TOKEN_OPEN}${n}${TOKEN_CLOSE}`;

/**
 * @param {string} text
 * @param {GlossaryEntry[]} entries
 * @param {string} target 目标语言代码
 * @returns {{masked: string, units: Record<string, string>, hits: string[]}}
 */
export function protect(text, entries, target) {
  let masked = text;
  /** @type {Record<string, string>} */
  const units = {};
  /** @type {string[]} */
  const hits = [];

  // 长词优先："轻舟 Pro Max" 必须先于 "轻舟" 命中
  const active = (entries ?? [])
    .filter((e) => e && e.isEnabled !== false && (e.term ?? '').trim())
    .sort((a, b) => b.term.length - a.term.length);

  let index = 0;
  for (const entry of active) {
    if (!masked.includes(entry.term)) continue;
    const replacement = entry.policy === 'fixed'
      ? (entry.translations?.[target] ?? entry.term)
      : entry.term;
    const t = token(index);
    masked = masked.split(entry.term).join(t);
    units[t] = replacement;
    hits.push(entry.term);
    index += 1;
  }

  return { masked, units, hits };
}

/**
 * @param {string} translated
 * @param {Record<string, string>} units
 * @returns {string}
 */
export function restore(translated, units) {
  if (!units || Object.keys(units).length === 0) return translated;

  // 容忍：引擎加了空格、换了全角括号、丢了个别括号
  const re = /[⟦\[【]\s*\d+\s*[⟧\]】]/g;
  return translated.replace(re, (match) => {
    const digits = match.replace(/\D/g, '');
    const key = token(Number(digits));
    return units[key] ?? match; // 还原不了就保留，让用户看见异常
  });
}

/**
 * 文本命中了哪些词条（用于风险提示）。
 * @param {string} text
 * @param {GlossaryEntry[]} entries
 * @returns {string[]}
 */
export function glossaryHits(text, entries) {
  return (entries ?? [])
    .filter((e) => e && e.isEnabled !== false && e.term && text.includes(e.term))
    .map((e) => e.term);
}
