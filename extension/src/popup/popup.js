/**
 * 微翻 · 弹窗逻辑
 *
 * 面板职责：
 * - 选语言（源 / 目标）
 * - 选模式（免费额度 / 自定义 API）与服务商
 * - 主按钮：翻译当前页面的选区，没有选区就翻剪贴板
 * - 四个功能入口：文档翻译 / 文本翻译 / 鼠标悬停 / 划词翻译
 *
 * 所有翻译请求都发给 background（密钥不進页面）。
 */

import { EXT, sendToBackground } from '../shared/ext.js';
import { LANGUAGES, SEND_TARGETS } from '../core/language.js';
import { PROVIDER_LANG_CODES } from '../core/langs.js';
import { applyFromSettings, effectiveTheme, systemPrefersDark, watchTheme } from '../shared/theme.js';

const $ = (id) => document.getElementById(id);

const STYLE_LABEL = {
  business: '商务',
  casual: '日常',
  literal: '直译'
};

/** @type {any} */
let config = null;
/** 最近一次翻译结果（替换原文要用） */
let lastResult = null;

init();

async function init() {
  try {
    config = await sendToBackground({ type: 'getConfig' });
  } catch (error) {
    $('statusLine').textContent = '后台没连上：' + error.message;
    return;
  }

  renderLanguages();
  renderMode();
  renderService();
  renderStyle();
  renderCompact();
  renderTiles();
  renderStatus();
  renderModeHint();

  // 主题：先按设置上色，再挂监听（系统主题变 / 自定义时段跨界时自动重算）
  applyFromSettings(config?.settings ?? {});
  watchTheme(config?.settings ?? {});
  renderThemeButton();

  $('version').textContent = 'v' + (await version());
  checkPage();
}

/* ── 昼夜切换 ─────────────────────────────────────── */

const THEME_ORDER = ['light', 'dark', 'auto'];
const THEME_LABEL = { light: '昼', dark: '夜', auto: '跟随系统' };

/** 顶栏那个小按钮：三档循环（昼 → 夜 → 跟随系统 → 昼） */
function renderThemeButton() {
  const mode = config?.settings?.themeMode ?? 'auto';
  const button = $('themeBtn');
  button.dataset.mode = mode;
  button.title = `昼夜切换（当前：${THEME_LABEL[mode]}）`;
}

function initThemeButton() {
  $('themeBtn').addEventListener('click', async () => {
    const current = config?.settings?.themeMode ?? 'auto';
    const next = THEME_ORDER[(THEME_ORDER.indexOf(current) + 1) % THEME_ORDER.length];

    config = config ?? {};
    config.settings = { ...(config.settings ?? {}), themeMode: next };
    await patchSettings({ themeMode: next });

    renderThemeButton();
    applyFromSettings(config.settings);
    toast(`已切换为${THEME_LABEL[next]}`);
  });
}

/** @returns {Promise<string>} */
async function version() {
  try {
    const data = await sendToBackground({ type: 'ping' });
    return data.version;
  } catch {
    return '0.2.0';
  }
}

/* ── 语言 ─────────────────────────────────────────── */

function renderLanguages() {
  const source = $('sourceLang');
  const target = $('targetLang');
  source.innerHTML = '';
  target.innerHTML = '';

  const auto = document.createElement('option');
  auto.value = 'auto';
  auto.textContent = '自动检测';
  source.appendChild(auto);

  for (const code of Object.keys(LANGUAGES)) {
    const option = document.createElement('option');
    option.value = code;
    option.textContent = LANGUAGES[code].name;
    source.appendChild(option);
  }

  for (const code of SEND_TARGETS) {
    const option = document.createElement('option');
    option.value = code;
    option.textContent = LANGUAGES[code].name;
    target.appendChild(option);
  }

  source.value = 'auto';
  target.value = config?.settings?.defaultTarget ?? 'en';

  source.addEventListener('change', () => {
    $('notice').hidden = true;
  });
  target.addEventListener('change', async () => {
    await patchSettings({ defaultTarget: $('targetLang').value });
    renderStatus();
    renderModeHint();
  });
}

/* ── 模式 ─────────────────────────────────────────── */

