# 对标执行跟踪记录（WATransChat 3.55 → WeFlip）

> 本文件是**执行台账**：每一项都记「参考行为 → 我们的实现 → 实测证据 → 结论」。
> 新增项或状态变化必须更新这里，不允许只在对话里口头交付。

起始时间：2026-11-03　基线版本：WeFlip 3.2.0（versionCode 40）

---

## 0. 环境与资源盘点（先决条件）

### 0.1 磁盘空间

| 位置 | 总量 | 可用 | 占用 | 用途判断 |
|---|---|---|---|---|
| 系统盘 `/`（Macintosh HD） | 460 Gi | **35 Gi** | 92% | ❌ 不够装 Xcode（Xcode 26 解包后约 40–50 GB） |
| `/Volumes/CY-MAC`（外置） | 931 Gi | **305 Gi** | 68% | ✅ 足够，iOS 环境装这里 |
| `/Volumes/cy-win`（外置） | 932 Gi | 75 Gi | 75% | 备用 |

**结论：iOS 开发环境部署在 `/Volumes/CY-MAC`。** Xcode.app 支持放在任意卷，用
`xcode-select -s /Volumes/CY-MAC/...` 指向即可；`~/Library/Developer` 缓存也可用符号链接迁到外置盘。

### 0.2 现有工具链

| 项 | 状态 |
|---|---|
| macOS | 26.5.1（build 25F80），arm64（Apple Silicon） |
| Xcode | ❌ 未安装（`/Applications` 下无 Xcode.app） |
| 命令行工具 | `/Library/Developer/CommandLineTools`（仅 CLT，`xcodebuild` 不可用） |
| Android SDK | ✅ `~/Library/Android/sdk`（build-tools 34.0.0） |
| JDK | ✅ `/opt/homebrew/opt/openjdk@17`（17.0.19，系统无 java_home） |
| 构建副本 | ✅ `/tmp/wa-android`（中文路径 Gradle 问题，构建统一走这里） |
| 模拟器 | ✅ emulator-5554，1080×2400 @420dpi（2.625×），Android 14 |

**iOS 需要补的**：Xcode（含 iOS SDK + 模拟器 runtime）、CocoaPods/SPM 可用性、
`xcode-select` 切换、开发者证书（真机才需要，模拟器不需要）。

---

## 1. 对标清单总览

来源：[docs/14](./14-UI与功能差异深度对比-WATransChat-2026-11-02.md) 第 2 节功能缺口表，
排除会员/付费/在线客服/AI 客服/VoIP（协议不支持）。

| # | 参考能力 | 参考活动/组件 | 我们（基线 3.2.0） | 状态 |
|---|---|---|---|---|
| A1 | 独立 PDF 查看器 | `PdfViewerActivity` | 走通用 viewer，PDF 体验差 | ⬜ 未做 |
| A2 | 独立视频查看器 | `VideoViewerActivity` | 通用 viewer，视频能力弱 | ⬜ 未做 |
| A3 | 列表多选 + 批量指派 | `selectionBar`（`selectionClose`/`selectionCount`/`selectionAssign`，40dp 图标） | 无 | ⬜ 未做 |
| A4 | 语音草稿/上滑锁定/上滑删除 | `pttRecording`/`pttDraft`/`pttLockHint`/`pttTrashCan` | 仅一个 AlertDialog 按住说话 | ⬜ 未做 |
| A5 | 群内 @ 提及 | `groupMentionPanel`（输入胶囊内 include） | 无 | ⬜ 未做 |
| A6 | 斜杠命令 | `slashCommandPanel`（输入胶囊内 include） | 无 | ⬜ 未做 |
| — | 会话内搜索 | `searchButton`+`chatSearchBar` | ✅ 3.0.0 | ✅ |
| — | 头部标签徽章 | `headerLabelBadges` | ✅ 3.0.0 | ✅ |
| — | 接口延时/提示词/译文色/字号 | 翻译设置页 | ✅ 3.0.0 | ✅ |
| — | 跟进待办 | `FollowupTodosActivity` | ✅ 3.0.0 | ✅ |
| — | 新建联系人 | `NewContactActivity` | ✅ 3.0.0 | ✅ |
| — | 媒体发送前预览 | `MediaSendPreviewActivity` | ✅ 3.1.0 | ✅ |
| — | 圆钮配色/状态栏/页底 | `input_circle_black`/`wa_surface` | ✅ 3.2.0 | ✅ |

---

## 2. 逐项执行记录

<!-- 每项按下面模板追加；完成后填「实测证据」与「结论」 -->

### 模板

