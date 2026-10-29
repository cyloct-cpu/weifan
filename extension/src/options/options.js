import { LANGUAGES, SEND_TARGETS } from '../core/language.js';
import { DEFAULT_SETTINGS } from '../core/core.js';
import { PROVIDERS } from '../core/providers.js';
import { EXT, sendToBackground } from '../shared/ext.js';
import { DEFAULT_SCHEDULE, applyFromSettings, effectiveTheme, toMinutes } from '../shared/theme.js';

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
  initCollapsibleGroups();
}

function render() {
  const { settings } = store;
  const modeRadio = document.querySelector(`input[name="svcMode"][value="${settings.mode ?? 'free'}"]`);
  if (modeRadio) modeRadio.checked = true;
  renderServiceGrid();
  renderServiceSelect();

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
  renderTheme(settings);
}

/* ── 分组折叠 ─────────────────────────────────────────── */

/**
 * 设置项分组多，全部展开页面太长。
 * 点标题就展开/收起 —— **默认只展开最该看见的那一组**（翻译服务）。
 */
const COLLAPSIBLE = [
  // [分组标题关键词, 默认展开]
  ['翻译服务', true],
  ['发件', false],
  ['收件', false],
  ['额度保护', false],
  ['外观', false],
  ['术语', false]
];

function initCollapsibleGroups() {
  const groups = Array.from(document.querySelectorAll('section.group'));
  for (const group of groups) {
    const head = group.querySelector('.group-head');
    const h2 = group.querySelector('h2');
    if (!head || !h2) continue;

    const title = h2.textContent.trim();
    const rule = COLLAPSIBLE.find(([key]) => title.includes(key));

    // 没在名单里的分组（比如以后的"翻译工具"）保持原样，不加折叠
    if (!rule) continue;

    group.dataset.collapsible = '1';
    if (!rule[1]) group.classList.add('is-collapsed');

    head.setAttribute('role', 'button');
    head.setAttribute('tabindex', '0');
    head.setAttribute('aria-expanded', String(rule[1]));
    head.title = rule[1] ? '点击收起' : '点击展开';

    const toggle = () => {
      const collapsed = group.classList.toggle('is-collapsed');
      head.setAttribute('aria-expanded', String(!collapsed));
      head.title = collapsed ? '点击展开' : '点击收起';
    };

    head.addEventListener('click', toggle);
    head.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        toggle();
      }
    });
  }
}

/* ── 外观：昼夜切换 ─────────────────────────────────── */

/**
 * 三档主题：昼 / 夜 / 跟随系统。
 * 「跟随系统」时可另设夜间时段（跨零点也算）。
 */
function renderTheme(settings) {
  const mode = settings.themeMode ?? 'auto';
  const radio = document.querySelector(`input[name="themeMode"][value="${mode}"]`);
  if (radio) radio.checked = true;

  const schedule = settings.themeSchedule ?? DEFAULT_SCHEDULE;
  $('scheduleEnabled').checked = Boolean(schedule.enabled);
  $('scheduleStart').value = schedule.start ?? DEFAULT_SCHEDULE.start;
  $('scheduleEnd').value = schedule.end ?? DEFAULT_SCHEDULE.end;

  updateThemeUi(mode);
}

/** 时段输入框只在「跟随系统 + 启用自定义时段」时才显示 */
function updateThemeUi(mode) {
  const auto = mode === 'auto';
  const enabled = $('scheduleEnabled').checked;
  const row = $('scheduleRow');
  const times = $('scheduleTimes');

  row.classList.toggle('is-muted', !auto);
  times.hidden = !(auto && enabled);

  $('themeNote').textContent = auto
    ? '跟随系统：跟手机 / 浏览器的深浅色走。'
    : mode === 'dark'
      ? '夜：始终用暗色调。'
      : '昼：始终用浅色调。';

  const start = $('scheduleStart').value;
  const end = $('scheduleEnd').value;
  const valid = toMinutes(start) !== null && toMinutes(end) !== null;
  $('scheduleNote').textContent = valid
    ? `${start} – ${end} 为夜间，跨过午夜也算。`
    : '时间填得不合法，按 24 小时制填「时:分」。';
}

/** 把当前界面上的主题设置读出来 */
function collectTheme() {
  return {
    themeMode: document.querySelector('input[name="themeMode"]:checked')?.value ?? 'auto',
    themeSchedule: {
      enabled: $('scheduleEnabled').checked,
      start: $('scheduleStart').value || DEFAULT_SCHEDULE.start,
      end: $('scheduleEnd').value || DEFAULT_SCHEDULE.end
    }
  };
}

