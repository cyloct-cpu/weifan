/**
 * 微翻 · 页面代理
 *
 * 负责三件事：
 * 1. 划词翻译 —— 选中文字后浮出可操作气泡（可编辑 / 复制 / 一键替换原文）
 * 2. 鼠标悬停 —— 鼠标停 0.6 秒显示只读译文气泡
 * 3. 给弹窗提供「取选区 / 替换原文」的能力
 *
 * 为什么是经典脚本而不是模块：manifest 的 content_scripts 不支持 type: module。
 * 气泡用 Shadow DOM 隔离，避免被页面样式污染，也不污染页面。
 *
 * 所有翻译请求都发给 background（密钥不進页面）。
 */

(function () {
  'use strict';

  const EXT = globalThis.browser?.runtime?.id ? globalThis.browser : globalThis.chrome;
  if (!EXT?.runtime?.id) return;

  const HOST_ID = 'weifan-bubble-host';
  const HOVER_DELAY = 600;
  const MIN_LEN = 1;
  const MAX_LEN = 600;

  /** @type {any} */
  let settings = {};
  let hoverEnabled = false;
  let selectionEnabled = true;
  let target = 'en';
  let style = 'business';

  /** @type {ShadowRoot|null} */
  let shadow = null;
  /** @type {HTMLElement|null} */
  let bubble = null;
  let hoverTimer = 0;
  let lastHoverWord = '';
  let hoverInFlight = false;

  /* ── 与后台通信 ─────────────────────────────── */

  function send(message) {
    return new Promise((resolve, reject) => {
      try {
        EXT.runtime.sendMessage(message, (reply) => {
          const lastError = globalThis.chrome?.runtime?.lastError;
          if (lastError) {
            reject(new Error(lastError.message));
            return;
          }
          if (!reply) {
            reject(new Error('后台没响应'));
            return;
          }
          if (reply.ok === false) {
            reject(new Error(reply.error || '未知错误'));
            return;
          }
          resolve(reply.data);
        });
      } catch (error) {
        reject(error);
      }
    });
  }

  async function loadSettings() {
    try {
      const config = await send({ type: 'getConfig' });
      settings = config.settings ?? {};
      hoverEnabled = Boolean(settings.hoverTranslate);
      selectionEnabled = settings.selectionTranslate !== false;
      target = settings.defaultTarget ?? 'en';
      style = settings.style ?? 'business';
    } catch {
      // 后台还没起来就先用默认值
    }
  }

  /* ── 气泡 ───────────────────────────────────── */

  function ensureBubble() {
    if (bubble) return bubble;

    if (!document.getElementById(HOST_ID)) {
      const host = document.createElement('div');
      host.id = HOST_ID;
      host.style.cssText = 'position:fixed;z-index:2147483000;top:0;left:0;width:0;height:0;';
      document.documentElement.appendChild(host);
      shadow = host.attachShadow({ mode: 'open' });
      shadow.innerHTML = `
        <style>
          .box {
            position: fixed;
            min-width: 180px;
            max-width: 320px;
            background: #ffffff;
            color: #111b21;
            border: 1px solid rgba(22,27,34,.12);
            border-radius: 12px;
            box-shadow: 0 6px 24px -8px rgba(22,27,34,.28);
            font: 13px/1.55 -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif;
            padding: 9px 11px;
            opacity: 0;
            transform: translateY(4px);
            transition: opacity .14s ease, transform .14s ease;
          }
          .box.show { opacity: 1; transform: none; }
          .row { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; }
          .tag {
            font-size: 10px; font-weight: 600; color: #00806f;
            background: rgba(0,233,221,.16); border-radius: 999px; padding: 2px 7px;
          }
          .tag.ghost { color: #8696a0; background: #f5f6f6; border: 1px solid rgba(22,27,34,.09); }
          .text { white-space: pre-wrap; word-break: break-word; }
          .text.busy { color: #8696a0; }
          textarea {
            width: 100%; min-height: 54px; resize: vertical;
            background: #f5f6f6; border: 1px solid rgba(22,27,34,.12); border-radius: 8px;
            color: #111b21; font: 13px/1.55 inherit; padding: 7px 8px; outline: none;
          }
          textarea:focus { border-color: #128c7e; box-shadow: 0 0 0 3px rgba(18,140,126,.18); }
          .acts { display: flex; gap: 6px; margin-top: 7px; }
          button {
            flex: 1; background: #f5f6f6; border: 1px solid rgba(22,27,34,.1); border-radius: 7px;
            padding: 5px 8px; font: 12px/1.3 inherit; color: #667781; cursor: pointer;
          }
          button:hover { color: #111b21; border-color: rgba(22,27,34,.2); }
          button:disabled { opacity: .45; cursor: default; }
          button.primary { background: #128c7e; border-color: #128c7e; color: #fff; font-weight: 600; }
          @media (prefers-color-scheme: dark) {
            .box { background: #111b21; color: #e9edef; border-color: #22303a; box-shadow: 0 8px 26px -8px rgba(0,0,0,.6); }
            textarea, button { background: #182229; border-color: #22303a; color: #e9edef; }
            .tag.ghost { background: #1d242e; border-color: #2a333f; color: #8b96a6; }
          }
        </style>
        <div class="box" id="box"></div>
      `;
    }

    bubble = shadow.getElementById('box');
    return bubble;
  }

  /**
   * 显示气泡。
   * @param {{x: number, y: number}} at
   * @param {string} html
   * @param {boolean} [sticky] 是否显示关闭按钮
   */
  function showBubble(at, html, sticky) {
    const box = ensureBubble();
    box.innerHTML = html;
    box.classList.add('show');

    const rect = box.getBoundingClientRect();
    const width = rect.width || 240;
    const height = rect.height || 80;
    let left = at.x;
    let top = at.y + 14;

    if (left + width > window.innerWidth - 8) left = window.innerWidth - width - 8;
    if (left < 8) left = 8;
    if (top + height > window.innerHeight - 8) top = Math.max(8, at.y - height - 12);

    box.style.left = `${Math.round(left)}px`;
    box.style.top = `${Math.round(top)}px`;
    box.dataset.sticky = sticky ? '1' : '';
  }

  function hideBubble() {
    if (!bubble) return;
    bubble.classList.remove('show');
  }

  /* ── 划词 ───────────────────────────────────── */

  document.addEventListener('mouseup', (event) => {
    if (!selectionEnabled) return;
    if (isOurs(event.target)) return;
    if (event.target instanceof HTMLElement && ['INPUT', 'TEXTAREA'].includes(event.target.tagName)) return;

    setTimeout(() => {
      const selection = window.getSelection();
      const text = (selection?.toString() ?? '').trim();
      if (!text || text.length < MIN_LEN || text.length > MAX_LEN) return;

      const range = selection.getRangeAt(0);
      const rect = range.getBoundingClientRect();
      if (!rect.width && !rect.height) return;

      runSelectionFlow(text, { left: rect.left, bottom: rect.bottom });
    }, 10);
  });

  /**
   * 划词翻译主流程：先弹「翻译中…」，拿到译文换成可操作气泡。
   * mouseup（划词）与右键菜单 / 快捷键（translateSelection 消息）共用这一条链路。
   * @param {string} text
   * @param {{left: number, bottom: number}} at 选区位置
   */
  function runSelectionFlow(text, at) {
    showBubble(
      at,
      `<div class="row"><span class="tag">翻译中…</span></div><div class="text busy">${escapeHtml(text)}</div>`,
      true
    );

    translate(text)
      .then((result) => {
        showBubble(
          at,
          `<div class="row">
             <span class="tag">${escapeHtml(result.provider)}</span>
             ${result.degradedFrom ? `<span class="tag ghost">已从 ${escapeHtml(result.degradedFrom)} 降级</span>` : ''}
           </div>
           <textarea spellcheck="false"></textarea>
           <div class="acts">
             <button type="button" data-act="speak">朗读</button>
             <button type="button" data-act="copy">复制</button>
             <button type="button" data-act="replace" class="primary">替换原文</button>
           </div>`,
          true
        );
        const area = bubble.querySelector('textarea');
        area.value = result.text;
        area.addEventListener('click', (e) => e.stopPropagation());
        area.addEventListener('mousedown', (e) => e.stopPropagation());
        setupSpeakButton(bubble.querySelector('[data-act="speak"]'), area, target);
        bubble.querySelector('[data-act="copy"]').addEventListener('click', async () => {
          await copyText(area.value);
          flash(bubble.querySelector('[data-act="copy"]'), '已复制');
        });
        bubble.querySelector('[data-act="replace"]').addEventListener('click', () => {
          replaceSelection(area.value);
        });
      })
      .catch((error) => {
        showBubble(
          at,
          `<div class="row"><span class="tag ghost">翻译失败</span></div><div class="text">${escapeHtml(error.message)}</div>`,
          true
        );
      });
  }

  /** 朗读按钮：浏览器/系统内置语音合成，零成本零请求。读不了的语言置灰说明，不弹窗打断。 */
  function setupSpeakButton(button, area, langCode) {
    if (!button) return;
    const synth = globalThis.speechSynthesis;
    const tag = globalThis.WeifanTts?.ttsTag?.(langCode) ?? null;
    if (!synth || !tag) {
      button.disabled = true;
      button.title = '这个语言没有对应的语音';
      return;
    }
    button.addEventListener('click', () => {
      synth.cancel();
      const utterance = new SpeechSynthesisUtterance(area.value);
      utterance.lang = tag;
      synth.speak(utterance);
    });
  }

  /** 用译文替换当前选区 */
  function replaceSelection(text) {
    const selection = window.getSelection();
    if (!selection || selection.rangeCount === 0) return;
    const range = selection.getRangeAt(0);
    range.deleteContents();
    const node = document.createTextNode(text);
    range.insertNode(node);
    selection.removeAllRanges();
    hideBubble();
  }

  /* ── 悬停 ───────────────────────────────────── */

  document.addEventListener('mousemove', (event) => {
    if (!hoverEnabled || isOurs(event.target)) return;
    const target_ = event.target;
    if (target_ instanceof HTMLElement) {
      if (['INPUT', 'TEXTAREA', 'SELECT'].includes(target_.tagName)) return;
      if (target_.isContentEditable) return;
    }

    clearTimeout(hoverTimer);
    hoverTimer = setTimeout(() => {
      const word = wordAtPoint(event.clientX, event.clientY);
      if (!word || word === lastHoverWord || hoverInFlight) return;
      lastHoverWord = word;
      hoverInFlight = true;

      translate(word)
        .then((result) => {
          showBubble(
            { x: event.clientX, y: event.clientY },
            `<div class="row"><span class="tag">${escapeHtml(result.provider)}</span></div><div class="text">${escapeHtml(result.text)}</div>`,
            false
          );
        })
        .catch(() => {})
        .finally(() => {
          hoverInFlight = false;
        });
    }, HOVER_DELAY);
  });

  /** 取鼠标位置所在的单词 */
  function wordAtPoint(x, y) {
    let node = null;
    let offset = 0;

    if (document.caretPositionFromPoint) {
      const position = document.caretPositionFromPoint(x, y);
      if (position) {
        node = position.offsetNode;
        offset = position.offset;
      }
    } else if (document.caretRangeFromPoint) {
      const range = document.caretRangeFromPoint(x, y);
      if (range) {
        node = range.startContainer;
        offset = range.startOffset;
      }
    }
    if (!node || node.nodeType !== 3) return '';

    const text = node.nodeValue ?? '';
    const isWord = (ch) => /[\p{L}\p{N}\p{M}'’-]/u.test(ch);

    let start = offset;
    let end = offset;
    while (start > 0 && isWord(text[start - 1])) start -= 1;
    while (end < text.length && isWord(text[end])) end += 1;

    const word = text.slice(start, end).trim();
    return word.length >= 1 && word.length <= 80 ? word : '';
  }

  /* ── 后台消息 ───────────────────────────────── */

  EXT.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    (async () => {
      switch (message?.type) {
        case 'ping':
          sendResponse({ ok: true, data: { alive: true } });
          return;
        case 'getSelection': {
          const text = (window.getSelection()?.toString() ?? '').trim();
          sendResponse({ ok: true, data: { text } });
          return;
        }
        case 'replaceSelection': {
          replaceSelection(String(message.text ?? ''));
          sendResponse({ ok: true, data: { replaced: true } });
          return;
        }
        case 'refresh':
          await loadSettings();
          sendResponse({ ok: true, data: { ok: true } });
          return;
        case 'translateSelection': {
          // 右键菜单 / 快捷键触发：background 已带原文过来；没带就吃页面当前选区
          const passed = String(message.text ?? '').trim();
          const text = passed || (window.getSelection()?.toString() ?? '').trim();
          if (!text) {
            sendResponse({ ok: false, error: '页面上没有选中的文字' });
            return;
          }
          const selection = window.getSelection();
          const range = selection && selection.rangeCount ? selection.getRangeAt(0) : null;
          const rect = range?.getBoundingClientRect();
          const at = rect && (rect.width || rect.height)
            ? { left: rect.left, bottom: rect.bottom }
            : { left: window.innerWidth / 2 - 120, bottom: window.innerHeight / 2 };
          runSelectionFlow(text.slice(0, MAX_LEN), at);
          sendResponse({ ok: true, data: { shown: true } });
          return;
        }
        default:
          sendResponse({ ok: false, error: '未知消息' });
      }
    })();
    return true;
  });

  /* ── 杂项 ───────────────────────────────────── */

  async function translate(text) {
    return send({ type: 'translateText', text, source: null, target, style });
  }

  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      const area = document.createElement('textarea');
      area.value = text;
      area.style.cssText = 'position:fixed;opacity:0';
      document.body.appendChild(area);
      area.select();
      try {
        document.execCommand('copy');
      } catch {
        // 复制不了就算了，气泡里还有文本，用户可以自己选中
      }
      area.remove();
    }
  }

  /** @param {HTMLElement} el @param {string} text */
  function flash(el, text) {
    if (!el) return;
    const original = el.textContent;
    el.textContent = text;
    setTimeout(() => {
      el.textContent = original;
    }, 1200);
  }

  function isOurs(node) {
    const host = document.getElementById(HOST_ID);
    return Boolean(host && node && (host === node || host.contains(node)));
  }

  function escapeHtml(text) {
    return String(text)
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;');
  }

  document.addEventListener('mousedown', (event) => {
    if (!isOurs(event.target)) hideBubble();
  });

  loadSettings();
  // 设置可能被弹窗改了，每分钟同步一次
  setInterval(loadSettings, 60_000);
})();
