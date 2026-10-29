package com.weifan.app

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Filter
import android.widget.Filterable
import android.widget.BaseAdapter
import android.widget.TextView
import com.weifan.app.core.Lang

/**
 * 可输入的语言框：既能下拉选，也能直接打字。
 *
 * 匹配规则：**名称或代码包含输入内容**（忽略大小写）——
 * 输入 "en" 命中「英语（en）」，输入 "英语" 也命中，输入 "jp" 命中「日语（jp）」。
 */
class LanguageField(
    context: Context,
    initialCode: String,
    private val onChanged: (String) -> Unit = {}
) {

    val view: AutoCompleteTextView = AutoCompleteTextView(context).apply {
        textSize = 13.5f
        setTextColor(Brand.palette(context).text)
        setHintTextColor(Brand.palette(context).text3)
        setSingleLine(true)
        background = LanguageAdapter.roundBg(context)
        setPadding(dp(context, 12), dp(context, 11), dp(context, 12), dp(context, 11))
        threshold = 1
    }

    private val adapter = LanguageAdapter(context)
    private val codes = Lang.all.keys.toList()

    /** 当前选中的语言代码 */
    var code: String = initialCode
        private set

    init {
        view.setAdapter(adapter)
        view.setText(display(initialCode), false)
        view.setOnItemClickListener { _, _, position, _ ->
            code = adapter.codeAt(position)
            onChanged(code)
        }
        // 失焦时把显示还原成当前语言的规范写法（打字没匹配上就不该留乱码）
        view.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val typed = view.text.toString().trim()
                val matched = codes.firstOrNull {
                    it.equals(typed, true) || Lang.name(it).equals(typed, true) ||
                        Lang.name(it).contains(typed, true) || it.contains(typed, true)
                }
                view.setText(display(matched ?: code), false)
            }
        }
        // 手动打字时也同步代码：能精确匹配就取匹配项，否则保持原值
        view.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val typed = s?.toString()?.trim().orEmpty()
                val matched = codes.firstOrNull {
                    it.equals(typed, true) || Lang.name(it).equals(typed, true) ||
                        Lang.name(it).contains(typed, true) || it.contains(typed, true)
                }
                if (matched != null && matched != code) {
                    code = matched
                    onChanged(code)
                }
            }
        })
    }

    /** 设置语言（不触发 onChanged 的递归） */
    fun setCode(newCode: String) {
        code = newCode
        view.setText(display(newCode), false)
        onChanged(newCode)
    }

    private fun display(code: String): String {
        if (code.isBlank()) return ""
        if (code == "auto") return "自动检测"
        val name = Lang.all[code]?.name ?: code
        return "$name（$code）"
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

/**
 * 语言下拉适配器：名称 + 代码，过滤时两边都匹配。
 */
class LanguageAdapter(context: Context) : BaseAdapter(), Filterable {

    private var items: MutableList<String> = displayAll()
    private var codes: List<String> = Lang.all.keys.toList()

    private val filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val query = constraint?.toString()?.trim()?.lowercase().orEmpty()
            val matched = if (query.isEmpty()) {
                codes.indices.toList()
            } else {
                codes.indices.filter { i ->
                    codes[i].lowercase().contains(query) ||
                        Lang.name(codes[i]).lowercase().contains(query)
                }
            }
            val filtered = matched.map { "${Lang.name(codes[it])}（${codes[it]}）" }
            return FilterResults().apply {
                values = filtered
                count = filtered.size
            }
        }

        override fun publishResults(constraint: CharSequence?, results: FilterResults) {
            @Suppress("UNCHECKED_CAST")
            val list = (results.values as? List<String>) ?: emptyList()
            items = list.toMutableList()
            if (results.count > 0) notifyDataSetChanged() else notifyDataSetInvalidated()
        }
    }

    private fun displayAll(): MutableList<String> =
        Lang.all.keys.map { "${Lang.name(it)}（$it）" }.toMutableList()

    /** 第 position 项对应的语言代码 */
    fun codeAt(position: Int): String = codes.getOrNull(position) ?: "en"

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView as? TextView ?: TextView(parent.context).apply {
            textSize = 14f
            setPadding(dp(parent.context, 16), dp(parent.context, 12), dp(parent.context, 16), dp(parent.context, 12))
        }
        view.text = items[position]
        return view
    }

    override fun getFilter(): Filter = filter

    companion object {
        fun roundBg(context: Context) = Ui.roundBg(
            context,
            Brand.palette(context).surface2,
            10,
            1,
            Brand.palette(context).line
        )

        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()
    }
}
