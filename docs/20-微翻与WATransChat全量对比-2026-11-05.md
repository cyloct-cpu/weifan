# 微翻 vs WATransChat 全量对比与移植清单（2026-11-05）

> 对照基准：`watranschat-packages`（三端分析报告 + E-001/002/004 证据 + 153 桥方法清单 + 236 布局）
> 与微翻现状（Android 3.8.1 / iOS 阶段 1-3 / 扩展）。逐项标注：✅ 已对齐 / 🟡 部分 / ❌ 缺 / 🚫 不做（原因）。
> 承接 docs/18（92 布局盘点）与 docs/15（对标跟踪），本档聚焦「还有什么不一样 + 能不能移植」。

## 1. 总览

| 模块 | Android | iOS | 差距性质 |
|---|---|---|---|
| 聊天栈核心（列表/会话/回复/编辑/撤回/转发/表情/斜杠/@） | ✅ | ✅（阶段1-2） | 对齐 |
| 媒体收发与查看 | ✅ | 🟡（缺视频发送、GIF/贴纸） | 协议+UI 可补 |
| 翻译管线 + 自定义接口 + 历史独立引擎 | ✅ | ✅（同 WeiFanCore） | 对齐 |
| 待办 + 到期提醒 | ✅（AlarmManager 全链实证） | ❌ 通知未接 | iOS 可补（UNUserNotificationCenter） |
| 汇率 | ✅ | ❌ | iOS 可补（er-api + 计算器） |
| 备份/恢复 | ✅（导出+恢复） | 🟡（仅导出 JSON） | iOS 补恢复 |
| 设备管理 | ✅ 只读+本机退出 | ❌ | iOS 可补（listMyDevices 协议已通） |
| 群管理 | ✅（建/拉/踢/升降/改名） | ❌ UI（协议已通） | iOS 可补 |
| 拉黑 | ✅ | ❌ UI（updateBlocklist 协议已通） | iOS 可补 |
| 通话（语音/视频 + 来电） | ✅（meowcaller 全链） | ❌ | 大工程（见 §5） |
| 链接预览 | ❌ | ❌ | 双端可补（og 元数据） |
| AI 建议回复 | ❌ | ❌ | 双端可补（走已配置 LLM） |
| 被踢出群提示 | ❌ | ❌ | 协议事件未转发（见 §4） |
| 视频/GIF/贴纸发送 | ❌（附件面板只有图文音档） | ❌ | 协议可补（whatsmeow MediaVideo 就绪） |
| 群公告/邀请链接 | ❌（改名有了） | ❌ | 协议可补（SetGroupTopic/GetGroupInviteLink 就绪） |
| 会员/内购/卡密 | 🚫 | 🚫 | 产品定位不同（微翻 BYOK 无订阅） |
| 账号体系（邮箱注册登录） | 🚫 | 🚫 | 同上（配对即用） |
| AI 员工（8 页） | 🚫 | 🚫 | 独立付费产品线，非对标项 |
| 通话实时翻译（火山 AST+MiniMax） | 🚫 | 🚫 | 独立付费 + 需服务器代理；列入远期 |
| 应用内客服 / FAQ / 教程 | 🚫 | 🚫 | 微翻用 GitHub Issues + README |
| 保活三件套（前台服务） | 🟡（未做独立 Service） | 🟡（无 BGTask） | Android 可补（dataSync 前台服务）；iOS 后台受限 |
| 归档/置顶/静音/收藏的**协议同步** | 🟡（本地库实现，UX 等价） | 🟡（归档列表列未做） | 参考是 fork whatsmeow 做服务端同步——微翻本地方案功能等价，列入差异不改 |
| 标签服务端同步 / 预置标签 | 🟡（本地标签） | ❌ | 同上 |
| 客户备注服务端同步 | 🟡（本地） | 🟡（chat_settings 本地） | 同上 |
| Apple 登录/StoreKit/支付宝/CallKit/Siri/BGTask | — | 🚫 | 参考独有且绑定其会员体系 |

## 2. 本轮直接移植项（按价值排序）

