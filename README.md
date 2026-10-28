# 微翻 WeiFan

WhatsApp 跨境沟通的自动翻译工具：**你打中文，发出去的是对方的语言；对方说什么，你看到的是中文。**

- 当前主形态：**浏览器扩展（Chrome / Edge，加载即用，无需构建）**
- 平台顺序：网页扩展 → macOS 原生 → Android → iOS（已修订，见 `docs/07-方案修订-2026-10-29.md`）
- 命名：中文「微翻」，英文 WeiFan
- 定位：自用 / 小范围，不上架商店

> ⚠️ 2026-10-29 做过一次重大修订：原计划 iOS 键盘首发，因三项事实改为**浏览器扩展优先**——
> ① 调研发现 iOS 是唯一既读不到对方消息、又做不了无障碍的端；② 扩展是唯一能逐条内联双语的形态；
> ③ 开发机没有 Xcode，而扩展用 node 就能跑。详见 `docs/07-方案修订-2026-10-29.md`。

## 三种形态的能力对照

| 形态 | 读对方消息 | 改写输入框 | 要切键盘 | 当前状态 |
|---|---|---|---|---|
| **网页扩展** | ✅ DOM 直读 | ✅ 直接改 | ❌ 不用 | ✅ 可运行 |
| macOS 原生 | ✅ AX（粒度粗） | ✅ AX 注入 | ❌ 不用 | ⏸ 需 Xcode |
| Android | ✅ 无障碍 | ✅ `ACTION_SET_TEXT` | ❌ 不用 | ⏸ 需 Android Studio |
| iOS 键盘 | ❌ 读不到 | ⚠️ 只能改输入框 | ✅ 每次切两次 | ⏸ 需 Xcode + 真机 |

## 30 秒上手

```bash
# 1. 打开 chrome://extensions ，开启「开发者模式」
# 2. 点「加载已解压的扩展程序」，选择：weifan/extension
# 3. 点扩展图标 → 填一个密钥（Groq / DeepL / Google 任一）
# 4. 打开 https://web.whatsapp.com 并刷新
```

用法：对方消息下方自动出现中文；输入框右下角点「译」或 `Ctrl/Cmd + Enter`，中文原地变对方语言，**不会自动发送**。

内核自测（不需要 Chrome）：

```bash
cd extension && node tests/selftest.mjs    # 33 项断言
```

## 目录结构

```
weifan/
├── extension/                # ★ 主形态：WhatsApp Web 扩展（MV3，零构建）
│   ├── manifest.json
│   ├── src/core/             # 纯逻辑：检测 / 术语 / 路由 / 服务商 / 门面
│   ├── src/background.js     # 所有网络请求与密钥都在这里
│   ├── src/content/          # 唯一依赖 WhatsApp DOM 的适配层
│   ├── src/options/          # 设置页
│   └── tests/selftest.mjs    # 内核断言集
├── core/                     # Swift 版内核：现在作为「行为规格 / 未来 iOS、macOS 的参考实现」
│   ├── Sources/WeiFanCore/
│   ├── Tests/                # XCTest（需 Xcode）
│   └── Scripts/main.swift    # 无 Xcode 的断言脚本（与 TS 版同源）
├── ios/                      # iOS 三 target 源码（键盘/分享/主 App），暂缓
├── docs/
│   ├── 00-决策记录.md            # 最初的 16 条决策（含已被推翻的，保留以记录思路）
│   ├── 01-产品需求.md
│   ├── 02-技术方案.md
│   ├── 03-四端路线图.md
│   ├── 04-待验证清单.md
│   ├── 05-交接说明.md
│   ├── 06-参考项目.md             # 调研：KeyboardKit / SwiftSlate / wa-translator 等
│   └── 07-方案修订-2026-10-29.md  # ★ 当前有效的决策
└── README.md
```

**读文档的顺序**：`07`（现在做什么）→ `06`（别人怎么做的）→ `02`（技术细节）→ `04`（先验证什么）→ `00`（当初为什么那么想）。

## 为什么不做"官方插件"

WhatsApp 对个人号**没有**任何插件接口。能真正长在 WhatsApp 里的只有网页版 + 浏览器扩展；移动端只能站在 WhatsApp 外面（iOS 键盘 / Android 无障碍 / 桌面辅助功能）。微翻选择的都是"不改 WhatsApp 本体"的路，代价是不修改它，好处是 WhatsApp 改版不会让我们整体崩溃——只会让选择器失效，改一个文件就能修。

## 已知限制

| 限制 | 说明 |
|---|---|
| 依赖 web.whatsapp.com | 手机必须保持联网，否则网页版掉线 |
| DOM 改版会失效 | 选择器集中在 `extension/src/content/whatsapp.js` 顶部，改一处即可 |
| 不自动发送 | 合规底线：最后一下永远由人按 |
| 语音未支持 | 按修订后的 D-23 后置；`core` 里保留了调用点 |
| iOS / macOS / Android 未开工 | 前两者需 Xcode（当前机器没有），Android 需 Android Studio |
| Swift 版内核未编译验证 | 当前机器的 Swift 工具链有 SDK 版本错配，只能用 `swiftc -parse` 做语法检查 |

## 合规与隐私

- 不修改、不注入、不自动化 WhatsApp 本体；不做群发、不做自动回复、不代替用户点击发送。
- 聊天内容会发给你自己配置的第三方翻译服务（Groq / DeepL / Google / DeepSeek）；密钥只存在本机浏览器（`chrome.storage.local`），不经过任何自建服务器。
- 本地只保留最近翻译缓存（内存，上限 300 条），刷新即清。
