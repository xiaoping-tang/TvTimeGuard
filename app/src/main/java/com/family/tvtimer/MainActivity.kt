package com.family.tvtimer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.time.LocalDate
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var refresher: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        render()
    }

    override fun onPause() {
        super.onPause()
        refresher?.let { handler.removeCallbacks(it) }
    }

    override fun onResume() {
        super.onResume()
        refresher?.let { handler.post(it) }
    }

    private fun render() {
        refresher?.let { handler.removeCallbacks(it) }
        refresher = null
        when (prefs.role) {
            "tv" -> renderTv()
            "phone" -> renderPhone()
            else -> renderChooseRole()
        }
    }

    // ---------------- 选择角色 ----------------

    private fun renderChooseRole() {
        val root = page()
        root.addView(title("电视时间管家"))
        root.addView(text("同一个 App 装在电视和家长手机上。请选择这台设备的用途："))
        root.addView(button("📺 这台是电视（被管控）") {
            prefs.role = "tv"
            if (prefs.pairCode.isBlank()) prefs.pairCode = Prefs.newPairCode()
            render()
        })
        root.addView(button("📱 这台是家长手机（遥控）") {
            prefs.role = "phone"
            render()
        })
    }

    // ---------------- 电视端 ----------------

    /** 电视端第一次使用：先设置 4 位家长 PIN */
    private fun renderSetPin() {
        val root = page()
        root.addView(title("电视时间管家 · 设置家长密码"))
        root.addView(text("请先设置 4 位家长密码。以后停止守护、查看配对码，以及「时间到了」时临时解锁，都要用这个密码。请不要让小孩看到。"))
        var first = ""
        val hint = text("请输入新密码")
        hint.textSize = 18f
        root.addView(hint)
        root.addView(Ui.pinPad(this, "_ _ _ _") { pin ->
            if (first.isEmpty()) {
                first = pin
                hint.text = "请再输入一次确认"
                true
            } else if (pin == first) {
                prefs.pin = pin
                toast("✅ 家长密码已设置")
                render()
                true
            } else {
                first = ""
                hint.text = "两次不一样，请重新输入新密码"
                false
            }
        })
    }

    /** 输对家长 PIN 才执行 action */
    private fun withPin(action: () -> Unit) {
        lateinit var dialog: AlertDialog
        val pad = Ui.pinPad(this, "请输入家长密码") { pin ->
            val ok = pin == prefs.pin
            if (ok) {
                dialog.dismiss()
                action()
            }
            ok
        }
        dialog = AlertDialog.Builder(this).setView(pad).setNegativeButton("取消", null).create()
        dialog.show()
    }

    private fun renderTv() {
        if (prefs.pin.isBlank()) {
            renderSetPin()
            return
        }
        val root = page()
        root.addView(title("电视时间管家 · 电视端"))

        val status = text("")
        status.textSize = 20f
        root.addView(card(status))

        root.addView(section("第 1 步：授权本机 ADB"))
        root.addView(text("需先在「设置 → 系统 → 关于」里，对「Android TV OS 版本号（Build）」连按 7 下打开开发者选项，再到「设置 → 系统 → 开发者选项」里打开「USB 调试」。点下面按钮后电视会弹窗，勾选「始终允许」再点「允许」。"))
        root.addView(button("授权 / 测试 ADB") {
            toast("正在连接，请留意电视弹窗…")
            thread {
                val r = runCatching { TvController(this).setup() }
                runOnUiThread {
                    r.onSuccess { toast("✅ ADB 正常：$it") }
                        .onFailure { toast("❌ ADB 失败：${it.message}\n请确认已打开 USB 调试") }
                }
            }
        })

        root.addView(section("第 2 步：启动守护"))
        root.addView(text("在手机配对之前，守护只计时、不会关电视。"))
        val guardBtn = button("") {
            if (prefs.guardRunning) withPin {
                GuardService.stop(this)
                handler.postDelayed({ render() }, 300)
            } else {
                GuardService.start(this)
                handler.postDelayed({ render() }, 300)
            }
        }
        guardBtn.text = if (prefs.guardRunning) "停止守护（需密码）" else "▶ 启动守护"
        root.addView(guardBtn)

        root.addView(section("第 3 步：在家长手机上输入配对码"))
        val codeView = text("配对码已隐藏")
        codeView.textSize = 28f
        codeView.typeface = Typeface.MONOSPACE
        root.addView(codeView)
        root.addView(button("显示 / 隐藏配对码（需密码）") {
            if (codeView.text.startsWith("配对码已隐藏")) withPin { codeView.text = prefs.pairCode }
            else codeView.text = "配对码已隐藏"
        })

        root.addView(section("最近记录（出问题时看这里）"))
        val logView = text("")
        logView.textSize = 14f
        root.addView(logView)

        root.addView(section("其他"))
        root.addView(button("更换配对码（需密码，旧手机将失效）") {
            withPin {
                prefs.pairCode = Prefs.newPairCode()
                prefs.lastCmdId = ""
                prefs.paired = false
                render()
            }
        })
        root.addView(button("修改家长密码（需旧密码）") {
            withPin {
                prefs.pin = ""
                render()
            }
        })
        root.addView(button("更改设备用途（需密码）") {
            withPin {
                GuardService.stop(this)
                prefs.role = ""
                prefs.paired = false
                render()
            }
        })

        refresher = object : Runnable {
            override fun run() {
                status.text = buildString {
                    append(if (prefs.guardRunning) "🟢 守护运行中" else "🔴 守护未运行")
                    append(if (prefs.paired) " · 已配对手机\n" else " · 未配对手机（只计时不关机）\n")
                    append(prefs.lastStatus)
                    append("\n每日 ${prefs.dailyLimitMin} 分钟（今日奖励 +${prefs.bonusMin}，其中做题 +${prefs.quizBonusMin}）")
                    append("\n允许时段 ${prefs.windowText()}")
                    if (prefs.isPaused()) append("\n⏸ 管控暂停中")
                    append(if (android.provider.Settings.canDrawOverlays(this@MainActivity))
                        "\n✅ 已有「显示在其他应用上层」权限" else "\n⚠ 还没有「显示在其他应用上层」权限（点「授权 / 测试 ADB」获取）")
                }
                logView.text = prefs.eventLog.lines().take(15).joinToString("\n").ifBlank { "（暂无）" }
                handler.postDelayed(this, 2000)
            }
        }.also { handler.post(it) }
    }

    // ---------------- 家长手机端 ----------------

    private fun renderPhone() {
        val root = page()
        root.addView(title("电视时间管家 · 家长端"))

        val status = text("正在获取电视状态…")
        status.textSize = 12f // 原来 18，按家长要求缩小到 2/3
        root.addView(card(status))
        root.addView(button("🔄 刷新状态") { fetchStatus(status) })

        root.addView(section("快捷操作"))
        val row1 = row()
        row1.addView(button("+15 分钟", weight = true) { sendBonus(15) })
        row1.addView(button("+30 分钟", weight = true) { sendBonus(30) })
        row1.addView(button("+60 分钟", weight = true) { sendBonus(60) })
        root.addView(row1)
        root.addView(button("📝 做题加时（电视出题，答对 +${Prefs.QUIZ_REWARD_MIN} 分钟）") {
            send(JSONObject().put("t", "quiz"), "已发送，电视 30 秒内出题")
        })
        root.addView(button("⛔ 立即关电视") {
            confirm("现在就关掉电视？") { send(JSONObject().put("t", "off"), "已发送关机指令") }
        })
        val row2 = row()
        row2.addView(button("暂停管控 1 小时", weight = true) {
            send(JSONObject().put("t", "pause").put("until", System.currentTimeMillis() + 3600_000), "已暂停 1 小时")
        })
        row2.addView(button("恢复管控", weight = true) {
            send(JSONObject().put("t", "pause").put("until", 0), "已恢复管控")
        })
        root.addView(row2)
        root.addView(button("今日时间清零") {
            confirm("把今天已看的时间和奖励都清零？") {
                send(JSONObject().put("t", "reset").put("date", LocalDate.now().toString()), "已清零")
            }
        })

        root.addView(section("规则设置"))
        root.addView(text("每天最多看（分钟）"))
        val limit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.dailyLimitMin.toString())
        }
        root.addView(limit)

        root.addView(text("做题加时每天最多加（分钟）"))
        val quizCap = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.quizCapMin.toString())
        }
        root.addView(quizCap)

        root.addView(text("允许看电视的时段"))
        var start = prefs.allowStartMin
        var end = prefs.allowEndMin
        val timeRow = row()
        val startBtn = button("从 ${Prefs.fmtHm(start)}", weight = true) {}
        val endBtn = button("到 ${Prefs.fmtHm(end)}", weight = true) {}
        startBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> start = h * 60 + m; startBtn.text = "从 ${Prefs.fmtHm(start)}" },
                start / 60, start % 60, true).show()
        }
        endBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> end = h * 60 + m; endBtn.text = "到 ${Prefs.fmtHm(end)}" },
                end / 60, end % 60, true).show()
        }
        timeRow.addView(startBtn)
        timeRow.addView(endBtn)
        root.addView(timeRow)

        val enforce = Switch(this).apply {
            text = "超时自动关电视（关掉则只记录时间）"
            isChecked = prefs.enforce
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(enforce)

        root.addView(button("💾 保存并发送到电视") {
            val l = limit.text.toString().toIntOrNull()
            if (l == null || l < 0 || l > 1440) {
                toast("每日分钟数请填 0–1440")
                return@button
            }
            val qc = quizCap.text.toString().toIntOrNull()
            if (qc == null || qc < 0 || qc > 600) {
                toast("做题加时上限请填 0–600")
                return@button
            }
            prefs.quizCapMin = qc
            prefs.dailyLimitMin = l
            prefs.allowStartMin = start
            prefs.allowEndMin = end
            prefs.enforce = enforce.isChecked
            send(prefs.settingsJson(), "设置已发送，电视 30 秒内生效")
        })

        root.addView(section("配对"))
        root.addView(text("在电视端 App 里点「显示配对码」，把配对码填在这里："))
        val code = EditText(this).apply {
            setText(prefs.pairCode)
            hint = "电视上显示的配对码"
            typeface = Typeface.MONOSPACE
        }
        root.addView(code)
        root.addView(button("保存配对码") {
            prefs.pairCode = code.text.toString()
            // 电视收到第一份设置后才开始真正管控
            send(prefs.settingsJson(), "已配对，设置已发送到电视")
            handler.postDelayed({ fetchStatus(status) }, 3000)
        })
        root.addView(button("更改设备用途") {
            confirm("确定把这台设备改成别的用途吗？") {
                prefs.role = ""
                render()
            }
        })

        refresher = object : Runnable {
            override fun run() {
                fetchStatus(status)
                handler.postDelayed(this, 30_000)
            }
        }.also { handler.post(it) }
    }

    private fun sendBonus(min: Int) = send(
        JSONObject().put("t", "bonus").put("min", min).put("date", LocalDate.now().toString()),
        "已给今天加 $min 分钟"
    )

    private fun send(body: JSONObject, okMsg: String) {
        val code = prefs.pairCode
        if (code.isBlank()) {
            toast("请先在下面填写配对码")
            return
        }
        thread {
            val r = runCatching { Relay.publish(Relay.cmdTopic(code), body) }
            runOnUiThread {
                r.onSuccess { toast("✅ $okMsg") }.onFailure { toast("❌ 发送失败：${it.message}") }
            }
        }
    }

    private fun fetchStatus(view: TextView) {
        val code = prefs.pairCode
        if (code.isBlank()) {
            view.text = "还没配对：请在下方输入电视上的配对码"
            return
        }
        thread {
            val r = runCatching { Relay.poll(Relay.statusTopic(code), "12h").lastOrNull() }
            runOnUiThread {
                view.text = r.fold(
                    onSuccess = { m -> if (m == null) "12 小时内没有收到电视的消息。\n请确认电视端已启动守护、配对码正确。" else statusText(m) },
                    onFailure = { "❌ 获取状态失败：${it.message}" }
                )
            }
        }
    }

    private fun statusText(m: Relay.Msg): String {
        val j = m.body
        val ago = (System.currentTimeMillis() - j.optLong("ts", m.time)) / 60_000
        val total = j.optInt("limit") + j.optInt("bonus")
        val used = j.optLong("used") / 60
        val start = Prefs.fmtHm(j.optInt("start"))
        val end = Prefs.fmtHm(j.optInt("end"))
        return buildString {
            append(if (j.optBoolean("on")) "📺 电视开着\n" else "⚫ 电视待机\n")
            append("今天已看 $used / $total 分钟（剩 ${(total - used).coerceAtLeast(0)}）\n")
            if (j.optInt("quiz") > 0) append("其中做题赚了 ${j.optInt("quiz")} 分钟\n")
            append("允许时段 $start–$end")
            if (!j.optBoolean("enforce")) append(" · 仅记录不关机")
            if (System.currentTimeMillis() < j.optLong("pause")) append("\n⏸ 管控暂停中")
            append("\n${j.optString("msg")}")
            // 电视的本机 ADB 今天连不上过：电视可能关不掉、界面可能打不开
            val adbFail = j.optLong("adbFail")
            if (adbFail > 0 && System.currentTimeMillis() - adbFail < 24 * 3600_000L) {
                val t = java.time.Instant.ofEpochMilli(adbFail).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
                append("\n⚠ 电视的本机 ADB 最近在 %02d:%02d 连不上：请到电视「开发者选项」确认「USB 调试」还开着，有「允许 USB 调试」弹窗要勾选「始终允许」".format(t.hour, t.minute))
            }
            if (j.has("overlay") && !j.optBoolean("overlay"))
                append("\n⚠ 电视还没有「显示在其他应用上层」权限，做题/锁屏界面要靠本机 ADB 才能打开")
            append("\n（$ago 分钟前上报")
            if (ago > 70) append("，电视可能已待机或断网")
            append("）")
            if (ago > 70 && j.optBoolean("on"))
                append("\n电视开着却很久没上报：可能是家里网络今天的免费消息额度（ntfy.sh 每天 250 条）用完了，第二天会恢复")
            val log = j.optString("log")
            if (log.isNotBlank()) append("\n\n最近记录：\n$log")
        }
    }

    // ---------------- 简单的 UI 工具 ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun page(): LinearLayout {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F5F7F5"))
            // 内容避开状态栏/导航栏，不会被挡住
            fitsSystemWindows = true
            addView(ll)
        })
        return ll
    }

    private fun title(s: String) = TextView(this).apply {
        text = s
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#1B5E20"))
        setPadding(0, 0, 0, dp(8))
    }

    private fun section(s: String) = TextView(this).apply {
        text = s
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#2E7D32"))
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun text(s: String) = TextView(this).apply {
        text = s
        textSize = 15f
        setTextColor(Color.parseColor("#333333"))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun card(v: View) = LinearLayout(this).apply {
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Color.parseColor("#C8E6C9"))
        }
        addView(v)
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun button(s: String, weight: Boolean = false, onClick: () -> Unit) =
        Ui.button(this, s, weight, onClick)

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("确定") { _, _ -> ok() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