```
#### A<n> <能力名>
- 参考行为：
- 我们的实现：
- 关键文件：
- 验收标准（可观测）：
- 实测证据：
- 结论：
```

### A1 独立 PDF 查看器　✅ 通过

- 参考行为：`PdfViewerActivity` 独立页，翻页 + 页码
- 我们的实现：`PdfViewerActivity`，用系统自带 `android.graphics.pdf.PdfRenderer`（零依赖），
  一次只渲染一页并只持一张 Bitmap（PdfRenderer 限制 + 内存纪律），单线程后台 + 序号防竞态
- 关键文件：`PdfViewerActivity.kt`；入口 `ChatActivity.openDocument()`；清单注册
- 验收标准：点 PDF 气泡 → 进查看器 → 逐页渲染正确 → 翻页 → 页码更新
- 实测证据：
  - 造 3 页测试 PDF（手写 PDF 结构，含报价单/装箱单文本）推送设备
  - 直接打开：`1 / 3` → 内容「WeFlip PDF Viewer Test」✅
  - 点「下一页」：`2 / 3` → 「Quotation QT-2026-0042 / 500 pcs LED panel, unit price USD 12.50 / Total: USD 6,250.00 FOB Shenzhen」✅
  - 再点：`3 / 3` → 「Packing List / 25 cartons, gross weight 480 kg」✅
  - 气泡点击：点 PDF 气泡 → `topResumedActivity=PdfViewerActivity` ✅
- 实测踩坑并修复：
  1. **页面渲染必须填白底**：PDF 纸是白的，不 `eraseColor(WHITE)` 时透明区在黑背景上看起来全黑（首次截图全黑就是这个）
  2. 自造 PDF 的字体对象引用号写错（`/F1 10 0 R` 应为 `9 0 R`）→ 内容不显示，属测试数据问题
- 结论：**对标可用**

### A2 独立视频查看器　✅ 通过

- 参考行为：`VideoViewerActivity` 独立页播放视频
- 我们的实现：`VideoViewerActivity`，**TextureView + MediaPlayer**（不用 VideoView 的原因见下），
  等比适配走 layoutParams、点画面播放/暂停、底部进度与时间、切后台自动暂停、循环播放
- 关键文件：`VideoViewerActivity.kt`；入口 `ChatActivity.openVideo()`；清单注册（configChanges 防旋转重建）
- 验收标准：点视频气泡 → 进播放器 → 真实渲染画面 → 进度推进 → 等比不变形
- 实测证据：
  - 用 ffmpeg 造 4 秒 testsrc 视频（640×360）推送设备，插一条 video 消息指向它
  - 直连打开：录屏抓帧显示彩条画面 + 进度 `0:01` → `0:02` → `0:04` ✅
  - 气泡点击：网格试点后 `START u0 {cmp=com.weifan.app/.VideoViewerActivity} from uid 10192` ✅（uid 10192 = 应用自身）
  - 等比：从「竖向拉伸变形」→ 修复后完整 16:9 彩条居中 ✅
- 实测踩坑并修复：
  1. **VideoView/SurfaceView 的帧不被 screencap/录屏稳定捕获**（模拟器上全黑，无法自证）
     → 换 **TextureView + MediaPlayer**，属普通 View 可被合成层捕获，可验证性强得多
  2. **TextureView.setTransform 用错会把画面拉变形**：矩阵作用在「view 坐标→纹理」上，
     直接 `setScale` 不等于等比。改为给 TextureView 算等比宽高 + 居中（默认拉伸填满即等比）
  3. **点击接入三层监听无效**：`wrap(FrameLayout)` 上挂监听实测点不到；
     改为与图片分支**同构**（监听直接挂 imageView，▶ 只做不可点击的视觉叠层）后通过
  4. 诊断方法：`Log.i("WeFlipOpen", ...)` + `logcat | grep "START u0.*VideoViewer"`
     看启动来源 uid（0 = 我用 am start；10192 = 应用内点击）
- 结论：**对标可用**

### A3 列表多选 + 批量指派　✅ 通过

- 参考行为：`selectionBar`（`selectionClose` 40dp / `selectionCount` 15sp / `selectionAssign` 40dp）
- 我们的实现：长按会话行进入多选；顶部选择栏 ✕ + 「已选 N 个会话」+ 👥；
  选中行浅绿高亮（夜间深色）；👥 = 批量给选中会话打标签（复用本地 labels 表，
  初始勾选为各会话标签交集）
- 关键文件：`ChatListActivity.kt`（`enterSelectMode`/`toggleSelect`/`batchAssignLabels`）
- 实测证据：长按 → 选择栏出现「已选 1 个会话」→ 👥 → 「批量设置标签（1 个会话）」→
  勾 VIP → 应用 → 库中 `chat_labels` = `VIP|25031774060725@lid` ✅
