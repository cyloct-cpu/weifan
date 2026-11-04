package com.weifan.app.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.weifan.app.Prefs
import com.weifan.app.WeiFanApplication
import com.weifan.app.core.Engine
import com.weifan.app.data.ChatDb
import com.weifan.app.data.MessageRow
import mobile.CallListener
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
        client.setVideoListener(object : mobile.VideoListener {
            override fun onVideoFrame(data: ByteArray?) {
                if (data != null) videoSink?.invoke(data)
            }
        })
        client.setAudioListener(object : mobile.AudioListener {
            override fun onAudioFrame(data: ByteArray?) {
                if (data != null) audioSink?.invoke(data)
            }
        })
        client.setCallListener(object : mobile.CallListener {
            override fun onIncomingCall(from: String?, callID: String?, video: Boolean,
                                        timestampMs: Long) {
                onIncomingCall?.invoke(from ?: "", callID ?: "", video)
            }
            override fun onCallTerminated(callID: String?, reason: String?) {
                onCallTerminated?.invoke(callID ?: "")
            }
            override fun onCallState(phase: Long) {
                onCallPhase?.invoke(phase.toInt())
            }
        })
        // pollUpdateListener 在下方属性声明后于 initPoll 注册（属性初始化顺序）
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

    /** 投票结果变更（含改票）；UI 订阅后重渲染该投票气泡 */
    var onPollVotesChanged: ((chatJid: String, pollMsgId: String) -> Unit)? = null

    // Go 侧投票解密回调：SHA-256 哈希 → 用该会话选项表反向映射成选项名落库
    private val pollUpdateListener = object : mobile.PollUpdateListener {
        override fun onPollUpdate(json: String) {
            try {
                val o = JSONObject(json)
                val chatJid = o.getString("chatJid")
                val pollMsgId = o.getString("pollMsgId")
                val voterJid = o.getString("voterJid")
                val voterName = o.optString("voterName")
                val ts = o.optLong("ts", System.currentTimeMillis())
                val hashes = o.optJSONArray("optionHash") ?: return
                // 选项表：从本地消息行 mediaMeta.waveform（\n 分隔）取
                val row = db.message(chatJid, pollMsgId) ?: return
                val meta = try { JSONObject(row.mediaMeta) } catch (_: Throwable) { JSONObject() }
                val options = meta.optString("waveform").split("\n").filter { it.isNotBlank() }
                // SHA-256(optionName) → optionName；对不上（选项已改）就丢弃该哈希
                val names = (0 until hashes.length()).mapNotNull { i ->
                    val h = hashes.getString(i)
                    options.firstOrNull { sha256Hex(it.toByteArray()) == h }
                }
                db.replacePollVotes(chatJid, pollMsgId, voterJid, voterName, names, ts)
                main.post { onPollVotesChanged?.invoke(chatJid, pollMsgId) }
            } catch (_: Throwable) {
            }
        }
    }

    init {
        client.setPollUpdateListener(pollUpdateListener)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    /** 以本人身份给投票投选（改票即重投） */
    fun votePollAsync(chatJid: String, pollMsgId: String, senderJid: String, tsMs: Long,
                      optionNames: List<String>, onResult: (String?) -> Unit) {
        Thread {
            val err = try {
                client.votePoll(chatJid, pollMsgId, senderJid, tsMs, optionNames.joinToString(","))
                null
            } catch (e: Throwable) { e.message }
            main.post { onResult(err) }
        }.start()
    }


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
                    "poll" -> "📊 " + mediaName
                    "location" -> "📍 " + mediaName.ifBlank { "位置" }
                    "contact" -> "👤 " + mediaName
                    else -> "[消息]"
                }
            }
            if (chatJid.isBlank() || id.isBlank() || (text.isBlank() && mediaKind.isBlank())) return
            // 应用内拉黑：来信直接丢弃（不入库、不通知、不进列表）
            if (!fromMe && !fromHistory && db.isBlocked(chatJid)) return

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
                // P8：群元数据事件（Go onGroupInfo 转发）——被踢时弹 dialog_kicked
                "groupInfo" -> {
                    if (o.optBoolean("kicked")) {
                        val group = o.optString("group")
                        main.post { onKickedFromGroup?.invoke(group) }
                    }
                }
            }
            main.post { onChatUpdated?.invoke() }
        } catch (_: Throwable) { }
    }

    /** P5：群公告（SetGroupTopic） */
    fun setGroupTopicAsync(groupJid: String, topic: String, callback: (String?) -> Unit) {
        Thread {
            try {
                client.setGroupTopic(groupJid, topic)
                main.post { callback(null) }
            } catch (e: Throwable) {
                main.post { callback(e.message ?: e.toString()) }
            }
        }.start()
    }

    /** P5：群邀请链接（GetGroupInviteLink；reset=true 重置） */
    fun groupInviteLinkAsync(groupJid: String, reset: Boolean, callback: (String?, String?) -> Unit) {
        Thread {
            try {
                val link = client.getGroupInviteLink(groupJid, reset)
                main.post { callback(link, null) }
            } catch (e: Throwable) {
                main.post { callback(null, e.message ?: e.toString()) }
            }
        }.start()
    }

    /** P6：AI 建议回复——把最后 N 条消息喂给已配置的 LLM，产 3 条候选 */
    fun aiSuggestAsync(chatJid: String, callback: (List<String>, String?) -> Unit) {
        Thread {
            try {
                val recent = db.messages(chatJid, 8).joinToString("\n") { m ->
                    (if (m.fromMe) "我" else "对方") + ": " + m.text
                }
                val customs = prefs.customs
                val prof = customs.firstOrNull { it.key.isNotBlank() && it.baseUrl.isNotBlank() }
                    ?: run { main.post { callback(emptyList(), "没有可用的 LLM：设置→翻译服务→自定义接口") }; return@Thread }
                val prompt = "你是外贸客服助手。根据以下 WhatsApp 对话，给用户 3 条简短的中文建议回复（每条一行，不要编号，不要解释）：\n\n$recent"
                val body = org.json.JSONObject().apply {
                    put("model", prof.model)
                    put("messages", org.json.JSONArray().put(org.json.JSONObject().apply {
                        put("role", "user"); put("content", prompt)
                    }))
                    put("temperature", 0.7)
                }.toString()
                val conn = java.net.URL(prof.baseUrl.trimEnd('/') + "/chat/completions")
                    .openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000; conn.readTimeout = 30000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer " + prof.key)
                conn.outputStream.use { it.write(body.toByteArray()) }
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val text = org.json.JSONObject(resp).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content")
                val suggestions = text.lines().map { it.trim().removePrefix("- ").removePrefix("· ") }
                    .filter { it.isNotBlank() && !it.startsWith("回复") }.take(3)
                conn.disconnect()
                main.post { callback(suggestions, null) }
            } catch (e: Throwable) {
                main.post { callback(emptyList(), e.message ?: e.toString()) }
            }
        }.start()
    }

    /** P8：被移出群回调（ChatListActivity/ChatActivity 弹 dialog_kicked） */
    var onKickedFromGroup: ((String) -> Unit)? = null

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

    /**
     * B6：用指定引擎重译一条已有收件消息（译成中文）。
     * 场景：“当前译文不对 / 这家接口挂了”，用户长按消息换一家重翻。
     */
    fun retranslateAsync(chatJid: String, id: String, text: String, engineId: String,
                         callback: (translated: String?, error: String?) -> Unit) {
        Thread {
            try {
                val res = Engine.translateText(
                    text = text, source = null,
                    target = "zh-Hans",
                    prefs = prefs, engineId = engineId)
                if (res.text.isBlank()) {
                    main.post { callback(null, "未返回译文") }
                    return@Thread
                }
                db.updateTranslation(chatJid, id, res.text, "")
                main.post { callback(res.text, null) }
            } catch (e: Throwable) {
                main.post { callback(null, e.message ?: e.toString()) }
            }
        }.start()
    }

    /**
     * 列出本账号已登录设备（只读）。返回 (设备数, 当前设备id)；失败时 count=-1 并带错误。
     * 注意：协议层只能读列表，无法注销任意设备（whatsmeow 无通用 IQ 发送口）。
     */
    /** 收侧音频回调（由 CallAudioBridge 挂上，写 AudioTrack） */
    var audioSink: ((ByteArray) -> Unit)? = null
    /** 视频帧回调（H.264 Annex-B，由 H264Decoder 解码上屏） */
    var videoSink: ((ByteArray) -> Unit)? = null

    fun attachVideoSink(sink: ((ByteArray) -> Unit)?) { videoSink = sink }

    /** 订阅对端视频流 */
    fun startVideoReceiveAsync(callback: (String?) -> Unit = {}) {
        Thread {
            try { client.startVideoReceive(); main.post { callback(null) } }
            catch (e: Throwable) { main.post { callback(e.message ?: e.toString()) } }
        }.start()
    }

    /** 发送一帧已编码的 H.264 Annex-B */
    fun sendVideoFrame(data: ByteArray) {
        try { client.sendVideoFrame(data) } catch (_: Throwable) { }
    }

    /** 升级为视频通话并允许发本地画面 */
    fun enableSendVideoAsync(callback: (String?) -> Unit = {}) {
        Thread {
            try { client.enableSendVideo(); main.post { callback(null) } }
            catch (e: Throwable) { main.post { callback(e.message ?: e.toString()) } }
        }.start()
    }

    fun setMuted(m: Boolean) { try { client.setMuted(m) } catch (_: Throwable) { } }
    fun setSpeakerOn(on: Boolean) { try { client.setSpeakerOn(on) } catch (_: Throwable) { } }

    fun attachAudioSink(sink: ((ByteArray) -> Unit)?) { audioSink = sink }

    /** 启动 Go 侧双向音频（接听/发起成功后调用） */
    fun startAudioEngine() {
        Thread {
            try { client.startAudio() } catch (_: Throwable) { }
        }.start()
    }

    fun stopAudioEngine() {
        Thread { try { client.stopAudio() } catch (_: Throwable) { } }.start()
    }

    /** 同步进度 JSON：{busy,messages,lastMs} */
    fun syncStats(): String = try { client.syncStats() } catch (_: Throwable) { "{}" }

    /** 请求 WhatsApp 同步整体历史（需原装 WhatsApp 在线后台运行） */
    fun requestFullHistorySyncAsync(count: Int, callback: (String?) -> Unit) {
        Thread {
            try { client.requestFullHistorySync(count); main.post { callback(null) } }
            catch (e: Throwable) { main.post { callback(e.message ?: e.toString()) } }
        }.start()
    }

    /** 退出 WhatsApp 账号（注销本设备，不可逆，需重新配对） */
    fun logoutWhatsAppAsync(callback: (String?) -> Unit) {
        Thread {
            try { client.logoutWhatsApp(); main.post { callback(null) } }
            catch (e: Throwable) { main.post { callback(e.message ?: e.toString()) } }
        }.start()
    }

    /** Go 侧通话统计 JSON（state/tx/rx 帧与字节数） */
    fun callStats(): String = try { client.callStats() } catch (_: Throwable) { "{}" }

    /** 喂麦克风 PCM（CallAudioBridge 采集线程调用，必须在后台线程） */
    fun feedMicPcm(data: ByteArray) {
        try { client.feedMicPcm(data) } catch (_: Throwable) { }
    }

    /** 来电回调：(fromJid, callID, isVideo)。meowcaller 支持接听/拒接/发起 */
    var onIncomingCall: ((String, String, Boolean) -> Unit)? = null
    var onCallTerminated: ((String) -> Unit)? = null
    /** 通话阶段：0 idle / 1 calling / 2 ringing / 3 connecting / 4 active / 5 ended / 6 waitingRoom */
    var onCallPhase: ((Int) -> Unit)? = null

    /** 接听当前来电 */
    fun answerCallAsync(callback: (String?) -> Unit) {
        Thread {
            try { client.answerCall(); main.post { callback(null) } }
            catch (e: Throwable) { main.post { callback(e.message ?: e.toString()) } }
        }.start()
    }

    /** 拒接当前来电 */
    fun rejectCallAsync(callback: (String?) -> Unit) {
        Thread {
            try { client.rejectCall("", ""); main.post { callback(null) } }
            catch (e: Throwable) { main.post { callback(e.message ?: e.toString()) } }
        }.start()
    }

    /** 挂断当前通话 */
    fun hangupCallAsync(callback: (String?) -> Unit) {
        Thread {
            try { client.hangupCall(); main.post { callback(null) } }
            catch (e: Throwable) { main.post { callback(e.message ?: e.toString()) } }
        }.start()
    }

    /** 发起通话，返回 callID */
    fun startCallAsync(targetJid: String, video: Boolean, callback: (String?, String?) -> Unit) {
        Thread {
            try {
                val id = client.startCall(targetJid, video)
                main.post { callback(id, null) }
            } catch (e: Throwable) {
                main.post { callback(null, e.message ?: e.toString()) }
            }
        }.start()
    }

    fun listDevicesAsync(callback: (Int, Int, String?) -> Unit) {
        Thread {
            try {
                val json = client.listMyDevices()
                val arr = org.json.JSONArray(json)
                var cur = 0
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optBoolean("current")) cur = o.optInt("device")
                }
                main.post { callback(arr.length(), cur, null) }
            } catch (e: Throwable) {
                main.post { callback(-1, 0, e.message ?: e.toString()) }
            }
        }.start()
    }

    /** 发送商品卡片（ProductMessage）。需 Business 账号的商品目录 productID。 */
    fun sendProductAsync(chatJid: String, productId: String, title: String, desc: String,
                         currency: String, price: Double, url: String,
                         onResult: (String?, String?) -> Unit) {
        Thread {
            try {
                val msgId = client.sendProduct(chatJid, productId, title, desc,
                    currency, price, url)
                val now = System.currentTimeMillis()
                val meta = org.json.JSONObject().apply {
                    put("productId", productId); put("title", title)
                    put("desc", desc); put("currency", currency)
                    put("price", price); put("url", url)
                }.toString()
                db.insertMessage(MessageRow(
                    id = msgId, chatJid = chatJid, senderJid = "", fromMe = true,
                    text = title, original = "", translated = "", translatedLang = "",
                    ts = now, mediaKind = "product", mediaMime = "",
                    mediaName = title, mediaSize = 0, mediaPath = "",
                    mediaThumb = "", mediaMeta = meta))
                db.upsertChat(chatJid, "", "[商品] $title", now, incrementUnread = false)
                main.post { onResult(msgId, null) }
            } catch (e: Throwable) {
                main.post { onResult(null, e.message ?: e.toString()) }
            }
        }.start()
    }

    /** 发送活动邀请（EventMessage）。成功后落库一条 media_kind="event" 的消息。 */
    fun sendEventAsync(chatJid: String, name: String, desc: String,
                       startMs: Long, endMs: Long, location: String, link: String,
                       onResult: (String?, String?) -> Unit) {
        Thread {
            try {
                val msgId = client.sendEvent(chatJid, name, desc, startMs, endMs, location, link)
                val now = System.currentTimeMillis()
                // 活动信息存 mediaMeta（JSON），气泡渲染时读
                val meta = org.json.JSONObject().apply {
                    put("name", name); put("desc", desc)
                    put("start", startMs); put("end", endMs)
                    put("location", location); put("link", link)
                }.toString()
                db.insertMessage(MessageRow(
                    id = msgId, chatJid = chatJid, senderJid = "", fromMe = true,
                    text = name, original = "", translated = "", translatedLang = "",
                    ts = now, mediaKind = "event", mediaMime = "",
                    mediaName = name, mediaSize = 0, mediaPath = "",
                    mediaThumb = "", mediaMeta = meta))
                db.upsertChat(chatJid, "", "[活动] $name", now, incrementUnread = false)
                main.post { onResult(msgId, null) }
            } catch (e: Throwable) {
                main.post { onResult(null, e.message ?: e.toString()) }
            }
        }.start()
    }

    /** 给消息贴表情（reaction="" 取消）。先本地写库保证 UI 立刻反映，再发协议。 */
    fun reactAsync(chatJid: String, msgId: String, senderJid: String, fromMe: Boolean,
                   reaction: String, callback: (String?) -> Unit) {
        db.setReaction(chatJid, msgId, reaction)
        Thread {
            try {
                client.sendReaction(chatJid, if (fromMe) "" else senderJid, msgId, reaction)
                main.post { callback(null) }
            } catch (e: Throwable) {
                main.post {
                    // 发送失败回滚本地状态，避免 UI 显示成功实际没发出去
                    db.setReaction(chatJid, msgId, "")
                    callback(e.message ?: e.toString())
                }
            }
        }.start()
    }

    /** 收件：对方语言 → 中文 */
    fun translateIncomingAsync(chatJid: String, id: String, text: String) {
        Thread {
            try {
                // B5：历史消息用专用引擎（空则跟随全局链）
                val res = Engine.translateIncoming(text, prefs, prefs.historyEngine.ifBlank { null })
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
                    // U1：会话级客户设置优先（在「客户语言」弹层锁定过就用它）
                    explicitTarget = prefs.chatSetting(chatJid).lang.ifBlank {
                        prefs.waTargetLang
                    }.ifBlank { null },
                    chatLanguage = chatLangMemory[chatJid],
                    engineId = prefs.chatSetting(chatJid).engine.ifBlank { null }
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
                    // U1：会话级客户设置优先（在「客户语言」弹层锁定过就用它）
                    explicitTarget = prefs.chatSetting(chatJid).lang.ifBlank {
                        prefs.waTargetLang
                    }.ifBlank { null },
                    chatLanguage = chatLangMemory[chatJid],
                    engineId = prefs.chatSetting(chatJid).engine.ifBlank { null }
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

    /** P4：视频发送（协议 SendVideoFromPath；whatsmeow MediaVideo 上传） */
    fun sendVideoAsync(jid: String, path: String, caption: String,
                       onResult: (String?, String?) -> Unit) {
        Thread {
            try {
                val msgId = client.sendVideoFromPath(jid, path, caption, 0)
                val now = System.currentTimeMillis()
                db.insertMessage(MessageRow(
                    id = msgId, chatJid = jid, senderJid = "", fromMe = true,
                    text = caption, original = "", translated = "", translatedLang = "",
                    ts = now, mediaKind = "video", mediaMime = "video/mp4",
                    mediaName = java.io.File(path).name,
                    mediaSize = java.io.File(path).length(), mediaPath = path,
                    mediaThumb = "", mediaMeta = "", reaction = ""))
                db.upsertChat(jid, "", "[视频]", now, incrementUnread = false)
                main.post { onResult(msgId, null) }
            } catch (e: Throwable) {
                main.post { onResult(null, e.message ?: e.toString()) }
            }
        }.start()
    }

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
                    fromMe = true, text = "", original = "",
                    translated = "", translatedLang = "", ts = now,
                    mediaKind = "poll", mediaMime = "", mediaName = question,
                    mediaMeta = "{\"kind\":\"poll\",\"waveform\":\"" +
                        optionsCsv.replace("\\", "\\\\").replace("\"", "\\\"")
                            .replace(",", "\\n") + "\"}"))
                db.upsertChat(chatJid, "", "📊 " + question, now, incrementUnread = false)  // 摘要仍显示问题
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
                          caption: String = "",
                          onResult: (String?, String?) -> Unit) {
        Thread {
            try {
                val msgId = client.sendDocumentFromPath(chatJid, path, fileName, caption)
                val now = System.currentTimeMillis()
                db.insertMessage(MessageRow(
                    id = msgId ?: "local-$now", chatJid = chatJid, senderJid = "",
                    fromMe = true, text = caption, original = "",
                    translated = "", translatedLang = "", ts = now,
                    mediaKind = "document", mediaMime = "",
                    mediaName = fileName, mediaSize = java.io.File(path).length(),
                    mediaPath = path))
                db.upsertChat(chatJid, "", caption.ifBlank { "[文件] $fileName" }, now, incrementUnread = false)
                main.post { onChatUpdated?.invoke() }
                main.post { onResult(msgId, null) }
            } catch (e: Throwable) {
                main.post { onResult(null, e.message ?: e.toString()) }
            }
        }.start()
    }
}
