/**
 * 译文朗读：目标语言代码 → BCP-47 朗读标签（纯映射，无 DOM、无网络）。
 *
 * 为什么做成「挂 globalThis 的普通脚本」而不是 ES 模块：
 * - content script 不支持模块（pageagent.js 是经典脚本），它从 globalThis.WeifanTts 取；
 * - 文本翻译页（模块）用 `import '../core/tts.js'` 副作用引入，同样从 globalThis 取；
 * - 自测（tests/selftest.mjs）import 本文件后校验映射 —— 一份实现三处用。
 *
 * Android 同规格实现：android/app/src/main/java/com/weifan/app/core/Tts.kt —— **改映射两边同改**。
 */
(function (root) {
  'use strict';

  /** 语言代码 → BCP-47 朗读标签（与 Tts.kt 同表） */
  const TAGS = {
    'zh-Hans': 'zh-CN',
    'zh-Hant': 'zh-TW',
    en: 'en-US',
    es: 'es-ES',
    pt: 'pt-PT',
    fr: 'fr-FR',
    de: 'de-DE',
    it: 'it-IT',
    nl: 'nl-NL',
    ru: 'ru-RU',
    ar: 'ar-SA',
    tr: 'tr-TR',
    vi: 'vi-VN',
    th: 'th-TH',
    id: 'id-ID',
    ms: 'ms-MY',
    hi: 'hi-IN',
    ja: 'ja-JP',
    ko: 'ko-KR',
    pl: 'pl-PL',
    he: 'he-IL',
    fil: 'fil-PH'
  };

  /**
   * 目标语言代码 → 朗读标签；没有映射返回 null，调用方把朗读按钮置灰。
   * @param {string} code
   * @returns {string|null}
   */
  function ttsTag(code) {
    const key = String(code ?? '').trim();
    return Object.prototype.hasOwnProperty.call(TAGS, key) ? TAGS[key] : null;
  }

  root.WeifanTts = { TAGS, ttsTag };
})(globalThis);