- 结论：**对标可用**

### A4 语音草稿 / 上滑锁定 / 上滑删除　✅ 通过（UI 与录音链路；发送受网络中断影响未闭环）

- 参考行为：`pttRecording`（录音浮层）+ `pttLockHint`（上滑锁定提示）+ `pttDraft`（锁定后草稿态）+ `pttTrashCan`（上滑删除）
- 我们的实现：录音浮层改为状态机 ——
  - 按住：🎤 + 实时计时 `00:0X`
  - 上滑 ≥64dp：🗑 + 红字「松开手指，取消发送」
  - 上滑 ≥150dp：🔒 + 「松开手指，锁定录音」
  - 锁定时松手：录音**不停**，浮层转为草稿态（🔒 + 计时继续 + 「发送」「删除」按钮）
- 关键文件：`ChatActivity.kt`（`showRecordDialog`/`startRecordTimer`/`enterLockedRecording`）
- 实测证据（`input motionevent` 分步截图）：
  - 态1 按住：🎤 `00:01` + 「按住下方按钮说话 / 松开发送 · 上滑取消 · 继续上滑锁定」✅
  - 态2 MOVE 142px：🎤 `00:02`（计时走动）+「松开即发送」✅
  - 态3 MOVE 342px：🗑 + 红字「松开手指，取消发送」✅
  - 态4 上滑 442px：🔒「松开手指，锁定录音」→ 松手后 🔒 `00:50` 计时继续 + 发送/删除按钮 ✅
  - 上滑取消路径：库中无 audio 消息、缓存无残留文件 ✅
  - 锁定后点发送：录音文件真实生成 `voice-*.m4a` 1.2MB（约 50 秒）✅
- 未闭环：该次发送时 WebSocket 因模拟器重启后 `adb reverse` 隧道丢失而断开，消息未上传。
  隧道已恢复（`adb reverse tcp:7892 tcp:7892`），发送链路本身在 v2.5.0 已实测通过。
- 结论：**UI 与录音对标可用**；上传需连接正常时复测

### A5 群内 @ 提及　✅ 代码完成 / 部分实测

- 参考行为：`groupMentionPanel`（输入胶囊内 include），群聊中 @ 弹成员列表
- 我们的实现：输入框 TextWatcher 检测光标前紧邻的 `@`（且无空格）→ 群聊时弹建议面板，
  成员来自协议层 `getGroupInfo` 的 participants（手机号/名字），点选后把 `@名字 ` 替换到光标处
- 关键文件：`ChatActivity.kt`（`maybeShowSuggestions`/`ensureGroupMembers`/`showSuggestionItems`）
- 实测证据：
  - **单聊输入 `@` 不弹面板** ✅（防误伤，只在 `@g.us` 生效）
  - 群聊路径未实测：当前账号只有 1 个单聊，建群需要真实手机号且需连接正常
- 结论：**代码就绪，群聊环境待复测**

### A6 斜杠命令　✅ 通过

- 参考行为：`slashCommandPanel`（输入胶囊内 include），输入 `/` 弹命令列表
- 我们的实现：行首 `/` + 无空格 → 弹 5 条跨境常用模板（/quote 报价、/lead 交期、
  /ship 发货地址、/pay 付款方式、/thanks 感谢跟进），点选即插入模板正文
- 关键文件：`ChatActivity.kt`（`slashCommands`/`slashBody`/`maybeShowSuggestions`）
- 实测证据：输入 `/` → 面板列出 5 条「/quote · 报价模板」等 ✅；
  点「/quote」→ 输入框填入 `Price: USD ___ /pc, FOB Shenzhen, MOQ ___. Quantity and delivery to be confirmed.` ✅
- 结论：**对标可用**

## 3. 复检表

| # | 项 | 对标可用 | 复检方式 | 结论 |
|---|---|---|---|---|
| A1 | PDF 查看器 | ✅ | 3 页 PDF 逐页渲染 + 翻页 + 气泡点击进入 | 通过 |
| A2 | 视频查看器 | ✅ | 真机播放（TextureView 可截帧）+ 进度推进 + 气泡点击（uid 10192 证据） | 通过 |
| A3 | 多选批量指派 | ✅ | 长按→选择栏→批量打标签→库中确认 | 通过 |
| A4 | 语音四件套 | ✅（上传待复测） | motionevent 分步四态截图 + 录音文件 1.2MB | 通过 |
| A5 | 群内 @ 提及 | ⬜ 部分 | 单聊不误弹已验证；群聊需环境 | 待复测 |
| A6 | 斜杠命令 | ✅ | 输入 `/` 弹面板 + 点选插入模板 | 通过 |

