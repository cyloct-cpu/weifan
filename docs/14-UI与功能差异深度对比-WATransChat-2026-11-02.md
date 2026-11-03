# WATransChat 3.55 vs WeFlip 2.9.1 — UI 与功能差异深度对比

日期：2026-11-02　设备：emulator-5554（1080×2400 @420dpi，密度 2.625×）

## 0. 取证方法与证据分级

| 级别 | 来源 | 覆盖范围 |
|---|---|---|
| **A｜实机截图** | 模拟器 screencap | 参考应用：启动 splash、WhatsApp 登录页（三步 stepper）、翻译设置页（含接口延时下拉）；我们的：列表/会话/信息/设置 |
| **B｜反编译布局** | `.local-only/ref/SPEC.md`（1410 行精确 dp/sp/color）+ `all-resources.txt`（2.3MB 资源表）+ aapt2 xmltree | 参考应用 6 个核心页全部控件树；色板/描边/圆角实值 |
| **C｜清单比对** | `aapt dump badging` 35 个 Activity vs 我们 10 个 | 页面级缺口 |

**诚实声明**：参考应用登录态已过期（现停在 `.ui.login.LoginActivity`），其**会话页/列表页未能实机截图**，这两页的对比基于 B 级证据（真实布局 XML，比截图更精确）+ 我们早前复刻时的记录。若需 A 级实机并排，需你用手机重新登录一次。

### 0.1 A 级并排补充（2026-11-02，登录/配对流程）

两边都有登录页，可直接并排 —— 见 `docs/screenshots/compare-pairing.png`。

| 项 | 参考 | 我们（修正前） | 处置 |
|---|---|---|---|
| **状态栏底色** | `#FFFFFF`（与页底融合） | `#EFF2F5` 灰带 | ✅ 已修：BaseActivity 统一 `applyStatusBar()`，只有 3 个页面单独设过 |
| **页面底色** | `#FFFFFF` 纯白（`wa_surface`） | `#EFF2F5` 浅灰（白卡片浮在灰底上） | ✅ 已修：`Brand.LIGHT.bg → Color.WHITE`，改为参考的平面风 |
| 三步 stepper | 实心靛蓝圆点 + 连线填充 | 实心绿圆点 + 连线（品牌色） | 保留品牌色（有意差异） |
| 主按钮 | 靛蓝 `#667EEA` 全宽胶囊 | 墨绿 `#128C7E` 全宽胶囊 | 保留品牌色（有意差异） |
| 输入胶囊 | 灰底 `#F2F2F7` | 灰底同规格 | 一致 |
| 左上返回 | 无 | 有 `‹` | 我们有（更符合 Android 习惯） |
| 右下在线客服 | 有悬浮按钮 | 无 | 按需求不做 |

修正后取样：两边**状态栏与页底均为 `#FFFFFF`，像素一致**。

---

## 1. UI 层面差异（按可见度排序）

### 1.1 发送/麦克风圆钮配色 —— 一眼可见，实锤不同
| | 参考 | 我们 |
|---|---|---|
| 资源 | `drawable/input_circle_black` | `WaSpec.INPUT_CIRCLE_GREEN` |
| 底 | `wa_input_action_btn_bg = @color/wa_surface` → **白 #FFFFFF**（夜 #1C1C1E） | **实心青绿 #06CE9B** |
| 描边 | `wa_input_border` **1dp #14000000**（夜 #33FFFFFF） | 无 |
| 图标 | `wa_input_action_btn_icon = @color/wa_text_primary` → **#111111**（夜 #E9EDEF） | 白色 |
| 尺寸 | 48dp（`ptt_send_button_size`），marginStart 4，marginBottom 2，**elevation 2dp** | 见我们代码 |

→ 参考用的是**新版 WhatsApp 的"白圆+细描边+深色图标"**，不是绿色。这是最显眼的风格差。
→ 改动成本：仅 `WaSpec` + 一处 drawable，低风险。

### 1.2 会话页头部按钮：参考 3 个，我们 1 个
参考 `chatHeader`（minHeight **72dp**，padding 12/8/12/8）右侧依次为：
1. `callButton` 40dp（visibility=gone，VoIP 不可用时的占位）
2. **`customerLanguageButton`** 40dp `ic_customer_language_settings` —— 客户级翻译/语言设置入口
3. **`searchButton`** 40dp `ic_search_messages_header` —— 会话内消息搜索
4. `moreMenuButton`

