# 微翻 WeiFan

WhatsApp 跨境沟通的自动翻译工具：**你打中文，发出去的是对方的语言；对方说什么，你看到的是中文。**

- 平台规划：iOS → macOS → Android → Windows
- 当前状态：**iOS MVP 代码骨架完成，未编译验证**（开发机没装 Xcode，见下方「已知限制」）
- 命名：中文「微翻」，英文 WeiFan，Bundle ID 前缀 `com.weifan`

---

## 一句话说清它和"官方插件"的关系

WhatsApp 对个人号**没有**任何插件接口。能真正"长在 WhatsApp 里"的只有两条路：

1. **网页版 + 浏览器扩展**（web.whatsapp.com）——唯一的"真插件"，但它只在网页版在线时有效；
2. **站在 WhatsApp 外面**——iOS 自定义键盘 / Android 无障碍服务 / 桌面端辅助功能。

微翻走的是第 2 条。好处是**WhatsApp 改版不会让我们挂掉**（我们从不修改它），坏处是每一步都要靠系统给的能力，而 iOS 给得最少。

## iOS 上的真实用法（三步）

iOS 同时只能显示一个键盘，所以不能"在微翻键盘上打中文"，正确流程是：

1. 在 WhatsApp 输入框里用**系统拼音键盘**把中文打完，先不要发；
2. 点地球键切到「微翻」，看清顶部目标语言，点「译」；
3. 中文被译文**原地替换**，点 WhatsApp 自己的绿色发送键。

收件方向：长按对方消息 → 分享 → 微翻 → 直接看到中文译文（也支持语音消息分享过来转写）。

详见 [docs/01-产品需求.md](docs/01-产品需求.md)。

---

## 目录结构

```
weifan/
├── core/                     # 纯 Swift 翻译内核（无 UI，将来可移植到安卓/Rust）
│   ├── Sources/WeiFanCore/
│   │   ├── Language.swift        # 语种枚举
│   │   ├── LanguageDetector.swift# 本地语言检测（脚本判定 + 停用词打分）
│   │   ├── Glossary.swift        # 术语保护（占位符 + 还原）
│   │   ├── Routing.swift         # 混合路由 + 发送前风险闸门
│   │   ├── Providers.swift       # Google / DeepL / 大模型 / Whisper
│   │   ├── TranslationCore.swift # 唯一门面 + 历史记录
│   │   └── Storage.swift         # 设置、客户档案、App Group 存储
│   ├── Tests/WeiFanCoreTests/    # XCTest（需 Xcode）
│   └── Scripts/main.swift        # 无 Xcode 时的冒烟自测
├── ios/
│   ├── project.yml               # XcodeGen 工程描述
│   ├── Sources/App/              # 主 App（SwiftUI）
│   ├── Sources/Keyboard/         # 键盘扩展
│   ├── Sources/Share/            # 分享扩展（收件方向 + 语音）
│   ├── Sources/Shared/           # Keychain（App 与扩展共用）
│   └── Resources/                # Info.plist / entitlements
└── docs/                         # 决策记录、需求、技术方案、路线图、待验证清单
```

建议阅读顺序：`docs/00-决策记录.md`（为什么这么做）→ `docs/02-技术方案.md`（怎么实现）→ `docs/04-待验证清单.md`（先测什么）。

---

## 跑起来（需要 macOS + Xcode 15+）

```bash
# 1. 安装工程生成器
brew install xcodegen

# 2. 生成 Xcode 工程
cd ios
xcodegen generate

# 3. 在 project.yml 里把 DEVELOPMENT_TEAM 改成你的 Team ID，然后打开工程
open WeiFan.xcodeproj
```

首次真机运行后：

- 设置 → 通用 → 键盘 → 键盘 → 添加新键盘 → 微翻 → **打开「允许完全访问」**（不打开无法联网翻译）
- 在 App 的「设置」页填入至少一个翻译服务密钥
- WhatsApp 里长按地球键即可切到微翻

### 只跑内核的冒烟测试（不需要 Xcode）

```bash
cd core
swiftc -parse Sources/WeiFanCore/*.swift Scripts/main.swift   # 语法检查
# 若你的工具链正常，可直接编译执行：
swiftc -swift-version 5 -O -o /tmp/wfcheck Sources/WeiFanCore/*.swift Scripts/main.swift && /tmp/wfcheck
```

## 已知限制（务必先读）

| 限制 | 说明 |
|---|---|
| 本仓库代码**未经编译验证** | 开发机（macOS + Command Line Tools）的 Swift 工具链存在 SDK 版本错配（`SwiftBridging` 模块重定义），无法做 `swift build` / `swift test`。已完成 `swiftc -parse` 语法检查，逻辑有 XCTest 与冒烟脚本双份覆盖，但**首次编译大概率仍有个位数错误**，属正常，改完即可。 |
| iOS 键盘读不到聊天内容 | 系统限制。收件方向只能靠分享扩展或手动粘贴。 |
| 键盘扩展内存上限约 30–60MB | 因此不做本地模型、不做全量缓存；键盘里只持有当次请求。 |
| 每 7 天重签 | 免费 Apple ID 侧载的必然结果；用 TestFlight（$99/年）可 90 天。 |
| 占位符可能被引擎改写 | 见 `docs/04-待验证清单.md` V-03，已做宽松还原与失败可见化。 |

## 合规与边界

- 微翻**不修改、不注入、不自动化** WhatsApp 本体，只使用系统公开能力（键盘扩展、分享扩展、辅助功能）。
- 不做群发、不做自动回复、不代替用户点击发送——最后一下永远由人按。
- 聊天内容会发送到你自己配置的第三方翻译服务（Google / DeepL / DeepSeek 等），本地只保留最近 200 条记录。给客户报合同价之前，请确认这条链路你能接受。
