/**
 * 微翻 · 文档翻译
 *
 * 支持：
 *   txt / md / csv  直接读，按段落切
 *   pdf             内置 pdf.js 抽文字（版式不保留）
 *   docx            解 zip 读 word/document.xml，逐段翻译后写回新 docx（段落保留，字体表格不保留）
 *
 * 文件全程留在本机，只有切好的段落会发给 background 翻译。
 */

import { EXT, sendToBackground } from '../shared/ext.js';
import { LANGUAGES, SEND_TARGETS } from '../core/language.js';
import { unzipSync, zipSync, strToU8, strFromU8 } from '../vendor/fflate.js';
import { applyFromSettings, watchTheme } from '../shared/theme.js';

const $ = (id) => document.getElementById(id);

const BATCH = 10;

/** @type {any} */
let config = null;
/** @type {File|null} */
let file = null;

init();

async function init() {
  config = await sendToBackground({ type: 'getConfig' });

  // 主题跟随全局设置
  applyFromSettings(config.settings ?? {});
  watchTheme(config.settings ?? {});

  const target = $('targetLang');
  for (const code of SEND_TARGETS) {
    const option = document.createElement('option');
    option.value = code;
    option.textContent = LANGUAGES[code].name;
    target.appendChild(option);
  }
  target.value = config.settings?.defaultTarget ?? 'en';

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

  $('style').value = config.settings?.style ?? 'business';

  $('file').addEventListener('change', (event) => {
    const picked = event.target.files?.[0];
    if (picked) setFile(picked);
  });

  const drop = $('drop');
  for (const type of ['dragenter', 'dragover']) {
    drop.addEventListener(type, (event) => {
      event.preventDefault();
      drop.classList.add('is-over');
    });
  }
  for (const type of ['dragleave', 'drop']) {
    drop.addEventListener(type, (event) => {
      event.preventDefault();
      drop.classList.remove('is-over');
    });
  }
  drop.addEventListener('drop', (event) => {
    const dropped = event.dataTransfer?.files?.[0];
    if (dropped) setFile(dropped);
  });

  $('run').addEventListener('click', run);
}

/** @param {File} picked */
function setFile(picked) {
  file = picked;
  $('fileName').hidden = false;
  $('fileName').textContent = `${picked.name}　·　${(picked.size / 1024).toFixed(0)} KB`;
  $('run').disabled = false;
  $('preview').hidden = true;
  setStatus('准备好了');
}

/* ── 主流程 ─────────────────────────────────────── */

async function run() {
  if (!file) return;
  $('run').disabled = true;
  $('preview').hidden = true;
  $('progress').hidden = false;
  setProgress(0);

  try {
    const kind = detectKind(file);
    const parsed = await parse(file, kind);

    if (!parsed.units.length) {
      throw new Error('这个文件里没找到可翻译的文字');
    }

    setStatus(`共 ${parsed.units.length} 段，开始翻译…`);
    const translated = await translateAll(parsed.units);

    const blob = await rebuild(parsed, translated, kind);
    download(blob, outputName(file.name, kind));

    showPreview(parsed.units, translated);
    setStatus('翻完啦，已开始下载', 'ok');
  } catch (error) {
    setStatus(error.message || '翻译失败', 'err');
  } finally {
    $('run').disabled = false;
    $('progress').hidden = true;
  }
}

/** @param {string} name */
function detectKind(name) {
  const lower = name.toLowerCase();
  if (lower.endsWith('.pdf')) return 'pdf';
  if (lower.endsWith('.docx')) return 'docx';
  if (lower.endsWith('.csv')) return 'csv';
  if (lower.endsWith('.md') || lower.endsWith('.markdown')) return 'md';
  return 'txt';
}

/**
 * 解析文件 → 段落数组。
 * @param {File} f
 * @param {string} kind
 */