function renderMode() {
  const mode = config?.settings?.mode ?? 'free';
  const radio = document.querySelector(`input[name="mode"][value="${mode}"]`);
  if (radio) radio.checked = true;

  for (const input of document.querySelectorAll('input[name="mode"]')) {
    input.addEventListener('change', async () => {
      if (!input.checked) return;
      const selection = input.value === 'free' ? 'auto' : firstCustomId();
      await patchSettings({ mode: input.value, selection });
      renderService();
      renderStatus();
      renderModeHint();
    });
  }
}

/** @returns {string} */
function firstCustomId() {
  const custom = (config?.providers ?? []).find((p) => p.mode === 'custom');
  return custom?.id ?? 'auto';
}

function renderService() {
  const select = $('service');
  select.innerHTML = '';

  const mode = config?.settings?.mode ?? 'free';
  const providers = (config?.providers ?? []).filter((p) => p.mode === mode);

  if (mode === 'free') {
    const auto = document.createElement('option');
    auto.value = 'auto';
    auto.textContent = '自动（按语种优选）';
    select.appendChild(auto);
  }

  for (const provider of providers) {
    const option = document.createElement('option');
    option.value = provider.id;
    option.textContent = provider.kind === 'llm' ? provider.label : provider.label;
    select.appendChild(option);
  }

  const current = config?.settings?.selection ?? 'auto';
  select.value = providers.some((p) => p.id === current) || current === 'auto' ? current : 'auto';
  if (!select.value) select.selectedIndex = 0;

  select.addEventListener('change', async () => {
    await patchSettings({ selection: $('service').value });
    renderStatus();
    renderModeHint();
  });
}

function renderStyle() {
  const style = config?.settings?.style ?? 'business';
  $('style').value = style;
  $('style').addEventListener('change', async () => {
    await patchSettings({ style: $('style').value });
  });
}

function renderCompact() {
  const compact = Boolean(config?.settings?.compact);
  $('compact').checked = compact;
  document.body.classList.toggle('is-compact', compact);
  $('compact').addEventListener('change', async () => {
    const on = $('compact').checked;
    document.body.classList.toggle('is-compact', on);
    await patchSettings({ compact: on });
  });
}

/* ── 状态与提示 ───────────────────────────────────── */

function renderStatus() {
  const providers = config?.providers ?? [];
  const mode = config?.settings?.mode ?? 'free';
  const usable = providers.filter((p) => p.mode === mode);
  const line = $('statusLine');

  if (!providers.length) {
    line.textContent = '还没配置服务，去设置填一个密钥';
    line.style.color = 'var(--warn-ink)';
  } else {
    line.textContent = `${usable.length} 个${mode === 'free' ? '免费额度' : '自定义'}服务可用`;
    line.style.color = '';
  }
}

function renderModeHint() {
  const mode = config?.settings?.mode ?? 'free';
  const target = $('targetLang').value;
  const selection = config?.settings?.selection ?? 'auto';
  const hint = $('modeHint');
  const providers = (config?.providers ?? []).filter((p) => p.mode === mode);

  if (!providers.length) {
    hint.textContent = mode === 'free'
      ? '免费额度模式需要你自己去平台申请密钥，填一次就能一直用。'
      : '自定义 API 模式：填 key + 接口地址 + 模型名，支持任何 OpenAI 兼容接口。';
    hint.classList.add('warn');
    return;
  }

  hint.classList.remove('warn');

  if (mode === 'custom') {
    hint.textContent = '自定义接口走大模型：术语保护、语气控制都更准。';
    return;
  }

  if (selection !== 'auto') {
    const map = PROVIDER_LANG_CODES[selection];
    if (map && !map[target]) {
      hint.textContent = `${labelOf(selection)}不支持${LANGUAGES[target]?.name ?? target}，会自动降级到其他服务。`;
      hint.classList.add('warn');
      return;
    }
  }

  hint.textContent = '服务商失败会自动换下一家，结果里会写明这次是谁翻的。';
}

/** @param {string} id */
function labelOf(id) {
  return (config?.providers ?? []).find((p) => p.id === id)?.label ?? id;
}

