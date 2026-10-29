package com.weifan.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.weifan.app.core.Docx
import com.weifan.app.core.Engine
import com.weifan.app.core.Lang

/**
 * 文档翻译：选文件 → 逐段翻译 → 存回文件。
 *
 * 支持 **txt / md / csv / docx**：
 * - 文本类按空行切段；
 * - docx 是 zip + XML，用纯 JDK 解（见 core/Docx.kt），保留段落结构与数量，
 *   字体、表格、分栏不保留。
 *
 * ⚠️ PDF 暂不支持：Android 上抽 PDF 文字得把 pdf.js 塞进 WebView，还要处理 worker 的
 * 加载域名问题，不如留到后面做（扩展侧已支持 PDF）。
 */
class TranslateDocActivity : BaseActivity() {

    private lateinit var fileText: TextView
    private lateinit var targetSpinner: Spinner
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var preview: TextView
    private lateinit var startButton: Button
    private lateinit var saveButton: Button

    private var sourceUri: Uri? = null
    private var sourceName = ""
    private var isDocx = false
    /** 译文（纯文本） */
    private var translatedText = ""
    /** docx 的译文包，写回时用 */
    private var translatedDocx: ByteArray? = null

    private val p: Palette get() = palette
    private val ctx: Context get() = this

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = p.bg
        setContentView(buildUI())
    }

    private fun buildUI(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 14), Ui.dp(ctx, 16), Ui.dp(ctx, 28))
        }

        root.addView(TextView(this).apply {
            text = "微翻 · 文档翻译"
            textSize = 19f
            setTextColor(p.text)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "支持 txt / md / csv / docx。文件全程留在本机，不上传。"
            textSize = 12f
            setTextColor(p.text3)
            setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 2), Ui.dp(ctx, 4), Ui.dp(ctx, 12))
        })

        // 选文件
        root.addView(Ui.button(ctx, "选择文件", false) { pickFile() })
        fileText = TextView(this).apply {
            textSize = 13f
            setTextColor(p.text2)
            setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 10), Ui.dp(ctx, 4), 0)
        }
        root.addView(fileText)

        // 目标语言
        root.addView(Ui.label(ctx, "目标语言", p.text2, 12.5f, true))
        targetSpinner = Ui.spinner(ctx, Lang.all.keys.map { "${Lang.name(it)}（$it）" })
        val index = Lang.all.keys.toList().indexOf(prefs.defaultTarget)
        if (index >= 0) targetSpinner.setSelection(index)
        root.addView(Ui.withMargin(ctx, targetSpinner, 6, 0))

        startButton = Ui.button(ctx, "开始翻译", true) { translate() }
        startButton.isEnabled = false
        root.addView(Ui.withMargin(ctx, startButton, 12, 0))

        // 进度
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        progressBar = ProgressBar(this).apply {
            isIndeterminate = false
            max = 100
        }
        progressRow.addView(progressBar)
        progressText = TextView(this).apply {
            textSize = 11.5f
            setTextColor(p.text3)
            setPadding(Ui.dp(ctx, 10), 0, 0, 0)
        }
        progressRow.addView(progressText)
        root.addView(Ui.withMargin(ctx, progressRow, 10, 0))

        // 预览
        root.addView(Ui.label(ctx, "译文预览", p.text2, 12.5f, true))
        preview = Ui.resultBox(this)
        root.addView(Ui.withMargin(ctx, preview, 6, 0))

        saveButton = Ui.button(ctx, "保存译文", false) { saveResult() }
        saveButton.isEnabled = false
        root.addView(Ui.withMargin(ctx, saveButton, 10, 0))

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "text/plain",
                    "text/markdown",
                    "text/csv",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                )
            )
        }
        startActivityForResult(intent, REQUEST_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        if (requestCode == REQUEST_PICK) {
            sourceUri = uri
            sourceName = displayName(uri)
            isDocx = sourceName.endsWith(".docx", ignoreCase = true) ||
                contentResolver.getType(uri)?.contains("wordprocessingml") == true
            fileText.text = "已选：$sourceName${if (isDocx) "（Word）" else "（文本）"}"
            startButton.isEnabled = true
            saveButton.isEnabled = false
            translatedText = ""
            translatedDocx = null
            preview.text = ""
            return
        }

        if (requestCode == REQUEST_SAVE) {
            try {
                val payload = translatedDocx ?: translatedText.toByteArray(Charsets.UTF_8)
                contentResolver.openOutputStream(uri)?.use { it.write(payload) }
                    ?: throw IllegalStateException("写不了文件")
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(this, "保存失败：${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun displayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else uri.lastPathSegment ?: "文件"
            } ?: (uri.lastPathSegment ?: "文件")
        } catch (t: Throwable) {
            uri.lastPathSegment ?: "文件"
        }
    }

    private fun translate() {
        val uri = sourceUri ?: return
        val target = Lang.all.keys.toList().getOrNull(targetSpinner.selectedItemPosition) ?: "en"

        startButton.isEnabled = false
        progressRowVisible(true)
        preview.text = ""

        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("读不到文件")

                val paragraphs: List<String>
                val doc: Docx.Doc?

                if (isDocx) {
                    doc = Docx.read(bytes)
                    paragraphs = doc.paragraphs
                } else {
                    doc = null
                    val text = bytes.toString(Charsets.UTF_8)
                    paragraphs = text.split("\n\n").map { it.trim() }
                }

                val indices = paragraphs.indices.filter { paragraphs[it].isNotBlank() }
                val items = indices.map { paragraphs[it] }
                if (items.isEmpty()) throw IllegalStateException("这个文件里没找到可翻译的文字")

                val results = Engine.translateBatch(items, target, prefs) { done ->
                    runOnUiThread {
                        progressBar.progress = (done * 100 / items.size)
                        progressText.text = "已翻译 $done / ${items.size} 段"
                    }
                }

                // 译文按原段落索引放回
                val byIndex = HashMap<Int, String>()
                indices.forEachIndexed { i, index -> byIndex[index] = results[i] }

                // 按原段落顺序回填（空段落也占位），docx 写回才不会错位
                val aligned = paragraphs.mapIndexed { index, text ->
                    if (text.isBlank()) "" else (byIndex[index] ?: text)
                }
                val fullText = aligned.joinToString("\n\n")

                runOnUiThread {
                    translatedText = fullText
                    preview.text = fullText.take(2000) + if (fullText.length > 2000) "\n……" else ""
                    progressText.text = "翻译完成，共 ${items.size} 段"
                    progressBar.progress = 100
                    saveButton.isEnabled = true
                    startButton.isEnabled = true
                    if (doc != null) {
                        translatedDocx = Docx.write(doc, aligned)
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    progressText.text = "失败：${t.message ?: "未知错误"}"
                    progressText.setTextColor(p.err)
                    progressRowVisible(true)
                    startButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun progressRowVisible(visible: Boolean) {
        // 进度行是 progressBar 的父容器
        (progressBar.parent as? View)?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun saveResult() {
        if (translatedText.isBlank()) return
        val base = sourceName.substringBeforeLast('.', sourceName)
        val mime = if (isDocx) {
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        } else {
            "text/plain"
        }
        val name = if (isDocx) "$base.译文.docx" else "$base.译文.txt"

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(intent, REQUEST_SAVE)
    }

    companion object {
        private const val REQUEST_PICK = 101
        private const val REQUEST_SAVE = 102
    }
}