我们头部右侧**只有 ⋮ 一个按钮**，且无会话内搜索（代码检索 `searchMessages` = 0 命中）。

### 1.3 头部客户标签徽章行（CRM 的可见入口）
参考在副标题下方还有 `headerLabelRow > headerLabelBadgesScroll(HorizontalScrollView) > headerLabelBadges`，marginTop 4dp —— 当前客户身上的标签直接摊在会话页顶部。
我们有本地标签系统（7 个文件命中"标签"），但**只在列表页 chips 出现，会话页头部不显示**。

### 1.4 头像环
参考 `headerAvatarContainer` 43×43dp，内含三层：`headerAvatarImage` + **`bg_avatar_ring`（环）** + `headerAvatarPlaceholder(ic_default_contact)`。
我们头像无 ring 层。

### 1.5 输入区组件缺口
| 参考 include 的面板 | 我们 |
|---|---|
| `replyBarContainer` 引用条 | ✅ 有 |
| `emojiPanel` 表情面板 | ✅ 有（emoji 按钮） |
| **`slashCommandPanel` 斜杠命令** | ❌ 无（`slash` 0 命中） |
| **`groupMentionPanel` 群内 @ 提及** | ❌ 无（`mention` 0 命中） |
| **`pttRecording` 录音浮层** | ⚠️ 仅一个 AlertDialog |
| **`pttDraft` 语音草稿** | ❌ 无 |
| **`pttLockHint` 上滑锁定提示** | ❌ 无 |
| **`pttTrashCan` 上滑删除垃圾桶** | ❌ 无 |

输入框参数对照：参考 `messageInput` 16sp / minHeight 36dp / **maxLines 4** / hint「输入消息」；`translateButton` 高 36dp、12sp medium、文案「**开**」、`bg_translate_button`。
我们 translateButton 文案是「译」，其余尺寸一致。

### 1.6 会话列表页
| 元素 | 参考 | 我们 |
|---|---|---|
| 标题 | 20sp sans-serif-medium `#111111`/夜`#E9EDEF` | 同规格 ✅ |
| 搜索条 | 40dp 高，`bg_search_bar`，margins 12/10/12，图标 18dp tint `#6F6F6F`/夜`#8696A0`，hint「搜索或开始新聊天」15sp | ✅ 已复刻 |
| chips | 所有/未读/特别关注/群组 + **`chipAdd` 32dp 圆＋** | **所有/未读/特别关注/群组/已归档 + 用户标签 + ＋**（我们更多） |
| `connectingBar` | ProgressBar + 「正在连接…」13sp `#8E8E93`/夜`#8696A0`，bg `wa_gray6 #F2F2F7`/夜#000 | ✅ 有 |
| **`selectionBar` 多选栏** | `selectionClose` 40dp + `selectionCount` 15sp + **`selectionAssign` 40dp（批量指派）** | ❌ 无列表多选 |
| **空态四段式** | `emptySyncProgress` + `emptySyncTitle`「正在同步聊天记录，请稍候…」+ **`emptySyncStats`（同步计数）** + `emptySyncHint`「首次关联需要一些时间…」+ `emptyIdleText`「同步完成，暂无会话。点击右上角 + 开始新聊天。」 | ⚠️ 有 emptyBox，但**无同步进度条与统计数字** |
| `archivedChatsPanel` | include 内嵌整块面板 | 我们用 chip 筛选 |

### 1.7 翻译设置页 —— 差距最大的一页
参考（A 级实机取证）：
1. **16 个接口，下拉每项带实时延时**：谷歌1215 / 谷歌Chrome827 / Bing642 / Yandex1175 / 火山512 / DeepLX671 / 百度357 / DeepL1199 / Deepseek-V4 390 / 火山Deepseek-V4 239 / 阿里Qwen2.5-7B-Instruct **53** / 腾讯Hunyuan-MT-7B 211 / 智谱GLM-4-Flash 321 / ChatGPT591 / OpenAI自定义563 / Deepseek自定义1137（格式「名称 · NNNms」）
2. **AI 翻译提示词**：可编辑大文本框，内置含 `${toCode}` 变量 + Translation Rules 1–5 + OUTPUT FORMAT 的系统提示词
3. 三按钮：**保存（实心蓝）/ 默认（描边）/ 模板（描边）**
4. **译文颜色**：色块 + 右箭头，「设置聊天中译文的显示颜色」
5. **原文字号**：独立调节项

