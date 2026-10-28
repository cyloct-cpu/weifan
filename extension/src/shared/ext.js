/**
 * 跨浏览器 API 兼容层。
 *
 * 为什么要这层：
 * - Chrome / Edge / Brave 等 Chromium 系：只有 `chrome`，异步方法可返回 Promise；
 * - Firefox：`browser` 是 Promise 式，`chrome` 是回调式别名。**直接 await chrome.storage.local.get()
 *   在 Firefox 里会拿到 undefined**，这是移植时最常见的静默失败。
 *
 * 用法（模块上下文：background / options）：
 *   import { EXT, sendToBackground } from '../shared/ext.js';
 *   const { weifan } = await EXT.storage.local.get('weifan');
 *
 * content script 不能用 import，里面有一份等价的内联实现。
 */

/** Firefox 提供 browser，Chromium 只有 chrome */
export const EXT = globalThis.browser?.runtime?.id ? globalThis.browser : globalThis.chrome;

/** 是否为 Promise 式 API（Firefox 的 browser.*） */
export const isPromiseAPI = Boolean(globalThis.browser?.runtime?.id);

/**
 * 统一发消息：Chrome 用回调，Firefox 用 Promise。
 * @param {any} message
 * @returns {Promise<any>}
 */
export function sendToBackground(message) {
  if (isPromiseAPI) {
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

/**
 * 拆掉 { ok, data } 信封。
 * @param {any} response
 */
function unwrap(response) {
  if (!response) throw new Error('扩展没有响应，刷新页面试试');
  if (!response.ok) throw new Error(response.error || '未知错误');
  return response.data;
}
