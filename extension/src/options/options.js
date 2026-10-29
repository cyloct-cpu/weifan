import { LANGUAGES, SEND_TARGETS } from '../core/language.js';
import { DEFAULT_SETTINGS } from '../core/core.js';
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
let store = { keys: {}, settings: { ...DEFAULT_SETTINGS }, chats: {} };

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
      chats: value.chats ?? {}
    };
  } catch {
    // 读不到也先把页面渲染出来，不能白屏
  }
  render();
}

function render() {
  const { keys, settings } = store;

  $('groq').value = keys.groq ?? '';
  $('deepl').value = keys.deepl ?? '';
  $('deeplEndpoint').value = keys.deeplEndpoint ?? 'https://api-free.deepl.com/v2/translate';
  $('google').value = keys.google ?? '';
  $('llm').value = keys.llm ?? '';
  $('llmBaseURL').value = keys.llmBaseURL ?? 'https://api.deepseek.com/v1';
  $('llmModel').value = keys.llmModel ?? 'deepseek-chat';

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

  $('glossary').value = formatGlossary(settings.glossary ?? []);
  updateGlossaryCount();
  refreshKeyStatus();
}

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
  return {
    keys: {
      groq: $('groq').value.trim() || undefined,
      deepl: $('deepl').value.trim() || undefined,
      deeplEndpoint: $('deeplEndpoint').value.trim() || undefined,
      google: $('google').value.trim() || undefined,
      llm: $('llm').value.trim() || undefined,
      llmBaseURL: $('llmBaseURL').value.trim() || undefined,
      llmModel: $('llmModel').value.trim() || undefined
    },
    settings: {
      ...store.settings,
      defaultTarget: $('defaultTarget').value,
      style: getStyle(),
      autoIncoming: $('autoIncoming').checked,
      allowDirectSend: $('allowDirectSend').checked,
      previewOnLowConfidence: $('previewOnLowConfidence').checked,
      longSentenceThreshold: Number($('longSentenceThreshold').value),
      glossary: parseGlossary($('glossary').value)
    },
    chats: store.chats ?? {}
  };
}

/** 密钥徽标 + 分组计数 */
function refreshKeyStatus() {
  const ids = ['groq', 'deepl', 'google', 'llm'];
  let configured = 0;
  for (const id of ids) {
    const filled = Boolean($(id).value.trim());
    if (filled) configured += 1;
    $(`pill-${id}`).hidden = !filled;
  }
  $('svcCount').textContent = `${configured} / ${ids.length}`;
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
  const hasAnyKey = next.keys.google || next.keys.deepl || next.keys.groq || next.keys.llm;
  if (!hasAnyKey) {
    setStatus('至少要填一个密钥，否则没法翻译', 'err');
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
  setStatus('已保存，刷新 WhatsApp 页面后生效', 'ok');

  const button = $('save');
  button.classList.add('is-done');
  button.querySelector('.btn-label').textContent = '已保存';
  clearTimeout(save.doneTimer);
  save.doneTimer = setTimeout(() => {
    button.classList.remove('is-done');
    button.querySelector('.btn-label').textContent = '保存';
  }, 1600);
}

// ── 事件绑定 ───────────────────────────────────────

$('save').addEventListener('click', save);

$('clearCache').addEventListener('click', async () => {
  try {
    await sendToBackground({ type: 'clearCache' });
    setStatus('缓存已清空', 'ok');
  } catch (error) {
    setStatus(`清空失败：${error.message}`, 'err');
  }
});

// 密钥输入 → 徽标实时亮
for (const id of ['groq', 'deepl', 'google', 'llm']) {
  $(id).addEventListener('input', refreshKeyStatus);
}

// 显示 / 隐藏密钥
for (const button of document.querySelectorAll('.reveal')) {
  button.addEventListener('click', () => {
    const input = $(button.dataset.reveal);
    const show = input.type === 'password';
    input.type = show ? 'text' : 'password';
    if (show) button.setAttribute('data-on', '');
    else button.removeAttribute('data-on');
    button.title = show ? '隐藏密钥' : '显示密钥';
  });
}

// 语气分段 → 说明文字跟随
for (const radio of document.querySelectorAll('input[name="style"]')) {
  radio.addEventListener('change', () => setStyle(radio.value));
}

// 长句阈值
$('longSentenceThreshold').addEventListener('input', (event) => {
  $('thresholdLabel').textContent = event.target.value;
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
