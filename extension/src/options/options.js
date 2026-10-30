import { LANGUAGES, SEND_TARGETS } from '../core/language.js';
import { DEFAULT_SETTINGS } from '../core/core.js';
import { PROVIDERS } from '../core/providers.js';
import { EXT, sendToBackground } from '../shared/ext.js';
import { DEFAULT_SCHEDULE, applyFromSettings, nextThemeMode, toMinutes } from '../shared/theme.js';

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
  renderThemeButton(mode);

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
    themeMode: store.settings.themeMode ?? 'auto',
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
 * 全量渠道清单：九家免费服务商 + 全部自定义接口（D-56）。
 * @returns {{id: string, label: string, note: string, configured: boolean, kind: 'provider'|'custom', index?: number}[]}
 */
function channelList() {
  const free = PROVIDERS.map((p) => ({
    id: p.id,
    label: p.label,
    note: p.freeNote ?? '',
    configured: isProviderConfigured(p.id),
    kind: 'provider'
  }));
  const custom = customs.map((item, index) => ({
    id: `custom:${item.id}`,
    label: item.name || item.model || '自定义接口',
    note: item.baseURL || '还没填接口地址',
    configured: Boolean(item.key && item.baseURL),
    kind: 'custom',
    index
  }));
  return [...free, ...custom];
}

/** 当前视觉顺序：chainOrder 优先，新渠道追加尾部，消失的剔除 */
function orderedChannels() {
  const channels = channelList();
  const byId = new Map(channels.map((c) => [c.id, c]));
  const order = (store.settings.chainOrder ?? []).filter((id) => byId.has(id));
  for (const channel of channels) {
    if (!order.includes(channel.id)) order.push(channel.id);
  }
  return order.map((id) => byId.get(id));
}

function isChannelOff(id) {
  return (store.settings.chainOff ?? []).includes(id);
}

/** 把顺序写进 store 并落盘（开关的 off 名单里只留还存在的渠道） */
async function persistChain(channels) {
  store.settings = {
    ...store.settings,
    chainOrder: channels.map((c) => c.id),
    chainOff: (store.settings.chainOff ?? []).filter((id) => channels.some((c) => c.id === id))
  };
  await storage.set({ [STORAGE_KEY]: store });
}

/** 拖动中的渠道 id（HTML5 DnD 的 dataTransfer 在 Firefox 里读不到自设类型，用闭包兜底） */
let dragChannelId = '';

/**
 * 渠道胶囊：点主体开配置弹窗；开关切换启用；整颗可拖动改优先级。
 * 视觉顺序（左→右、上→下）就是降级链（D-56）。
 */
function channelCapsule(channel) {
  const capsule = document.createElement('div');
  capsule.className = 'provider-capsule';
  capsule.draggable = true;
  capsule.dataset.channel = channel.id;
  if (channel.configured) capsule.dataset.configured = '1';

  const top = document.createElement('span');
  top.className = 'capsule-top';

  const name = document.createElement('strong');
  name.textContent = channel.label;
  top.appendChild(name);

  const dot = document.createElement('i');
  dot.className = 'capsule-dot';
  dot.title = channel.configured ? '已配置' : '未配置';
  top.appendChild(dot);

  const sub = document.createElement('span');
  sub.className = 'capsule-note';
  sub.textContent = channel.note;

  const off = isChannelOff(channel.id);
  const switchLabel = document.createElement('label');
  switchLabel.className = 'capsule-switch';
  switchLabel.title = off ? '已停用：点这里启用' : '已启用：点这里停用';
  const box = document.createElement('input');
  box.type = 'checkbox';
  box.checked = !off;
  const knob = document.createElement('span');
  switchLabel.append(box, knob);
  // 开关点击别冒泡到胶囊（否则会打开配置弹窗）
  switchLabel.addEventListener('click', (event) => event.stopPropagation());
  box.addEventListener('change', async () => {
    const offSet = new Set(store.settings.chainOff ?? []);
    if (box.checked) offSet.delete(channel.id);
    else offSet.add(channel.id);
    store.settings = { ...store.settings, chainOff: [...offSet] };
    capsule.classList.toggle('is-off', !box.checked);
    switchLabel.title = box.checked ? '已启用：点这里停用' : '已停用：点这里启用';
    await storage.set({ [STORAGE_KEY]: store });
    renderServiceSelect();
  });

  capsule.append(top, sub, switchLabel);

  capsule.addEventListener('click', () => {
    if (channel.kind === 'custom') openCustomDialog(channel.index);
    else openProviderDialog(channel.id);
  });

  // 拖动：松手落在哪颗胶囊上，就插到它前面
  capsule.addEventListener('dragstart', (event) => {
    dragChannelId = channel.id;
    capsule.classList.add('is-dragging');
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', channel.id);
  });
  capsule.addEventListener('dragend', () => {
    dragChannelId = '';
    capsule.classList.remove('is-dragging');
  });
  capsule.addEventListener('dragover', (event) => {
    if (!dragChannelId || dragChannelId === channel.id) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
    capsule.classList.add('is-drop-target');
  });
  capsule.addEventListener('dragleave', () => capsule.classList.remove('is-drop-target'));
  capsule.addEventListener('drop', async (event) => {
    event.preventDefault();
    capsule.classList.remove('is-drop-target');
    if (!dragChannelId || dragChannelId === channel.id) return;
    const channels = orderedChannels();
    const from = channels.findIndex((c) => c.id === dragChannelId);
    if (from < 0) return;
    const [moved] = channels.splice(from, 1);
    const to = channels.findIndex((c) => c.id === channel.id);
    channels.splice(to, 0, moved);
    await persistChain(channels);
    renderServiceGrid();
  });

  return capsule;
}

