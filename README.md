# 微翻 WeiFan

<p align="center"><img src="brand/lockup-horizontal.svg" alt="微翻 WeiFan" width="360" /></p>

<p align="center"><img src="https://github.com/cyloct-cpu/weifan/actions/workflows/ci.yml/badge.svg" alt="ci" /></p>

WhatsApp 跨境沟通的自动翻译工具：**你打中文，发出去的是对方的语言；对方说什么，你看到的是中文。**

点扩展图标弹出一个面板，选好语言和服务就能用：**选区、划词、鼠标悬停、整份文档**都能翻。
翻译走你自己申请的大厂免费额度（百度 / 腾讯云 / Google / DeepL / Azure / 火山 / 火山大模型 / 阿里 / Yandex），也能接任何 OpenAI 兼容接口。**接口地址全部预置好，你只填密钥。**

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
| **浏览器扩展** | ✅ DOM 直读 | ✅ 直接改 | ❌ 不用 | 无 | ✅ 可运行（面板 + 划词/悬停/文档翻译） |
| Android | ⚠️ v0.1 未做 | ✅ `ACTION_SET_TEXT` | ❌ 不用 | JDK + Android SDK | 🚧 可编译，未真机验证 |
| iOS | ❌ 读不到 | ⚠️ 只能改输入框 | ✅ 每次切两次 | Xcode + 真机 + 签名 | ⏸ 最后做 |

## 面板与两种模式

点扩展图标弹出面板（360px，浅色品牌风）：

```
微翻 · 简洁开关
[自动检测] → [英语]
┌ 免费额度 | 自定义 API ┐      ← 分段开关
翻译服务 [自动（按语种优选）]
语气     [商务]
[ 翻译选区 / 剪贴板 ]            ← 暖色主按钮
[文档翻译][文本翻译]
[鼠标悬停][划词翻译]
⚙ 设置        v0.2.0      更多功能 ⌄
```

**两种模式**

| 模式 | 用什么 | 特点 |
|---|---|---|
| 免费额度 | 自己去大厂申请 key：百度 / 腾讯云 / Google / DeepL / Azure / 火山 / 火山大模型 / 阿里 / Yandex | 零成本；端点全部预置，用户只填密钥；扩展只做路由与降级 |
| 自定义 API | 任意 OpenAI 兼容接口（key + 接口地址 + 模型名） | 大模型翻译，术语与语气更准；可加多个档案 |

**降级**：用户选的服务商优先，失败（超时/报错/额度用完）自动换下一家「已配置 + 支持该目标语言」的服务，结果里会写明这次是谁翻的、原先那家为什么没用上。

**四个功能**

| 功能 | 行为 | Android |
|---|---|---|
| 文档翻译 | txt / md / csv 直读；PDF 内置 pdf.js 抽文字；docx 解 zip 逐段翻译后写回新 docx。**文件全程留在本机** | ✅ txt/md/csv/docx（PDF 暂不支持） |
| 文本翻译 | 独立页面，左原文右译文，可对调 | ✅ 独立页 |
| 鼠标悬停 | 停 0.6 秒弹只读译文气泡，同一个词不重复请求 | ❌ 手机没有鼠标 |
| 划词翻译 | 选中即弹可操作气泡：可编辑译文、复制、一键替换原文 | ✅ 系统划词菜单里选「微翻」，可一键替换原文 |

**额度保护**：设置页能给单个服务商设每日字符上限（默认不限）。达到上限当天自动跳过那家 —— 免得用户绑了信用卡的 Google key 被超量计费。

**单测**：点开某家的胶囊，弹窗里有「测试这一家」按钮——**只测这家、不走降级**，错误码直接印在弹窗里（百度那 13 个错误码都做了人话映射）。配了多家时不用猜是哪家的锅。弹窗的「更多功能 → 测试当前服务」同理。

**九家服务商在设置页是两列胶囊**，点开才出配置表单——一屏放得下，不用一直往下翻。

**单测**：点开某家的胶囊，弹窗里有「测试这一家」按钮——**只测这家、不走降级**，错误码直接印在弹窗里（百度那 13 个错误码都做了人话映射）。配了多家时不用猜是哪家的锅。弹窗的「更多功能 → 测试当前服务」同理。

**九家服务商在设置页是两列胶囊**，点开才出配置表单——一屏放得下，不用一直往下翻。

## 直接下载（不用自己构建）

