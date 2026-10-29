package com.weifan.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/**
 * 几个共用的纯代码视图构造器（不引 AndroidX/Compose 的前提下，样式只能这么搭）。
 */
object Ui {

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun roundBg(
        context: Context,
        fill: Int,
        radius: Int,
        stroke: Int = 0,
        strokeColor: Int = Color.TRANSPARENT
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(context, radius).toFloat()
        setColor(fill)
        if (stroke > 0) setStroke(dp(context, stroke), strokeColor)
    }

    fun withMargin(context: Context, view: View, top: Int, bottom: Int = 0): View {
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(context, top)
        lp.bottomMargin = dp(context, bottom)
        view.layoutParams = lp
        return view
    }

    fun label(context: Context, text: String, color: Int, size: Float = 12.5f, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun input(
        context: Context,
        hint: String,
        secret: Boolean = false,
        lines: Int = 1
    ): EditText = EditText(context).apply {
        this.hint = hint
        textSize = 13.5f
        setTextColor(Brand.palette(context).text)
        setHintTextColor(Brand.palette(context).text3)
        if (lines > 1) {
            setSingleLine(false)
            minLines = lines
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        } else {
            setSingleLine(true)
        }
        if (secret) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        background = roundBg(context, Brand.palette(context).surface2, 10, 1, Brand.palette(context).line)
        setPadding(dp(context, 12), dp(context, 11), dp(context, 12), dp(context, 11))
    }

    fun spinner(context: Context, items: List<String>): Spinner = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
        background = roundBg(context, Brand.palette(context).surface2, 10, 1, Brand.palette(context).line)
        setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6))
    }

    fun button(
        context: Context,
        text: String,
        primary: Boolean = true,
        onClick: () -> Unit
    ): Button {
        val palette = Brand.palette(context)
        return Button(context).apply {
            this.text = text
            textSize = 13.5f
            isAllCaps = false
            if (primary) {
                setTextColor(palette.ink)
                setTypeface(typeface, Typeface.BOLD)
                background = roundBg(context, palette.warm, 10)
            } else {
                setTextColor(palette.text2)
                background = roundBg(context, palette.surface, 10, 1, palette.line)
            }
            setPadding(dp(context, 16), dp(context, 6), dp(context, 16), dp(context, 6))
            setOnClickListener { onClick() }
        }
    }

    fun resultBox(context: Context): TextView = TextView(context).apply {
        textSize = 13.5f
        setTextColor(Brand.palette(context).text)
        setLineSpacing(dp(context, 4).toFloat(), 1f)
        setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
        background = roundBg(context, Brand.palette(context).surface2, 10, 1, Brand.palette(context).line)
        setTextIsSelectable(true)
        minLines = 4
    }
}
