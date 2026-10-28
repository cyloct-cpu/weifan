/**
 * WhatsApp Web 适配层 + 界面注入。
 *
 * ⚠️ 这个文件是全项目**唯一**依赖 WhatsApp DOM 结构的地方。
 * 改版导致失效时，只需要改这里的选择器，不要动 core/。
 *
 * 设计要点：
 * 1. 所有选择器都写成"多路回退"，一个失效还有下一个；
 * 2. 收件用 IntersectionObserver 只翻视口内的消息（控成本）；
 * 3. 发件不自动发送，只替换输入框内容 —— 最后一下永远由人按（D-01 合规底线）。
 */

(() => {
  'use strict';

  // MARK: - DOM 适配（改版时只改这一段）

  const SELECTORS = {
    main: ['#main', 'div[id="main"]'],
    chatTitle: ['#main header span[title]', '#main header [data-testid="conversation-info-header-chat-title"]'],
    messageRow: ['#main div[role="row"]'],
    messageText: ['.copyable-text span.selectable-text', 'span.selectable-text.copyable-text'],
    incoming: ['.message-in'],
    outgoing: ['.message-out'],
    input: [
      '#main footer div[contenteditable="true"]',
      'footer div[contenteditable="true"][role="textbox"]',
      'div[contenteditable="true"][data-tab="10"]'
    ],
    sendButton: ['#main footer button[data-testid="send"]', '#main footer span[data-icon="send"]']
  };

  /** @param {string[]} list @param {ParentNode} [root] @returns {Element|null} */
  const pick = (list, root = document) => {
    for (const selector of list) {
      const el = root.querySelector(selector);
      if (el) return el;
    }
    return null;
  };

  /** @param {string[]} list @param {ParentNode} [root] @returns {Element[]} */
  const pickAll = (list, root = document) => {
    for (const selector of list) {
      const els = root.querySelectorAll(selector);
      if (els.length) return [...els];
    }
    return [];
  };

  // MARK: - 状态

  const state = {
    /** @type {any} */
    config: null,
    /** @type {Record<string, {language: string}>} */
    chatLanguages: {},
    chatId: '',
    /** @type {string|null} */ lockedTarget: null,
    queue: [],
    active: 0,
    /** @type {{original: string, translated: string}|null} */ lastSwap: null,
    observerAttached: false
  };

  const CONCURRENCY = 2;
  const TRANSLATE_ATTR = 'data-wf';

  // MARK: - 与 background 通信

  // content script 不能用 import，这里内联一份与 src/shared/ext.js 等价的实现。
  // Firefox 的 browser.* 是 Promise 式，Chromium 的 chrome.* 是回调式，必须分两条路。
  const EXT = globalThis.browser?.runtime?.id ? globalThis.browser : globalThis.chrome;
  const IS_PROMISE_API = Boolean(globalThis.browser?.runtime?.id);

  /** @param {any} response */
  function unwrap(response) {
    if (!response) throw new Error('扩展没有响应，刷新 WhatsApp 页面试试');
    if (!response.ok) throw new Error(response.error || '未知错误');
    return response.data;
  }

  /** @param {any} message @returns {Promise<any>} */
  function send(message) {
    if (IS_PROMISE_API) {
      return EXT.runtime.sendMessage(message).then(unwrap);
    }
    return new Promise((resolve, reject) => {
      try {
        EXT.runtime.sendMessage(message, (response) => {
          const lastError = globalThis.chrome?.runtime?.lastError;
          if (lastError) {
            reject(new Error(lastError.message));
            return;
          }
          try {
            resolve(unwrap(response));
          } catch (error) {
            reject(error);
          }
        });
      } catch (error) {
        reject(error);
      }
    });
  }

  // MARK: - 启动

  async function init() {
    try {
      state.config = await send({ type: 'getConfig' });
    } catch (error) {
      console.warn('[微翻] 初始化失败：', error);
      return;
    }

    await refreshChat();
    mountToolbar();
    attachObservers();
    scanMessages();

    if (!state.config.hasKeys) {
      showToast('还没有配置翻译密钥：点扩展图标 → 选项，或右键扩展 → 选项', 'warn', 8000);
    }
  }

  /** 会话切换时刷新 chatId 与锁定语言 */
  async function refreshChat() {
    const title = pick(SELECTORS.chatTitle);
    const next = title?.getAttribute('title') ?? '';
    if (next === state.chatId) return;
    state.chatId = next;
    state.lockedTarget = null;

    if (!next) return;
    try {
      const { language } = await send({ type: 'getChatLanguage', chatId: next });
      state.chatLanguages[next] = { language };
    } catch {
      /* 忽略：读不到会话记忆不影响主流程 */
    }
    renderToolbar();
  }

  // MARK: - 收件：扫描与排队

  function scanMessages() {
    const rows = pickAll(SELECTORS.messageRow);
    for (const row of rows) queueIfNeeded(row);
  }

  /** @param {Element} row */
  function queueIfNeeded(row) {
    if (row.getAttribute(TRANSLATE_ATTR)) return;
    if (!row.querySelector(SELECTORS.incoming.join(','))) return; // 只翻对方消息

    const textEl = pick(SELECTORS.messageText, row);
    const text = normalize(textEl?.textContent ?? '');
    if (!text) return;

    row.setAttribute(TRANSLATE_ATTR, 'pending');
    observeVisibility(row, text);
  }

  /** @param {string} text */
  const normalize = (text) => text.replace(/\s+/g, ' ').trim();

  /**
   * 只翻进入视口的消息：滚动到哪译到哪，避免一次把整段历史全发出去。
   * @param {Element} row @param {string} text
   */
  function observeVisibility(row, text) {
    if (!('IntersectionObserver' in window)) {
      enqueue(row, text);
      return;
    }
    const io = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        io.unobserve(entry.target);
        enqueue(row, text);
      }
    }, { root: null, rootMargin: '200px 0px' });
    io.observe(row);
  }

  /** @param {Element} row @param {string} text */
  function enqueue(row, text) {
    if (!state.config?.settings?.autoIncoming) {
      row.setAttribute(TRANSLATE_ATTR, 'off');
      return;
    }
    const max = state.config.settings.autoTranslateMaxChars ?? 1500;
    if ([...text].length > max) {
      row.setAttribute(TRANSLATE_ATTR, 'toolong');
      renderNode(row, '太长，不自动翻译', { muted: true, clickable: true, retryText: text });
      return;
    }
    state.queue.push({ row, text });
    pump();
  }

  function pump() {
    while (state.active < CONCURRENCY && state.queue.length) {
      const job = state.queue.shift();
      state.active += 1;
      translateOne(job.row, job.text).finally(() => {
        state.active -= 1;
        pump();
      });
    }
  }

  /** @param {Element} row @param {string} text */
  async function translateOne(row, text) {
    try {
      const result = await send({ type: 'incoming', text });
      if (result.alreadyChinese) {
        row.setAttribute(TRANSLATE_ATTR, 'zh');
        return;
      }
      row.setAttribute(TRANSLATE_ATTR, 'done');
      renderNode(row, result.translated, {
        sourceLanguage: result.sourceLanguage,
        provider: result.provider,
        latency: result.latency
      });
    } catch (error) {
      row.setAttribute(TRANSLATE_ATTR, 'error');
      renderNode(row, `翻译失败：${error.message}`, { error: true, clickable: true, retryText: text });
    }
  }

  /**
   * 把译文插到消息气泡里、原文下方。
   * @param {Element} row @param {string} text @param {any} [meta]
   */
  function renderNode(row, text, meta = {}) {
    const textEl = pick(SELECTORS.messageText, row);
    const host = textEl?.closest('.copyable-text')?.parentElement ?? textEl?.parentElement;
    if (!host) return;

    const existing = host.querySelector('.wf-inline');
    const node = existing ?? document.createElement('div');
    node.className = 'wf-inline';
    node.textContent = '';

    const body = document.createElement('div');
    body.className = 'wf-inline-text';
    body.textContent = text;
    if (meta.muted) body.classList.add('wf-muted');
    if (meta.error) body.classList.add('wf-error');
    node.appendChild(body);

    if (meta.provider) {
      const foot = document.createElement('div');
      foot.className = 'wf-inline-meta';
      foot.textContent = [meta.sourceLanguage, meta.provider, meta.latency ? `${meta.latency}ms` : '']
        .filter(Boolean)
        .join(' · ');
      node.appendChild(foot);
    }

    if (meta.clickable && meta.retryText) {
      node.classList.add('wf-clickable');
      node.onclick = (event) => {
        event.stopPropagation();
        node.remove();
        row.removeAttribute(TRANSLATE_ATTR);
        enqueue(row, meta.retryText);
      };
    }

    if (!existing) host.appendChild(node);
  }

  // MARK: - 发件：工具栏与替换

  function mountToolbar() {
    let bar = document.querySelector('.wf-toolbar');
    if (!bar) {
      bar = document.createElement('div');
      bar.className = 'wf-toolbar';

      const select = document.createElement('select');
      select.className = 'wf-lang';
      select.title = '目标语言（锁定后不再自动判断）';
      const auto = document.createElement('option');
      auto.value = '';
      auto.textContent = '自动';
      select.appendChild(auto);
      for (const [code, meta] of Object.entries(state.config?.languages ?? {})) {
        if (code === 'zh-Hans' || code === 'zh-Hant') continue;
        const option = document.createElement('option');
        option.value = code;
        option.textContent = meta.name;
        select.appendChild(option);
      }
      select.onchange = () => {
        state.lockedTarget = select.value || null;
        if (state.chatId) {
          send({ type: 'setChatLanguage', chatId: state.chatId, language: state.lockedTarget }).catch(() => {});
        }
        showToast(state.lockedTarget ? `已锁定：${state.config.languages[state.lockedTarget]?.name}` : '已恢复自动判断');
      };

      const button = document.createElement('button');
      button.className = 'wf-translate-btn';
      button.textContent = '译';
      button.title = '把输入框里的中文译成对方语言（Ctrl+Enter）';
      button.onclick = (event) => {
        event.preventDefault();
        translateInput();
      };

      bar.append(select, button);
      document.body.appendChild(bar);
    }
    state.toolbar = bar;
    renderToolbar();
  }

  function renderToolbar() {
    const select = document.querySelector('.wf-toolbar .wf-lang');
    if (!select) return;
    const remembered = state.chatLanguages[state.chatId]?.language ?? '';
    const value = state.lockedTarget ?? remembered ?? '';
    select.value = value;
  }

  async function translateInput() {
    const input = pick(SELECTORS.input);
    if (!input) return showToast('找不到输入框，先点一下 WhatsApp 的输入区', 'warn');

    const text = normalize(input.innerText ?? '');
    if (!text) return showToast('输入框是空的', 'warn');

    const chat = state.chatLanguages[state.chatId];
    showBusy(true);

    try {
      const result = await send({
        type: 'sending',
        text,
        chatId: state.chatId,
        explicitTarget: state.lockedTarget,
        contextSample: lastIncomingText()
      });

      if (result.risk?.shouldPreview) {
        showConfirm(result, text);
        return;
      }
      applyTranslation(input, text, result.translated);
      showToast(`已替换为${state.config.languages[result.target]?.name ?? result.target} · ${result.provider} · ${result.latency}ms`, 'ok', 4000);
    } catch (error) {
      showToast(`翻译失败：${error.message}`, 'error', 6000);
    } finally {
      showBusy(false);
    }
  }

  /** @param {Element} input @param {string} original @param {string} translated */
  function applyTranslation(input, original, translated) {
    replaceInput(input, translated);
    state.lastSwap = { original, translated };
    setTimeout(() => {
      if (state.lastSwap?.translated === translated) state.lastSwap = null;
    }, 12000);
  }

  /** @param {Element} input @param {string} text */
  function replaceInput(input, text) {
    input.focus();
    const selection = window.getSelection();
    const range = document.createRange();
    range.selectNodeContents(input);
    selection?.removeAllRanges();
    selection?.addRange(range);

    const ok = document.execCommand('insertText', false, text);
    if (!ok) {
      // 兜底：直接改 DOM 并派发 input 事件，让 React 感知
      input.textContent = text;
      input.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true }));
    }
  }

  /** 取最近一条对方消息，作为发件时的语言检测样本 */
  function lastIncomingText() {
    const rows = pickAll(SELECTORS.messageRow).filter((row) => row.querySelector(SELECTORS.incoming.join(',')));
    for (let i = rows.length - 1; i >= 0; i -= 1) {
      const text = normalize(pick(SELECTORS.messageText, rows[i])?.textContent ?? '');
      if (text) return text;
    }
    return null;
  }

  // MARK: - 风险确认

  /** @param {any} result @param {string} original */
  function showConfirm(result, original) {
    const panel = document.createElement('div');
    panel.className = 'wf-panel';

    const title = document.createElement('div');
    title.className = 'wf-panel-title';
    title.textContent = `需确认：${result.risk.reasons.join(' · ')}`;

    const body = document.createElement('div');
    body.className = 'wf-panel-body';
    body.textContent = result.translated;

    const actions = document.createElement('div');
    actions.className = 'wf-panel-actions';

    const cancel = document.createElement('button');
    cancel.textContent = '取消';
    cancel.onclick = () => panel.remove();

    const confirm = document.createElement('button');
    confirm.className = 'wf-primary';
    confirm.textContent = '替换输入框';
    confirm.onclick = () => {
      const input = pick(SELECTORS.input);
      if (input) applyTranslation(input, original, result.translated);
      panel.remove();
    };

    actions.append(cancel, confirm);
    panel.append(title, body, actions);
    document.body.appendChild(panel);
  }

  // MARK: - 提示条

  /** @type {number|undefined} */
  let toastTimer;

  /** @param {string} message @param {'ok'|'warn'|'error'} [kind] @param {number} [duration] */
  function showToast(message, kind = 'ok', duration = 3000) {
    let toast = document.querySelector('.wf-toast');
    if (!toast) {
      toast = document.createElement('div');
      toast.className = 'wf-toast';
      document.body.appendChild(toast);
    }
    toast.textContent = message;
    toast.dataset.kind = kind;
    toast.classList.add('wf-show');

    if (state.lastSwap && kind === 'ok') {
      const undo = document.createElement('button');
      undo.textContent = '撤销';
      undo.className = 'wf-undo';
      undo.onclick = () => {
        const input = pick(SELECTORS.input);
        if (input && state.lastSwap) {
          replaceInput(input, state.lastSwap.original);
          state.lastSwap = null;
          showToast('已还原中文');
        }
      };
      toast.appendChild(undo);
    }

    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.remove('wf-show'), duration);
  }

  function showBusy(busy) {
    const button = document.querySelector('.wf-translate-btn');
    if (!button) return;
    button.disabled = busy;
    button.textContent = busy ? '…' : '译';
  }

  // MARK: - 监听

  function attachObservers() {
    if (state.observerAttached) return;
    state.observerAttached = true;

    let scheduled = false;
    const schedule = () => {
      if (scheduled) return;
      scheduled = true;
      setTimeout(() => {
        scheduled = false;
        scanMessages();
        refreshChat().then(renderToolbar);
        if (!document.querySelector('.wf-toolbar')) mountToolbar();
      }, 300);
    };

    const mo = new MutationObserver(schedule);
    const root = pick(SELECTORS.main);
    if (root) mo.observe(root, { childList: true, subtree: true });

    document.addEventListener('keydown', (event) => {
      const isEnter = event.key === 'Enter' && (event.ctrlKey || event.metaKey);
      if (!isEnter) return;
      const input = pick(SELECTORS.input);
      if (input && (input === document.activeElement || input.contains(document.activeElement))) {
        event.preventDefault();
        event.stopPropagation();
        translateInput();
      }
    }, true);
  }

  // 页面是 SPA，切换会话不一定触发 #main 变化，兜底轮询一次
  setInterval(() => {
    if (!document.querySelector('.wf-toolbar')) mountToolbar();
  }, 4000);

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init, { once: true });
  } else {
    init();
  }
})();
