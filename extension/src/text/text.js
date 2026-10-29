/**
 * 微翻 · 文本翻译页
 *
 * 三个可输入的语言框：源语言 / 目标语言 / 第二个目标语言。
 * - 都能直接打字（datalist 提示，输入 "en"、"english"、"英语" 都能命中）
 * - 「⇄」在源与目标之间互换（语言和原文/译文内容一起换）
 * - 第二个「⇄」在两个目标之间互换（两份译文对调）
 * - 填了第二个目标语言，就同时输出两种语言的译文
 */

import { EXT, sendToBackground } from '../shared/ext.js';
import { LANGUAGES, detectLanguage } from '../core/language.js';

const $ = (id) => document.getElementById(id);

/** 语言代码 → 显示名 */
const LANG_ENTRIES = Object.entries(LANGUAGES);

/** 显示名 → 语言代码 */
const CODE_BY_LABEL = new Map([
  ['自动检测', 'auto'],
  ...LANG_ENTRIES.map(([code, meta]) => [`${meta.name}（${code}）`, code]),
  ...LANG_ENTRIES.map(([code, meta]) => [meta.name, code]),
  ...LANG_ENTRIES.map(([code, meta]) => [code, code])
]);

/**
 * 从输入框的值反查语言代码。认不出来返回 null。
 * @param {string} value
 */
function codeOf(value) {
  const v = (value ?? '').trim();
  if (!v) return null;
  if (CODE_BY_LABEL.has(v)) return CODE_BY_LABEL.get(v);
  // 「英语（en」这种半截的写法，取括号里的代码
  const m = v.match(/[（(]([a-zA-Z-]{2,8})[）)]?\s*$/);
  if (m && LANGUAGES[m[1]]) return m[1];
  const lower = v.toLowerCase();
  const hit = LANG_ENTRIES.find(([code, meta]) => meta.name.toLowerCase() === lower || code.toLowerCase() === lower);
  return hit ? hit[0] : null;
}

/**
 * 语言代码 → 显示名
 * @param {string} code
 */
function labelOf(code) {
  if (!code) return '';
  if (code === 'auto') return '自动检测';
  const meta = LANGUAGES[code];
  return meta ? `${meta.name}（${code}）` : code;
}

init();

async function init() {
  // datalist：自动检测 + 全部语言
  const list = $('langlist');
  list.innerHTML = '<option value="自动检测"></option>';
  for (const [code, meta] of LANG_ENTRIES) {
    const option = document.createElement('option');
    option.value = `${meta.name}（${code}）`;
    list.appendChild(option);
  }

  const config = await sendToBackground({ type: 'getConfig' });
  $('style').value = config.settings?.style ?? 'business';

  const service = $('service');
  const auto = document.createElement('option');
  auto.value = 'auto';
  auto.textContent = '自动（按语种优选）';
  service.appendChild(auto);
  for (const provider of config.providers ?? []) {
    const option = document.createElement('option');
    option.value = provider.id;
    option.textContent = provider.label;
    service.appendChild(option);
  }
  service.value = config.settings?.selection ?? 'auto';
  if (!service.value) service.selectedIndex = 0;

  // 语言框初值
  $('srcLang').value = labelOf('auto');
  $('dstLang').value = labelOf(config.settings?.defaultTarget ?? 'en');

  $('run').addEventListener('click', run);
  $('copy').addEventListener('click', () => copyResult($('output').value));
  $('copy2').addEventListener('click', () => copyResult($('output2').value));
  $('paste').addEventListener('click', pasteInput);
  $('swap1').addEventListener('click', swapSourceTarget);
  $('swap2').addEventListener('click', swapTargets);

  // 第二个目标语言填了才显示第二块译文
  $('dstLang2').addEventListener('input', syncSecondPane);
  $('dstLang2').addEventListener('change', syncSecondPane);
}

/** 第二个目标语言是否已填 */
function secondTarget() {
  return codeOf($('dstLang2').value);
}

function syncSecondPane() {
  $('pane2').hidden = !secondTarget();
}

/** 源语言 ⇄ 目标语言：语言和原文/译文内容一起换 */
function swapSourceTarget() {
  const src = $('srcLang').value;
  const dst = $('dstLang').value;
  $('srcLang').value = dst;
  $('dstLang').value = src;

  const original = $('input').value;
  const translated = $('output').value;
  if (translated) {
    $('input').value = translated;
    $('output').value = original;
  }
}

/** 两个目标语言互换：两份译文对调 */
function swapTargets() {
  const a = $('dstLang').value;
  const b = $('dstLang2').value;
  if (!b) return;
  $('dstLang').value = b;
  $('dstLang2').value = a;

  const t1 = $('output').value;
  const t2 = $('output2').value;
  $('output').value = t2;
  $('output2').value = t1;
}

async function run() {
  const text = $('input').value.trim();
  if (!text) {
    setStatus('先粘贴一段文本', 'err');
    return;
  }

  let source = codeOf($('srcLang').value) ?? 'auto';
  let target = codeOf($('dstLang').value);
  const target2 = secondTarget();

  if (!target && !target2) {
    setStatus('先填一个目标语言', 'err');
    return;
  }

  // 源与目标同语言 = 白翻一遍，自动翻到另一边
  let note = '';
  const detected = detectLanguage(text)?.language;
  if (detected && target === detected) {
    const flipped = detected.startsWith('zh') ? 'en' : 'zh-Hans';
    $('dstLang').value = labelOf(flipped);
    if (source === 'auto') {
      source = detected;
      $('srcLang').value = labelOf(detected);
    }
    target = flipped;
    note = `（源与目标同语言，已自动改为 ${LANGUAGES[flipped]?.name ?? flipped}）`;
  }

  const targets = [target, target2].filter(Boolean);
  $('run').disabled = true;
  setStatus('翻译中…');

  try {
    const results = await Promise.all(
      targets.map((code) =>
        sendToBackground({
          type: 'translateText',
          text,
          source: source === 'auto' ? null : source,
          target: code,
          style: $('style').value,
          selection: $('service').value
        }).then((result) => ({ ok: true, result }), (error) => ({ ok: false, error: error.message }))
      )
    );

    const first = results[0];
    if (!first.ok) {
      setStatus(`失败：${first.error}`, 'err');
      return;
    }
    $('output').value = first.result.text;

    const second = results[1];
    if (second) {
      $('output2').value = second.ok ? second.result.text : `（第二个目标语言翻译失败：${second.error}）`;
      syncSecondPane();
    }

    const degraded = first.result.degradedFrom ? `（已从 ${first.result.degradedFrom} 降级）` : '';
    setStatus(`${first.result.provider}${degraded} · ${first.result.latency}ms${note}`, 'ok');
  } catch (error) {
    setStatus(error.message || '翻译失败', 'err');
  } finally {
    $('run').disabled = false;
  }
}

async function copyResult(text) {
  if (!text) {
    setStatus('还没有译文', 'err');
    return;
  }
  try {
    await navigator.clipboard.writeText(text);
    setStatus('已复制', 'ok');
  } catch {
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

/**
 * @param {string} message
 * @param {'ok'|'err'|'info'} [kind]
 */
function setStatus(message, kind = 'info') {
  const el = $('status');
  el.textContent = message;
  el.className = kind === 'ok' ? 'is-ok' : kind === 'err' ? 'is-err' : '';
}
