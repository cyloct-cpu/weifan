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
import mobile.MessageEventListener
import mobile.Mobile
import mobile.ReceiptListener
import mobile.TypingListener
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
class ChatEngine private constructor(context: Context) :
    MessageListener, ConnectionListener, ReceiptListener, TypingListener,
    MessageEventListener {

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
        client.setReceiptListener(this)
        client.setTypingListener(this)
        client.setMessageEventListener(this)
    }

    /** 每个会话最近确认的语言（"es" 等；会话级记忆） */
    val chatLangMemory = ConcurrentHashMap<String, String>()

    /** UI 订阅 */
    var onChatUpdated: (() -> Unit)? = null
    var onMessageTranslated: ((chatJid: String, id: String, translated: String) -> Unit)? = null
    var onConnectionChanged: ((connected: Boolean) -> Unit)? = null
    /** 回执：哪些我方消息被送达/已读（read=true 即蓝色 ✓✓） */
    var onReceiptChanged: ((chatJid: String, ids: List<String>, read: Boolean) -> Unit)? = null
    /** 对方正在输入 */
    var onTypingChanged: ((chatJid: String, typing: Boolean) -> Unit)? = null

    // 我方消息被读状态（内存：msgId → 是否已读）
    val readReceipts = ConcurrentHashMap<String, Boolean>()
    // 对方 typing 状态（chatJid → 正在输入）
    private val typingState = ConcurrentHashMap<String, Boolean>()

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
        client.isPaired
    } catch (_: Throwable) { false }
    fun isConnected(): Boolean = try {
        client.isConnected
    } catch (_: Throwable) { false }


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
            val fromHistory = o.optBoolean("history")
            // LID 会话的真实手机号（国家检测用），缓存到会话维度
            val pn = o.optString("pn")
            if (pn.isNotBlank()) pnCache[chatJid] = pn
            val mediaKind = o.optString("mediaKind")
            val mediaMime = o.optString("mimeType")
            val mediaName = o.optString("fileName")
            val mediaSize = o.optLong("fileLength")
            val mediaThumb = o.optString("thumbnail")
            // 下载元数据原样存 JSON，点开时回传给 Go 层
            val mediaMeta = if (mediaKind.isNotBlank()) JSONObject().apply {
                put("kind", mediaKind)
                put("url", o.optString("url"))
                put("directPath", o.optString("directPath"))
                put("mediaKey", o.optString("mediaKey"))
                put("fileSha256", o.optString("fileSha256"))
                put("fileEncSha256", o.optString("fileEncSha256"))
                put("fileLength", mediaSize)
                put("mime", mediaMime)
                put("fileName", mediaName)
                put("seconds", o.optLong("seconds"))
                put("waveform", o.optString("waveform"))
            }.toString() else ""
            // 纯媒体无文字时用中文标签做会话摘要
            val displayText = text.ifBlank {
                when (mediaKind) {
                    "image", "sticker" -> "[图片]"
                    "video" -> "[视频]"
                    "audio" -> "[语音]"
                    "document" -> "[文件] " + mediaName
                    else -> "[消息]"
                }
            }
            if (chatJid.isBlank() || id.isBlank() || (text.isBlank() && mediaKind.isBlank())) return

            val row = MessageRow(
                id = id, chatJid = chatJid, senderJid = o.optString("senderJid"),
                fromMe = fromMe, text = text, original = "",
                translated = "", translatedLang = "", ts = ts,
                mediaKind = mediaKind, mediaMime = mediaMime, mediaName = mediaName,
                mediaSize = mediaSize, mediaThumb = mediaThumb, mediaMeta = mediaMeta)
            val inserted = db.insertMessage(row)
            db.upsertChat(chatJid, pushName, displayText, ts, incrementUnread = !fromMe)

            main.post { onChatUpdated?.invoke() }

            // 历史同步/纯媒体不自动翻译（避免首次登录大规模翻译）
            if (!fromHistory && !fromMe && text.isNotBlank() &&
                prefs.waAutoTranslateIncoming && inserted) {
                translateIncomingAsync(chatJid, id, text)
            }
            // 收到非自发自的文本时顺带拉一次头像（内部有去重与缓存）
            if (!fromMe && !fromHistory) {
                fetchAvatarAsync(chatJid, null)
            }
        } catch (_: Throwable) { }
    }

    override fun onHistoryDone() { main.post { onChatUpdated?.invoke() } }

    // MARK: - 头像拉取（磁盘缓存 + 去重）

    private val avatarInFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * 拉取联系人头像。命中缓存立即回调；未命中且未在拉取中则经 Go 层下载后落盘再回调。
     * callback 在主线程，参数为缓存文件（可能为 null：无头像/拉取失败/已在拉取中）。
     */
    fun fetchAvatarAsync(chatJid: String, callback: ((java.io.File?) -> Unit)?) {
        val cacheDir = java.io.File(appContext.filesDir, "avatars").apply { mkdirs() }
        val key = chatJid.replace(Regex("[^A-Za-z0-9]"), "_")
        val f = java.io.File(cacheDir, "$key.jpg")
        if (f.exists() && f.length() > 0) {
            callback?.let { main.post { it(f) } }
            return
        }
        if (!avatarInFlight.add(chatJid)) {
            // 已在拉取：仅注册回调到回调链末尾
            return
        }
        Thread {
            var out: java.io.File? = null
            try {
                val bytes = client.getProfilePicture(chatJid)
                if (bytes != null && bytes.isNotEmpty()) {
                    f.writeBytes(bytes)
                    out = f
                }
            } catch (_: Throwable) {
            } finally {
                avatarInFlight.remove(chatJid)
            }
            val res = out
            callback?.let { main.post { it(res) } }
        }.start()
    }

    // MARK: - 媒体下载（v1.5）

    private val mediaInFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * 下载消息媒体：命中本地文件立即回调；否则按 mediaMeta 回传 Go 层下载（走代理），
     * 落盘 files/media/ 后更新 DB，再回调。callback 主线程，参数为文件或 null。
     */
    private var pnCache = ConcurrentHashMap<String, String>()

    /** 该会话最近一次收到的真实手机号 JID（Go 层随消息下发）；空=未知 */
    fun lastPnFor(chatJid: String): String = pnCache[chatJid] ?: ""

    /** 主动解析 LID→手机号（进会话页时调用，回调主线程） */
    fun resolvePnAsync(chatJid: String, callback: ((String) -> Unit)) {
        pnCache[chatJid]?.let { callback(it); return }
        Thread {
            val pn = try { client.resolvePN(chatJid) ?: "" } catch (_: Throwable) { "" }
            if (pn.isNotBlank()) pnCache[chatJid] = pn
            callback(pn)
        }.start()
    }

    fun fetchMediaAsync(chatJid: String, id: String, callback: ((java.io.File?) -> Unit)?) {
        val dir = java.io.File(appContext.filesDir, "media").apply { mkdirs() }
        val key = (chatJid + "_" + id).replace(Regex("[^A-Za-z0-9]"), "_")
        val f = java.io.File(dir, key)
        if (f.exists() && f.length() > 0) {
            if (db.messages(chatJid).firstOrNull { it.id == id }?.mediaPath.isNullOrBlank()) {
                db.updateMediaPath(chatJid, id, f.absolutePath)
            }
            callback?.let { main.post { it(f) } }
            return
        }
        if (!mediaInFlight.add(key)) return
        Thread {
            var out: java.io.File? = null
            try {
                val row = db.messages(chatJid).firstOrNull { it.id == id }
                if (row != null && row.mediaMeta.isNotBlank()) {
                    val m = JSONObject(row.mediaMeta)
                    val bytes = client.downloadMedia(
                        m.optString("kind"), m.optString("url"), m.optString("directPath"),
                        m.optString("mediaKey"), m.optString("fileSha256"), m.optString("fileEncSha256"),
                        m.optLong("fileLength"))
                    if (bytes != null && bytes.isNotEmpty()) {
                        f.writeBytes(bytes)
                        db.updateMediaPath(chatJid, id, f.absolutePath)
                        out = f
                    }
                }
            } catch (_: Throwable) {
            } finally {
                mediaInFlight.remove(key)
            }
            val res = out
            callback?.let { main.post { it(res) } }
        }.start()
    }

    // MARK: - ReceiptListener / TypingListener（P0-2/3）

    override fun onReceipt(json: String?) {
        try {
            val o = JSONObject(json ?: return)
            val chatJid = o.optString("chatJid")
            val read = o.optString("type") == "read"
            val ids = mutableListOf<String>()
            val arr = o.optJSONArray("ids") ?: return
            for (i in 0 until arr.length()) ids += arr.getString(i)
            for (id in ids) if (read) readReceipts[id] = true
            main.post { onReceiptChanged?.invoke(chatJid, ids, read) }
        } catch (_: Throwable) { }
    }

    override fun onTyping(json: String?) {
        try {
            val o = JSONObject(json ?: return)
            val chatJid = o.optString("chatJid")
            val typing = o.optBoolean("typing")
            typingState[chatJid] = typing
            main.post { onTypingChanged?.invoke(chatJid, typing) }
        } catch (_: Throwable) { }
    }

    fun isTyping(chatJid: String): Boolean = typingState[chatJid] == true

    // MARK: - MessageEventListener（P0-4 撤回/编辑）

    override fun onMessageEvent(json: String?) {
        try {
            val o = JSONObject(json ?: return)
            val chatJid = o.optString("chatJid")
            val id = o.optString("id")
            val kind = o.optString("kind")
            when (kind) {
                "revoke" -> db.applyRevoke(chatJid, id)
                "edit" -> {
                    val newText = o.optString("editText")
                    if (newText.isNotBlank()) db.applyEdit(chatJid, id, newText)
                }
            }
            main.post { onChatUpdated?.invoke() }
        } catch (_: Throwable) { }
    }

    /** 历史向上翻页：取当前最早一条做锚点，请求更早 count 条 */
    fun loadOlderMessagesAsync(chatJid: String, count: Int = 50, onDone: ((Boolean) -> Unit)? = null) {
        Thread {
            var ok = false
            try {
                val oldest = db.messages(chatJid, 1).firstOrNull()
                // messages() 是 ts ASC，第一条=最早
                val first = db.messages(chatJid).firstOrNull()
                if (first != null) {
                    client.requestHistorySyncOnDemand(
                        chatJid, first.id, first.fromMe, first.ts, count)
                    ok = true
                }
            } catch (_: Throwable) { }
            val res = ok
            onDone?.let { main.post { it(res) } }
        }.start()
    }

    /** 上报已读（进会话页时：把该会话所有收到的消息标 read） */
    fun markReadOnServerAsync(chatJid: String) {
        Thread {
            try {
                val ids = db.messages(chatJid).filter { !it.fromMe }.map { it.id }
                if (ids.isNotEmpty()) client.markChatRead(chatJid, ids.toString())
            } catch (_: Throwable) { }
        }.start()
    }

    /** 发送输入状态（有防抖：3 秒无输入自动停） */
    private var typingSent = false
    private val typingStopHandler = Handler(Looper.getMainLooper())

    fun notifyComposing(chatJid: String) {
        if (!typingSent) {
            typingSent = true
            Thread { try { client.sendChatPresence(chatJid, true) } catch (_: Throwable) { } }.start()
        }
        typingStopHandler.removeCallbacksAndMessages(null)
        typingStopHandler.postDelayed({
            typingSent = false
            Thread { try { client.sendChatPresence(chatJid, false) } catch (_: Throwable) { } }.start()
        }, 3000)
    }

    /** 标记自己在线（连接后调用一次；不在线收不到对方 typing） */
    fun announceOnlineAsync() {
        Thread { try { client.sendPresenceAvailable() } catch (_: Throwable) { } }.start()
    }

    fun subscribePresenceAsync(chatJid: String) {
        Thread { try { client.subscribePresence(chatJid) } catch (_: Throwable) { } }.start()
    }

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

    // MARK: - 回复 / 转发 / 发媒体（v1.6，对齐 WATransChat）

    /** 引用回复：入库记录引用关系（replyQuoted 存引用文本摘要） */
    fun sendReplyAsync(chatJid: String, quoteId: String, quoteParticipant: String,
                       quoteText: String, body: String,
                       onResult: (String?, String?) -> Unit) {
        Thread {
            try {
                val msgId = client.sendReply(chatJid, quoteId, quoteParticipant, quoteText, body)
                val now = System.currentTimeMillis()
                db.insertMessage(MessageRow(
                    id = msgId ?: "local-$now", chatJid = chatJid, senderJid = "",
                    fromMe = true, text = body, original = "",
                    translated = "", translatedLang = "", ts = now))
                db.upsertChat(chatJid, "", body, now, incrementUnread = false)
                main.post { onChatUpdated?.invoke() }
                main.post { onResult(msgId, null) }
            } catch (e: Throwable) {
                main.post { onResult(null, e.message ?: e.toString()) }
            }
        }.start()
    }

    /** 转发文本到指定会话（不入库当前会话，只入库目标会话） */
    fun forwardTextAsync(targetJid: String, text: String,
                         onResult: (String?, String?) -> Unit) {
        Thread {
            try {
                val msgId = client.sendForwardText(targetJid, text)
                val now = System.currentTimeMillis()
                db.insertMessage(MessageRow(
                    id = msgId ?: "local-$now", chatJid = targetJid, senderJid = "",
                    fromMe = true, text = text, original = "",
                    translated = "", translatedLang = "", ts = now))
                db.upsertChat(targetJid, "", text, now, incrementUnread = false)
                main.post { onChatUpdated?.invoke() }
                main.post { onResult(msgId, null) }
            } catch (e: Throwable) {
                main.post { onResult(null, e.message ?: e.toString()) }
            }
        }.start()
    }

    /** 发送方撤回（本地置占位，服务器广播 REVOKE 给对方） */
    fun revokeAsync(chatJid: String, msgId: String, onDone: ((Boolean) -> Unit)? = null) {
        Thread {
            var ok = false
            try {
                client.revokeMessage(chatJid, msgId)
                db.applyRevoke(chatJid, msgId)
                ok = true
            } catch (_: Throwable) { }
            val res = ok
            onDone?.let { main.post { it(res) } }
            main.post { onChatUpdated?.invoke() }
        }.start()
    }

    /** 发送方编辑文本（本地更新，服务器广播 EDIT；官方 15 分钟窗口） */
    fun editAsync(chatJid: String, msgId: String, newText: String, onDone: ((Boolean) -> Unit)? = null) {
        Thread {
            var ok = false
            try {
                client.editTextMessage(chatJid, msgId, newText)
                db.applyEdit(chatJid, msgId, newText)
                ok = true
            } catch (_: Throwable) { }
            val res = ok
            onDone?.let { main.post { it(res) } }
            main.post { onChatUpdated?.invoke() }
        }.start()
    }

    /** 转发媒体（用 mediaMeta 重建 proto，带 IsForwarded） */
    fun forwardMediaAsync(targetJid: String, metaJson: String,
                          onResult: (String?, String?) -> Unit) {
        Thread {
            try {
                val m = JSONObject(metaJson)
                val params = JSONObject().apply {
                    put("Kind", m.optString("kind"))
                    put("URL", m.optString("url"))
                    put("DirectPath", m.optString("directPath"))
                    put("MediaKeyB64", m.optString("mediaKey"))
                    put("ShaB64", m.optString("fileSha256"))
                    put("EncShaB64", m.optString("fileEncSha256"))
                    put("Mime", m.optString("mime") ,)
                    put("FileName", m.optString("fileName"))
                    put("FileLength", m.optLong("fileLength"))
                }
                val msgId = client.sendForwardMedia(targetJid, params.toString())
                main.post { onResult(msgId, null) }
            } catch (e: Throwable) {
                main.post { onResult(null, e.message ?: e.toString()) }
            }
        }.start()
    }

    /** 发送本地图片（经代理上传） */
    fun sendImageAsync(chatJid: String, path: String, caption: String,
                       onResult: (String?, String?) -> Unit) {
        Thread {
            try {
                val msgId = client.sendImageFromPath(chatJid, path, caption)
                val now = System.currentTimeMillis()
                db.insertMessage(MessageRow(
                    id = msgId ?: "local-$now", chatJid = chatJid, senderJid = "",
                    fromMe = true, text = caption, original = "",
                    translated = "", translatedLang = "", ts = now,
                    mediaKind = "image", mediaMime = "image/*",
                    mediaName = java.io.File(path).name, mediaSize = java.io.File(path).length(),
                    mediaPath = path))
                db.upsertChat(chatJid, "", caption.ifBlank { "[图片]" }, now, incrementUnread = false)
                main.post { onChatUpdated?.invoke() }
                main.post { onResult(msgId, null) }
            } catch (e: Throwable) {
                main.post { onResult(null, e.message ?: e.toString()) }
            }
        }.start()
    }

    /** 发送位置（经纬度 + 可选名称/地址） */
    fun sendLocationAsync(chatJid: String, lat: Double, lng: Double, name: String, address: String,
                          onResult: (String?, String?) -> Unit) {
        Thread {
            try {
                val msgId = client.sendLocation(chatJid, lat, lng, name, address)
                val now = System.currentTimeMillis()
                db.insertMessage(MessageRow(
                    id = msgId ?: "local-$now", chatJid = chatJid, senderJid = "",
                    fromMe = true, text = "📍 " + name.ifBlank { "位置" }, original = "",
                    translated = "", translatedLang = "", ts = now,
                    mediaKind = "location", mediaMime = "", mediaName = name))
                db.upsertChat(chatJid, "", "📍 " + name.ifBlank { "位置" }, now, incrementUnread = false)
                main.post { onChatUpdated?.invoke() }
                main.post { onResult(msgId, null) }
            } catch (e: Throwable) {
                main.post { onResult(null, e.message ?: e.toString()) }
            }
        }.start()
    }

    /** 发送联系人卡片 */
    fun sendContactAsync(chatJid: String, displayName: String, phone: String,
                         onResult: (String?, String?) -> Unit) {
        Thread {
            try {
                val digits = phone.replace("+", "").filter { it.isDigit() }
                val vcard = "BEGIN:VCARD\nVERSION:3.0\nFN:$displayName\nTEL;TYPE=CELL:+$digits\nEND:VCARD"
                val msgId = client.sendContactCard(chatJid, displayName, vcard)
                val now = System.currentTimeMillis()
                db.insertMessage(MessageRow(
                    id = msgId ?: "local-$now", chatJid = chatJid, senderJid = "",
                    fromMe = true, text = "👤 " + displayName, original = "",
                    translated = "", translatedLang = "", ts = now,
                    mediaKind = "contact", mediaMime = "", mediaName = displayName))
                db.upsertChat(chatJid, "", "👤 $displayName", now, incrementUnread = false)
                main.post { onChatUpdated?.invoke() }
                main.post { onResult(msgId, null) }
            } catch (e: Throwable) {
                main.post { onResult(null, e.message ?: e.toString()) }
            }
        }.start()
    }

    /** 发起投票 */
    fun sendPollAsync(chatJid: String, question: String, optionsCsv: String,
                      onResult: (String?, String?) -> Unit) {
        Thread {
            try {
                val msgId = client.sendPoll(chatJid, question, optionsCsv)
                val now = System.currentTimeMillis()
                db.insertMessage(MessageRow(
                    id = msgId ?: "local-$now", chatJid = chatJid, senderJid = "",
                    fromMe = true, text = "📊 " + question, original = "",
                    translated = "", translatedLang = "", ts = now,
                    mediaKind = "poll", mediaMime = "", mediaName = optionsCsv))
                db.upsertChat(chatJid, "", "📊 " + question, now, incrementUnread = false)
                main.post { onChatUpdated?.invoke() }
                main.post { onResult(msgId, null) }
            } catch (e: Throwable) {
                main.post { onResult(null, e.message ?: e.toString()) }
            }
        }.start()
    }

    /** 发送录音（AAC 音频消息；ptt 标记语音条样式） */
    fun sendAudioAsync(chatJid: String, path: String, seconds: Int,
                       onResult: (String?, String?) -> Unit) {
        Thread {
            try {
                val msgId = client.sendAudioFromPath(chatJid, path, true, seconds.toLong())
                val now = System.currentTimeMillis()
                db.insertMessage(MessageRow(
                    id = msgId ?: "local-$now", chatJid = chatJid, senderJid = "",
                    fromMe = true, text = "", original = "",
                    translated = "", translatedLang = "", ts = now,
                    mediaKind = "audio", mediaMime = "audio/aac",
                    mediaName = java.io.File(path).name, mediaSize = java.io.File(path).length(),
                    mediaPath = path))
                db.upsertChat(chatJid, "", "[语音]", now, incrementUnread = false)
                main.post { onChatUpdated?.invoke() }
                main.post { onResult(msgId, null) }
            } catch (e: Throwable) {
                main.post { onResult(null, e.message ?: e.toString()) }
            }
        }.start()
    }

    /** 发送本地文档（经代理上传） */
    fun sendDocumentAsync(chatJid: String, path: String, fileName: String,
                          onResult: (String?, String?) -> Unit) {
        Thread {
            try {
                val msgId = client.sendDocumentFromPath(chatJid, path, fileName, "")
                val now = System.currentTimeMillis()
                db.insertMessage(MessageRow(
                    id = msgId ?: "local-$now", chatJid = chatJid, senderJid = "",
                    fromMe = true, text = "", original = "",
                    translated = "", translatedLang = "", ts = now,
                    mediaKind = "document", mediaMime = "",
                    mediaName = fileName, mediaSize = java.io.File(path).length(),
                    mediaPath = path))
                db.upsertChat(chatJid, "", "[文件] $fileName", now, incrementUnread = false)
                main.post { onChatUpdated?.invoke() }
                main.post { onResult(msgId, null) }
            } catch (e: Throwable) {
                main.post { onResult(null, e.message ?: e.toString()) }
            }
        }.start()
    }
}