async function parse(f, kind) {
  if (kind === 'pdf') {
    const pages = await extractPdf(await f.arrayBuffer());
    return { kind, units: pages.join('\n\n').split(/\n{2,}/).map((s) => s.trim()).filter(Boolean), pages };
  }

  if (kind === 'docx') {
    const entries = unzipSync(new Uint8Array(await f.arrayBuffer()));
    const xmlEntry = entries['word/document.xml'];
    if (!xmlEntry) throw new Error('这不是一个 Word 文档（缺少 word/document.xml）');
    const xml = strFromU8(xmlEntry);
    const paragraphs = readDocxParagraphs(xml);
    // 保留空段落的位置，units 与 paragraphs 一一对应，译文才能写回原处
    return { kind, units: paragraphs.map((p) => p.text), paragraphs, xml, entries };
  }

  const text = await f.text();
  const units = kind === 'csv'
    ? text.split(/\r?\n/).map((s) => s.trim()).filter(Boolean)
    : text.split(/\n\s*\n/).map((s) => s.trim()).filter(Boolean);
  return { kind, units, raw: text };
}

/**
 * 逐段翻译。
 * @param {string[]} units
 * @returns {Promise<string[]>}
 */
async function translateAll(units) {
  const results = new Array(units.length);
  let done = 0;

  for (let i = 0; i < units.length; i += BATCH) {
    // 空段落不发请求，但保留索引，译文要写回原处
    const slice = units
      .slice(i, i + BATCH)
      .map((text, offset) => ({ text, index: i + offset }))
      .filter((item) => item.text && item.text.trim());

    if (!slice.length) continue;

    const replies = await sendToBackground({
      type: 'translateBatch',
      items: slice.map((item) => ({
        ...item,
        source: null,
        target: $('targetLang').value,
        style: $('style').value,
        selection: $('service').value
      }))
    });

    for (const reply of replies) {
      if (reply.ok) {
        results[reply.index] = reply.text;
      } else {
        results[reply.index] = units[reply.index];
        console.warn('这一段没翻成功：', reply.error);
      }
      done += 1;
    }

    setProgress(done / units.length);
    setStatus(`已翻译 ${done} / ${units.length} 段`);
  }

  return results;
}

/* ── 输出 ───────────────────────────────────────── */

/**
 * @param {any} parsed
 * @param {string[]} translated
 * @param {string} kind
 * @returns {Promise<Blob>}
 */
async function rebuild(parsed, translated, kind) {
  if (kind === 'docx') {
    const xml = replaceDocxText(parsed.xml, parsed.paragraphs, translated);
    const entries = { ...parsed.entries, 'word/document.xml': strToU8(xml) };
    const zipped = zipSync(entries, { level: 6 });
    return new Blob([zipped], { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' });
  }

  if (kind === 'pdf') {
    const text = translated.join('\n\n');
    return new Blob([text], { type: 'text/plain;charset=utf-8' });
  }

  const separator = kind === 'csv' ? '\n' : '\n\n';
  const text = translated.join(separator);
  return new Blob([text], { type: 'text/plain;charset=utf-8' });
}

/**
 * @param {string} name
 * @param {string} kind
 */
function outputName(name, kind) {
  const base = name.replace(/\.[^.]+$/, '');
  if (kind === 'docx') return `${base}.译文.docx`;
  if (kind === 'pdf') return `${base}.译文.txt`;
  return `${base}.译文${kind === 'csv' ? '.csv' : kind === 'md' ? '.md' : '.txt'}`;
}

/**
 * @param {Blob} blob
 * @param {string} name
 */
function download(blob, name) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 4000);
}

/* ── PDF ────────────────────────────────────────── */

