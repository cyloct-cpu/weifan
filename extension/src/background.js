/**
 * MV3 background service worker：所有网络请求都在这里发。
 *
 * 为什么不在 content script 里直接 fetch：
 * 1. MV3 的 content script 受页面 CORS 约束，打翻译 API 会被拦；
 * 2. 密钥不能暴露在页面上下文里（页面脚本理论上能读到 content script 的变量）。
 *
 * 后台同时负责：
 * - 发件 / 收件两条管道（WhatsApp 专用，术语保护 + 风险判定）
 * - 通用翻译（文本翻译、划词、悬停、文档翻译共用同一入口）
 * - 每日额度保护：单个服务商当天用超上限就跳过它，别让用户在平台那边被计费
 */

import { translateForSending, translateIncoming, DEFAULT_SETTINGS } from './core/core.js';
import { LANGUAGES } from './core/language.js';
import { PROVIDERS, listProviders, translate, testProvider } from './core/providers.js';
import { EXT } from './shared/ext.js';

const STORAGE_KEY = 'weifan';
const CACHE_LIMIT = 300;

/** @type {Map<string, any>} */
const cache = new Map();

/** @typedef {{keys: any, settings: any, chats: Record<string, {language: string, updatedAt: number}>, usage: Record<string, Record<string, number>>}} Store */

/** @returns {Promise<Store>} */
async function loadStore() {
  const raw = await EXT.storage.local.get(STORAGE_KEY);
  const value = raw?.[STORAGE_KEY] ?? {};
  return {
    keys: value.keys ?? {},
    settings: { ...DEFAULT_SETTINGS, ...(value.settings ?? {}) },
    chats: value.chats ?? {},
    usage: value.usage ?? {}
  };
}