/* ── 主按钮：翻译选区 / 剪贴板 ────────────────────── */

$('translate').addEventListener('click', translateSelection);
initThemeButton();

async function translateSelection() {
  const button = $('translate');
  const label = $('primaryLabel');
  const original = label.textContent;

  button.disabled = true;
  label.textContent = '翻译中…';

  try {
    let text = await getSelection();
    let origin = '选区';

    if (!text) {
      text = await readClipboard();
      origin = '剪贴板';
    }
    if (!text) {
      throw new Error('这个页面没有选中文字，剪贴板也是空的');
    }

    const result = await sendToBackground({
      type: 'translateText',
      text,
      source: $('sourceLang').value === 'auto' ? null : $('sourceLang').value,
      target: $('targetLang').value
    });

    lastResult = { ...result, origin };
    showResult(result, text, origin);
  } catch (error) {
    toast(error.message || '翻译失败');
  } finally {
    button.disabled = false;
    label.textContent = original;
  }
}

/** @returns {Promise<string>} */
async function getSelection() {
  const tab = await activeTab();
  if (!tab?.id) return '';
  try {
    const reply = await sendToTab(tab.id, { type: 'getSelection' });
    return (reply?.text ?? '').trim();
  } catch {
    return '';
  }
}

/** @returns {Promise<string>} */
async function readClipboard() {
  try {
    const text = await navigator.clipboard.readText();
    return (text ?? '').trim();
  } catch {
    return '';
  }
}

/**
 * @param {any} result
 * @param {string} source
 * @param {string} origin
 */
function showResult(result, source, origin) {
  $('result').hidden = false;
  $('resultSrc').textContent = `${origin}：${source}`;
  $('resultDst').textContent = result.text;
  $('resultProvider').textContent = result.provider;
  $('resultLatency').textContent = `${result.latency} ms`;

  const degraded = $('resultDegraded');
  if (result.degradedFrom) {
    degraded.hidden = false;
    degraded.textContent = `${result.degradedFrom} 没能用上，已自动换到 ${result.provider}`;
  } else {
    degraded.hidden = true;
  }

  $('replaceSelection').hidden = origin !== '选区';
}

/* ── 替换原文 ─────────────────────────────────────── */

$('replaceSelection').addEventListener('click', async () => {
  if (!lastResult) return;
  const tab = await activeTab();
  if (!tab?.id) return;
  try {
    await sendToTab(tab.id, { type: 'replaceSelection', text: lastResult.text });
    toast('已替换原文');
  } catch (error) {
    toast('替换失败：' + error.message);
  }
});

/* ── 四个功能 ─────────────────────────────────────── */

/**
 * 文档/文本是入口按钮；鼠标悬停/划词是开关（要显示当前状态）。
 * 状态必须在 config 到位之后再写，否则第一次打开全是"关"。
 */
function renderTiles() {
  for (const tile of document.querySelectorAll('.tile')) {
    const key = tile.dataset.toggle;
    if (!key) continue;
    tile.setAttribute('aria-pressed', String(Boolean(config?.settings?.[key])));
  }
}

for (const tile of document.querySelectorAll('.tile')) {
  const action = tile.dataset.action;

  if (action === 'doc' || action === 'text') {
    tile.addEventListener('click', () => {
      const page = action === 'doc' ? 'src/doc/index.html' : 'src/text/index.html';
      EXT.tabs.create({ url: EXT.runtime.getURL(page) });
      window.close();
    });
    continue;
  }

  const key = tile.dataset.toggle;

  tile.addEventListener('click', async () => {
    const next = tile.getAttribute('aria-pressed') !== 'true';
    tile.setAttribute('aria-pressed', String(next));
    await patchSettings({ [key]: next });
    toast(`${tile.querySelector('span').textContent}已${next ? '开启' : '关闭'}`);
  });
}

/* ── 更多功能 ─────────────────────────────────────── */

$('openOptions').addEventListener('click', () => {
  EXT.runtime.openOptionsPage();
  window.close();
});

$('more').addEventListener('click', (event) => {
  event.stopPropagation();
  const menu = $('moreMenu');
  menu.hidden = !menu.hidden;
});

