/**
 * 内核门面：所有"翻译判断逻辑"的唯一入口。
 * 对应 Swift 版 core/Sources/WeiFanCore/TranslationCore.swift。
 *
 * 发件管道顺序不可调换：
 *   定语言 → 术语保护 → 路由 → 翻译 → 还原 → 风险判定 → 落地
 * 其中"落地"（替换输入框）由 content script 做，内核只负责判断。
 */

import { detectLanguage, LANGUAGES, SEND_TARGETS, isChinese } from './language.js';
import { protect, restore } from './glossary.js';
import { route as pickRoute, assessRisk } from './routing.js';
import { translate } from './providers.js';

/**
 * @typedef {Object} Settings
 * @property {string} defaultTarget        兜底语言
 * @property {boolean} allowDirectSend     点译即替换
 * @property {string[]} hardBlock          直发模式下仍拦截的原因
 * @property {number} longSentenceThreshold
 * @property {boolean} previewOnLowConfidence
 * @property {'business'|'casual'|'literal'} style
 * @property {import('./glossary.js').GlossaryEntry[]} glossary
 * @property {number} autoTranslateMaxChars
 * @property {string} selection             用户选的服务商 id，'auto' = 自动优选
 * @property {'free'|'custom'} mode         免费额度池 / 自定义 API
 * @property {number} dailyCharLimit        单个服务商每日字符上限（0 = 不限）
 * @property {boolean} compact              简洁模式
 * @property {boolean} hoverTranslate       鼠标悬停翻译
 * @property {boolean} selectionTranslate   划词翻译
 */

/** @type {Settings} */
export const DEFAULT_SETTINGS = {
  defaultTarget: 'en',
  allowDirectSend: true,
  hardBlock: ['containsMoney'],
  longSentenceThreshold: 60,
  previewOnLowConfidence: true,
  style: 'business',
  glossary: [],
  autoTranslateMaxChars: 1500,
  selection: 'auto',
  mode: 'free',
  dailyCharLimit: 0,
  compact: false,
  hoverTranslate: false,
  selectionTranslate: true
};

/**
 * @typedef {Object} SendOutcome
 * @property {string} translated
 * @property {string} target
 * @property {'mt'|'llm'} route
 * @property {string} provider
 * @property {{shouldPreview: boolean, reasons: string[]}} risk
 * @property {string[]} glossaryHits
 * @property {number} latency
 * @property {'manual'|'detected'|'profile'|'fallback'} languageSource
 */

/**
 * 发件：中文 → 对方语言
 * @param {string} text
 * @param {Object} [opts]
 * @param {string|null} [opts.explicitTarget] 手动锁定的目标语言
 * @param {string|null} [opts.chatLanguage] 会话记忆的语言
 * @param {import('./glossary.js').GlossaryEntry[]} [opts.extraGlossary] 客户档案专属术语
 * @param {string} [opts.contextSample] 用于检测的对方消息样本
 * @param {Settings} [opts.settings]
 * @param {import('./providers.js').Keys} [opts.keys]
 * @returns {Promise<SendOutcome>}
 */
export async function translateForSending(text, opts = {}) {
  const settings = { ...DEFAULT_SETTINGS, ...(opts.settings ?? {}) };
  const keys = opts.keys ?? {};
  const started = performance.now();

  // ① 定语言：手动 > 内容检测 > 会话记忆 > 兜底
  const detection = opts.contextSample ? detectLanguage(opts.contextSample, SEND_TARGETS) : null;
  const resolved = resolveTarget(opts.explicitTarget, detection, opts.chatLanguage, settings);

  // ② 术语保护
  const entries = [...(settings.glossary ?? []), ...(opts.extraGlossary ?? [])];
  const protectedText = protect(text, entries, resolved.target);

  // ③ 路由
  const style = opts.style ?? settings.style;
  const { route } = pickRoute(protectedText.masked, style, {
    longTextThreshold: settings.longSentenceThreshold,
    businessThreshold: Math.max(20, Math.round(settings.longSentenceThreshold / 2)),
    escalateOnNumbers: true
  });

  // ④ 翻译
  const result = await translate(
    { text: protectedText.masked, source: 'zh-Hans', target: resolved.target, style, route, keys },
    { selection: settings.selection, mode: settings.mode, blocked: opts.blocked }
  );

  // ⑤ 还原
  const translated = restore(result.text, protectedText.units);

  // ⑥ 风险判定
  const risk = assessRisk({
    text,
    detection,
    glossaryHits: protectedText.hits,
    rememberedLanguage: opts.chatLanguage ?? null,
    allowDirectSend: settings.allowDirectSend,
    hardBlock: settings.hardBlock,
    longSentenceThreshold: settings.longSentenceThreshold
  });

  return {
    translated,
    target: resolved.target,
    route: result.route ?? route,
    provider: result.provider,
    risk,
    glossaryHits: protectedText.hits,
    latency: Math.round(result.latency ?? performance.now() - started),
    languageSource: resolved.source
  };
}

/**
 * 收件：对方语言 → 简体中文
 * @param {string} text
 * @param {Object} [opts]
 * @param {Settings} [opts.settings]
 * @param {import('./providers.js').Keys} [opts.keys]
 * @returns {Promise<{translated: string, sourceLanguage: string|null, provider: string, alreadyChinese: boolean, latency: number}>}
 */
export async function translateIncoming(text, opts = {}) {
  const settings = { ...DEFAULT_SETTINGS, ...(opts.settings ?? {}) };
  const started = performance.now();
  const detection = detectLanguage(text);

  if (detection && isChinese(detection.language)) {
    return {
      translated: text,
      sourceLanguage: detection.language,
      provider: 'none',
      alreadyChinese: true,
      latency: Math.round(performance.now() - started)
    };
  }

  const source = detection?.language ?? null;
  const { route } = pickRoute(text, 'casual', {
    longTextThreshold: settings.longSentenceThreshold,
    businessThreshold: 30,
    escalateOnNumbers: true
  });

  const result = await translate(
    { text, source, target: 'zh-Hans', style: 'casual', route, keys: opts.keys ?? {} },
    {
      selection: settings.selection,
      mode: settings.mode,
      blocked: opts.blocked
    }
  );

  return {
    translated: result.text,
    sourceLanguage: source ?? result.detectedSource,
    provider: result.provider,
    alreadyChinese: false,
    latency: Math.round(performance.now() - started)
  };
}

/**
 * 目标语言解析顺序。
 * @param {string|null|undefined} explicit
 * @param {import('./language.js').Detection|null} detection
 * @param {string|null|undefined} chatLanguage
 * @param {Settings} settings
 * @returns {{target: string, source: 'manual'|'detected'|'profile'|'fallback'}}
 */
export function resolveTarget(explicit, detection, chatLanguage, settings) {
  if (explicit) return { target: explicit, source: 'manual' };
  if (detection && detection.confidence >= 0.45 && !isChinese(detection.language)) {
    return { target: detection.language, source: 'detected' };
  }
  if (chatLanguage && LANGUAGES[chatLanguage]) return { target: chatLanguage, source: 'profile' };
  return { target: settings.defaultTarget || 'en', source: 'fallback' };
}