async function extractPdf(buffer) {
  const pdfjs = await import('../vendor/pdfjs/pdf.mjs');
  pdfjs.GlobalWorkerOptions.workerSrc = EXT.runtime.getURL('src/vendor/pdfjs/pdf.worker.mjs');

  const doc = await pdfjs.getDocument({ data: buffer }).promise;
  const pages = [];

  for (let i = 1; i <= doc.numPages; i += 1) {
    const page = await doc.getPage(i);
    const content = await page.getTextContent();

    // 按 y 坐标聚成行
    const rows = new Map();
    for (const item of content.items) {
      if (!item.str) continue;
      const y = Math.round(item.transform?.[5] ?? 0);
      let key = y;
      for (const existing of rows.keys()) {
        if (Math.abs(existing - y) < 3) {
          key = existing;
          break;
        }
      }
      rows.set(key, (rows.get(key) ?? '') + item.str);
    }

    const lines = [...rows.entries()]
      .sort((a, b) => b[0] - a[0])
      .map(([, text]) => text.trim())
      .filter(Boolean);

    pages.push(lines.join('\n'));
  }

  await doc.destroy();
  return pages;
}

/* ── docx ───────────────────────────────────────── */

/**
 * 读出所有段落（保留原文 XML 位置信息）。
 * @param {string} xml
 */
function readDocxParagraphs(xml) {
  const paragraphs = [];
  const re = /<w:p(?:\s[^>]*)?>[\s\S]*?<\/w:p>/g;
  let match;

  while ((match = re.exec(xml)) !== null) {
    const inner = match[1];
    let text = '';
    const textRe = /<w:t(?:\s[^>]*)?>([\s\S]*?)<\/w:t>|<w:tab\s*\/>/g;
    let piece;
    while ((piece = textRe.exec(inner)) !== null) {
      text += piece[1] !== undefined ? unescapeXml(piece[1]) : '\t';
    }
    paragraphs.push({ full: match[0], text: text.trim() });
  }

  return paragraphs;
}

/**
 * 把译文写回段落（保留 w:pPr，段落结构与数量不变）。
 * @param {string} xml
 * @param {{full: string, text: string}[]} paragraphs
 * @param {string[]} translated
 */
function replaceDocxText(xml, paragraphs, translated) {
  let cursor = 0;
  return xml.replace(/<w:p(?:\s[^>]*)?>[\s\S]*?<\/w:p>/g, (full) => {
    const index = cursor;
    cursor += 1;
    const paragraph = paragraphs[index];
    if (!paragraph || !paragraph.text) return full;

    const translatedText = translated[index];
    if (!translatedText) return full;

    const pPr = /<w:pPr>[\s\S]*?<\/w:pPr>/.exec(full)?.[0] ?? '';
    return `<w:p>${pPr}<w:r><w:t xml:space="preserve">${escapeXml(translatedText)}</w:t></w:r></w:p>`;
  });
}

/** @param {string} text */
function escapeXml(text) {
  return String(text)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;');
}

/** @param {string} text */
function unescapeXml(text) {
  return String(text)
    .replaceAll('&lt;', '<')
    .replaceAll('&gt;', '>')
    .replaceAll('&quot;', '"')
    .replaceAll('&apos;', "'")
    .replaceAll('&amp;', '&');
}

/* ── 预览与状态 ─────────────────────────────────── */

/**
 * @param {string[]} units
 * @param {string[]} translated
 */
function showPreview(units, translated) {
  const body = $('previewBody');
  body.innerHTML = '';
  const limit = Math.min(units.length, 30);

  for (let i = 0; i < limit; i += 1) {
    const pair = document.createElement('div');
    pair.className = 'pair';

    const original = document.createElement('p');
    original.className = 'o';
    original.textContent = units[i];

    const target = document.createElement('p');
    target.className = 't';
    target.textContent = translated[i] ?? '';

    pair.append(original, target);
    body.appendChild(pair);
  }

  if (units.length > limit) {
    const more = document.createElement('p');
    more.className = 'o';
    more.textContent = `… 还有 ${units.length - limit} 段没显示`;
    body.appendChild(more);
  }

  $('preview').hidden = false;
}

/** @param {number} ratio */
function setProgress(ratio) {
  $('progressFill').style.width = `${Math.round(ratio * 100)}%`;
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