到 **[Releases](https://github.com/cyloct-cpu/weifan/releases/latest)** 下载现成产物：

| 产物 | 谁用 | 怎么装 |
|---|---|---|
| `weifan-android-*.apk` | Android 手机 | 直接安装（debug 签名，仅供自用），装完在设置里开无障碍服务 |
| `weifan-chrome-*.zip` | Chrome / Edge / Brave / Opera / Vivaldi | 解压 → `chrome://extensions` → 开发者模式 → 加载已解压 → 选 `chrome` 目录 |
| `weifan-firefox-*.zip` | Firefox 121+ | 解压 → `about:debugging#/runtime/this-firefox` → 临时载入 → 选 `firefox/manifest.json` |

打 tag 会自动构建并发布（`.github/workflows/release.yml`）：

```bash
git tag -a v0.1.1 -m "说明" && git push origin v0.1.1
```

## 30 秒上手（Chrome / Edge / Brave / Opera）

```bash
cd extension
node scripts/build.mjs      # 生成 dist/chrome、dist/firefox、dist/safari
```

1. 打开 `chrome://extensions`，开启「开发者模式」
2. 「加载已解压的扩展程序」→ 选 **`extension/dist/chrome`**
3. 点扩展图标 → 弹面板 → 点底部「设置」填密钥（百度 / 腾讯云 / Google / DeepL / Azure / 火山 / 火山大模型 / 阿里 / Yandex 任一，或自定义 API）
4. 打开 https://web.whatsapp.com 并刷新

用法：对方消息下方自动出现中文；输入框右下角点「译」或 `Ctrl/Cmd + Enter`，中文原地变对方语言，**不会自动发送**。

面板用法：选中一段文字点「翻译选区 / 剪贴板」；任何页面上划词或悬停都能弹译文气泡；文档翻译支持 PDF 与 Word。

Firefox：`about:debugging#/runtime/this-firefox` → 临时载入 → 选 `extension/dist/firefox/manifest.json`。
Safari：代码已就绪，但必须用 Xcode 包装，当前机器没有 Xcode。

内核自测（不需要浏览器）：

```bash
cd extension && node tests/selftest.mjs    # 33 项断言
```

## 目录结构

```
weifan/
├── android/                      # Android 端：无障碍服务 + 后缀触发（Kotlin，零依赖）
│   ├── app/src/main/java/com/weifan/app/
│   │   ├── core/                 # 与扩展同构的判断逻辑（检测/术语/路由/服务商/门面）
│   │   ├── service/              # 无障碍服务：读输入框 → 翻译 → 原地替换
│   │   └── MainActivity.kt       # 设置页（纯 framework 视图）
│   └── README.md                 # 构建、首次使用、保活、已知限制
├── extension/                    # ★ 主形态：浏览器扩展（一份源码，三份产物）
│   ├── manifest.base.json        # manifest 的唯一来源
│   ├── scripts/build.mjs         # 生成 chrome / firefox / safari 三个产物
│   ├── src/core/                 # 纯逻辑：检测 / 术语 / 路由 / 服务商 / 门面
│   │   ├── providers.js          # 九家服务商 + 自定义 API 的注册表、端点表与降级链
│   │   ├── langs.js              # 各家语种代码映射（决定谁支持哪种语言）
│   │   └── signing.js            # MD5 / 腾讯 TC3 / 火山 V4 / 阿里 RPC 签名
│   ├── src/shared/theme.css       # 设计 token（popup 与设置页共用一份）
│   ├── src/shared/ext.js         # 跨浏览器 API 兼容层（browser.* / chrome.*）
│   ├── src/background.js         # 所有网络请求与密钥都在这里
│   ├── src/popup/                # ★ 点图标弹出的面板
│   ├── src/content/              # whatsapp.js（唯一依赖 WhatsApp DOM）+ pageagent.js（划词/悬停）
│   ├── src/text/                 # 文本翻译页
│   ├── src/doc/                  # 文档翻译页（pdf.js 抽文 + docx 解 zip 回写）
│   ├── src/options/              # 设置页（八家密钥表单由注册表自动生成）
│   ├── src/vendor/               # fflate + pdf.js（本地解析文档，不上传）
│   ├── tests/selftest.mjs        # 内核断言集（70 项）
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
│   └── 07-方案修订-2026-10-29.md  # ★ 当前有效的决策（含四轮修订）
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
| iOS 未开工 | 需要 Xcode（本机没有），且 iOS 键盘读不到对方消息，形态待定 |
| 语音未支持 | 按修订后的 D-23 后置 |
| Swift 版内核未编译验证 | 本机 Swift 工具链有 SDK 版本错配，只能用 `swiftc -parse` 做语法检查；Swift 版 providers 已被 JS 版取代，仅作规格存档 |
| 扩展体积 2.8MB | 内置 pdf.js + worker（+1.7MB），为了 PDF 文档翻译能离线用 |
| Word/PDF 只保文字 | docx 保留段落结构与数量，字体/表格/分栏不保留；PDF 版式不保留，只给纯文本 |
| 安装时会提示"读取所有网站数据" | 划词/悬停需要全站注入，面板里可随时关掉这两项 |
| Android 端不支持自定义 API 多档案 | 扩展可加多个，Android 端只有一个（DeepSeek 这类够用） |

## 合规与隐私

- 不修改、不注入、不自动化 WhatsApp 本体；不做群发、不做自动回复、不代替用户点击发送。
- 聊天内容会发给你自己配置的第三方翻译服务（百度 / 腾讯云 / Google / DeepL / Azure / 火山 / 阿里 / Yandex / 你自己的大模型接口）；密钥只存在本机浏览器（`chrome.storage.local`），不经过任何自建服务器。
- 文档翻译全程在本机解析（pdf.js / fflate），文件不会上传到任何服务器。
- 翻译缓存在内存里（上限 300 条），浏览器重启即清。
