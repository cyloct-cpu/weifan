import { LANGUAGES, SEND_TARGETS } from '../core/language.js';
import { DEFAULT_SETTINGS } from '../core/core.js';
import { PROVIDERS } from '../core/providers.js';
import { EXT, sendToBackground } from '../shared/ext.js';

const STORAGE_KEY = 'weifan';
const $ = (id) => document.getElementById(id);

/**
 * 存储兜底：扩展里走 chrome.storage.local；
 * 在普通浏览器里打开（本地预览、没打包扩展）用 localStorage 顶一份，
 * 否则设置页一打开就白屏，也没法点。
 */
const storage =
  EXT?.storage?.local ??
  ({
    async get(key) {
      const raw = globalThis.localStorage?.getItem(key);
      return raw ? { [key]: JSON.parse(raw) } : {};
    },
    async set(obj) {
      for (const [key, value] of Object.entries(obj)) {
        globalThis.localStorage?.setItem(key, JSON.stringify(value));
      }
    }
  });

/** @type {any} */
let store = { keys: {}, settings: { ...DEFAULT_SETTINGS }, chats: {}, usage: {} };

/** 自定义接口的草稿（界面上能增删，保存时才写进 keys） */
let customs = [];

const STYLE_NOTES = {
  business: '商务：正式、简洁、不用缩略语。',
  casual: '日常：口语、简短，像聊天。',
  literal: '直译：单据、合同条款，逐字对照。'
};

async function load() {
  try {
    const raw = await storage.get(STORAGE_KEY);
    const value = raw?.[STORAGE_KEY] ?? {};
    store = {
      keys: value.keys ?? {},
      settings: { ...DEFAULT_SETTINGS, ...(value.settings ?? {}) },
      chats: value.chats ?? {},
      usage: value.usage ?? {}
    };
  } catch {
    // 读不到也先把页面渲染出来，不能白屏
  }
  customs = (store.keys.customs ?? []).map((item) => ({ ...item }));
  render();
}

function render() {
  const { settings } = store;
  renderProviders();
  renderCustoms();

  const select = $('defaultTarget');
  select.innerHTML = '';
  for (const code of SEND_TARGETS) {
    const option = document.createElement('option');
    option.value = code;
    option.textContent = LANGUAGES[code].name;
    select.appendChild(option);
  }
  select.value = settings.defaultTarget;

  setStyle(settings.style);
  $('autoIncoming').checked = settings.autoIncoming !== false;
  $('allowDirectSend').checked = settings.allowDirectSend !== false;
  $('previewOnLowConfidence').checked = settings.previewOnLowConfidence !== false;

  const threshold = settings.longSentenceThreshold ?? 60;
  $('longSentenceThreshold').value = String(threshold);
  $('thresholdLabel').textContent = String(threshold);
  updateRangeFill($('longSentenceThreshold'));

  const limit = settings.dailyCharLimit ?? 0;
  $('dailyCharLimit').value = String(limit);
  $('limitLabel').textContent = limit ? `${limit} 字` : '不限制';
  updateRangeFill($('dailyCharLimit'));

  $('glossary').value = formatGlossary(settings.glossary ?? []);
  updateGlossaryCount();
  refreshKeyStatus();
}

/* ── 免费额度服务商表单 ───────────────────────────── */

