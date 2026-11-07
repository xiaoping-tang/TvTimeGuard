package com.family.tvtimer

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 「做题加时」全屏做题界面（新加坡 P5 英文应用题）。两种打开方式：
 *  - 今天的时间用完了（包括开机时已经用完）：守护服务自动打开，代替关机倒计时
 *  - 家长在手机上按「做题加时」
 * 答对 1 题：加 10 分钟（每天有上限），界面关闭，去看电视；10 分钟用完会再回到这里。
 * 答错：显示正确答案和解题步骤，然后出下一题。
 * 按返回键或「Give up」= 放弃：时间已用完时走原来的倒计时关机（家长可在那里输密码）。
 * 按主页键溜走会被守护服务拉回来。做题期间不计观看时间。
 */
class QuizActivity : Activity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var headerView: TextView
    private lateinit var scoreView: TextView
    private lateinit var topicView: TextView
    private lateinit var questionView: TextView
    private lateinit var inputView: TextView
    private lateinit var feedbackView: TextView
    private lateinit var submitBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var exitBtn: Button
    private lateinit var firstKey: Button
    private val keys = mutableListOf<Button>()

    private var current: Question? = null
    private val input = StringBuilder()
    private var answered = false
    private var wrong = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dp = { v: Int -> Ui.dp(this, v) }

        // 电视横屏：左边题目（可滚动），右边数字键盘
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0D2847"))
            setPadding(dp(28), dp(16), dp(28), dp(16))
        }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scroll = ScrollView(this).apply {
            isFocusable = false
            isFillViewport = true
            addView(left)
        }
        root.addView(scroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        fun label(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
            setLineSpacing(0f, 1.15f)
        }
        headerView = label(24f, Color.WHITE, bold = true)
        left.addView(headerView)
        scoreView = label(14f, Color.parseColor("#90CAF9"))
        left.addView(scoreView)
        topicView = label(15f, Color.parseColor("#FFD54F"))
        left.addView(topicView)
        questionView = label(22f, Color.WHITE)
        left.addView(questionView)
        inputView = label(26f, Color.parseColor("#FFF59D")).apply { typeface = Typeface.MONOSPACE }
        left.addView(inputView)
        feedbackView = label(17f, Color.WHITE)
        left.addView(feedbackView)

        // 右边：键盘卡片
        val padCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5F7F5"))
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val keyRows = listOf(
            listOf("7", "8", "9"),
            listOf("4", "5", "6"),
            listOf("1", "2", "3"),
            listOf("0", ".", "/"),
            listOf("⌫", "Clear"),
        )
        for (rowKeys in keyRows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            for (k in rowKeys) {
                val b = Ui.button(this, k, weight = true) { press(k) }.apply {
                    textSize = 20f
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                if (k == "1") firstKey = b
                keys.add(b)
                row.addView(b)
            }
            padCard.addView(row)
        }
        submitBtn = Ui.button(this, "✔ Submit") { submit() }.apply { textSize = 18f }
        padCard.addView(submitBtn)
        nextBtn = Ui.button(this, "Next question ▶") { nextQuestion() }.apply {
            textSize = 18f
            visibility = View.GONE
        }
        padCard.addView(nextBtn)
        exitBtn = Ui.button(this, "") { giveUp() }.apply { textSize = 14f }
        padCard.addView(exitBtn)

        root.addView(padCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(20) })
        setContentView(root)

        nextQuestion()
    }

    private fun timeUp() = prefs.remainingSec <= 0

    private fun refreshHeader() {
        val reward = prefs.quizRewardMin()
        headerView.text = when {
            reward <= 0 -> "📝 Maths practice"
            timeUp() -> "⏰ TV time is up! Solve 1 problem correctly to watch $reward more minutes."
            else -> "📝 Solve 1 problem correctly to get $reward more minutes of TV."
        }
        scoreView.text = buildString {
            append("Quiz bonus today: ${prefs.quizBonusMin} / ${prefs.quizCapMin} min")
            if (wrong > 0) append(" · Wrong so far: $wrong")
            if (reward <= 0) append("\nToday's quiz bonus limit is reached. You can still practise.")
        }
        exitBtn.text = if (timeUp()) "Give up (TV will turn off)" else "Exit"
    }

    private fun nextQuestion() {
        handler.removeCallbacksAndMessages(null)
        val q = Quiz.next()
        current = q
        answered = false
        input.clear()
        topicView.text = "Primary 5 · ${q.topic}"
        questionView.text = q.text
        feedbackView.text = "Type numbers only, no units. Use / for fractions, e.g. 3/4."
        feedbackView.setTextColor(Color.parseColor("#B0BEC5"))
        refreshInput()
        refreshHeader()
        keys.forEach { it.isEnabled = true }
        submitBtn.visibility = View.VISIBLE
        submitBtn.isEnabled = true
        nextBtn.visibility = View.GONE
        firstKey.requestFocus()
    }

    private fun press(k: String) {
        if (answered) return
        when (k) {
            "⌫" -> if (input.isNotEmpty()) input.deleteCharAt(input.length - 1)
            "Clear" -> input.clear()
            else -> if (input.length < 12) input.append(k)
        }
        feedbackView.text = ""
        refreshInput()
    }

    private fun refreshInput() {
        inputView.text = "Answer: " + if (input.isEmpty()) "_" else input.toString()
    }

    private fun submit() {
        val q = current ?: return
        if (answered) return
        val ans = Frac.parse(input.toString())
        if (ans == null) {
            feedbackView.setTextColor(Color.parseColor("#FFAB91"))
            feedbackView.text = if (input.isEmpty()) "Please type your answer first."
            else "That doesn't look like a number. Check it again (use / for fractions, e.g. 3/4)."
            return
        }
        answered = true
        keys.forEach { it.isEnabled = false }
        submitBtn.isEnabled = false
        if (ans == q.answer) {
            val add = prefs.addQuizBonus()
            feedbackView.setTextColor(Color.parseColor("#A5D6A7"))
            feedbackView.text = if (add > 0) "✅ Correct! Well done! Enjoy $add minutes of TV."
            else "✅ Correct! Well done! (Today's quiz bonus limit is reached.)"
            refreshHeader()
            GuardService.poke()
            // 一次只给 10 分钟：答对就关掉做题界面，去看电视
            handler.postDelayed({ finish() }, 3000)
        } else {
            wrong++
            feedbackView.setTextColor(Color.parseColor("#FFAB91"))
            feedbackView.text = "❌ Not quite. The correct answer is ${q.answerText}.\n\nWorking:\n${q.explain}"
            refreshHeader()
            submitBtn.visibility = View.GONE
            nextBtn.visibility = View.VISIBLE
            nextBtn.requestFocus()
            // 看完解题步骤按「Next question」继续，60 秒后也会自动继续
            handler.postDelayed({ nextQuestion() }, 60_000)
        }
    }

    /** 放弃做题：时间已用完时，守护服务会改走倒计时关机 */
    private fun giveUp() {
        declined = true
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        giveUp()
    }

    /** 遥控器或键盘上如果有数字键，也可以直接按 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val k = when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> (keyCode - KeyEvent.KEYCODE_0).toString()
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> (keyCode - KeyEvent.KEYCODE_NUMPAD_0).toString()
            KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_NUMPAD_DOT -> "."
            KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_NUMPAD_DIVIDE -> "/"
            KeyEvent.KEYCODE_DEL -> "⌫"
            else -> null
        }
        if (k != null) {
            press(k)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        launchGraceUntil = 0L
        refreshHeader()
    }

    override fun onPause() {
        super.onPause()
        visible = false
        // 离开做题界面后马上让守护检查一次：按主页键溜走会被拉回来，放弃则开始倒计时
        GuardService.poke()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        @Volatile
        var visible = false

        /** 守护服务刚发出「打开做题界面」后的几秒内，也当作在做题，免得被「时间到了」抢先盖住 */
        @Volatile
        var launchGraceUntil = 0L

        /** 小孩主动放弃了做题；电视待机或时间又够了时清掉 */
        @Volatile
        var declined = false

        fun isActive() = visible || System.currentTimeMillis() < launchGraceUntil
    }
}
