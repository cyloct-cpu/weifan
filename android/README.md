# 微翻 · Android

Android 端 v0.1。与浏览器扩展共用同一套判断逻辑（语言检测 / 术语保护 / 混合路由 / 风险闸门），只是落地方式不同。

## 交互：后缀触发

```
在任意 App 的输入框里打完中文 → 末尾加「?译」→ 自动读整段、翻译、原地替换
```

不需要点按钮、不需要悬浮窗、不需要切键盘。触发词可以在设置里改。

**为什么不做输入法**：一旦接管键盘，中文输入就得自己实现（词库、联想、候选），工程量十倍，且用户换输入法的心理成本很高。无障碍改写是这条路上唯一划算的方案，SwiftSlate / JevChat-Jarvis 已经跑通。

**为什么不用 hook / 改包**：封号风险极高。无障碍是官方公开 API。

## 构建

需要 JDK 17 + Android SDK（platform-34 / build-tools 34.0.0）。工程自带 Gradle wrapper（8.7），不依赖本机 gradle 版本。

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=$HOME/Library/Android/sdk
cd android
./gradlew assembleDebug       # 产物：app/build/outputs/apk/debug/app-debug.apk
```

**已验证**（2026-10-29，macOS arm64）：`BUILD SUCCESSFUL in 16s`，APK 825KB，
包名 `com.weifan.app`，`INTERNET` 权限与 `BIND_ACCESSIBILITY_SERVICE` 服务均已进包。
尚未在真机安装验证。

用 Android Studio 打开 `android/` 也可以，它会自动同步 Gradle。

安装到手机：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

仓库里还留了一份可直接安装的产物：`dist/android/weifan-android-0.1.0-debug.apk`（debug 签名，仅供自用）。

## 首次使用

1. 打开微翻 → 填一个翻译密钥（Groq / DeepL / Google 任一）→ 保存
2. 点「打开无障碍设置」→ 找到「微翻 · 翻译输入框」→ 开启
3. **再做两件事，否则服务会被系统杀掉**（详见下方保活）
4. 回到任意聊天 App，输入框里打「这批货下周能发吗？?译」

## 保活（国产 ROM 必做）

无障碍服务在息屏后被系统清理，是这类工具最常见的"用两天就失灵"原因：

| 品牌 | 要开的东西 |
|---|---|
| MIUI / HyperOS | 自启动、后台弹出界面、省电策略设为「无限制」、锁定后台 |
| EMUI / HarmonyOS | 应用启动管理 → 手动管理 → 允许自启动/关联启动/后台活动 |
| ColorOS / realme UI | 允许自启动、允许后台运行、电池优化 → 不优化 |
| vivo OriginOS | 后台高耗电、自启动、白名单 |

通用一步：系统设置 → 电池 → 电池优化 → 找到微翻 → 设为「不优化」。

## 代码结构

```
app/src/main/java/com/weifan/app/
  MainActivity.kt               设置页（纯 framework 视图，无 AndroidX）
  Prefs.kt                      SharedPreferences 存储
  core/
    Lang.kt                     语种表（22 个，与扩展一致）
    Detector.kt                 本地语言检测（脚本判定 + 停用词打分）
    Glossary.kt                 术语占位符保护与还原
    Routing.kt                  混合路由 + 风险闸门
    Providers.kt                Google / DeepL / Groq（HttpURLConnection + org.json）
    Engine.kt                   门面：定语言 → 保护 → 路由 → 翻译 → 还原 → 风险
  service/
    WeiFanAccessibilityService.kt   ← 唯一与系统交互的地方
```

**零第三方依赖**：不引 AndroidX、OkHttp、Gson —— JSON 用系统自带的 `org.json`，网络用 `HttpURLConnection`。这样 APK 小、编译快、不会因为某个库抬升 minSdk 而卡住。

## 与扩展的一致性

| Android | 扩展 | 必须一致的点 |
|---|---|---|
| `core/Detector.kt` | `extension/src/core/language.js` | 语种表、Unicode 范围、停用词 |
| `core/Glossary.kt` | `extension/src/core/glossary.js` | 占位符格式 `⟦n⟧`、长词优先、还原正则 |
| `core/Routing.kt` | `extension/src/core/routing.js` | 60 字阈值、含数字升级、金额硬拦截 |
| `core/Providers.kt` → `systemPrompt` | `providers.js` → `buildPrompt` | **提示词必须逐字一致** |

改任何一处，三端都要同步，否则同一个客户在不同设备上会收到风格不同的译文。

## 已知限制（v0.1）

| 限制 | 说明 |
|---|---|
| 读不到对方消息 | v0.1 只做发件方向。语言判断靠兜底语言，未接会话上下文 |
| 风险拦截不走弹窗 | 命中金额时不替换，改为复制译文到剪贴板并提示。悬浮确认条待做 |
| 不支持"固定译法" | 只做「不翻译」黑名单（对应决策 D-08） |
| 部分输入框不支持改写 | 自绘输入框会拒绝 `ACTION_SET_TEXT`，此时自动退回复制 |
| 没有单元测试 | Kotlin 侧尚未复现 33 项断言集（依赖 org.json，JVM 上跑不了；需 Robolectric 或 instrumented test） |

## 下一步

1. 真机验证：WhatsApp 里打中文 + `?译` 能否原地变西语
2. 加 `NotificationListenerService`，读通知里的对方消息做语言检测（低成本、独立权限）
3. 加 `TYPE_ACCESSIBILITY_OVERLAY` 的确认条，把"含金额"从复制改为可视化确认
4. 把 33 项断言集用 Kotlin 复现（`app/src/test/` + 手写 JSON 或换纯 Kotlin 的 JSON 解析）
5. 息屏 30 分钟后服务是否仍存活 —— 这是这类应用的真实死亡率所在