/** 从注册表自动生成八家表单，改服务商只要改 core/providers.js */
function renderProviders() {
  const host = $('providerList');
  host.innerHTML = '';

  for (const provider of PROVIDERS) {
    const card = document.createElement('div');
    card.className = 'provider';
    card.dataset.id = provider.id;

    const head = document.createElement('div');
    head.className = 'provider-head';

    const name = document.createElement('strong');
    name.textContent = provider.label;

    const pill = document.createElement('span');
    pill.className = 'pill';
    pill.hidden = true;
    pill.textContent = '已配置';

    const note = document.createElement('span');
    note.className = 'provider-note';
    note.textContent = provider.freeNote ?? '';

    head.append(name, pill, note);

    if (provider.docs) {
      const link = document.createElement('a');
      link.className = 'provider-link';
      link.href = provider.docs;
      link.target = '_blank';
      link.rel = 'noreferrer';
      link.textContent = '去申请';
      head.appendChild(link);
    }

    const fields = document.createElement('div');
    fields.className = 'provider-fields';

    for (const field of provider.fields ?? []) {
      const wrap = document.createElement('div');
      wrap.className = 'field';

      const label = document.createElement('label');
      label.textContent = field.label;

      const input = document.createElement('input');
      input.type = field.secret ? 'password' : 'text';
      input.placeholder = field.placeholder ?? '';
      input.dataset.field = field.id;
      input.dataset.provider = provider.id;
      input.spellcheck = false;
      input.autocomplete = 'off';

      const saved = readCredential(provider.id, field.id);
      if (saved) input.value = saved;

      input.addEventListener('input', refreshKeyStatus);

      wrap.append(label, input);
      fields.appendChild(wrap);
    }

    // 端点：固定的只展示一行说明，可改的才给输入框（用户通常只要填密钥）
    /** @type {HTMLElement[]} */
    const endpointParts = [];
    if (provider.endpoint) {
      if (provider.endpointEditable) {
        const wrap = document.createElement('div');
        wrap.className = 'field';
        const label = document.createElement('label');
        label.textContent = provider.id === 'deepl'
          ? '端点（付费版改成 https://api.deepl.com/v2/translate）'
          : '终结点（区域资源填自己的子域名，留空用默认）';
        const input = document.createElement('input');
        input.type = 'text';
        input.value = provider.endpoint;
        input.dataset.field = 'endpoint';
        input.dataset.provider = provider.id;
        input.spellcheck = false;
        wrap.append(label, input);
        endpointParts.push(wrap);
      } else {
        const note = document.createElement('p');
        note.className = 'provider-endpoint';
        note.textContent = `接口 ${provider.endpoint}`;
        endpointParts.push(note);
      }
    }

    const status = document.createElement('p');
    status.className = 'provider-status';
    status.hidden = true;

    const test = document.createElement('button');
    test.type = 'button';
    test.className = 'provider-test';
    test.textContent = '测试';
    test.addEventListener('click', () => runProviderTest(provider.id, status, test));
    head.appendChild(test);

    card.append(head, ...endpointParts, fields, status);
    host.appendChild(card);
  }
}

/**
 * 只测某一家：不走降级链，出错就是这一家的错。
 * 测之前先把输入框里**刚填的**值存下来——不然测的是上次的旧密钥。
 * @param {string} providerId
 * @param {HTMLElement} statusEl
 * @param {HTMLButtonElement} button
 */
async function runProviderTest(providerId, statusEl, button) {
  button.disabled = true;
  statusEl.hidden = false;
  statusEl.className = 'provider-status';
  statusEl.textContent = '测试中…';

  try {
    await saveProviderCredential(providerId);
    const result = await sendToBackground({ type: 'testProvider', providerId });
    statusEl.classList.add('is-ok');
    statusEl.textContent = `✓ ${result.text}（${result.provider} · ${result.latency}ms）`;
  } catch (error) {
    statusEl.classList.add('is-err');
    statusEl.textContent = `✗ ${error.message}`;
  } finally {
    button.disabled = false;
  }
}

/**
 * 把某个服务商当前输入框里的值写进存储（不碰其它服务商）。
 * @param {string} providerId
 */
async function saveProviderCredential(providerId) {
  if (providerId.startsWith('custom:')) {
    const item = customs.find((c) => c.id === providerId.slice(7));
    if (!item) return;
    item.key = item.key?.trim() ?? '';
    item.baseURL = item.baseURL?.trim() ?? '';
    item.model = item.model?.trim() || 'deepseek-chat';
    item.name = item.name?.trim() || '';
    const clean = customs
      .filter((c) => c.key && c.baseURL)
      .map((c) => ({ id: c.id, name: c.name || c.model || '自定义接口', key: c.key, baseURL: c.baseURL, model: c.model || 'deepseek-chat' }));
    store.keys = { ...store.keys, customs: clean };
    await storage.set({ [STORAGE_KEY]: store });
    return;
  }

  /** @type {Record<string, string>} */
  const values = {};
  for (const input of document.querySelectorAll(`input[data-provider="${providerId}"]`)) {
    if (input.value.trim()) values[input.dataset.field] = input.value.trim();
  }

  const def = PROVIDERS.find((p) => p.id === providerId);
  const required = (def?.fields ?? []).filter((f) => f.id !== 'endpoint' && !f.optional);
  const keys = { ...store.keys };
  if (required.length && required.every((f) => values[f.id])) {
    keys[providerId] = values;
  } else {
    delete keys[providerId];
  }
  store.keys = keys;
  await storage.set({ [STORAGE_KEY]: store });
}