document.addEventListener('click', () => {
  $('moreMenu').hidden = true;
});

for (const item of document.querySelectorAll('[data-menu]')) {
  item.addEventListener('click', async () => {
    $('moreMenu').hidden = true;
    await runMenu(item.dataset.menu);
  });
}

/** @param {string} action */
async function runMenu(action) {
  if (action === 'about') {
    toast(`微翻 · 本机密钥版\n${(await version())}`);
    return;
  }
  if (action === 'releases') {
    EXT.tabs.create({ url: 'https://github.com/cyloct-cpu/weifan/releases' });
    window.close();
    return;
  }
  if (action === 'issues') {
    EXT.tabs.create({ url: 'https://github.com/cyloct-cpu/weifan/issues' });
    window.close();
    return;
  }
  if (action === 'test') {
    const selection = config?.settings?.selection ?? 'auto';
    if (selection === 'auto') {
      toast('下拉里选一家具体的再测');
      return;
    }
    try {
      const result = await sendToBackground({ type: 'testProvider', providerId: selection });
      toast(`${result.provider} 正常：${result.text.slice(0, 26)}（${result.latency}ms）`);
    } catch (error) {
      toast(error.message || '测试失败');
    }
    return;
  }
  if (action === 'usage') {
    try {
      const data = await sendToBackground({ type: 'usage' });
      const used = Object.entries(data.usage ?? {});
      toast(used.length
        ? `今日用量：${used.map(([k, v]) => `${k} ${v} 字`).join(' · ')}`
        : '今天还没用过');
    } catch (error) {
      toast('读不到用量：' + error.message);
    }
    return;
  }
  if (action === 'cache') {
    try {
      await sendToBackground({ type: 'clearCache' });
      toast('缓存已清空');
    } catch (error) {
      toast('清空失败：' + error.message);
    }
    return;
  }
  if (action === 'reset') {
    const confirmed = confirm('恢复默认设置？密钥不会被删除，但语言、模式、服务选择都会重置。');
    if (!confirmed) return;
    await patchSettings({
      selection: 'auto', mode: 'free', style: 'business', compact: false,
      hoverTranslate: false, selectionTranslate: true, dailyCharLimit: 0
    });
    config.settings = { ...config.settings, selection: 'auto', mode: 'free', style: 'business', compact: false };
    renderMode();
    renderService();
    renderStyle();
    renderCompact();
    renderStatus();
    renderModeHint();
    toast('已恢复默认');
  }
}

/* ── 当前页是否可翻译 ─────────────────────────────── */

async function checkPage() {
  const tab = await activeTab();
  if (!tab?.id) return;
  try {
    await sendToTab(tab.id, { type: 'ping' });
  } catch {
    const notice = $('notice');
    notice.hidden = false;
    notice.textContent = '当前页面不可翻译（浏览器内部页面、扩展商店等），换一个普通网页再试。';
  }
}

/* ── 工具 ─────────────────────────────────────────── */

/** @returns {Promise<any>} */
async function activeTab() {
  try {
    const tabs = await EXT.tabs.query({ active: true, currentWindow: true });
    return tabs?.[0];
  } catch {
    return null;
  }
}

/**
 * @param {number} tabId
 * @param {any} message
 */
function sendToTab(tabId, message) {
  return new Promise((resolve, reject) => {
    try {
      EXT.tabs.sendMessage(tabId, message, (reply) => {
        const lastError = globalThis.chrome?.runtime?.lastError;
        if (lastError) {
          reject(new Error(lastError.message));
          return;
        }
        resolve(reply);
      });
    } catch (error) {
      reject(error);
    }
  });
}

/**
 * 改设置并同步内存里的 config。
 * @param {Object} patch
 */
async function patchSettings(patch) {
  try {
    const settings = await sendToBackground({ type: 'setSettings', patch });
    config.settings = { ...config.settings, ...settings };
  } catch (error) {
    toast('保存失败：' + error.message);
  }
}

/** @param {string} message */
function toast(message) {
  const el = $('toast');
  el.textContent = message;
  el.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => {
    el.hidden = true;
  }, 2600);
}
