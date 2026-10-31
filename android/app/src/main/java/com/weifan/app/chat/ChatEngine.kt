package com.weifan.app.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.weifan.app.Prefs
import com.weifan.app.WeiFanApplication
import com.weifan.app.core.Engine
import com.weifan.app.data.ChatDb
import com.weifan.app.data.MessageRow
import mobile.ConnectionListener
import mobile.MessageListener
import mobile.Mobile
import mobile.WeifanClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 聊天引擎：协议层（Go）→ 本地库 → 翻译内核（Kotlin core）的粘合层。
 * 单例生命周期 = Application。
 *
 * 翻译策略（与扩展端同规格，直接复用 core.Engine）：
 *  - 收件：Engine.translateIncoming —— 对方语言 → 中文，自动翻
 *  - 发件：Engine.translateForSending —— 中文 → 对方语言，含术语保护/风险拦截/降级链
 *  - 会话语言记忆：每个会话记住最近一次检测的目标语言（D-19 思路）
 */
class ChatEngine private constructor(context: Context) : MessageListener, ConnectionListener {

    private val appContext = context.applicationContext
    val db = ChatDb(appContext)
    val client: WeifanClient
    private val prefs = Prefs(appContext)
    private val main = Handler(Looper.getMainLooper())

    init {
        // 协议客户端必须来自 WeiFanApplication（带代理、全局唯一）
        client = (appContext as? WeiFanApplication)?.protocol()
            ?: Mobile.newClient(appContext.filesDir.absolutePath, prefs.waProxy)
        client.setMessageListener(this)
        client.setConnectionListener(this)
    }

    /** 每个会话最近确认的语言（"es" 等；会话级记忆） */
    val chatLangMemory = ConcurrentHashMap<String, String>()

    /** UI 订阅 */
    var onChatUpdated: (() -> Unit)? = null
    var onMessageTranslated: ((chatJid: String, id: String, translated: String) -> Unit)? = null
    var onConnectionChanged: ((connected: Boolean) -> Unit)? = null

    companion object {
        @Volatile private var instance: ChatEngine? = null
        fun get(context: Context): ChatEngine =
            instance ?: synchronized(this) {
                instance ?: ChatEngine(context.applicationContext).also { instance = it }
            }
    }

    // MARK: - 连接

    fun connectAsync() {
        Thread {
            try {
                if (client.isPaired) client.connect()
            } catch (_: Throwable) { }
        }.start()
    }

    fun isPaired(): Boolean = try {
        prefs.waPreviewMode || client.isPaired
    } catch (_: Throwable) { false }
    fun isConnected(): Boolean = try {
        prefs.waPreviewMode || client.isConnected
    } catch (_: Throwable) { false }

    /** 预览模式：塞演示会话（只塞一次） */
    private var seeded = false
    fun seedPreviewData() {
        if (seeded || !prefs.waPreviewMode) return
        seeded = true
        val now = System.currentTimeMillis()
        // (jid, 标题, 原文, 演示译文)
        val demo = listOf(
            listOf("8613800001111@s.whatsapp.net", "张伟 · 中东客户",
                "Hello! Do you have this in stock? Need 500 pcs",
                "你好！这个有货吗？我需要 500 件"),
            listOf("8613800002222@s.whatsapp.net", "Maria · 拉美客户",
                "Hola, ¿cuál es el precio FOB? Necesito una cotización",
                "你好，FOB 价格是多少？我需要一份报价"),
            listOf("8613800003333@s.whatsapp.net", "Ahmed · 中亚客户",
                "Ассалому алайкум! Когда можно отправить образцы?",
                "你好！什么时候可以寄样品？"),
            listOf("8613800004444@s.whatsapp.net", "老客户 · 李先生",
                "上次那批货质量很好，这次想追加订单",
                "上次那批货质量很好，这次想追加订单（已是中文，不翻）"))
        for ((idx, d) in demo.withIndex()) {
            val (jid, title, text, translated) = d
            val ts = now - idx * 3600_000L
            val id = "demo-$idx"
            db.insertMessage(com.weifan.app.data.MessageRow(
                id = id, chatJid = jid, senderJid = jid, fromMe = false,
                text = text, original = "",
                translated = "", translatedLang = "", ts = ts))
            db.upsertChat(jid, title, text, ts, incrementUnread = idx < 2)
            db.updateTranslation(jid, id, translated, "en")
        }
    }

    // MARK: - MessageListener（Go 层回调，非主线程）

    override fun onMessage(json: String?) {
        if (json.isNullOrBlank()) return
        try {
            val o = JSONObject(json)
            val chatJid = o.optString("chatJid")
            val id = o.optString("id")
            val text = o.optString("text")
            val fromMe = o.optBoolean("fromMe")
            val ts = o.optLong("timestamp", System.currentTimeMillis())
            val pushName = o.optString("pushName")
            if (chatJid.isBlank() || id.isBlank() || text.isBlank()) return

            val row = MessageRow(
                id = id, chatJid = chatJid, senderJid = o.optString("senderJid"),
                fromMe = fromMe, text = text, original = "",
                translated = "", translatedLang = "", ts = ts)
            val inserted = db.insertMessage(row)
            db.upsertChat(chatJid, pushName, text, ts, incrementUnread = !fromMe)

            main.post { onChatUpdated?.invoke() }

            if (!fromMe && prefs.waAutoTranslateIncoming && inserted) {
                translateIncomingAsync(chatJid, id, text)
            }
        } catch (_: Throwable) { }
    }