/**
 * @param {string} providerId
 * @param {string} fieldId
 */
function readCredential(providerId, fieldId) {
  const value = store.keys[providerId];
  if (!value) return '';
  if (typeof value === 'string') return fieldId === 'key' ? value : '';
  return value[fieldId] ?? '';
}

/* ── 自定义接口 ───────────────────────────────────── */

function renderCustoms() {
  const host = $('customList');
  host.innerHTML = '';

  if (!customs.length) {
    const empty = document.createElement('p');
    empty.className = 'hint';
    empty.textContent = '还没有自定义接口。填一个 key + 接口地址 + 模型名，就能接任何 OpenAI 兼容服务。';
    host.appendChild(empty);
    refreshKeyStatus();
    return;
  }

  customs.forEach((item, index) => {
    const card = document.createElement('div');
    card.className = 'provider';

    const head = document.createElement('div');
    head.className = 'provider-head';

    const name = document.createElement('strong');
    name.textContent = item.name || `接口 ${index + 1}`;

    const pill = document.createElement('span');
    pill.className = 'pill';
    pill.hidden = !(item.key && item.baseURL);
    pill.textContent = '已配置';

    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'provider-remove';
    remove.textContent = '删除';
    remove.addEventListener('click', () => {
      customs.splice(index, 1);
      renderCustoms();
    });

    // 端点：固定的只展示一行说明，可改的才给输入框（用户通常只要填密钥）
    /** @type {HTMLElement[]} */
    const endpointParts = [];
    if (provider.endpoint) {
      if (provider.endpointEditable) {
        const wrap = document.createElement('div');
        wrap.className = 'field';
        const label = document.createElement('label');
        label.textContent = provider.id === 'deepl'
          ? '端点（付费版改成 https://api.deepl.com/v2/translate）'
          : '终结点（区域资源填自己的子域名，留空用默认）';
        const input = document.createElement('input');
        input.type = 'text';
        input.value = provider.endpoint;
        input.dataset.field = 'endpoint';
        input.dataset.provider = provider.id;
        input.spellcheck = false;
        wrap.append(label, input);
        endpointParts.push(wrap);
      } else {
        const note = document.createElement('p');
        note.className = 'provider-endpoint';
        note.textContent = `接口 ${provider.endpoint}`;
        endpointParts.push(note);
      }
    }

    const status = document.createElement('p');
    status.className = 'provider-status';
    status.hidden = true;

    const test = document.createElement('button');
    test.type = 'button';
    test.className = 'provider-test';
    test.textContent = '测试';
    test.addEventListener('click', () => runProviderTest(`custom:${item.id}`, status, test));

    head.append(name, pill, test, remove);

    const fields = document.createElement('div');
    fields.className = 'provider-fields';

    const defs = [
      { id: 'name', label: '名称', placeholder: 'DeepSeek', secret: false },
      { id: 'baseURL', label: '接口地址', placeholder: 'https://api.deepseek.com/v1', secret: false },
      { id: 'key', label: 'API Key', placeholder: 'sk-…', secret: true },
      { id: 'model', label: '模型名', placeholder: 'deepseek-chat', secret: false }
    ];

    for (const field of defs) {
      const wrap = document.createElement('div');
      wrap.className = 'field';

      const label = document.createElement('label');
      label.textContent = field.label;

      const input = document.createElement('input');
      input.type = field.secret ? 'password' : 'text';
      input.placeholder = field.placeholder;
      input.value = item[field.id] ?? '';
      input.spellcheck = false;
      input.autocomplete = 'off';
      input.addEventListener('input', () => {
        item[field.id] = input.value;
        pill.hidden = !(item.key && item.baseURL);
        refreshKeyStatus();
      });

      wrap.append(label, input);
      fields.appendChild(wrap);
    }

    card.append(head, ...endpointParts, fields, status);
    host.appendChild(card);
  });

  refreshKeyStatus();
}