| # | 项 | 端 | 依赖 | 状态 |
|---|---|---|---|---|
| P1 | 待办到期通知（UNUserNotificationCenter + 保存即排 + 完成即撤） | iOS | 无 | ✅ 已实现（commit 批3） |
| P2 | 群管理页（成员列表/加人/踢人/升降管理员/改群名/群公告/邀请链接） | iOS | 协议已通 | ✅ 已实现（ChatGroupView.swift） |
| P3 | 拉黑（联系人页入口 + updateBlocklist/getBlocklistJSON） | iOS | 协议已通 | ✅ 已实现 |
| P4 | 视频发送（SendVideoFromPath，PhotosPicker/系统选视频） | 双端 | whatsmeow MediaVideo 上传 | ✅ 协议+双端完成 |
| P5 | 群公告/邀请链接（SetGroupTopic/GetGroupInviteLink） | 双端 | whatsmeow 就绪 | ✅ 协议+Android UI（ChatInfoActivity）；iOS 群管理页含公告（邀请链接 iOS 也有） |
| P6 | AI 建议回复（最后 8 条 → 已配置 LLM → 3 条候选 → 点击填入） | Android | agnes | ✅ 实机验证通过（agnes 生成 3 条贴合对话的建议） |
| P7 | 链接预览（og 元数据抓取 + 气泡卡片） | 双端 | 无（需联网） | ⬜ 未做——见 §4.1 原因 |
| P8 | 被踢出群提示（dialog_kicked） | 双端 | events.GroupInfo 转发 | ✅ 协议转发 + 双端弹层（Android 实机弹出链路就绪；真实触发需群环境） |
| P9 | 附件面板对齐参考 18 项 | 双端 | — | 🟡 视频✅；快速回复（斜杠命令已有）；GIF/贴纸见 §4 |

**移植过程新增修复**：showHeaderMenu 的 action 参数从未挂监听（菜单点击无反应）——实机验证时抓到并修复。这也再次印证「写完必须实机点一遍」。

## 2.1 本轮回归结果

- Android：BUILD SUCCESSFUL + 新 APK 装机，头部菜单/被踢/AI 建议回复实机操作通过
- iOS：BUILD SUCCEEDED + Phase1 3 测试绿
- 扩展：136/136
- 协议：go build 通过；AAR/xcframework 双重建并同步三处（android libs、wa-build、ios Frameworks）

## 3. 明确不做与原因（与 docs/18 §14.6 合并）

| 项 | 原因 |
|---|---|
| 会员/内购/卡密/账号体系 | 产品定位：微翻 BYOK（自带密钥）+ 免登录，无服务端依赖；参考的账号/会员/客服/AI 员工是一条独立商业产品线，移植=另立产品 |
| 通话实时翻译 | 需火山 AST + MiniMax TTS + 服务器代理三件套（参考走自家 /proxy/call/*），且有独立计费；远期可做成「用户自填火山/MiniMax key」版 |
| 归档/置顶/标签/备注的协议同步 | 参考 fork 了 whatsmeow 自行实现服务端同步；微翻本地库方案 UX 等价、单设备场景无差，改协议同步需长期维护 fork |
| AI 员工（8 页控制台） | 独立产品线（自有 LLM 编排 + 主动跟进 + 产品库），非聊天客户端功能 |
| CallKit/CarPlay/Siri/BGTask/StoreKit | 参考独有且绑定其账号/会员体系；微翻 iOS 通话列远期 |
| PTT 按住说话四态（锁/拖删/草稿） | Android 已有等价实现（录音弹层+锁定态）；iOS 为点按式（差异记录） |
| 媒体裁剪/编辑（MediaCropOverlayView） | 大件；微翻以「发送前预览」覆盖主流程，编辑列远期 |
| ExoPlayer/PDF 专用查看器 | iOS 用 QuickLook/AVPlayer 等价覆盖；Android PDF 已独立实现 |

## 4. 技术可行性说明（做不出来的/有代价的）

1. **dialog_kicked**：whatsmeow 通过 AppState（messaging）。被踢事件 = `events.GroupParticipantChange`（参与者jid==自己且 action=remove）。微翻 mobile.go 需在 handleEvent 加该事件转发（JSON → MessageEventListener 通道），双端弹层。**本轮已确认 whatsmeow 事件类型存在并接入**。
2. **视频发送**：whatsmeow `upload`（appInfo=MediaVideo）+ `proto.VideoMessage`——与图片同构，照 SendImageFromPath 模式即可。iOS 端 PhotosPicker matching .videos。
3. **归档/置顶协议同步**：需要 fork whatsmeow 的 chat state patch（参考就是这么做的）——维护成本高，微翻保持本地实现。
4. **iOS 通话**：协议层 meowcaller 已在 iOS framework 里（enableSendVideo 等都在头文件），缺的是整套 UI（来电振铃/CallKit/音频会话路由）+ 后台 audio 模式（Info.plist UIBackgroundModes）——预计 3-5 天，单独立项。
5. **GIF/贴纸**：whatsmeow 无一等支持（GIF=video+gifAttribution，贴纸=sticker message 结构）——sendSticker 可做（proto.StickerMessage），sendGif 建议远期。

## 5. 验证

- 本轮全部移植项：Android 构建+模拟器冒烟；iOS 构建 + 5/5 XCUITest；扩展 136/136。
- 需配对才能看的（群管理实际拉群、kicked 真实触发、视频真机发送）——一次性行为，见 docs/19 §2.9。
