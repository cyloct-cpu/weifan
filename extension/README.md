# 微翻 · 浏览器扩展

WhatsApp Web 的中文 ↔ 外语即时翻译。**这是当前唯一可运行、也是主力的形态**（见 `docs/07-方案修订-2026-10-29.md`）。

- 收件：对方消息下方自动出现中文（滚动到哪译到哪，不一次把历史全发出去）
- 发件：输入框右下角点「译」或 `Ctrl/Cmd + Enter`，中文原地变对方语言，**不自动发送**

一份源码，三种浏览器的产物：

| 产物 | 覆盖 | 后台模型 | 现在能用吗 |
|---|---|---|---|
| `dist/chrome` | Chrome / Edge / Brave / Opera / Vivaldi | MV3 service worker | ✅ 可以 |
| `dist/firefox` | Firefox 121+ | MV3 事件页（`background.scripts`） | ✅ 可以（需装 Firefox 实测） |
| `dist/safari` | Safari 16.4+（macOS） | MV3 service worker | ❌ 需要 Xcode 包一层 App |

## 安装

```bash
cd extension
node scripts/build.mjs        # 生成 dist/chrome、dist/firefox、dist/safari
```

### Chrome / Edge / Brave / Opera

1. 打开 `chrome://extensions`（Edge 是 `edge://extensions`）
2. 右上角打开「开发者模式」
3. 点「加载已解压的扩展程序」，选择 **`extension/dist/chrome`**（不是 `extension/` 本身）
4. 点扩展图标 → 打开设置页 → 至少填一个密钥（Groq / DeepL / Google 任一）
5. 打开 https://web.whatsapp.com 并刷新页面

### Firefox

1. 地址栏打开 `about:debugging#/runtime/this-firefox`
2. 点「临时载入附加组件」
3. 选择 **`extension/dist/firefox/manifest.json`**

临时载入的扩展在浏览器重启后会消失，这是设计如此。要长期使用，需要用 `web-ext sign` 或开发者版签名。

### Safari（当前做不了，因为需要 Xcode）

```bash
xcrun safari-web-extension-converter ./dist/safari \
  --project-location ./safari-project \
  --app-name "微翻" \
  --bundle-identifier com.weifan.safari
```

然后在 Xcode 里选 Team、Run 一次，再去 Safari 设置 → 扩展 勾选。

改完代码要：`node scripts/build.mjs` → 扩展页点刷新 → WhatsApp 页面也刷新一次。

## 密钥从哪来

| 服务 | 用途 | 申请地址 |
|---|---|---|
| Groq | 大模型通道（长句、含金额/数字），延迟低 | console.groq.com |
| DeepL | 机翻通道，中→欧语质量最稳 | deepl.com/pro-api（Free 版每月 50 万字符） |
| Google Cloud Translation | DeepL 不支持的语种兜底 | console.cloud.google.com |
| DeepSeek / 自建 | 备用大模型（OpenAI 兼容） | platform.deepseek.com |

不填也能装上，但翻译时会提示"没有可用的翻译服务"。

## 自测

```bash
cd extension
node tests/selftest.mjs   # 内核断言集，33 项
npm run check             # 全部脚本 --check 语法检查
node scripts/build.mjs    # 打包三个产物
```

`tests/selftest.mjs` 与 Swift 版 `core/Scripts/main.swift` 是**同一份规格的两种实现**，改内核逻辑必须两边同改。

## 代码结构

```
src/
  core/            ← 纯逻辑，不碰 DOM、不碰浏览器 API
    language.js    语种表 + 本地语言检测（21 语种）
    glossary.js    术语占位符保护与还原
    routing.js     混合路由 + 发送前风险闸门
    providers.js   Google / DeepL / Groq / OpenAI 兼容
    core.js        门面：定语言 → 保护 → 路由 → 翻译 → 还原 → 风险
  shared/
    ext.js         ← 跨浏览器 API 兼容层（browser.* / chrome.*）
  background.js    service worker / 事件页：所有网络请求与密钥
  content/
    whatsapp.js    ← 唯一依赖 WhatsApp DOM 的文件
    content.css
  options/         设置页
scripts/build.mjs  多浏览器打包（零依赖）
manifest.base.json manifest 的唯一来源
```

## 跨浏览器兼容要点

| 差异 | 处理 |
|---|---|
| Chrome 只认 `background.service_worker`，Firefox 只认 `background.scripts` | 由 `scripts/build.mjs` 分别生成，不共用一份 manifest |
| Firefox 必须有 `browser_specific_settings.gecko.id` | 打包时写入 `weifan@weifan.local` |
| Firefox 的 `browser.*` 是 Promise 式，Chromium 的 `chrome.*` 是回调式 | `src/shared/ext.js` 统一；**别直接 `await chrome.storage.local.get()`，在 Firefox 里会拿到 undefined** |
| content script 不能用 `import` | `whatsapp.js` 里内联了一份等价实现 |
| Safari 不接受解压目录 | 必须 `safari-web-extension-converter` + Xcode |

## 改版失效了怎么修

只改 `src/content/whatsapp.js` 顶部的 `SELECTORS`。每项都是"多路回退"数组，加一个选择器就能救活：

```js
const SELECTORS = {
  messageText: ['.copyable-text span.selectable-text', '这里加新的选择器'],
  input: ['#main footer div[contenteditable="true"]', '这里加新的选择器']
};
```

排查方法：在 WhatsApp 页面按 F12 → Console，手动执行
`document.querySelectorAll('#main div[role="row"]').length`，
如果是 0，说明消息行的选择器失效了。

## 已知限制

| 限制 | 说明 |
|---|---|
| 依赖 web.whatsapp.com | 手机必须保持联网（WhatsApp 多设备机制），手机没电/断网时网页版会掉线 |
| DOM 改版会失效 | WhatsApp 每次大改都可能打断选择器，这是这个形态的固有成本 |
| 不能翻桌面客户端 | WhatsApp Desktop 不在覆盖范围（那需要 macOS/Windows 原生，已取消） |
| 不做自动发送 | 合规底线：最后一下永远由人按 |
| 不翻译自己的历史消息 | 只翻 `.message-in`（对方消息），避免无意义消耗 |
| 群聊无专门优化 | 多人多语言群里会频繁判错语言，建议手动锁定目标语言 |
| 语音消息未支持 | 按 D-23 后置，等真有需求再接 Whisper |

## 下一步

1. 在真实会话里跑 3 天，记录：翻错的、该翻没翻的、语言判错的
2. 验 V-03（占位符在 DeepL/Groq 下是否保真）
3. 补"逐条重译 / 换语言 / 复制译文"的右键菜单
4. 客户档案按会话标题自动匹配（现在只有会话级语言记忆）
5. 装 Firefox 验 `dist/firefox`；有 Xcode 后打 `dist/safari`