$('addCustom').addEventListener('click', () => {
  customs.push({
    id: `c${Date.now().toString(36)}`,
    name: '',
    key: '',
    baseURL: '',
    model: ''
  });
  renderCustoms();
});

/* ── 保存 ─────────────────────────────────────────── */

/** @param {string} value */
function setStyle(value) {
  const radio = document.querySelector(`input[name="style"][value="${value}"]`);
  if (radio) radio.checked = true;
  $('styleNote').textContent = STYLE_NOTES[value] ?? STYLE_NOTES.business;
}

/** @returns {string} */
function getStyle() {
  return document.querySelector('input[name="style"]:checked')?.value ?? 'business';
}

/** @param {any[]} entries */
function formatGlossary(entries) {
  return entries
    .map((entry) => {
      if (entry.policy === 'fixed') {
        const pairs = Object.entries(entry.translations ?? {});
        if (pairs.length === 0) return entry.term;
        return `${entry.term} => ${pairs[0][0]}: ${pairs[0][1]}`;
      }
      return entry.term;
    })
    .join('\n');
}

/** @param {string} text */
function parseGlossary(text) {
  const entries = [];
  for (const line of text.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed) continue;

    const match = trimmed.match(/^(.*?)\s*=>\s*([a-zA-Z-]{2,7})\s*:\s*(.+)$/);
    if (match) {
      entries.push({
        term: match[1].trim(),
        policy: 'fixed',
        translations: { [match[2]]: match[3].trim() },
        isEnabled: true
      });
    } else {
      entries.push({ term: trimmed, policy: 'keepOriginal', translations: {}, isEnabled: true });
    }
  }
  return entries;
}

function collect() {
  /** @type {Record<string, any>} */
  const keys = {};

  for (const provider of PROVIDERS) {
    /** @type {Record<string, string>} */
    const credential = {};
    for (const input of document.querySelectorAll(`input[data-provider="${provider.id}"]`)) {
      if (input.value.trim()) credential[input.dataset.field] = input.value.trim();
    }
    const required = (provider.fields ?? []).filter((f) => f.id !== 'endpoint' && !f.optional);
    if (required.length && required.every((f) => credential[f.id])) {
      keys[provider.id] = credential;
    }
  }

  const cleanCustoms = customs
    .filter((item) => item.key?.trim() && item.baseURL?.trim())
    .map((item) => ({
      id: item.id,
      name: item.name?.trim() || item.model?.trim() || '自定义接口',
      key: item.key.trim(),
      baseURL: item.baseURL.trim(),
      model: item.model?.trim() || 'deepseek-chat'
    }));

  if (cleanCustoms.length) keys.customs = cleanCustoms;

  return {
    keys,
    settings: {
      ...store.settings,
      defaultTarget: $('defaultTarget').value,
      style: getStyle(),
      autoIncoming: $('autoIncoming').checked,
      allowDirectSend: $('allowDirectSend').checked,
      previewOnLowConfidence: $('previewOnLowConfidence').checked,
      longSentenceThreshold: Number($('longSentenceThreshold').value),
      dailyCharLimit: Number($('dailyCharLimit').value),
      glossary: parseGlossary($('glossary').value)
    },
    chats: store.chats ?? {},
    usage: store.usage ?? {}
  };
}

/** 密钥徽标 + 分组计数 */
function refreshKeyStatus() {
  let configured = 0;
  for (const provider of PROVIDERS) {
    const pill = document.querySelector(`.provider[data-id="${provider.id}"] .pill`);
    const required = (provider.fields ?? []).filter((f) => f.id !== 'endpoint' && !f.optional);
    const filled = required.every((field) => {
      const input = document.querySelector(`input[data-provider="${provider.id}"][data-field="${field.id}"]`);
      return Boolean(input?.value.trim());
    });
    if (pill) pill.hidden = !filled;
    if (filled) configured += 1;
  }

  $('freeCount').textContent = `${configured} / ${PROVIDERS.length}`;
  $('customCount').textContent = `${customs.filter((c) => c.key && c.baseURL).length} 个`;
}