我们：九家 + 自定义接口、密钥、优先级、额度保护、试译、**网格拖拽排序（参考没有，是我们的优势）**。
缺：**延时探测展示、提示词编辑、模板、译文颜色、原文字号**。

### 1.8 登录/配对流程
参考（A 级）：白底居中 logo splash（无文字无进度）→ **三步 stepper**「获取代码 → 输入代码 → 完成!」（当前步实心靛蓝圆点，连线填充，未到步灰）→ 标题「输入 WhatsApp 手机号」粗体居中 → `+86` 胶囊 + 手机号胶囊输入 → 大实心靛蓝胶囊「下一步」→「二维码登录」文字链 → 灰色辅助说明。
主色 `primary_color #667EEA` / 夜 `#8B9FFF`（靛蓝，用于账户/会员/登录；WhatsApp 绿只用于会话内元素）。
我们：品牌色按你要求保留自有（墨绿 #128C7E + 青绿 #06CE9B），**这是有意差异，不算缺陷**；但 **stepper 步骤指示器**值得评估。

---

## 2. 功能层面缺口（已排除会员/付费/在线客服/AI 客服）

参考 35 个 Activity，扣除会员体系（`MemberInfo`/`ForgotPassword`/`Register`）、AI 客服 8 页（`AiCs*`/`AiChat`）、`SupportChat`、`TutorialVideo`、`Call`（whatsmeow 不支持 VoIP，做不了）后，真实缺口：

| # | 参考页面/能力 | 我们 | 价值 | 成本 |
|---|---|---|---|---|
| 1 | **会话内消息搜索**（`searchButton`+`chatSearchBar`） | ❌ | 高（客服找历史单） | 低（DB LIKE 即可） |
| 2 | **跟进待办** `FollowupTodosActivity` | ❌ | **高**（你已定 CRM 方向，这是第二块） | 中 |
| 3 | **媒体发送前预览** `MediaSendPreviewActivity` | ❌ | 中高（选图后可配文/裁剪再发） | 中 |
| 4 | **新建联系人** `NewContactActivity` | ❌ | 中 | 低 |
| 5 | 独立 **PDF 查看器** `PdfViewerActivity` | ⚠️ 走通用 viewer | 中（PDF 体验差） | 中（需 pdfium） |
| 6 | 独立 **视频查看器** `VideoViewerActivity` | ⚠️ 同上 | 中 | 低 |
| 7 | **群内 @ 提及** `groupMentionPanel` | ❌ | 中 | 中高 |
| 8 | **斜杠命令** `slashCommandPanel` | ❌ | 低中 | 中 |
| 9 | 语音 **草稿/锁定/上滑删除** 三件套 | ❌ | 中 | 中 |
| 10 | 列表**多选 + 批量指派** `selectionBar` | ❌ | 中（团队场景，单人价值低） | 中 |
| 11 | 头部**客户标签徽章行** | ❌ | 高（CRM 可见性） | 低 |
| 12 | 翻译**延时探测** + 提示词编辑 + 译文颜色 + 字号 | ❌ | 高（核心卖点页） | 中 |

我们**反超**的地方：本地标签系统（chips 可增删）、附件面板 12 项、翻译服务网格拖拽排序、投票点选与票数回流、客户备注 CRM、媒体分类 tabs、全屏 viewer 左右滑、语音波形条、应用内+官方端双拉黑。

---

## 3. 建议的落地顺序

**第一批（纯 UI，低风险高感知）**
1. 发送/麦克风圆钮 → 白底+1dp 描边+深色图标（夜间对应反转）
2. 会话页头部加「搜索」按钮 + 会话内搜索条（DB LIKE）
3. 会话页头部标签徽章行（复用已有标签数据）
4. translateButton 文案「译」→「开」，高度对齐 36dp

**第二批（翻译设置页补齐）**
5. 接口列表加实时延时（并发 ping + 缓存，格式「名称 · NNNms」）
6. AI 提示词编辑框 + 默认/模板按钮
7. 译文颜色、原文字号两项

**第三批（CRM 纵深）**
8. 跟进待办页（DB v9 `todos` 表：客户/内容/到期/状态）
9. 新建联系人
10. 媒体发送前预览

