package com.weifan.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.weifan.app.Prefs
import com.weifan.app.core.Engine
import com.weifan.app.core.Lang
import com.weifan.app.core.Routing
import java.util.concurrent.Executors

/**
 * 微翻的无障碍服务 —— Android 端唯一的落地通道。
 *
 * 交互（D-30，来自 SwiftSlate 的后缀触发）：
 *   在任意 App 的输入框里打完中文，末尾加「?译」
 *   → 服务读到整段文本 → 翻译 → 用 ACTION_SET_TEXT 原地替换
 *
 * 为什么不用输入法：一旦接管键盘，中文输入就得自己实现（词库、联想、候选），工程量十倍。
 * 为什么不 hook WhatsApp：改包/注入的封号风险极高，无障碍是官方公开 API。
 *
 * 安全底线（与扩展一致）：
 * - 不自动发送，只改输入框内容，最后一下永远由人按；
 * - 命中金额等硬风险时**不替换**，改为把译文放进剪贴板并提示，由人决定。
 */
class WeiFanAccessibilityService : AccessibilityService() {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var busy = false

    /** 防止同一个词条被重复处理（用户继续打字会再次触发事件） */
    private var lastHandled = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        announce("微翻已连接：在输入框末尾打「${Prefs(this).trigger}」即可翻译")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        if (busy) return

        val prefs = Prefs(this)
        if (!prefs.hasAnyKey()) return

        val trigger = prefs.trigger
        if (trigger.isEmpty()) return

        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: e.source ?: return
        val text = node.text?.toString() ?: return
        if (!text.endsWith(trigger)) return

        val body = text.dropLast(trigger.length).trim()
        if (body.isEmpty()) return
        if (body == lastHandled) return
        lastHandled = body

        busy = true
        executor.execute {
            try {
                val outcome = Engine.translateForSending(body, prefs)
                main.post { apply(node, outcome, prefs) }
            } catch (t: Throwable) {
                main.post { announce("翻译失败：${t.message ?: "未知错误"}") }
            } finally {
                main.post {
                    busy = false
                    // 短暂留痕，避免同一段文本在 2 秒内反复触发
                    main.postDelayed({ if (lastHandled == body) lastHandled = "" }, 2000)
                }
            }
        }
    }

    override fun onInterrupt() {
        // 无需处理：本服务不提供连续的语音/触觉反馈
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    // MARK: - 落地

    private fun apply(node: AccessibilityNodeInfo, outcome: Engine.SendOutcome, prefs: Prefs) {
        if (outcome.risk.shouldPreview) {
            // 硬风险（默认只有"含金额"）：不替换，交给人工确认
            copyToClipboard(outcome.translated)
            val reasons = outcome.risk.reasons.joinToString(" · ") { Routing.riskLabel(it) }
            announce("$reasons：译文已复制，确认后手动粘贴")
            return
        }

        if (!prefs.autoReplace) {
            copyToClipboard(outcome.translated)
            announce("译文已复制（${Lang.name(outcome.target)}，${outcome.latencyMs}ms）")
            return
        }

        val ok = setText(node, outcome.translated)
        if (ok) {
            announce("已替换为${Lang.name(outcome.target)} · ${outcome.provider} · ${outcome.latencyMs}ms")
        } else {
            // 部分 App 的自绘输入框不接受 ACTION_SET_TEXT，退回复制
            copyToClipboard(outcome.translated)
            announce("这个输入框不支持直接替换，译文已复制，请手动粘贴")
        }
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val done = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (done) {
            // 把光标移到末尾，方便直接按发送
            val selection = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        }
        return done
    }

    private fun copyToClipboard(text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("微翻译文", text))
    }

    /**
     * 无障碍服务在后台时 Toast 可能被系统拦，这是尽力而为的提示。
     * 真正的反馈是输入框内容变了 —— 那才是用户必然会看到的。
     */
    private fun announce(message: String) {
        try {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            // 忽略：提示失败不影响主流程
        }
    }
}