/* ── 免费额度服务商表单 ───────────────────────────── */

/** 从注册表自动生成八家表单，改服务商只要改 core/providers.js */
/* ── 服务商胶囊 + 配置弹窗 ───────────────────────────── */

/** @type {{type: 'provider'|'custom', id: string, index: number}|null} */
let dialogTarget = null;

/**
 * 胶囊卡片：两列排布，点开才出配置表单——九家竖着排太长，用户得一直滑。
 */
function providerCapsule({ title, note, configured }) {
  const capsule = document.createElement('button');
  capsule.type = 'button';
  capsule.className = 'provider-capsule';
  if (configured) capsule.dataset.configured = '1';

  const top = document.createElement('span');
  top.className = 'capsule-top';

  const name = document.createElement('strong');
  name.textContent = title;
  top.appendChild(name);

  const dot = document.createElement('i');
  dot.className = 'capsule-dot';
  dot.title = configured ? '已配置' : '未配置';
  top.appendChild(dot);

  const sub = document.createElement('span');
  sub.className = 'capsule-note';
  sub.textContent = note;

  capsule.append(top, sub);
  return capsule;
}

/**
 * 服务商网格 + 下拉。层级：**模式 → 翻译服务下拉 → 网格**。
 * 自定义接口是「自定义 API」模式下的网格成员，不再另开一个同名分组（避免两处入口）。
 */
function renderServiceGrid() {
  const host = $('providerList');
  host.innerHTML = '';
  const mode = currentSvcMode();

  if (mode === 'custom') {
    if (!customs.length) {
      const empty = document.createElement('p');
      empty.className = 'hint grid-full';
      empty.textContent = '还没有自定义接口。填一个 key + 接口地址 + 模型名，就能接任何 OpenAI 兼容服务。';
      host.appendChild(empty);
    }
    customs.forEach((item, index) => {
      const capsule = providerCapsule({
        title: item.name || item.model || '自定义接口',
        note: item.baseURL || '还没填接口地址',
        configured: Boolean(item.key && item.baseURL)
      });
      capsule.addEventListener('click', () => openCustomDialog(index));
      host.appendChild(capsule);
    });

    const add = document.createElement('button');
    add.type = 'button';
    add.className = 'provider-capsule provider-add';
    add.innerHTML = '<span class="capsule-top"><strong>+ 添加接口</strong></span>'
      + '<span class="capsule-note">DeepSeek / 硅基流动 / 自建网关</span>';
    add.addEventListener('click', () => {
      customs.push({ id: `c${Date.now().toString(36)}`, name: '', key: '', baseURL: '', model: '' });
      renderServiceGrid();
      openCustomDialog(customs.length - 1);
    });
    host.appendChild(add);
  } else {
    for (const provider of PROVIDERS) {
      const capsule = providerCapsule({
        title: provider.label,
        note: provider.freeNote,
        configured: isProviderConfigured(provider.id)
      });
      capsule.addEventListener('click', () => openProviderDialog(provider.id));
      host.appendChild(capsule);
    }
  }
}

/** 翻译服务下拉：按当前模式列出已配置的服务商，第一项永远是「自动」 */
function renderServiceSelect() {
  const select = $('serviceSelect');
  select.innerHTML = '';

  const auto = document.createElement('option');
  auto.value = 'auto';
  auto.textContent = '自动（按语种优选）';
  select.appendChild(auto);

  const ids = ['auto'];
  const mode = currentSvcMode();

  if (mode === 'custom') {
    for (const item of customs) {
      if (!item.key || !item.baseURL) continue;
      const option = document.createElement('option');
      option.value = `custom:${item.id}`;
      option.textContent = item.name || item.model || '自定义接口';
      select.appendChild(option);
      ids.push(option.value);
    }
  } else {
    for (const provider of PROVIDERS) {
      if (!isProviderConfigured(provider.id)) continue;
      const option = document.createElement('option');
      option.value = provider.id;
      option.textContent = provider.label;
      select.appendChild(option);
      ids.push(option.value);
    }
  }

  const saved = store.settings.selection ?? 'auto';
  select.value = ids.includes(saved) ? saved : 'auto';

  select.onchange = async () => {
    store.settings = { ...store.settings, selection: select.value };
    await storage.set({ [STORAGE_KEY]: store });
  };
}

/** 当前模式：免费额度 / 自定义 API */
function currentSvcMode() {
  const radio = document.querySelector('input[name="svcMode"]:checked');
  return radio?.value ?? 'free';
}

