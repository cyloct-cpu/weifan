package com.weifan.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
 * 共用纯代码视图构造器（不引 AndroidX/Compose 的前提下，样式只能这么搭）。
 *
 * 尺寸 / 字号 / 圆角 / 配色全部取自 WaSpec，与参考客户端 1:1 对齐。
 */
object Ui {

    /**
     * 子页面顶栏：‹ + 标题。配对页、文本页、文档页共用，保证这三页都有可点的返回。
     * 底色与设置页一致（绿），夜间用深绿。
     */
    fun topBar(
        context: Context,
        title: String,
        onBack: () -> Unit,
        night: Boolean = false,
        action: View? = null,
        titleSp: Float = 19f
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(if (night) WaSpec.GREEN_DARK else WaSpec.GREEN)
        setPadding(dp(context, 8), dp(context, 12), dp(context, 16), dp(context, 12))
        addView(TextView(context).apply {
            text = "‹"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(context, 10), 0, dp(context, 12), 0)
            contentDescription = "返回"
            isClickable = true
            setOnClickListener { onBack() }
        })
        addView(TextView(context).apply {
            text = title
            textSize = titleSp
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            if (action != null) {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        })
        if (action != null) addView(action)
    }

    /**
     * 会话列表顶栏（对齐 activity_chat_list）：
     * 左标题 20sp medium，右侧图标按钮 42dp，paddingStart 16dp / paddingEnd 8dp / 上下 10dp、4dp。
     */
    fun listHeader(
        context: Context,
        title: String,
        night: Boolean,
        actions: List<View>
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(WaSpec.surface(night))
        setPadding(dp(context, WaSpec.LIST_HEADER_PADDING_START_DP), dp(context, WaSpec.LIST_HEADER_PADDING_TOP_DP),
            dp(context, WaSpec.LIST_HEADER_PADDING_END_DP), dp(context, WaSpec.LIST_HEADER_PADDING_BOTTOM_DP))
        addView(TextView(context).apply {
            text = title
            textSize = WaSpec.LIST_TITLE_SP.toFloat()
            setTextColor(WaSpec.textPrimary(night))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for (v in actions) addView(v)
    }

    /**
     * 42dp 透明图标按钮（顶栏右侧 ⋮ / 会员位）。
     * 参考实现里这些按钮背景是透明的 colorAccent 占位，这里用可点区域 + 文字字形。
     */
    fun headerIconButton(
        context: Context,
        glyph: String,
        night: Boolean,
        glyphSize: Float = 22f,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = glyph
        textSize = glyphSize
        gravity = Gravity.CENTER
        setTextColor(WaSpec.textPrimary(night))
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            dp(context, WaSpec.LIST_ICON_BUTTON_DP), dp(context, WaSpec.LIST_ICON_BUTTON_DP))
    }

    /**
     * 搜索条（对齐 activity_chat_list）：外框高 40dp、左右外边距 12dp、顶部 10dp，
     * 内边距 14dp，图标 18dp，输入 15sp，图标与输入间距 8dp，胶囊圆角 = 高度一半。
     */
    fun searchBar(
        context: Context,
        hint: String,
        night: Boolean,
        onTextChanged: (String) -> Unit
    ): Pair<LinearLayout, EditText> {
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundBg(context, WaSpec.searchBg(night), WaSpec.SEARCH_BAR_HEIGHT_DP / 2)
            setPadding(dp(context, WaSpec.SEARCH_BAR_PADDING_H_DP), 0, dp(context, WaSpec.SEARCH_BAR_PADDING_H_DP), 0)
        }
        wrap.addView(TextView(context).apply {
            text = "🔍"
            textSize = 13f
            setTextColor(WaSpec.searchIcon(night))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(context, WaSpec.SEARCH_ICON_SIZE_DP), dp(context, WaSpec.SEARCH_ICON_SIZE_DP)))
        val input = EditText(context).apply {
            this.hint = hint
            textSize = WaSpec.SEARCH_INPUT_SP.toFloat()
            setTextColor(WaSpec.textPrimary(night))
            setHintTextColor(WaSpec.searchIcon(night))
            setBackgroundResource(0)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(0, 0, 0, 0)
        }
        wrap.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = dp(context, WaSpec.SEARCH_INPUT_GAP_DP)
        })
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { onTextChanged(s?.toString() ?: "") }
        })
        return wrap to input
    }

    /** 把搜索条包进带外边距的容器（12dp 左右 / 10dp 顶）*/
    fun searchBarRow(context: Context, bar: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(context, WaSpec.SEARCH_BAR_MARGIN_H_DP), dp(context, WaSpec.SEARCH_BAR_MARGIN_TOP_DP),
            dp(context, WaSpec.SEARCH_BAR_MARGIN_H_DP), 0)
        addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, WaSpec.SEARCH_BAR_HEIGHT_DP)))
    }

    /**
     * 筛选 chip：选中/未选双态，圆角 = 高度一半。
     * 参考实现里 chip 高由 padding 决定，文字 13sp。
     */
    /** 40dp 字母圆头像（跟进待办行用，与 ChatActivity.avatarView 同一套配色） */
    fun avatarCircle(context: Context, title: String, night: Boolean, sizeDp: Int): android.view.View {
        val colors = arrayOf("#25D366", "#34B7F1", "#FFB74D", "#9575CD", "#4DB6AC", "#F06292")
        val letter = title.firstOrNull()?.toString() ?: "#"
        val color = colors[(title.hashCode().let { if (it < 0) -it else it }) % colors.size]
        return android.widget.TextView(context).apply {
            text = letter
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundBg(context, android.graphics.Color.parseColor(color), sizeDp / 2)
        }
    }

    fun chip(context: Context, text: String, selected: Boolean, night: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(if (selected) WaSpec.CHIP_TEXT_SELECTED else WaSpec.textPrimary(night))
            typeface = Typeface.create(
                if (selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            background = roundBg(context, WaSpec.chipFill(selected, night), 18, 1,
                WaSpec.chipStroke(selected, night))
            setPadding(dp(context, 16), dp(context, 7), dp(context, 16), dp(context, 7))
            isClickable = true
            setOnClickListener { onClick() }
        }

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

    /** 只有底部圆角的气泡（会话页用）*/
    fun bubbleBg(context: Context, fill: Int, radius: Int, tailOnRight: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            val r = dp(context, radius).toFloat()
            val tail = dp(context, 2).toFloat()
            cornerRadii = if (tailOnRight)
                floatArrayOf(r, r, r, r, r, r, tail, tail)
            else
                floatArrayOf(r, r, tail, tail, r, r, r, r)
            setColor(fill)
        }

    /** 登录页渐变胶囊按钮（对齐 bg_login_button：#667EEA → #8B5CF6，圆角 28dp）*/
    fun authButton(context: Context, text: String, onClick: () -> Unit): TextView {
        val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(WaSpec.PRIMARY_COLOR, WaSpec.LOGIN_BTN_GRADIENT_END)).apply {
            cornerRadius = dp(context, WaSpec.AUTH_BUTTON_RADIUS_DP).toFloat()
        }
        return TextView(context).apply {
            this.text = text
            textSize = WaSpec.AUTH_BUTTON_TEXT_SP.toFloat()
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = g
            setPadding(dp(context, WaSpec.AUTH_BUTTON_PADDING_H_DP), dp(context, WaSpec.AUTH_BUTTON_PADDING_V_DP),
                dp(context, WaSpec.AUTH_BUTTON_PADDING_H_DP), dp(context, WaSpec.AUTH_BUTTON_PADDING_V_DP))
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /** 语言页底部实心按钮（圆角 26dp / 高 52dp / 17sp 粗体）*/
    fun solidButton(context: Context, text: String, fill: Int, textColor: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 17f
            setTextColor(textColor)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = roundBg(context, fill, WaSpec.LANG_BOTTOM_BUTTON_HEIGHT_DP / 2)
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 带图标 + 描边的输入行（登录页邮箱 / 密码）*/
    fun authInputRow(
        context: Context,
        icon: String,
        hint: String,
        night: Boolean,
        secret: Boolean = false
    ): Pair<LinearLayout, EditText> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundBg(context, WaSpec.authBg(night), WaSpec.AUTH_INPUT_RADIUS_DP, 1,
                if (night) WaSpec.LOGIN_INPUT_BORDER_NIGHT else WaSpec.LOGIN_INPUT_BORDER)
            setPadding(dp(context, WaSpec.AUTH_INPUT_PADDING_H_DP), dp(context, 4),
                dp(context, WaSpec.AUTH_INPUT_PADDING_H_DP), dp(context, 4))
        }
        row.addView(TextView(context).apply {
            text = icon
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(WaSpec.authMuted(night))
        }, LinearLayout.LayoutParams(dp(context, WaSpec.AUTH_INPUT_ICON_DP), dp(context, WaSpec.AUTH_INPUT_ICON_DP)))
        val input = EditText(context).apply {
            this.hint = hint
            textSize = WaSpec.AUTH_INPUT_TEXT_SP.toFloat()
            setTextColor(WaSpec.authText(night))
            setHintTextColor(WaSpec.authHint(night))
            setBackgroundResource(0)
            setSingleLine(true)
            inputType = if (secret)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setPadding(0, dp(context, 12), 0, dp(context, 12))
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(context, WaSpec.AUTH_INPUT_GAP_DP)
            marginEnd = dp(context, 8)
        })
        return row to input
    }

    /** 浅紫试用横幅（对齐 bg_login_trial_banner）*/
    fun authBanner(context: Context, icon: String, text: String, night: Boolean): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundBg(context, WaSpec.LOGIN_BANNER_BG, 12)
            setPadding(dp(context, WaSpec.AUTH_BANNER_PADDING_DP), dp(context, WaSpec.AUTH_BANNER_PADDING_DP),
                dp(context, WaSpec.AUTH_BANNER_PADDING_DP), dp(context, WaSpec.AUTH_BANNER_PADDING_DP))
            addView(TextView(context).apply {
                this.text = icon
                textSize = 14f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(context, WaSpec.AUTH_BANNER_ICON_DP), dp(context, WaSpec.AUTH_BANNER_ICON_DP)))
            addView(TextView(context).apply {
                this.text = text
                textSize = WaSpec.AUTH_BANNER_TEXT_SP.toFloat()
                setTextColor(WaSpec.primary(night))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 10)
                marginEnd = dp(context, 8)
            })
            addView(TextView(context).apply {
                this.text = "›"
                textSize = 16f
                setTextColor(WaSpec.primary(night))
                gravity = Gravity.CENTER
            })
        }

    /**
     * 会话页输入区（对齐 activity_chat 的 inputCapsule + sendContainer）：
     * 外层 padding 8dp；胶囊 background bg_input_capsule，paddingH 6dp，minHeight 48dp；
     * 内含 ＋(28dp) / 😊(28dp) / 输入框(16sp, minHeight 36dp, maxLines 4) / 译按钮(36dp)；
     * 右侧发送圆钮 48dp，左间距 4dp。
     */
    fun composer(
        context: Context,
        night: Boolean,
        onSend: (String) -> Unit
    ): Triple<LinearLayout, EditText, TextView> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val capsule = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundBg(context, WaSpec.surface(night), WaSpec.INPUT_CAPSULE_MIN_HEIGHT_DP / 2, 1,
                if (night) WaSpec.INPUT_BORDER_NIGHT else WaSpec.INPUT_BORDER)
            setPadding(dp(context, WaSpec.INPUT_CAPSULE_PADDING_DP), 0,
                dp(context, WaSpec.INPUT_CAPSULE_PADDING_DP), 0)
            minimumHeight = dp(context, WaSpec.INPUT_CAPSULE_MIN_HEIGHT_DP)
        }
        for (glyph in listOf("＋", "😊")) {
            capsule.addView(TextView(context).apply {
                text = glyph
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(WaSpec.textPrimary(night))
                setPadding(dp(context, WaSpec.ATTACH_BUTTON_PADDING_DP), 0,
                    dp(context, WaSpec.ATTACH_BUTTON_PADDING_DP), 0)
            }, LinearLayout.LayoutParams(
                dp(context, WaSpec.ATTACH_BUTTON_SIZE_DP), dp(context, WaSpec.ATTACH_BUTTON_SIZE_DP)))
        }
        val input = EditText(context).apply {
            hint = "输入消息"
            textSize = WaSpec.MESSAGE_INPUT_SP.toFloat()
            setTextColor(WaSpec.textPrimary(night))
            setHintTextColor(WaSpec.textSecondary(night))
            setBackgroundResource(0)
            maxLines = WaSpec.MESSAGE_INPUT_MAX_LINES
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, WaSpec.MESSAGE_INPUT_MIN_HEIGHT_DP)
            setPadding(dp(context, 4), dp(context, 6), dp(context, 4), dp(context, 6))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        capsule.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val translateBtn = TextView(context).apply {
            text = "译"
            textSize = WaSpec.TRANSLATE_BUTTON_TEXT_SP.toFloat()
            setTextColor(WaSpec.primary(night))
            gravity = Gravity.CENTER
            background = roundBg(context, WaSpec.GREEN_LIGHT, 10)
            setPadding(dp(context, WaSpec.TRANSLATE_BUTTON_PADDING_H_DP), 0,
                dp(context, WaSpec.TRANSLATE_BUTTON_PADDING_H_DP), 0)
        }
        capsule.addView(translateBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, WaSpec.TRANSLATE_BUTTON_HEIGHT_DP)).apply {
            marginStart = dp(context, 6)
        })
        row.addView(capsule, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val send = TextView(context).apply {
            text = "➤"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(WaSpec.inputBtnIcon(night))
            // 对齐参考 input_circle_black：白底 + 1dp 淡描边 + 深色图标（夜间反转）
            background = roundBg(context, WaSpec.inputBtnBg(night),
                WaSpec.PTT_SEND_BUTTON_SIZE_DP / 2, 1, WaSpec.inputBtnStroke(night))
            isClickable = true
            setOnClickListener { onSend(input.text.toString()) }
        }
        row.addView(send, LinearLayout.LayoutParams(
            dp(context, WaSpec.PTT_SEND_BUTTON_SIZE_DP), dp(context, WaSpec.PTT_SEND_BUTTON_SIZE_DP)).apply {
            marginStart = dp(context, WaSpec.PTT_SEND_MARGIN_DP)
            bottomMargin = dp(context, 2)
        })
        return Triple(row, input, send)
    }

    // ────────────────────── 弹层规格族（对齐参考 dialog_* 五族）──────────────────────
    // 参考的 40+ 个弹层其实只有 5 种规格，从 layout dump 归纳：
    //   ① 普通表单：padding 20/22/20/18 + 标题 17sp
    //   ② 大标题：  padding 20/22/20/20 + 标题 20sp
    //   ③ 选择型：  标题 20sp + 搜索框 15sp
    //   ④ 行式：    行高 40dp + 16sp
    //   ⑤ 确认型：  正文 15sp + padding 20
    // 统一走这几个工厂，避免每个弹层各写一套 padding/字号。

    // ────────────────── 真正的弹层基座（对齐参考视觉，不用系统按钮）──────────────────
    // 之前所有弹层用 AlertDialog.setPositiveButton，那是系统右下角文字按钮，
    // 跟参考的「大胶囊按钮」完全不同。这里改成：setView(整块自定义布局)，按钮自己画。

    /**
     * 居中标题弹层：标题 20sp BOLD 居中 + 内容 + 底部按钮行。
     * 对齐参考 dialog_new_chat / dialog_create_label 的视觉结构。
     * @param buttons 底部按钮：从右往左排，最后一个为主按钮（实心）
     */
    fun dialogShellCentered(context: Context, title: String, night: Boolean,
                            content: View, buttons: List<Pair<String, Boolean>>,
                            onButton: (Int) -> Unit): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 22), dp(context, 20), dp(context, 20))
            // 参考 bg_dialog_card：圆角 16dp 实心卡片（不再用系统弹层默认背景）
            background = roundBg(context,
                if (night) WaSpec.SURFACE_NIGHT else android.graphics.Color.WHITE,
                WaSpec.DIALOG_CARD_RADIUS_DP.toInt())
        }
        root.addView(TextView(context).apply {
            text = title
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(WaSpec.textPrimary(night))
        })
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 18)
        })
        // 按钮行：主按钮在右且更宽（参考 取消:开始聊天 ≈ 1:1.6）
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        for ((i, spec) in buttons.withIndex()) {
            val (label, primary) = spec
            val btn = pillButton(context, label, primary, night)
            btn.setOnClickListener { onButton(i) }
            row.addView(btn, LinearLayout.LayoutParams(
                0, dp(context, 52), if (primary) 1.6f else 1f).apply {
                if (i > 0) marginStart = dp(context, 10)
            })
        }
        root.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 20)
        })
        return root
    }

    /**
     * 显示一个「参考样式」弹层：内容已是 16dp 卡片，所以把系统弹层背景设透明，
     * 否则会出现系统白底 + 我们卡片两层边（这是之前视觉差的主因之一）。
     */
    fun showCardDialog(context: android.app.Activity, content: View,
                       widthRatio: Float = 0.92f): android.app.AlertDialog {
        val dlg = android.app.AlertDialog.Builder(context, R.style.WeifanDialog).setView(content).create()
        dlg.show()
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * widthRatio).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        return dlg
    }

    /** 胶囊按钮：高 52dp、圆角 26dp。主按钮实心主色白字，次按钮白底 1dp 描边 */
    fun pillButton(context: Context, label: String, primary: Boolean, night: Boolean): TextView =
        TextView(context).apply {
            text = label
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            if (primary) {
                setTextColor(android.graphics.Color.WHITE)
                background = roundBg(context, WaSpec.primary(night), 26)
            } else {
                setTextColor(WaSpec.textPrimary(night))
                background = roundBg(context,
                    if (night) WaSpec.SURFACE_NIGHT else android.graphics.Color.WHITE,
                    26, 1, WaSpec.divider(night))
            }
            isClickable = true
            minimumHeight = dp(context, 52)
        }

    /** 带左侧图标的输入框（对齐参考「📞 号码或@账号」那种样式） */
    fun iconInput(context: Context, icon: String, hint: String, night: Boolean,
                  inputType: Int = android.text.InputType.TYPE_CLASS_TEXT): EditText {
        val wrap = android.widget.FrameLayout(context).apply {
            background = roundBg(context,
                if (night) WaSpec.SEARCH_BG_NIGHT else WaSpec.GRAY6, 12)
            minimumHeight = dp(context, 50)
        }
        wrap.addView(TextView(context).apply {
            text = icon
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setTextColor(WaSpec.textSecondary(night))
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(context, 44), dp(context, 44)).apply {
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                marginStart = dp(context, 6)
            }
        })
        val et = EditText(context).apply {
            this.hint = hint
            textSize = 15f
            setTextColor(WaSpec.textPrimary(night))
            setHintTextColor(WaSpec.textSecondary(night))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(0, 0, dp(context, 12), 0)
            maxLines = 1
            this.inputType = inputType
            tag = "iconInputEdit"
        }
        wrap.addView(et, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 50)).apply {
            marginStart = dp(context, 48)
        })
        return et
    }

    /** ① 普通表单弹层：padding 20/22/20/18 + 标题 17sp */
    fun formDialogShell(context: Context, title: String, night: Boolean,
                        content: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 20), dp(context, 22), dp(context, 20), dp(context, 18))
        addView(TextView(context).apply {
            text = title
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(WaSpec.textPrimary(night))
        })
        addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 12)
        })
    }

    /** ② 大标题弹层：padding 20/22/20/20 + 标题 20sp */
    fun bigTitleDialogShell(context: Context, title: String, night: Boolean,
                            content: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 20), dp(context, 22), dp(context, 20), dp(context, 20))
        addView(TextView(context).apply {
            text = title
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(WaSpec.textPrimary(night))
        })
        addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 14)
        })
    }

    /** ③ 选择型弹层：标题 20sp + 搜索框 15sp（转发/新建会话/选标签都用它） */
    fun pickerDialogShell(context: Context, title: String, hint: String, night: Boolean,
                          onQuery: (String) -> Unit): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 22), dp(context, 20), dp(context, 8))
        }
        box.addView(TextView(context).apply {
            text = title
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(WaSpec.textPrimary(night))
        })
        box.addView(EditText(context).apply {
            this.hint = hint
            textSize = 15f
            setTextColor(WaSpec.textPrimary(night))
            setHintTextColor(WaSpec.textSecondary(night))
            setBackground(roundBg(context,
                if (night) WaSpec.SEARCH_BG_NIGHT else WaSpec.GRAY6, 10))
            setPadding(dp(context, 12), dp(context, 9), dp(context, 12), dp(context, 9))
            maxLines = 1
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    onQuery(s?.toString() ?: "")
                }
            })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 12)
        })
        return box
    }

    /** ④ 行式弹层的一行：行高 40dp + 16sp（poll 选项/联系人选择/商品/活动都用） */
    fun dialogRow(context: Context, text: String, night: Boolean,
                  hint: String? = null, onClick: (() -> Unit)? = null): TextView =
        TextView(context).apply {
            this.text = text
            this.hint = hint
            textSize = 16f
            setTextColor(WaSpec.textPrimary(night))
            setHintTextColor(WaSpec.textSecondary(night))
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = roundBg(context,
                if (night) WaSpec.SEARCH_BG_NIGHT else WaSpec.GRAY6, 10)
            setPadding(dp(context, 12), 0, dp(context, 12), 0)
            minHeight = dp(context, 40)
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
        }

    /** ⑤ 确认弹层正文：15sp + 可带明细 */
    fun confirmBody(context: Context, text: String, detail: String?, night: Boolean): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 4))
            addView(TextView(context).apply {
                this.text = text
                textSize = 15f
                setTextColor(WaSpec.textPrimary(night))
            })
            if (!detail.isNullOrBlank()) {
                addView(TextView(context).apply {
                    this.text = detail
                    textSize = 13f
                    setTextColor(WaSpec.textSecondary(night))
                    setPadding(0, dp(context, 6), 0, 0)
                })
            }
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
