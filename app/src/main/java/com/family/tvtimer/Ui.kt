package com.family.tvtimer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** MainActivity 和 LockActivity 共用的界面组件 */
object Ui {
    val GREEN = Color.parseColor("#2E7D32")
    val DARK_GREEN = Color.parseColor("#1B5E20")

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun button(ctx: Context, s: String, weight: Boolean = false, onClick: () -> Unit) = Button(ctx).apply {
        text = s
        isAllCaps = false
        textSize = 16f
        setOnClickListener { onClick() }
        // 遥控器选中（focused）时整块变绿，电视上一眼能看出当前选的是哪个按钮
        fun shape(fill: Int, stroke: Int) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(ctx, 10).toFloat()
            setStroke(dp(ctx, 2), stroke)
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(GREEN, DARK_GREEN))
            addState(intArrayOf(android.R.attr.state_pressed), shape(Color.parseColor("#C8E6C9"), GREEN))
            addState(intArrayOf(), shape(Color.WHITE, Color.parseColor("#A5D6A7")))
        }
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(Color.WHITE, DARK_GREEN)
        ))
        setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
        layoutParams = (if (weight) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            .apply { setMargins(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6)) }
    }

    /**
     * 4 位 PIN 数字键盘。用遥控器方向键 + 确认键就能输入，不依赖输入法。
     * 输满 4 位时回调 onDone；回调返回 false 表示输错，键盘会清空。
     */
    fun pinPad(ctx: Context, hint: String, onDone: (String) -> Boolean): LinearLayout {
        val pad = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val display = TextView(ctx).apply {
            text = hint
            textSize = 26f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(DARK_GREEN)
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        }
        pad.addView(display)
        val entered = StringBuilder()
        fun refresh() {
            display.text = if (entered.isEmpty()) hint else "● ".repeat(entered.length).trim()
        }
        fun press(k: String) {
            when (k) {
                "⌫" -> if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1)
                "清空" -> entered.clear()
                else -> if (entered.length < 4) entered.append(k)
            }
            refresh()
            if (entered.length == 4) {
                val pin = entered.toString()
                entered.clear()
                if (!onDone(pin)) display.text = "密码不对，请重试"
            }
        }
        for (rowKeys in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("清空", "0", "⌫"))) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 360), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            for (k in rowKeys) row.addView(button(ctx, k, weight = true) { press(k) }.apply { textSize = 20f })
            pad.addView(row)
        }
        return pad
    }
}