/** @param {Store} store */
async function saveStore(store) {
  await EXT.storage.local.set({ [STORAGE_KEY]: store });
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

/** 今天的日期键（本地时区） */
function today() {
  const now = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/**
 * 今天已超限额的服务商。
 * @param {Store} store
 * @param {number} limit
 */
function blockedProviders(store, limit) {
  if (!limit) return [];
  const used = store.usage[today()] ?? {};
  return Object.keys(used).filter((id) => used[id] >= limit);
}

/**
 * 记一笔用量。
 * @param {Store} store
 * @param {string} providerId
 * @param {number} chars
 * @param {number} limit
 */
async function trackUsage(store, providerId, chars, limit) {
  if (!providerId || !limit) return;
  const day = today();
  store.usage[day] = store.usage[day] ?? {};
  store.usage[day][providerId] = (store.usage[day][providerId] ?? 0) + chars;
  await saveStore(store);
}

/**
 * 通用的翻译入口：文本翻译、划词、悬停、文档翻译都走这里。
 * @param {Store} store
 * @param {Object} req
 */
async function translateText(store, req) {
  const settings = store.settings;
  const style = req.style ?? settings.style;
  const selection = req.selection ?? settings.selection;
  const mode = req.mode ?? settings.mode;
  const blocked = blockedProviders(store, settings.dailyCharLimit);

  const key = `t|${mode}|${selection}|${req.source ?? ''}|${req.target}|${style}|${fingerprint(req.text)}`;
  if (cache.has(key)) return cache.get(key);

  const result = await translate(
    {
      text: req.text,
      source: req.source ?? null,
      target: req.target,
      style,
      route: style === 'business' ? 'llm' : 'mt',
      keys: store.keys
    },
    { selection, mode, blocked }
  );

  cacheSet(key, result);

  // 记用量（按实际使用的服务商算）
  await trackUsage(store, result.providerId, req.text.length, settings.dailyCharLimit);
  if (blockedProviders(store, settings.dailyCharLimit).includes(result.providerId)) {
    result.note = '这家今天已到上限，下次自动换一家';
  }
  return result;
}

EXT.runtime.onMessage.addListener((message, _sender, sendResponse) => {
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
      return { version: EXT.runtime.getManifest().version };

    case 'getConfig': {
      const providers = listProviders(store.keys);
      return {
        settings: store.settings,
        languages: LANGUAGES,
        hasKeys: providers.length > 0,
        providers: providers.map((p) => ({ id: p.id, label: p.label, mode: p.mode, kind: p.kind })),
        catalog: PROVIDERS.map((p) => ({
          id: p.id,
          label: p.label,
          mode: p.mode,
          kind: p.kind,
          freeNote: p.freeNote ?? '',
          docs: p.docs ?? '',
          fields: p.fields ?? []
        }))
      };
    }

    case 'setSettings': {
      const patch = message.patch ?? {};
      store.settings = { ...store.settings, ...patch };
      await saveStore(store);
      return store.settings;
    }

    case 'usage':
      return { day: today(), usage: store.usage[today()] ?? {}, limit: store.settings.dailyCharLimit };

    case 'incoming': {
      const text = String(message.text ?? '').trim();
      if (!text) throw new Error('空文本');
      const key = `in|${fingerprint(text)}`;
      if (cache.has(key)) return cache.get(key);

      const result = await translateIncoming(text, {
        settings: store.settings,
        keys: store.keys,
        blocked: blockedProviders(store, store.settings.dailyCharLimit)
      });
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
        keys: store.keys,
        blocked: blockedProviders(store, store.settings.dailyCharLimit)
      });
      cacheSet(key, result);

      // 检测到的语言回写为会话记忆，下次进这个会话直接可用
      if (result.languageSource === 'detected' && chatId) {
        store.chats[chatId] = { language: result.target, updatedAt: Date.now() };
        await saveStore(store);
      }
      return result;
    }

    case 'translateText': {
      const text = String(message.text ?? '').trim();
      if (!text) throw new Error('没有可翻译的内容');
      return translateText(store, {
        text,
        source: message.source ?? null,
        target: String(message.target ?? store.settings.defaultTarget),
        style: message.style,
        selection: message.selection,
        mode: message.mode
      });
    }

    case 'testProvider': {
      // 设置页里「测试这一家」：只测指定服务商，不降级，错误原样抛出
      const providerId = String(message.providerId ?? '');
      if (!providerId) throw new Error('缺少 providerId');
      return testProvider(
        providerId,
        {
          text: String(message.text ?? 'Hello, when can you ship the order?'),
          source: null,
          target: String(message.target ?? 'zh-Hans'),
          style: 'casual'
        },
        store.keys
      );
    }

    case 'translateBatch': {
      const items = Array.isArray(message.items) ? message.items : [];
      if (!items.length) return [];
      const results = [];
      for (const item of items) {
        const index = item.index ?? results.length;
        try {
          const result = await translateText(store, {
            text: String(item.text ?? ''),
            source: item.source ?? null,
            target: String(item.target ?? store.settings.defaultTarget),
            style: item.style,
            selection: item.selection,
            mode: item.mode
          });
          results.push({ ok: true, index, ...result });
        } catch (error) {
          results.push({
            ok: false,
            index,
            text: String(item.text ?? ''),
            error: error?.message ?? String(error)
          });
        }
      }
      return results;
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

// 首次安装时写入默认设置，避免 content script 读到 undefined；顺带建右键菜单
EXT.runtime.onInstalled.addListener(async () => {
  const store = await loadStore();
  await saveStore(store);
  createContextMenus();
});

/* ── 右键菜单 + 快捷键（D-55）─────────────────────────── */

const MENU_TRANSLATE = 'weifan-translate-selection';

/** 右键菜单：只建一个「翻译选区」，目标语言与服务沿用全局设置 */
function createContextMenus() {
  if (!EXT.contextMenus?.create) return;
  EXT.contextMenus.removeAll(() => {
    EXT.contextMenus.create({
      id: MENU_TRANSLATE,
      title: '用微翻翻译“%s”',
      contexts: ['selection']
    });
  });
}

EXT.contextMenus?.onClicked?.addListener(async (info, tab) => {
  if (info.menuItemId !== MENU_TRANSLATE) return;
  await translateSelectionIn(tab, info.selectionText ?? '');
});

EXT.commands?.onCommand?.addListener(async (command) => {
  if (command === 'open-text-page') {
    await openTextPage('');
    return;
  }
  if (command === 'toggle-hover') {
    await toggleHover();
    return;
  }
  if (command === 'translate-selection') {
    const [tab] = await EXT.tabs.query({ active: true, currentWindow: true });
    // 选区在页面里，得先问 pageagent（chrome:// 等页问不到 → 走文本页兜底）
    let text = '';
    try {
      const reply = await EXT.tabs.sendMessage(tab.id, { type: 'getSelection' });
      text = reply?.data?.text ?? '';
    } catch {
      // 页面吃不到消息，text 留空
    }
    await translateSelectionIn(tab, text);
  }
});

/**
 * 把选中文本投给所在标签页弹划词气泡（与划词同一条链路）；
 * 页面接不住（chrome:// 等无法注入的页）就打开文本翻译页并带上原文。
 * @param {any} tab
 * @param {string} text
 */
async function translateSelectionIn(tab, text) {
  const trimmed = String(text ?? '').trim();
  if (!trimmed || !tab?.id) {
    await openTextPage(trimmed);
    return;
  }
  try {
    const reply = await EXT.tabs.sendMessage(tab.id, { type: 'translateSelection', text: trimmed });
    if (!reply?.ok) throw new Error(reply?.error ?? '页面没接住');
  } catch {
    await openTextPage(trimmed);
  }
}

/** @param {string} text 空字符串就只开页面，不预填 */
async function openTextPage(text) {
  const base = EXT.runtime.getURL('src/text/index.html');
  const url = text ? `${base}?text=${encodeURIComponent(text)}` : base;
  await EXT.tabs.create({ url });
}

/** 快捷键开关悬停：改设置 + 让已注入的页面立即生效 + 角标提示一下 */
async function toggleHover() {
  const store = await loadStore();
  const next = !store.settings.hoverTranslate;
  store.settings = { ...store.settings, hoverTranslate: next };
  await saveStore(store);

  // 页面平时每分钟才同步一次设置，开关后逐页叫它们立刻刷新
  const tabs = await EXT.tabs.query({});
  for (const tab of tabs) {
    try {
      await EXT.tabs.sendMessage(tab.id, { type: 'refresh' });
    } catch {
      // chrome:// 等页发不进消息，跳过
    }
  }

  try {
    EXT.action.setBadgeText({ text: next ? '开' : '关' });
    setTimeout(() => EXT.action.setBadgeText({ text: '' }), 1500);
  } catch {
    // 角标失败不影响开关本身
  }
}
