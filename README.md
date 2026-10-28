# 微翻 WeiFan

WhatsApp 跨境沟通的自动翻译工具：**你打中文，发出去的是对方的语言；对方说什么，你看到的是中文。**

- 范围：**三端** —— 浏览器扩展 / Android / iOS（macOS 原生不做）
- 当前状态：**浏览器扩展已可运行**；Android、iOS 未开工（缺工具链，见下）
- 顺序：扩展 → Android → iOS（决策见 `docs/07-方案修订-2026-10-29.md`）
- 定位：自用 / 小范围，不上架商店

> 2026-10-29 做过两轮修订：
> ① 原计划 iOS 键盘首发，因"iOS 是唯一既读不到对方消息、又做不了无障碍的端"+ 开发机没有 Xcode，改为**浏览器扩展优先**；
> ② 随后确定为三端，**取消 macOS 原生**（浏览器已能覆盖 Mac 上的 WhatsApp 使用场景）。
> 详见 `docs/07-方案修订-2026-10-29.md`。

## 三端能力对照

| 端 | 读对方消息 | 改写输入框 | 要切键盘 | 交付门槛 | 状态 |
|---|---|---|---|---|---|
| **浏览器扩展** | ✅ DOM 直读 | ✅ 直接改 | ❌ 不用 | 无 | ✅ 可运行 |
| Android | ✅ 无障碍 | ✅ `ACTION_SET_TEXT` | ❌ 不用 | Android Studio / JDK | ⏸ 需装工具链 |
| iOS | ❌ 读不到 | ⚠️ 只能改输入框 | ✅ 每次切两次 | Xcode + 真机 + 签名 | ⏸ 最后做 |

## 30 秒上手（Chrome / Edge / Brave / Opera）

```bash
cd extension
node scripts/build.mjs      # 生成 dist/chrome、dist/firefox、dist/safari
```

1. 打开 `chrome://extensions`，开启「开发者模式」
2. 「加载已解压的扩展程序」→ 选 **`extension/dist/chrome`**
3. 点扩展图标 → 填一个密钥（Groq / DeepL / Google 任一）
4. 打开 https://web.whatsapp.com 并刷新

用法：对方消息下方自动出现中文；输入框右下角点「译」或 `Ctrl/Cmd + Enter`，中文原地变对方语言，**不会自动发送**。

Firefox：`about:debugging#/runtime/this-firefox` → 临时载入 → 选 `extension/dist/firefox/manifest.json`。
Safari：代码已就绪，但必须用 Xcode 包装，当前机器没有 Xcode。

内核自测（不需要浏览器）：

```bash
cd extension && node tests/selftest.mjs    # 33 项断言
```

## 目录结构

```
weifan/
├── extension/                    # ★ 主形态：浏览器扩展（一份源码，三份产物）
│   ├── manifest.base.json        # manifest 的唯一来源
│   ├── scripts/build.mjs         # 生成 chrome / firefox / safari 三个产物
│   ├── src/core/                 # 纯逻辑：检测 / 术语 / 路由 / 服务商 / 门面
│   ├── src/shared/ext.js         # 跨浏览器 API 兼容层（browser.* / chrome.*）
│   ├── src/background.js         # 所有网络请求与密钥都在这里
│   ├── src/content/              # 唯一依赖 WhatsApp DOM 的适配层
│   ├── src/options/              # 设置页
│   ├── tests/selftest.mjs        # 内核断言集（33 项）
│   └── dist/                     # 打包产物（已 gitignore）
├── core/                         # Swift 版内核：作为「行为规格 / 未来 iOS 的参考实现」
├── ios/                          # iOS 三 target 源码（键盘/分享/主 App），归档待用
├── docs/
│   ├── 00-决策记录.md            # 最初的 16 条决策（含已被推翻的，保留思路）
│   ├── 01-产品需求.md
│   ├── 02-技术方案.md
│   ├── 03-三端路线图.md          # 扩展 / Android / iOS 的实现路径与坑
│   ├── 04-待验证清单.md
│   ├── 05-交接说明.md
│   ├── 06-参考项目.md            # 调研：KeyboardKit / SwiftSlate / wa-translator 等
│   └── 07-方案修订-2026-10-29.md  # ★ 当前有效的决策（含两轮修订）
└── README.md
```

**读文档的顺序**：`07`（现在做什么）→ `03`（三端怎么落地）→ `06`（别人怎么做的）→ `04`（先验证什么）→ `00`（当初为什么那么想）。

## 为什么不做"官方插件"

WhatsApp 对个人号**没有**任何插件接口。能真正长在 WhatsApp 里的只有网页版 + 浏览器扩展；移动端只能站在 WhatsApp 外面（Android 无障碍 / iOS 键盘）。微翻选择的都是"不改 WhatsApp 本体"的路——好处是 WhatsApp 改版不会让产品整体崩溃，只会让选择器失效，改一个文件就能修。

## 已知限制

| 限制 | 说明 |
|---|---|
| 依赖 web.whatsapp.com | 手机必须保持联网，否则网页版掉线；桌面客户端不在覆盖范围 |
| DOM 改版会失效 | 选择器集中在 `extension/src/content/whatsapp.js` 顶部，改一处即可 |
| Safari 交付被 Xcode 阻塞 | 打包命令已备好，装 Xcode 后约 30 分钟能通 |
| Android / iOS 未开工 | 前者需 Android Studio（本机无 JDK/SDK），后者需 Xcode（本机没有） |
| 语音未支持 | 按修订后的 D-23 后置 |
| Swift 版内核未编译验证 | 本机 Swift 工具链有 SDK 版本错配，只能用 `swiftc -parse` 做语法检查 |

## 合规与隐私

- 不修改、不注入、不自动化 WhatsApp 本体；不做群发、不做自动回复、不代替用户点击发送。
- 聊天内容会发给你自己配置的第三方翻译服务（Groq / DeepL / Google / DeepSeek）；密钥只存在本机浏览器（`chrome.storage.local`），不经过任何自建服务器。
- 翻译缓存在内存里（上限 300 条），浏览器重启即清。
