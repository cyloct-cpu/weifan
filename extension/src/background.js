/**
 * MV3 background service worker：所有网络请求都在这里发。
 *
 * 为什么不在 content script 里直接 fetch：
 * 1. MV3 的 content script 受页面 CORS 约束，打翻译 API 会被拦；
 * 2. 密钥不能暴露在页面上下文里（页面脚本理论上能读到 content script 的变量）。
 */

import { translateForSending, translateIncoming, DEFAULT_SETTINGS } from './core/core.js';
import { LANGUAGES } from './core/language.js';

const STORAGE_KEY = 'weifan';
const CACHE_LIMIT = 300;

/** @type {Map<string, any>} */
const cache = new Map();

/** @typedef {{keys: any, settings: any, chats: Record<string, {language: string, updatedAt: number}>}} Store */

/** @returns {Promise<Store>} */
async function loadStore() {
  const raw = await chrome.storage.local.get(STORAGE_KEY);
  const value = raw?.[STORAGE_KEY] ?? {};
  return {
    keys: value.keys ?? {},
    settings: { ...DEFAULT_SETTINGS, ...(value.settings ?? {}) },
    chats: value.chats ?? {}
  };
}

/** @param {Store} store */
async function saveStore(store) {
  await chrome.storage.local.set({ [STORAGE_KEY]: store });
}

/** @param {string} key @param {any} value */
function cacheSet(key, value) {
  if (cache.size >= CACHE_LIMIT) {
    const oldest = cache.keys().next().value;
    if (oldest !== undefined) cache.delete(oldest);
  }
  cache.set(key, value);
}

/** @param {string} text */
const fingerprint = (text) => text.length + ':' + text.slice(0, 64);

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  handle(message)
    .then((data) => sendResponse({ ok: true, data }))
    .catch((error) => sendResponse({ ok: false, error: error?.message ?? String(error) }));
  return true; // 保持消息通道打开（异步响应）
});

/** @param {any} message */
async function handle(message) {
  const store = await loadStore();

  switch (message?.type) {
    case 'ping':
      return { version: chrome.runtime.getManifest().version };

    case 'getConfig':
      // 只回给 content script 需要的部分，密钥不出 background
      return {
        settings: store.settings,
        languages: LANGUAGES,
        hasKeys: Boolean(store.keys.google || store.keys.deepl || store.keys.groq || store.keys.llm)
      };

    case 'incoming': {
      const text = String(message.text ?? '').trim();
      if (!text) throw new Error('空文本');
      const key = `in|${fingerprint(text)}`;
      if (cache.has(key)) return cache.get(key);

      const result = await translateIncoming(text, { settings: store.settings, keys: store.keys });
      cacheSet(key, result);
      return result;
    }

    case 'sending': {
      const text = String(message.text ?? '').trim();
      if (!text) throw new Error('空文本');
      const chatId = String(message.chatId ?? '');
      const chat = store.chats[chatId];
      const key = `out|${chatId}|${message.explicitTarget ?? ''}|${fingerprint(text)}`;
      if (cache.has(key)) return cache.get(key);

      const result = await translateForSending(text, {
        explicitTarget: message.explicitTarget ?? null,
        chatLanguage: chat?.language ?? null,
        contextSample: message.contextSample ?? null,
        settings: store.settings,
        keys: store.keys
      });
      cacheSet(key, result);

      // 检测到的语言回写为会话记忆，下次进这个会话直接可用
      if (result.languageSource === 'detected' && chatId) {
        store.chats[chatId] = { language: result.target, updatedAt: Date.now() };
        await saveStore(store);
      }
      return result;
    }

    case 'setChatLanguage': {
      const chatId = String(message.chatId ?? '');
      if (!chatId) throw new Error('缺少 chatId');
      if (message.language) {
        store.chats[chatId] = { language: message.language, updatedAt: Date.now() };
      } else {
        delete store.chats[chatId];
      }
      await saveStore(store);
      return { chatId, language: message.language ?? null };
    }

    case 'getChatLanguage':
      return { language: store.chats[String(message.chatId ?? '')]?.language ?? null };

    case 'clearCache':
      cache.clear();
      return { cleared: true };

    default:
      throw new Error(`未知消息类型：${String(message?.type)}`);
  }
}

// 首次安装时写入默认设置，避免 content script 读到 undefined
chrome.runtime.onInstalled.addListener(async () => {
  const store = await loadStore();
  await saveStore(store);
});

// 点扩展图标直接打开设置页（没有 popup，减少一次点击）
chrome.action.onClicked.addListener(() => {
  chrome.runtime.openOptionsPage();
});
