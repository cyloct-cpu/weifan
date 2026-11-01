# WATransChat 3.55 与微翻（weifan Android）功能对比与实现路线

> 依据：`watranschat-packages/reports/WATransChat-三端分析报告.md`（REA 静态分析）
> + 本次新证据 `watranschat-packages/exports/mobile-bridge-methods.txt`
> （从 APK 的 classes.dex/classes2.dex 方法表解析出的 `mobile.*` 桥完整 API：
> Client 153 个方法 + 25 个回调接口）。
> 对比范围：排除会员/客服/支付/席位等商业化功能。日期 2026-11-01。

## 一、差距总览（按微翻定位「跨境卖家翻译聊天」排优先级）

### P0 —— 没有它就不像一个正经聊天工具

| # | 功能 | WATransChat 桥方法 | whatsmeow 对应 | 工作量 |
|---|---|---|---|---|
| 1 | 媒体消息**接收显示**（图/视频/音频/文档/贴纸/GIF） | downloadMediaByRef(+ToFile)、EnsureMessageMediaOptions、MessageMediaIndex、decodeVoiceForPlayback | `client.Download(msg)` 直接可用；缩略图内嵌 JPEGThumbnail 可先显示 | Go 1 天 + Kotlin 2 天 |
| 2 | **已读/送达回执**（发出消息 ✓→✓✓→蓝色✓✓；对方读你的） | setReceiptListener、ChatReadListener、markMessagesRead、markChatAsRead | `events.Receipt` + `client.MarkRead()` | 各 0.5 天 |
| 3 | **在线状态 / 正在输入**（「对方正在输入…」） | sendPresenceAvailable、sendChatPresence、subscribePresence、PresenceListener(onPresence/onChatPresence) | `SendPresence` / `SubscribePresence` / `events.Presence` / `events.ChatPresence` | Go 0.5 天 + Kotlin 0.5 天 |
| 4 | **对方编辑/撤回**消息的显示 | MessageEventListener(onMessageEdited/onMessageRevoked)、editMessage、revokeMessage | `events.Message.Edit/Revoke`；`client.EditMessage/RevokeMessage` | 各 0.5 天 |
| 5 | **历史按需翻页**（进会话往上翻加载更早消息） | requestChatHistorySync、HistoryAnchor、setHistoricalMessageListener、isApplyingHistorySync | fork 里加的 ON_DEMAND 封装；官方 whatsmeow 有 `BuildHistorySyncRequest`+`SendPeerMessage` | Go 1 天 + Kotlin 1 天 |

### P1 —— 让卖家用得顺手

| # | 功能 | WATransChat 桥方法 | 实现路径 |
|---|---|---|---|
| 6 | 引用回复（气泡带引用条） | sendReplyMessage(+WithID)、sendReplyTextMessageWithMentions | Kotlin：长按气泡→回复栏；Go：`client.BuildReply`（whatsmeow 有 Send/modular） |
| 7 | 转发消息 | forwardMessage | `client.ForwardMessage`（fork 有；官方用 buildForward） |
| 8 | 置顶 / 归档 / 静音 / 收藏 | setChatPinned、setChatArchived、setMuted、syncChatPins(+Force)、syncChatArchives(+Force)、migrateFavorites、getPinnedChatIDsJSON、getArchivedChatIDsJSON、getFavoriteChatIDsJSON | whatsmeow 的 appState 读写（WAPatch）；工作量主要在 Kotlin UI（列表过滤 tab） |
| 9 | 联系人同步（真实姓名） | syncContacts、saveContact、ContactListener、ContactTitleListener、formatChatDisplayTitle、chatListTitle | `client.Store.Contacts` + `events.Contact`；UI 列表标题优先联系人名 |
| 10 | 群信息/群管理（读侧） | getGroupInfoJSON、getGroupInviteLink、GroupListener、GroupEventListener、syncGroups | `client.GetGroupInfo` / `GetGroupInfoFromLink` / `events.Group` |
| 11 | 发送前校验号码是否注册 | checkPhoneOnWhatsApp、getErrPhoneNotOnWhatsApp | 官方 whatsmeow **现成** `client.IsOnWhatsApp()` |
| 12 | ~~拉黑骚扰客户~~ ✅ **v2.7.0 应用内屏蔽** | — | DB v6 blocked 列 + 来信丢弃 + 设置页管理；官方端拉黑（appstate）留作后续 |

### P2 —— 外贸场景差异化（对标它尊享版的商务能力，但不做 AI）

