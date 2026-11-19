package com.family.tvtimer

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 「时间到了」全屏提示：倒计时结束后电视待机。
 * 家长输入 4 位 PIN 可暂停管控 1 小时。守护服务把 lockDeadline 清零时自动关闭。
 */
class LockActivity : Activity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var countdown: TextView
    private lateinit var quizBtn: android.widget.Button

    private val ticker = object : Runnable {
        override fun run() {
            val deadline = prefs.lockDeadline
            if (deadline == 0L && !prefs.hardLock) {
                finish()
                return
            }
            val left = ((deadline - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            countdown.text = if (prefs.hardLock) "请关掉电视休息一下" else "$left 秒后电视将关闭"
            quizBtn.visibility = if (prefs.quizRewardMin() > 0 && prefs.isInAllowedWindow() && prefs.remainingSec <= 0) android.view.View.VISIBLE else android.view.View.GONE
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 电视是横屏：左边文字，右边密码键盘
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#102A12"))
            setPadding(Ui.dp(this@LockActivity, 24), Ui.dp(this@LockActivity, 24),
                Ui.dp(this@LockActivity, 24), Ui.dp(this@LockActivity, 24))
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        root.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fun label(s: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
            text = s
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(color)
            setPadding(0, Ui.dp(this@LockActivity, 4), 0, Ui.dp(this@LockActivity, 4))
        }
        info.addView(label("⏰ 看电视的时间到了", 40f))
        info.addView(label(prefs.lockReason, 22f, Color.parseColor("#C8E6C9")))
        countdown = label("", 28f, Color.parseColor("#FFD54F"))
        info.addView(countdown)
        info.addView(label("休息一下眼睛吧！\n家长输入密码可以多看 1 小时", 18f, Color.parseColor("#A5D6A7")))
        // 小孩改主意了：回去做题（做题加时还没到上限时才显示）
        quizBtn = Ui.button(this, "📝 去做题加时") {
            QuizActivity.declined = false
            prefs.hardLock = false
            prefs.lockDeadline = 0L
            startActivity(android.content.Intent(this, QuizActivity::class.java))
            GuardService.poke()
            finish()
        }.apply { textSize = 18f }
        info.addView(quizBtn)

        val padCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#F5F7F5"))
            setPadding(Ui.dp(this@LockActivity, 16), Ui.dp(this@LockActivity, 8),
                Ui.dp(this@LockActivity, 16), Ui.dp(this@LockActivity, 8))
        }
        padCard.addView(Ui.pinPad(this, "家长密码") { pin ->
            val ok = pin == prefs.pin
            if (ok) {
                prefs.pauseUntil = System.currentTimeMillis() + 60 * 60_000
                prefs.lockDeadline = 0L
                prefs.hardLock = false
                prefs.logEvent("家长在锁屏输入密码，暂停管控 1 小时")
                finish()
            }
            ok
        })
        root.addView(padCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = Ui.dp(this@LockActivity, 24) })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        visible = false
        handler.removeCallbacks(ticker)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 返回键不能关掉提示
    }

    companion object {
        @Volatile
        var visible = false
    }
}
