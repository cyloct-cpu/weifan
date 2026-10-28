/**
 * 混合路由 + 发送前风险闸门。
 * 对应 Swift 版 core/Sources/WeiFanCore/Routing.swift。
 *
 * 核心判断：字符少 + 无数字 → 机翻足够；出现金额/数量/日期/长句 → 大模型。
 */

/** @typedef {'mt'|'llm'} Route */
/** @typedef {'business'|'casual'|'literal'} Style */

export const RISK_LABELS = {
  containsMoney: '含金额',
  containsNumber: '含数字',
  containsDate: '含日期',
  glossaryHit: '命中专有名词',
  longSentence: '长句',
  lowDetectionConfidence: '语言判断不确定',
  switchedLanguage: '对方语言变了'
};

const MONEY_RE = /(\d[\d,.]*\s*(usd|rmb|cny|eur|dollars?|yuan|元|美元|欧元|美金|万|千|k\b|pcs|件|箱|吨))|([$￥€£]\s*\d)/i;
const DATE_RE = /(\d{1,4}[-/年.]\d{1,2}[-/月.]\d{1,2})|(\d{1,2}\s*(月|日|号))|(next\s+(week|month|monday|tuesday|wednesday|thursday|friday))|(下\s*(周|月|星期))/i;
const NUMBER_RE = /\d/;

/** 文本特征（纯正则、零网络） */
export const signals = {
  /** @param {string} t */
  hasNumber: (t) => NUMBER_RE.test(t),
  /** @param {string} t */
  hasMoney: (t) => MONEY_RE.test(t),
  /** @param {string} t */
  hasDate: (t) => DATE_RE.test(t)
};

/**
 * @typedef {Object} RoutingPolicy
 * @property {number} longTextThreshold
 * @property {number} businessThreshold
 * @property {boolean} escalateOnNumbers
 */

/** @type {RoutingPolicy} */
export const DEFAULT_POLICY = {
  longTextThreshold: 60,
  businessThreshold: 30,
  escalateOnNumbers: true
};

/**
 * @param {string} text
 * @param {Style} [style]
 * @param {RoutingPolicy} [policy]
 * @returns {{route: Route, reasons: string[]}}
 */
export function route(text, style = 'business', policy = DEFAULT_POLICY) {
  const length = [...text].length;
  /** @type {string[]} */
  const reasons = [];

  if (length > policy.longTextThreshold) {
    reasons.push(`长度 ${length} > ${policy.longTextThreshold}`);
    return { route: 'llm', reasons };
  }
  if (policy.escalateOnNumbers && signals.hasNumber(text)) {
    reasons.push('含数字/金额，机翻易出错');
    return { route: 'llm', reasons };
  }
  if (style === 'business' && length > policy.businessThreshold) {
    reasons.push('商务长句');
    return { route: 'llm', reasons };
  }
  if (style === 'literal') {
    reasons.push('单据直译');
    return { route: 'llm', reasons };
  }
  reasons.push('短句无数字，机翻足够');
  return { route: 'mt', reasons };
}

/**
 * 决定"直接替换"还是"先给用户看一眼"。
 * @param {Object} input
 * @param {string} input.text
 * @param {import('./language.js').Detection|null} input.detection
 * @param {string[]} [input.glossaryHits]
 * @param {string|null} [input.rememberedLanguage]
 * @param {boolean} [input.allowDirectSend]
 * @param {string[]} [input.hardBlock] 直发模式下仍然拦截的原因
 * @param {number} [input.longSentenceThreshold]
 * @returns {{shouldPreview: boolean, reasons: string[]}}
 */
export function assessRisk({
  text,
  detection,
  glossaryHits = [],
  rememberedLanguage = null,
  allowDirectSend = true,
  hardBlock = ['containsMoney'],
  longSentenceThreshold = 60
}) {
  /** @type {string[]} */
  let reasons = [];

  if (signals.hasMoney(text)) reasons.push('containsMoney');
  else if (signals.hasNumber(text)) reasons.push('containsNumber');
  if (signals.hasDate(text)) reasons.push('containsDate');
  if (glossaryHits.length) reasons.push('glossaryHit');
  if ([...text].length > longSentenceThreshold) reasons.push('longSentence');
  if (detection && detection.confidence < 0.45) reasons.push('lowDetectionConfidence');
  if (rememberedLanguage && detection && detection.language !== rememberedLanguage && detection.confidence > 0.6) {
    reasons.push('switchedLanguage');
  }

  if (allowDirectSend) {
    reasons = reasons.filter((r) => hardBlock.includes(r));
  }

  return { shouldPreview: reasons.length > 0, reasons };
}