| # | 功能 | WATransChat 桥方法 | 实现路径 |
|---|---|---|---|
| 13 | WhatsApp Business **产品目录/发产品** | sendProduct、sendCatalog、getOwnCatalog | whatsmeow 有 Catalog/Product proto 支持；发送 product message 需自行组 proto |
| 14 | **客户备注**（CRM 属性） | setCustomerNote、syncNotes、NoteListener | 本地 SQLite 即可先行（不必用它 fork 的 appstate note） |
| 15 | **标签系统**（它用 Label 管理客户） | createLabel/updateLabel/deleteLabel、syncLabels、assignChatsToLabel、getLabelsJSON、getRecommendedLabelsJSON、getChatLabelsJSON、LabelListener | 第一版本地标签（微翻 chips 已有雏形）；接 WhatsApp 原生 label 需读 appstate `LabelAssociation`，whatsmeow 已暴露 appstate keys |
| 16 | 语音条播放（opus→PCM）与发送 | decodeVoiceOgg、decodeVoiceForPlayback、VoicePlaybackAudio、sendAudio | 接收：Android MediaPlayer 直接播 ogg/opus（whatsmeow Download 后即可）；发送：MediaRecorder→ogg 上传 |
| 17 | 群管理（写侧）部分完成 ✅ v2.6.0（改群名/移除成员）；建群/增员/管理员升降级待做 | — | SetGroupName/RemoveGroupParticipants 已通 |
| 18 | 位置 / 联系人卡片 / 投票发送 ✅ v2.6.0；三类**接收真渲染** ✅ v2.8.0；**投票点选+票数回流** ✅ v2.9.0（VotePoll + poll_votes 落库 + 气泡票数徽章，改票=重投） | — | SendLocation/SendContactCard/SendPoll(BuildPollCreation) 已通，投票实测送达 |
| 19 | 联系人补充来源（手机通讯录）✅ v2.8.0：READ_CONTACTS + deviceContacts() 与 WhatsApp LID 去重合并入选择器 | — | 纯设备端合并，不上传 |
| 20 | 客户备注（本地 CRM）✅ v2.8.0：DB v7 chats.note + 联络人资料页多行备注 | — | 本地存储，不动官方 appstate（官方备注同步留作后续） |
| 21 | 稳定性 ✅ v2.8.0：ANR（主线程解码）+ lmkd 内存击杀（1.4GB→300MB：inSampleSize 降采样+LRU 缓存+渲染去抖）双修复 |
| 22 | 投票点选/改票+票数回流 ✅ v2.9.0（VotePoll+poll_votes DB v8+气泡徽章，实测改票生效）；群管理员升降级 ✅；官方端拉黑 ✅ |
| 23 | APK 减重 ✅ v2.9.1：splits.abi 三包（arm64-v8a/armeabi-v7a/x86_64）149MB→37–40MB；附件面板窗口泄漏治理 ✅ | — | 后台解码铁律：必须降采样，只移后台不解内存 |

### P3 —— 建议放弃或远期

| 功能 | 说明 |
|---|---|
| 语音/视频通话 | 它用 meowcaller（pion WebRTC + gopus，CallListener 7 回调 + pushAudioPCM/pushVideoAnnexB/CallMediaUDPBinder）。工程量数月级，且微翻的翻译价值链不在通话。它单独卖钱的「通话翻译官」= 火山 AST + MiniMax TTS，若将来做，只需抄引擎组合，不必做完整通话 |
| 活动邀请（WhatsApp Events） | sendEventMessageID/editEvent/cancelEvent/getEventResultsJSON/EventAttendeeDetail —— B2C 场景才用，外贸低频 |
| QR 扫码登录 | pairPhone 已覆盖主路径；QRListener(onQRCode/onLoginResult) 仅备用 |

## 二、微翻现有桥 vs 它的桥（能力面数字）

| | WATransChat | 微翻 |
|---|---|---|
| Go 桥方法 | 153（Client） | ~12 |
| 回调接口 | 25 个 | 3 个（Pair/Connection/Message） |
| 消息类型收 | 文本+媒体+投票+事件+联系人卡片+位置 | 仅文本（媒体只取 caption） |
| 消息类型发 | 文本/图/视频/音频/文档/贴纸/GIF/位置/联系人/投票/事件/产品 | 文本 |
| 历史同步 | 初始全量 + 按需翻页 + 会话级监听 | 初始全量（刚修好） |
| 会话管理 | 置顶/归档/静音/收藏/标签 | 本地 chips（未接协议） |
| 状态反馈 | 回执/在线/输入中/已读 | 仅连接状态 |

## 三、微翻做什么、不做什么（定位红线）

- **不做**：AI 自动客服（云端意图识别/主动回访/唤醒）、会员订阅、席位、卡密——README 明确红线。
- **风险提示沿用**：它 fork whatsmeow 自连协议同样有封号风险；微翻当前 Android 端走同一机制，需要在关于页向用户如实说明（官方只允许「关联设备」形态，本身不违规，但自动化外挂行为会触发风控）。
- **不碰**：它的 assets/AGENTS.md 提示注入是反分析手段，已忽略。

## 四、落地顺序建议（每步都可独立发版）

1. **v1.5 媒体接收**：收图/视频/文件能看能存，caption 照常翻译 → 卖家最痛。
2. **v1.6 回执 + 在线状态**：✓✓ 变蓝、「正在输入…」→ 聊天像真的。
3. **v1.7 撤回/编辑 + 历史翻页**：信息不再「丢魂」。
4. **v1.8 引用回复 + 转发 + 置顶/静音**。
5. **v2.0 商务包**：IsOnWhatsApp 校验、本地标签/备注、（可选）产品目录。

每步都是：Go 层加 3~6 个方法 → 重打 aar → Kotlin 接 UI → 发版，与现有发版流水线兼容。