function updateGlossaryCount() {
  const lines = $('glossary').value.split('\n').filter((line) => line.trim()).length;
  $('glossaryCount').textContent = `${lines} 条`;
}

/** @param {HTMLInputElement} input */
function updateRangeFill(input) {
  const min = Number(input.min);
  const max = Number(input.max);
  const pct = ((Number(input.value) - min) / (max - min)) * 100;
  input.style.setProperty('--fill', `${pct}%`);
}

/**
 * @param {string} message
 * @param {'ok'|'err'|'info'} [kind]
 */
function setStatus(message, kind = 'info') {
  const el = $('status');
  el.textContent = message;
  el.className = kind === 'ok' ? 'is-ok' : kind === 'err' ? 'is-err' : '';
  el.classList.add('is-show');
  clearTimeout(setStatus.timer);
  setStatus.timer = setTimeout(() => {
    if (el.textContent === message) {
      el.textContent = '';
      el.className = '';
    }
  }, 4000);
}

async function save() {
  const next = collect();
  const hasAny = Object.keys(next.keys).length > 0;
  if (!hasAny) {
    setStatus('至少要填一个服务商，否则没法翻译', 'err');
    return;
  }
  try {
    await storage.set({ [STORAGE_KEY]: next });
  } catch (error) {
    setStatus(`保存失败：${error.message}`, 'err');
    return;
  }
  store = next;
  refreshKeyStatus();
  setStatus('已保存，刷新页面后生效', 'ok');

  const button = $('save');
  button.classList.add('is-done');
  button.querySelector('.btn-label').textContent = '已保存';
  clearTimeout(save.doneTimer);
  save.doneTimer = setTimeout(() => {
    button.classList.remove('is-done');
    button.querySelector('.btn-label').textContent = '保存';
  }, 1600);
}

/* ── 事件绑定 ─────────────────────────────────────── */

$('save').addEventListener('click', save);

$('clearCache').addEventListener('click', async () => {
  try {
    await sendToBackground({ type: 'clearCache' });
    setStatus('缓存已清空', 'ok');
  } catch (error) {
    setStatus(`清空失败：${error.message}`, 'err');
  }
});

// 语气分段 → 说明文字跟随
for (const radio of document.querySelectorAll('input[name="style"]')) {
  radio.addEventListener('change', () => setStyle(radio.value));
}

// 滑杆
$('longSentenceThreshold').addEventListener('input', (event) => {
  $('thresholdLabel').textContent = event.target.value;
  updateRangeFill(event.target);
});
$('dailyCharLimit').addEventListener('input', (event) => {
  const value = Number(event.target.value);
  $('limitLabel').textContent = value ? `${value} 字` : '不限制';
  updateRangeFill(event.target);
});

// 术语计数 + 示例
$('glossary').addEventListener('input', updateGlossaryCount);

for (const chip of document.querySelectorAll('.chip')) {
  chip.addEventListener('click', () => {
    const area = $('glossary');
    const line = chip.dataset.add;
    const lines = area.value.split('\n').filter((item) => item.trim());
    if (lines.includes(line)) {
      setStatus('这一条已经在表里了', 'info');
      return;
    }
    lines.push(line);
    area.value = lines.join('\n');
    updateGlossaryCount();
    area.focus();
    area.setSelectionRange(area.value.length, area.value.length);
  });
}

// ⌘S / Ctrl+S 保存
document.addEventListener('keydown', (event) => {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
    event.preventDefault();
    save();
  }
});

// 顶栏滚动出分隔线
const topbar = $('topbar');
const onScroll = () => topbar.classList.toggle('is-scrolled', window.scrollY > 4);
window.addEventListener('scroll', onScroll, { passive: true });
onScroll();

load();