/** 某家是否已配置（必填字段齐了） */
function isProviderConfigured(providerId) {
  const def = PROVIDERS.find((p) => p.id === providerId);
  if (!def) return false;
  const required = (def.fields ?? []).filter((f) => !f.optional);
  if (!required.length) return false;
  const values = store.keys[providerId] ?? {};
  return required.every((f) => values[f.id]);
}

/* ── 弹窗 ─────────────────────────────────────────── */

function openProviderDialog(providerId) {
  const provider = PROVIDERS.find((p) => p.id === providerId);
  if (!provider) return;

  dialogTarget = { type: 'provider', id: providerId, index: -1 };
  $('pdTitle').textContent = provider.label;
  $('pdNote').textContent = provider.freeNote;
  resetDialogStatus();

  const body = $('pdBody');
  body.innerHTML = '';

  if (provider.endpoint) {
    if (provider.endpointEditable) {
      body.appendChild(fieldBlock(
        provider.id === 'deepl'
          ? '端点（付费版改成 https://api.deepl.com/v2/translate）'
          : '终结点（区域资源填自己的子域名，留空用默认）',
        'endpoint',
        provider.endpoint,
        provider.id
      ));
    } else {
      const note = document.createElement('p');
      note.className = 'provider-endpoint';
      note.textContent = `接口 ${provider.endpoint}${provider.endpointNote ?? ''}`;
      body.appendChild(note);
    }
  }

  for (const field of provider.fields ?? []) {
    body.appendChild(fieldBlock(
      field.label + (field.optional ? '（选填）' : ''),
      field.id,
      readCredential(provider.id, field.id),
      provider.id,
      field.secret
    ));
  }

  if (provider.docs) {
    const link = document.createElement('a');
    link.className = 'provider-link';
    link.href = provider.docs;
    link.target = '_blank';
    link.rel = 'noreferrer';
    link.textContent = '去申请密钥 ↗';
    body.appendChild(link);
  }

  openDialog();
}

function openCustomDialog(index) {
  const item = customs[index];
  if (!item) return;

  dialogTarget = { type: 'custom', id: item.id, index };
  $('pdTitle').textContent = item.name || '自定义接口';
  $('pdNote').textContent = '任意 OpenAI 兼容接口：DeepSeek / 硅基流动 / 自建网关';
  resetDialogStatus();

  const body = $('pdBody');
  body.innerHTML = '';
  body.appendChild(fieldBlock('名称', 'name', item.name, item.id));
  body.appendChild(fieldBlock('接口地址', 'baseURL', item.baseURL, item.id));
  body.appendChild(fieldBlock('API Key', 'key', item.key, item.id, true));
  body.appendChild(fieldBlock('模型名', 'model', item.model, item.id));

  openDialog();
}

/**
 * 一个带标签的输入框。
 * @param {string} labelText
 * @param {string} fieldId
 * @param {string} value
 * @param {string} ownerId
 * @param {boolean} [secret]
 */
function fieldBlock(labelText, fieldId, value, ownerId, secret = false) {
  const wrap = document.createElement('div');
  wrap.className = 'field';

  const label = document.createElement('label');
  label.textContent = labelText;

  const input = document.createElement('input');
  input.type = secret ? 'password' : 'text';
  input.value = value ?? '';
  input.dataset.field = fieldId;
  input.dataset.owner = ownerId;
  input.spellcheck = false;
  input.autocomplete = 'off';

  wrap.append(label, input);
  return wrap;
}

function openDialog() {
  const dialog = $('providerDialog');
  if (!dialog.open) dialog.showModal();
  const first = $('pdBody').querySelector('input');
  if (first) first.focus();
}

function resetDialogStatus() {
  const status = $('pdStatus');
  status.hidden = true;
  status.className = 'pd-status';
  status.textContent = '';
}

/** 弹窗里当前填的值 */
function collectDialogValues() {
  /** @type {Record<string,string>} */
  const values = {};
  for (const input of $('pdBody').querySelectorAll('input[data-field]')) {
    if (input.value.trim()) values[input.dataset.field] = input.value.trim();
  }
  return values;
}

