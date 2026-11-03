# WATransChat iOS 版（2.82）逆向分析 → 我们的 iOS 版实现依据

日期：2026-11-03　样本：`/Volumes/CY-MAC/自动翻译/WATransChat.ipa`（20 MB）
方法：REA（shipped-artifact 静态分析）。**全程未运行该应用**，所有结论来自包内文件。

---

## 0. 取证条件（决定结论可信度）

| 项 | 实测值 |
|---|---|
| 包结构 | 标准 App Store ipa：`Payload/WATransChat.app` + `_CodeSignature` + `embedded.mobileprovision` |
| 可执行文件 | `WATransChat`，Mach-O 64-bit **arm64**，40 MB |
| **FairPlay 加密** | **`cryptid 0` = 未加密** ✅ 可完整静态读取 |
| 符号表 | 自有类型已被 strip（2871 个 Swift 符号里几乎全是系统框架），**拿不到页面类名** |
| 可用证据 | `Localizable.strings`（15 语言，中文 1206 条）、`Info.plist`、`Assets.car`、`otool -L`、字符串表、数据文件 |
| 未能做到 | 无法在模拟器运行（真机 arm64 ipa 不能装 iOS Simulator；且模拟器也装不了 WhatsApp） |

**结论口径**：功能面与文案是**直接观测**；页面层级是**从文案 key 与命名推断**，已在下文逐条标注。

---

## 1. 应用画像

| 项 | 值 |
|---|---|
| Bundle ID | `com.zla6.WATransChat` |
| 版本 | **2.82**（Android 版为 3.55，双端不同步） |
| 最低系统 | iOS 16.0 |
| UI 框架 | **SwiftUI 纯代码**（包内**无** storyboard / xib / nib） |
| 数据层 | **GRDB**（`GRDB_GRDB.bundle`，SQLite 封装） |
| 网络图片 | **Kingfisher**（`Kingfisher_Kingfisher.bundle`） |
| 内购 | StoreKit + `WATransChat.storekit` 配置 + 支付宝 URL scheme `watranschatalipay` |
| **协议层** | **Go + `go.mau.fi/libsignal`**（含 `libsignal/cipher.EncryptCbc/DecryptCbc`、`libsignal/ecc`）→ 与我们的 whatsmeow 同源 |
| 后台模式 | `fetch`、`processing`（+ 长连接保活） |
| 权限 | 相机、通讯录、定位、麦克风、相册读写 |
| 屏幕方向 | 竖屏 + 横屏 |
| 接口扩展 | `INSendMessageIntent`（Siri 发消息） |
| 内置数据 | `area_code_to_country.json`、`language_labels.json`、`ai_chat_default_prompt.txt`、`call_minimax_voice_phrases.json`、铃声 |

**对我们的直接意义**：协议层同为 Go + libsignal → `weifan-protocol` 用 `gomobile bind -target=ios` 可产出 `WeifanProtocol.xcframework` 直接复用，**不需要重写协议**。

---

## 2. 功能模块全景（1206 条文案聚类，按体量排序）