**不建议做**：VoIP 通话（协议不支持）、AI 客服套件/会员/在线客服（你已排除）、列表批量指派（单人使用价值低）。

---

## 8. 【重要修正】同粒度复核：上一版缺口表漏了 6 项

日期：2026-11-03

### 8.1 问题出在分析方法不一致

| | 我当时用的证据 | 能看到什么 |
|---|---|---|
| **Android 3.55 分析** | `aapt dump badging` 的 **35 个 Activity 清单** + `SPEC.md` 布局规格 | 只能看到"有哪些页面" |
| **iOS 2.82 分析** | `Localizable.strings` 的 **1206 条业务文案** | 能看到"页面里每个按钮、提示、报错" |

结论：**不是 iOS 版功能更多，是我看 iOS 看得更细。**

实测反证——Android APK 里同样有完整文案表：

```
android/app 资源表：string/ 条目 1569 个
提取中文值：1024 条（去掉 appcompat/material 等 SDK 前缀后 919 条业务文案）
两端 key 命名基本一致：601 条完全相同 → 确为同一产品的双端
跟随待办（followup）52 条只在 iOS；Android 版有 FollowupTodosActivity 但文案未走 strings
```

**我上一版只 grep 了色值（`wa_*`）和 drawable 名，没有系统导出字符串 → 漏掉大量功能细节。**

### 8.2 被漏掉的 6 项（参考应用双端都有，我们都没有）

已用代码反查确认：我们 Android 侧 `grep` 命中数均为 **0 个文件**。

| # | 能力 | 参考应用文案证据 | 我们 |
|---|---|---|---|
| B1 | **汇率转换** | `fx_amount_hint` 支持四则运算 `100*0.2`；`fx_subtitle_local` 按对方国家自动选币；`fx_pin` 置顶；长按复制；双向输入 | ❌ 无 |
| B2 | **备份与恢复** | 导出含会话/消息/媒体/置顶/特别关注/翻译偏好/全局设置；恢复可逐项勾选；覆盖警告 | ❌ 无 |
| B3 | **多设备管理** | `device_limit_message` 每账号最多 N 台；在线/离线筛选；注销释放名额 | ❌ 无 |
| B4 | **译文字号** | `translation_text_font_size` + `original_text_font_size` —— **两个都有**（我此前误称"参考只有译文字号"） | ⚠️ 只有原文字号 |
| B5 | **历史消息独立引擎** | `chat_history_translation_engine` + 说明"仅用于历史消息，不影响输入框翻译" | ❌ 无 |
| B6 | **换引擎重译** | `message_retranslating_with_engine` / `_failed_with_engine` —— 单条消息改用别的接口重翻 | ❌ 无 |

### 8.3 另外两个细节修正

| 原结论 | 修正后 |
|---|---|
| "参考应用有 16 个接口，**下拉带实时延时**" | 实时延时是 **iOS 版**的界面；Android 3.55 的接口清单在 `engine_*` 系列（21 条），**未观察到延时展示文案**。我们已做的测速功能属于**超出参考**的实现 |
| "附件面板参考 18 项" | 18 项是 **iOS 版**；Android 3.55 的 `attach_*` 为 11 条。两端不同步，我们按 18 项对齐是对的（取并集） |

### 8.4 双端不同步清单（供实现取并集）

| 只在 iOS 2.82 | 只在 Android 3.55 |
|---|---|
| `followup_*` 52 条（跟进待办完整版） | `background_*` 23 条（后台保活） |
| `group_*` 24 条增强（@所有人等） | `media_*` 16 条（媒体处理细节） |
| `event_*` 24 条（活动邀请） | `pairing_*` 13 条（配对流程） |
| `iap_*` 15 条、`membership_*` 21 条 | `support_*` 10 条、`volc_*` 10 条 |
| `link_*` 20 条、`qr_*` 12 条增强 | `engine_*` 12 条（引擎枚举） |
| `chat_*` 12、`message_*` 11、`poll_*` 9、`attach_*` 7 | `translation_*` 8、`bing_*` 7、`password_*` 6 |

### 8.5 已做项复核：A1–A6 结论不受影响

A1 PDF、A2 视频、A3 多选批量指派、A4 语音四件套、A5 @提及、A6 斜杠命令——
这 6 项在参考应用里确实存在、我们确实缺失，**结论不变**。
只是清单**不完整**，现补入 B1–B6。