/** 渠道网格：全部渠道混排（九家 + 自定义接口），顺序即优先级（D-56） */
function renderServiceGrid() {
  const host = $('providerList');
  host.innerHTML = '';

  for (const channel of orderedChannels()) {
    host.appendChild(channelCapsule(channel));
  }

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
}

/** 翻译服务下拉：自动 / 按我的排序 / 只用某一家（跨模式列出全部已配置渠道，D-56） */
function renderServiceSelect() {
  const select = $('serviceSelect');
  select.innerHTML = '';

  const options = [
    { value: 'auto', label: '自动（按语种优选）' },
    { value: 'priority', label: '按我的排序（网格从左到右、从上到下）' }
  ];
  for (const channel of channelList()) {
    if (!channel.configured) continue;
    options.push({ value: channel.id, label: `${channel.label}（只用这家，不降级）` });
  }
  for (const option of options) {
    const el = document.createElement('option');
    el.value = option.value;
    el.textContent = option.label;
    select.appendChild(el);
  }

  const saved = store.settings.selection ?? 'auto';
  select.value = options.some((o) => o.value === saved) ? saved : 'auto';
  renderServiceHint(select.value);

  select.onchange = async () => {
    store.settings = { ...store.settings, selection: select.value };
    await storage.set({ [STORAGE_KEY]: store });
    renderServiceHint(select.value);
  };
}

/** 下拉底下的一行说明：三种模式各自的降级语义 */
function renderServiceHint(value) {
  $('serviceHint').textContent =
    value === 'auto'
      ? '按语种自动挑：该用大模型还是机翻谁合适先谁，失败按默认顺序降级。'
      : value === 'priority'
        ? '顺序就是上面网格的排布（左→右、上→下），关了开关的会被跳过。'
        : '只用这一家：失败不降级，直接把原因报出来。';
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

  const customCount = customs.filter((c) => c.key && c.baseURL).length;
  $('svcCount').textContent = customCount
    ? `${configured + customCount} 家（含自定义 ${customCount} 个）`
    : `${configured} / ${PROVIDERS.length}`;
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

/** 顶栏昼夜图标：点一下循环到下一档，立即生效并存盘（D-54 修订） */
function renderThemeButton(mode) {
  const button = $('themeBtn');
  button.dataset.mode = mode;
  button.title = `昼夜切换（当前：${{ light: '昼', dark: '夜', auto: '跟随系统' }[mode]}，点一下换下一档）`;
}

$('themeBtn').addEventListener('click', async () => {
  const next = nextThemeMode(store.settings.themeMode ?? 'auto');
  store.settings = { ...store.settings, themeMode: next };
  await storage.set({ [STORAGE_KEY]: store });
  renderThemeButton(next);
  updateThemeUi(next);
  applyFromSettings(store.settings);
});

$('scheduleEnabled').addEventListener('change', async () => {
  updateThemeUi(store.settings.themeMode ?? 'auto');
  const theme = collectTheme();
  store.settings = { ...store.settings, ...theme };
  await storage.set({ [STORAGE_KEY]: store });
  applyFromSettings(store.settings);
});

for (const id of ['scheduleStart', 'scheduleEnd']) {
  $(id).addEventListener('change', async () => {
    updateThemeUi(store.settings.themeMode ?? 'auto');
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