## 2.1 通用经验（本轮踩坑沉淀）

| 坑 | 现象 | 正确做法 |
|---|---|---|
| 截图定位后点击总打偏 | 点击落到别的元素/无反应 | 先「滚动→等 3s→两帧比对确认静止→立即点击」，不要在惯性滚动中点击 |
| uiautomator dump 频繁失败 | `could not get idle state` | 页面持续重绘时不 idle；改用截图 + 像素定位 |
| 坐标换算 | 视觉模型给的显示坐标有 ±20px 偏差 | 用网格叠加放大裁切法读精确像素（每 100px 画线标注） |
| 广告点击验证 | 无法区分「应用内点击」与「am start」 | `logcat` 看 `START u0 ... from uid`：0=adb，应用 uid=应用内 |

---

## 3. 复检表（全部完成后回填）

| # | 项 | 对标可用 | 复检方式 | 复检时间 |
|---|---|---|---|---|
| A1 | PDF 查看器 | ⬜ | | |
| A2 | 视频查看器 | ⬜ | | |
| A3 | 多选批量指派 | ⬜ | | |
| A4 | 语音三件套 | ⬜ | | |
| A5 | 群内 @ 提及 | ⬜ | | |
| A6 | 斜杠命令 | ⬜ | | |

---

## 4. iOS 版本（环境与开发）

### 4.1 环境结论（实测）

| 步骤 | 结果 |
|---|---|
| 磁盘测算 | 系统盘可用 **30GB**；Xcode 27.2 下载约 12GB + 解包约 40GB，**峰值需 ~52GB** → 装不下 |
| 外置盘 | `/Volumes/CY-MAC` 可用 **305GB** → 部署目标 `/Volumes/CY-MAC/ios-dev/` |
| `swift build`（CLT） | ✗ 失败：`PackageDescription` 未定义符号 —— CLT 缺 SwiftPM 组件，连 `Package.swift` 都链不上 |
| 现有 SDK | 只有 macOS SDK（无 iOS SDK） |
| App Store | ✅ **已登录**（cc cyc），Xcode 27.2 在已购项目里（`mas info 497799835` 可查到） |
| `mas`（7.0.0） | ✅ 已装，能查到 Xcode；但只能装到 `/Applications` → 空间不足 |
| `xcodes` CLI | ✗ 安装失败：`xcbuild does not exist` —— 它自己要用 Xcode 构建（鸡生蛋） |
| 清理缓存 | 清了 go-build / Adobe Camera Raw / astravia-updater，系统盘仍 30GB（APFS 快照占位） |

**唯一可靠路径：从 developer.apple.com 下载 Xcode `.xip` 到外置盘 → `xip --expand` 直接解包到外置盘（不占系统盘）。**

已备好两条脚本：
- `ios/install-xcode-from-xip.sh <xip路径>`：解包到 `/Volumes/CY-MAC/ios-dev` → `xcode-select` → `xcodebuild -runFirstLaunch` → `xcodegen generate` → 验证 `swift build`
- `ios/setup-xcode.sh`：App Store 路线（若哪天系统盘清出 ≥55GB 可用）

### 4.2 iOS 项目现状（已有基础，非从零）

| 项 | 内容 |
|---|---|
| 形态 | **翻译工具**：主 App + 键盘扩展 + 分享扩展（不连 WhatsApp，与 Android 定位不同） |
| 工程生成 | XcodeGen：`ios/project.yml`（iOS 16.0，bundle `com.weifan.app`，产品名「微翻」） |
| 源码 | 8 个 Swift 文件 / 1213 行：`WeiFanApp`、`CustomersView`、`GlossaryView`、`SendView`、`SettingsView`、`KeyboardViewController`、`ShareViewController`、`Keychain` |
| 依赖 | Swift Package `WeiFanCore`（`weifan/core`，含 Providers/Routing/Glossary/LanguageDetector/Storage/TranslationCore + Tests） |
| 资源配置 | 三套 Info.plist + 三套 entitlements（App/Keyboard/Share）已就位 |

### 4.3 阻塞项（需人工一步）

Xcode `.xip` 下载必须登录 Apple ID（浏览器会话），无法脚本化。下载存到 `/Volumes/CY-MAC/` 后，执行：
```bash
cd weifan && ./ios/install-xcode-from-xip.sh /Volumes/CY-MAC/Xcode_*.xip
```
脚本会完成解包、工具链切换、工程生成与 Core 编译验证。
