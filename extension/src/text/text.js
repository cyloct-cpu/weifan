/**
 * 微翻 · 文本翻译页
 * 左原文右译文，中间一排控制条；翻译请求发给 background。
 */

import { EXT, sendToBackground } from '../shared/ext.js';
import { LANGUAGES, SEND_TARGETS } from '../core/language.js';

const $ = (id) => document.getElementById(id);

/** @type {any} */
let config = null;

init();

async function init() {
  config = await sendToBackground({ type: 'getConfig' });

  const source = $('sourceLang');
  const target = $('targetLang');
  const service = $('service');

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
  target.value = config.settings?.defaultTarget ?? 'en';
  $('style').value = config.settings?.style ?? 'business';

  const autoOption = document.createElement('option');
  autoOption.value = 'auto';
  autoOption.textContent = '自动（按语种优选）';
  service.appendChild(autoOption);
  for (const provider of config.providers ?? []) {
    const option = document.createElement('option');
    option.value = provider.id;
    option.textContent = provider.label;
    service.appendChild(option);
  }
  service.value = config.settings?.selection ?? 'auto';
  if (!service.value) service.selectedIndex = 0;

  $('run').addEventListener('click', run);
  $('copy').addEventListener('click', copyOutput);
  $('paste').addEventListener('click', pasteInput);
  $('swap').addEventListener('click', swap);

  $('input').addEventListener('keydown', (event) => {
    if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') run();
  });
}

async function run() {
  const text = $('input').value.trim();
  if (!text) {
    setStatus('先粘贴一段文本', 'err');
    return;
  }

  $('run').disabled = true;
  setStatus('翻译中…');

  try {
    const result = await sendToBackground({
      type: 'translateText',
      text,
      source: $('sourceLang').value === 'auto' ? null : $('sourceLang').value,
      target: $('targetLang').value,
      style: $('style').value,
      selection: $('service').value
    });
    $('output').value = result.text;
    setStatus(
      result.degradedFrom
        ? `${result.provider} 翻译完成（${result.degradedFrom} 已降级）· ${result.latency} ms`
        : `${result.provider} 翻译完成 · ${result.latency} ms`,
      'ok'
    );
  } catch (error) {
    setStatus(error.message, 'err');
  } finally {
    $('run').disabled = false;
  }
}

async function copyOutput() {
  const text = $('output').value;
  if (!text) {
    setStatus('还没有译文', 'err');
    return;
  }
  try {
    await navigator.clipboard.writeText(text);
    setStatus('已复制', 'ok');
  } catch {
    $('output').select();
    setStatus('请手动复制（Ctrl+C）', 'err');
  }
}

async function pasteInput() {
  try {
    $('input').value = await navigator.clipboard.readText();
    setStatus('已粘贴', 'ok');
  } catch {
    setStatus('读不到剪贴板，直接粘贴到左边就行', 'err');
  }
}

function swap() {
  const source = $('sourceLang').value;
  const target = $('targetLang').value;
  if (source === 'auto') {
    setStatus('源语言是「自动检测」，没法对调', 'err');
    return;
  }
  $('sourceLang').value = target;
  $('targetLang').value = source;
  const output = $('output').value;
  $('output').value = $('input').value;
  $('input').value = output;
}

/**
 * @param {string} message
 * @param {'ok'|'err'|'info'} [kind]
 */
function setStatus(message, kind = 'info') {
  const el = $('status');
  el.textContent = message;
  el.className = kind === 'ok' ? 'is-ok' : kind === 'err' ? 'is-err' : '';
}