| 模块前缀 | 条数 | 性质 | 我们是否要做 |
|---|---|---:|---|
| `ai` | 196 | AI 客服（自动回复/产品库/提示词） | ❌ 用户已排除 |
| `call` | 93 | 语音/视频通话 + 同声传译 | ❌ 协议不支持 VoIP；且是付费会员功能 |
| `followup` | 52 | 跟进待办 | ✅ **要做**（Android 已做，iOS 补） |
| `membership` | 43 | 会员体系 | ❌ 用户已排除 |
| `contact` | 41 | 联系人/顾客备注/商业信息 | ✅ 要做 |
| `link` | 38 | 配对（手机号/二维码） | ✅ 要做 |
| `voice` | 31 | 通话翻译会员 + 语音消息 | ⚠️ 仅"语音消息"部分要做 |
| `fx` | 30 | **汇率转换** | ✅ **要做（我们完全没有）** |
| `group` | 27 | 群管理 | ✅ 要做 |
| `event` | 24 | 活动邀请 | ⭕ 低优先 |
| `restore` | 22 | 备份恢复 | ✅ 要做 |
| `chat` | 21 | 会话菜单/置顶/归档/拉黑 | ✅ 要做 |
| `message` | 19 | 消息操作/重译/排队 | ✅ 要做 |
| `attach` | 18 | 附件面板 18 项 | ✅ 要做 |
| `device` | 17 | 多设备管理 | ✅ 要做 |
| `volc` | 16 | 火山引擎（通话翻译密钥） | ❌ 会员功能 |
| `iap`/`plan`/`trial`/`sub*` | ~30 | 内购/套餐/试用 | ❌ 排除 |
| `action` | 13 | 消息长按操作 | ✅ 要做 |
| `qr` | 13 | 二维码配对指引 | ✅ 要做 |
| `sync` | 11 | 手动同步历史消息 | ✅ 要做 |
| `translation` | 11 | 译文设置（颜色/字号/引擎） | ✅ 要做 |
| `label`/`filter`/`search` | ~23 | 标签/筛选/搜索 | ✅ 要做 |
| `poll` | 9 | 投票 | ✅ 要做 |
| `media` | 9 | 媒体发送/保存 | ✅ 要做 |
| `deepseek`/`deepl`/`openai`/`chatgpt`/`qwen`/`glm`/`hunyuan`/`bing`/`baidu`/`google` | ~60 | 各翻译引擎配置 | ✅ 要做 |
| `slash` | 4 | 快速回复（短语模板） | ✅ 要做 |
| `tutorial`/`faq`/`support` | ~13 | 教程/FAQ/在线客服 | ❌ 排除客服 |

---

## 3. 关键模块详解（含文案证据）

### 3.1 附件面板 = 18 项（比我们 Android 的 12 项多 6 项）

```
附件 / 音频 / 相机 / 目录 / 联系人 / 汇率转换 / 文档 / 活动 / GIF / 图片 /
位置 / 新建贴图 / 照片和视频 / 投票 / 快速回复 / 贴纸 / 添加待办 / 视频
```

我们已有：文档、照片和视频、相机、音频、联系人、位置、投票、活动、新建贴图、目录、快速回复、添加待办（12）
**缺**：GIF、贴纸、图片（独立项）、视频（独立项）、汇率转换、音频独立入口

### 3.2 消息操作（`action` 13 + `message` 19）

回复 / 转发 / 编辑 / 撤回 / 复制 / 置顶 / 收藏 / 取消收藏 / 下载 / 添加到列表 / 更换列表 / 丢弃暂存消息

**独有能力：换引擎重译**（我们完全没有）
```
message_retranslating_with_engine   "％@ 翻译中"
message_retranslate_engine_failed   "％@翻译失败"
message_retranslate_failed_with_engine "％@失败: ％@"
message_retranslate_empty_result    "未返回译文"
message_translation_failed_tap_retry "翻译失败，点击重试"
message_send_failed_tap_retry       "发送失败，点击重试"
message_queued_offline              "已排队，联网后发送"
```

### 3.3 输入框状态提示（比我们的"开/关"细腻）

```
composer_hint_same_language   "同语不译"     ← 检测到同语言时自动提示不翻译
composer_hint_translate_off   "发送原文"     ← 关闭翻译时的明确提示
auto_send_translation          "自动发送译文"
auto_send_translation_desc     "开启后自动翻译并发送译文，关闭后自动翻译但不自动发送译文，需手动点击发送按钮。"
auto_send_translation_manual_send_tip "现在请按键盘上的回车/换行键来翻译消息…"
```

### 3.4 翻译设置（11 条，有两处我们没做）

```
translation_engine              翻译接口
translation_color / _desc       译文颜色（我们有）
translation_text_font_size      **译文字号**（我们做的是"原文字号"，方向不同！）
chat_history_translation_engine **聊天记录翻译接口**（历史消息与输入框分别设引擎）
chat_history_translation_engine_desc "仅用于聊天窗口内历史消息的自动翻译，不影响消息输入框翻译。"
```