/** 把弹窗里的值写进 store 并落盘 */
async function applyDialogValues() {
  if (!dialogTarget) return;
  const values = collectDialogValues();

  if (dialogTarget.type === 'provider') {
    const def = PROVIDERS.find((p) => p.id === dialogTarget.id);
    const required = (def?.fields ?? []).filter((f) => !f.optional);
    const keys = { ...store.keys };
    if (required.length && required.every((f) => values[f.id])) {
      keys[def.id] = values;
    } else {
      delete keys[def.id];
    }
    store.keys = keys;
  } else {
    const item = customs[dialogTarget.index];
    if (item) {
      item.name = values.name ?? '';
      item.baseURL = values.baseURL ?? '';
      item.key = values.key ?? '';
      item.model = values.model ?? '';
      const clean = customs
        .filter((c) => c.key && c.baseURL)
        .map((c) => ({
          id: c.id,
          name: c.name || c.model || '自定义接口',
          key: c.key,
          baseURL: c.baseURL,
          model: c.model || 'deepseek-chat'
        }));
      store.keys = { ...store.keys, customs: clean };
    }
  }

  await storage.set({ [STORAGE_KEY]: store });
}

async function saveDialog() {
  await applyDialogValues();
  refreshKeyStatus();
  renderServiceGrid();
  renderServiceSelect();
  $('providerDialog').close();
  setStatus('已保存', 'ok');
}

async function runDialogTest() {
  if (!dialogTarget) return;
  const button = $('pdTest');
  const status = $('pdStatus');

  button.disabled = true;
  status.hidden = false;
  status.className = 'pd-status';
  status.textContent = '测试中…';

  try {
    // 后台从存储读密钥，得先落盘再测
    await applyDialogValues();
    const providerId = dialogTarget.type === 'custom' ? `custom:${dialogTarget.id}` : dialogTarget.id;
    const result = await sendToBackground({ type: 'testProvider', providerId });
    status.className = 'pd-status is-ok';
    status.textContent = `✓ ${result.text}（${result.provider} · ${result.latency}ms）`;
  } catch (error) {
    status.className = 'pd-status is-err';
    status.textContent = `✗ ${error.message}`;
  } finally {
    button.disabled = false;
  }
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
  // 服务商的密钥在弹窗里单独保存，这里直接用已存的值
  const keys = { ...store.keys };

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
      glossary: parseGlossary($('glossary').value),
      ...collectTheme()
    },
    chats: store.chats ?? {},
    usage: store.usage ?? {}
  };
}

/** 密钥徽标 + 分组计数 */
function refreshKeyStatus() {
  let configured = 0;
  for (const provider of PROVIDERS) {
    if (isProviderConfigured(provider.id)) configured += 1;
  }

  if (currentSvcMode() === 'custom') {
    $('svcCount').textContent = `${customs.filter((c) => c.key && c.baseURL).length} 个自定义接口`;
  } else {
    $('svcCount').textContent = `${configured} / ${PROVIDERS.length}`;
  }
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

// 模式切换：立即生效并重绘网格与下拉
for (const radio of document.querySelectorAll('input[name="svcMode"]')) {
  radio.addEventListener('change', async () => {
    if (!radio.checked) return;
    store.settings = { ...store.settings, mode: radio.value, selection: 'auto' };
    await storage.set({ [STORAGE_KEY]: store });
    renderServiceGrid();
    renderServiceSelect();
    refreshKeyStatus();
  });
}

// 主题：切换即刻生效（不用等保存），并立刻存盘
for (const radio of document.querySelectorAll('input[name="themeMode"]')) {
  radio.addEventListener('change', async () => {
    if (!radio.checked) return;
    updateThemeUi(radio.value);
    const theme = collectTheme();
    store.settings = { ...store.settings, ...theme };
    await storage.set({ [STORAGE_KEY]: store });
    applyFromSettings(store.settings);
  });
}

$('scheduleEnabled').addEventListener('change', async () => {
  updateThemeUi(document.querySelector('input[name="themeMode"]:checked')?.value ?? 'auto');
  const theme = collectTheme();
  store.settings = { ...store.settings, ...theme };
  await storage.set({ [STORAGE_KEY]: store });
  applyFromSettings(store.settings);
});

for (const id of ['scheduleStart', 'scheduleEnd']) {
  $(id).addEventListener('change', async () => {
    updateThemeUi(document.querySelector('input[name="themeMode"]:checked')?.value ?? 'auto');
    const theme = collectTheme();
    store.settings = { ...store.settings, ...theme };
    await storage.set({ [STORAGE_KEY]: store });
    applyFromSettings(store.settings);
  });
}

$('pdSave').addEventListener('click', saveDialog);
$('pdCancel').addEventListener('click', () => $('providerDialog').close());
$('pdClose').addEventListener('click', () => $('providerDialog').close());
$('pdTest').addEventListener('click', runDialogTest);

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
