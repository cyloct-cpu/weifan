import { LANGUAGES, SEND_TARGETS } from '../core/language.js';
import { DEFAULT_SETTINGS } from '../core/core.js';
import { EXT, sendToBackground } from '../shared/ext.js';

const STORAGE_KEY = 'weifan';
const $ = (id) => document.getElementById(id);

/** @type {any} */
let store = { keys: {}, settings: { ...DEFAULT_SETTINGS }, chats: {} };

async function load() {
  const raw = await EXT.storage.local.get(STORAGE_KEY);
  const value = raw?.[STORAGE_KEY] ?? {};
  store = {
    keys: value.keys ?? {},
    settings: { ...DEFAULT_SETTINGS, ...(value.settings ?? {}) },
    chats: value.chats ?? {}
  };
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

  $('style').value = settings.style;
  $('autoIncoming').checked = settings.autoIncoming !== false;
  $('allowDirectSend').checked = settings.allowDirectSend !== false;
  $('previewOnLowConfidence').checked = settings.previewOnLowConfidence !== false;
  $('longSentenceThreshold').value = String(settings.longSentenceThreshold ?? 60);
  $('thresholdLabel').textContent = String(settings.longSentenceThreshold ?? 60);
  $('glossary').value = formatGlossary(settings.glossary ?? []);
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
      style: $('style').value,
      autoIncoming: $('autoIncoming').checked,
      allowDirectSend: $('allowDirectSend').checked,
      previewOnLowConfidence: $('previewOnLowConfidence').checked,
      longSentenceThreshold: Number($('longSentenceThreshold').value),
      glossary: parseGlossary($('glossary').value)
    },
    chats: store.chats ?? {}
  };
}

function setStatus(message, color = '#00a884') {
  const el = $('status');
  el.textContent = message;
  el.style.color = color;
  setTimeout(() => {
    if (el.textContent === message) el.textContent = '';
  }, 4000);
}

async function save() {
  const next = collect();
  const hasAnyKey = next.keys.google || next.keys.deepl || next.keys.groq || next.keys.llm;
  if (!hasAnyKey) {
    setStatus('至少要填一个密钥，否则没法翻译', '#f15c6d');
    return;
  }
  await EXT.storage.local.set({ [STORAGE_KEY]: next });
  store = next;
  setStatus('已保存，刷新 WhatsApp 页面后生效');
}

$('save').addEventListener('click', save);

$('clearCache').addEventListener('click', async () => {
  try {
    await sendToBackground({ type: 'clearCache' });
    setStatus('缓存已清空');
  } catch (error) {
    setStatus(`清空失败：${error.message}`, '#f15c6d');
  }
});

$('longSentenceThreshold').addEventListener('input', (event) => {
  $('thresholdLabel').textContent = event.target.value;
});

load();