**16 个接口**：谷歌 / 谷歌Chrome / 谷歌网页 / Bing / 百度 / DeepL / 火山 / Deepseek-V4 / 火山Deepseek-V4 / 阿里Qwen2.5-7B-Instruct / 腾讯Hunyuan-MT-7B / 智谱GLM-4-Flash / ChatGPT / OpenAI自定义 / Deepseek自定义

注意：Qwen、Hunyuan、GLM、DeepL、ChatGPT 在它这里**需要会员才能用**（`*_key_required` 文案要求先登录专业版）；我们自建版无此限制，是我们的优势。

### 3.5 跟进待办（52 条，比我们 Android 版完整得多）

| 能力 | 它的做法 | 我们 Android |
|---|---|---|
| 分组 | 今日计划 / 稍后 / 逾期未跟 | 单一列表 |
| 标签页 | 全部 / 今日 / 逾期 / 已完成 | 未完成 / 全部 |
| 快速选择 | 30分钟 / 1小时 / **明天 10:00** / 3天后 | 今天 / 明天 / 三天后 / 不指定 |
| 时间精度 | 日期 `yyyy-MM-dd` + 时间 `HH:mm` | 仅日期（固定 23:59） |
| **本地通知** | 到期提醒 + 通知渠道 + **精确闹钟权限** + 电池白名单引导 | ❌ 无 |
| 统计 | 逾期 N / 待跟进 N | 仅"N 件待跟进" |
| 入口 | 聊天附件菜单「添加待办」 | 设置页 + 会话长按 |

### 3.6 汇率转换（30 条，我们完全没有）

```
fx_amount_hint     "支持四则运算，如 100*0.2、100/1.2、100+50"
fx_subtitle_local  "当地货币 · ％@"        ← 按对方国家自动选货币
fx_subtitle_unknown "未识别对方货币，请手动选择 · ％@"
fx_target_amount_hint "支持反向输入与四则运算；长按复制金额"
fx_pin / fx_unpin  置顶常用货币
fx_rates_updated   "汇率更新于 ％@"
```

设计要点：**自动识别对方国家货币 → 双向换算 → 支持算式 → 一键复制**，对报价场景极实用。

### 3.7 群 @ 提及（我们 Android 已做，它更完整）

```
group_mention_everyone       "所有人"
group_mention_everyone_hint  "提醒所有人"
group_mention_empty          "无匹配成员"
```

### 3.8 设备与配对

```
device_limit_message  "每个账号最多登录 ％d 台设备。请注销其中一台旧设备…"
device_select_offline "全选离线"
link_phone_guide_step1~5  手机号配对五步指引（含"输入配套设备中 8 个字符的验证码"）
qr_scan_guide_step1~5     二维码配对五步（含"借其他人手机拍照"的兜底路径）
```

**"借别人手机拍二维码"**这个兜底设计很实用——我们 Android 没有。

### 3.9 备份恢复（我们 Android 没有）

```
备份内容：会话与消息 / 媒体文件 / 特别关注 / 置顶会话 / 联系人翻译偏好 / 全局翻译设置
恢复时可逐项勾选：restore_options_title "恢复选项"
进度细分：读取→写入元数据→打包媒体→恢复消息
覆盖警告：restore_replace_warning "恢复会话与消息将覆盖当前所有聊天记录。"
```

---

## 4. 与现状的差距表

### 4.1 我们的 Android 版（v3.3.0）对比

| 能力 | WATransChat iOS 2.82 | 我们 Android 3.3.0 |
|---|---|---|
| 协议层 | Go + libsignal | Go + whatsmeow ✅ 同源 |
| 附件项 | 18 | 12（缺 GIF/贴纸/汇率等） |
| 换引擎重译 | ✅ | ❌ |
| 译文字号 | ✅ | 只有原文字号（方向不同） |
| 历史消息独立引擎 | ✅ | ❌ |
| 跟进待办 | 分组+定时通知+精确闹钟 | 基础版（无通知） |
| 汇率转换 | ✅ | ❌ |
| 备份恢复 | ✅ | ❌ |
| 多设备管理 | ✅ | ❌ |
| @所有人 | ✅ | 仅 @成员 |
| 联系人商业信息 | 营业时间/地址/邮箱 | ❌ |