    override fun onHistoryDone() { /* 第一阶段无额外动作 */ }

    // MARK: - ConnectionListener

    override fun onConnected() { main.post { onConnectionChanged?.invoke(true) } }
    override fun onDisconnected(reason: String?) { main.post { onConnectionChanged?.invoke(false) } }

    // MARK: - 翻译（复用 core.Engine）

    /** 收件：对方语言 → 中文 */
    fun translateIncomingAsync(chatJid: String, id: String, text: String) {
        Thread {
            try {
                val res = Engine.translateIncoming(text, prefs)
                if (!res.alreadyChinese && res.translated.isNotBlank()) {
                    res.sourceLanguage?.let { lang ->
                        if (lang.isNotBlank()) chatLangMemory[chatJid] = lang
                    }
                    db.updateTranslation(chatJid, id, res.translated, res.sourceLanguage ?: "")
                    main.post { onMessageTranslated?.invoke(chatJid, id, res.translated) }
                }
            } catch (_: Throwable) { }
        }.start()
    }

    /** 发件：中文 → 目标语言（显式 > 会话记忆 > 兜底），回调在主线程 */
    fun translateOutgoingAsync(chatJid: String, text: String,
                               callback: (translated: String, lang: String, error: String?) -> Unit) {
        Thread {
            try {
                val res = Engine.translateForSending(
                    text = text,
                    prefs = prefs,
                    explicitTarget = prefs.waTargetLang.ifBlank { null },
                    chatLanguage = chatLangMemory[chatJid]
                )
                chatLangMemory[chatJid] = res.target
                main.post {
                    if (res.translated.isBlank()) callback("", "", "翻译失败，请检查翻译服务配置")
                    else callback(res.translated, res.target, null)
                }
            } catch (e: Throwable) {
                main.post { callback("", "", e.message ?: "翻译失败") }
            }
        }.start()
    }

    // MARK: - 发送

    /**
     * 直译直发：先把用户敲的中文原文翻译成对方语言，把译文发给对方，
     * 再把「译文(主) + 原文(副)」落库。失败时不发送（原文留在输入框）。
     */
    fun sendWithTranslation(chatJid: String, originalText: String,
                            onResult: (serverId: String?, error: String?) -> Unit) {
        Thread {
            try {
                val res = Engine.translateForSending(
                    text = originalText,
                    prefs = prefs,
                    explicitTarget = prefs.waTargetLang.ifBlank { null },
                    chatLanguage = chatLangMemory[chatJid]
                )
                if (res.translated.isBlank()) {
                    main.post { onResult(null, "翻译失败，消息未发送") }
                    return@Thread
                }
                chatLangMemory[chatJid] = res.target
                if (prefs.waPreviewMode) {
                    // 预览模式：没有真实连接，本地回显「已翻译待发」气泡
                    val now = System.currentTimeMillis()
                    val localId = "preview-$now"
                    db.insertMessage(MessageRow(
                        id = localId, chatJid = chatJid, senderJid = "",
                        fromMe = true, text = res.translated, original = originalText,
                        translated = "", translatedLang = res.target, ts = now))
                    db.upsertChat(chatJid, "", res.translated, now, incrementUnread = false)
                    main.post { onChatUpdated?.invoke() }
                    main.post { onResult(localId, null) }
                    return@Thread
                }
                val msgId = client.sendMessage(chatJid, res.translated)
                val now = System.currentTimeMillis()
                db.insertMessage(MessageRow(
                    id = msgId ?: "local-$now", chatJid = chatJid, senderJid = "",
                    fromMe = true, text = res.translated, original = originalText,
                    translated = "", translatedLang = res.target, ts = now))
                db.upsertChat(chatJid, "", res.translated, now, incrementUnread = false)
                main.post { onChatUpdated?.invoke() }
                main.post { onResult(msgId, null) }
            } catch (e: Throwable) {
                main.post { onResult(null, e.message ?: e.toString()) }
            }
        }.start()
    }

    /** 纯发送（已翻译好的文本，预览模式/直接发原文用） */
    fun sendText(chatJid: String, text: String, original: String = "",
                 onResult: (serverId: String?, error: String?) -> Unit) {
        Thread {
            try {
                val msgId = client.sendMessage(chatJid, text)
                val now = System.currentTimeMillis()
                db.insertMessage(MessageRow(
                    id = msgId ?: "local-$now", chatJid = chatJid, senderJid = "",
                    fromMe = true, text = text, original = original,
                    translated = "", translatedLang = "", ts = now))
                db.upsertChat(chatJid, "", text, now, incrementUnread = false)
                main.post { onChatUpdated?.invoke() }
                main.post { onResult(msgId, null) }
            } catch (e: Throwable) {
                main.post { onResult(null, e.message ?: e.toString()) }
            }
        }.start()
    }
}