### 4.2 我们 iOS 版（当前）对比

| 能力 | WATransChat iOS | 我们 iOS |
|---|---|---|
| 形态 | WhatsApp 多设备客户端 | 翻译工具（App+键盘+分享） |
| 聊天 | ✅ 完整 | ❌ 完全没有 |
| 协议层 | Go+libsignal | ❌ 未接 |
| 翻译内核 | 云端/自带 key | ✅ WeiFanCore（27 测试通过） |

---

## 5. 实现路线建议（iOS 版）

### 阶段 0：协议层打通（前置，约 1 天）
```bash
cd weifan-protocol && ./build-ios.sh   # gomobile bind -target=ios,iossimulator
```
产出 `WeifanProtocol.xcframework`，Swift 侧 `import WeifanProtocol` 即可调用（API 与 Android 的 `mobile.WeifanClient` 同构）。
**关键**：我们的 Go 层已有 `NewClient/SendMessage/GetChats/fetchMedia/VotePoll/UpdateBlocklist/promote...`，可直接映射。

### 阶段 1：能收能发（最小闭环）
1. 配对页（手机号 8 位码 + 二维码，含"借别人手机拍码"兜底）
2. 会话列表（搜索、筛选 chips、未读、连接状态条）
3. 会话页（文本收发 + 翻译、气泡、时间/回执）
4. 本地库（GRDB 或直接 SQLite，schema 参考我们 Android 的 `ChatDb` v9）

### 阶段 2：媒体与消息操作
5. 附件面板 18 项（优先：照片视频、文档、相机、位置、联系人、投票）
6. 消息长按：回复/转发/编辑/撤回/复制/**换引擎重译**
7. 媒体查看器（图片左右滑、视频、PDF）

### 阶段 3：差异化能力（我们已有 Android 实现，可平移）
8. 翻译设置（16 接口 + 译文字号 + 历史消息独立引擎 + 提示词）
9. 跟进待办（分组 + HH:mm + 本地通知 + 精确闹钟）
10. 顾客备注 / 标签 / 批量打标签
11. 汇率转换（含算式、自动识别对方货币）
12. 备份恢复
13. 群管理（建群/加人/升降级/@所有人）

### 阶段 4：可选
14. 活动邀请、GIF、贴纸、多设备管理

**不建议做**：会员/付费/内购、AI 客服、VoIP 通话翻译（协议+成本双重不可行）、在线客服。

---

## 6. 未确定项（诚实标注）

| 问题 | 状态 |
|---|---|
| 具体页面层级（Tab 结构、导航栈） | **未知**——类型符号被 strip，且无法运行样本 |
| 每页的确切布局尺寸/颜色 | **未知**——SwiftUI 代码内联样式，无 xib 可读；Assets.car 需进一步解析 |
| 协议层版本与 whatsmeow 上游差异 | **未比对**——只知道用了 `go.mau.fi/libsignal` |
| 服务端接口（翻译走云端还是本地 key） | **部分**——文案显示"填 API Key"，但也提到"接口余额不足请提醒客服续费"→ 存在自建中转，具体未知 |

若要补这些，两条路：①用完整版 Xcode + 真机跑样本并抓包；②继续解析 `Assets.car` 与 `__swift5_types` 段（收益递减）。

---

## 7. 一句话总结

**它是「Android 客户端的 iOS 移植」**：SwiftUI 重写 UI、Go 协议层直接复用、GRDB 存本地、翻译走 16 家接口 + 会员门槛。
**我们的优势**：协议层同样 Go，可直接复用；翻译接口无会员限制；Android 端已有大量能力（待办/标签/备注/PDF/视频）可平移。
**我们的缺口**：iOS 侧只有翻译工具形态，缺整个聊天栈——这就是阶段 1–3 要补的。
