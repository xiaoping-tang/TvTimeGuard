package com.family.tvtimer

import android.content.Context
import org.json.JSONObject
import java.security.SecureRandom
import java.time.LocalDate
import java.time.LocalTime

/** 所有设置和当天的观看记录，存在 SharedPreferences 里。电视端和手机端共用。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tv_timer", Context.MODE_PRIVATE)

    /** "tv"、"phone"，或空字符串（还没选） */
    var role: String
        get() = sp.getString("role", "") ?: ""
        set(v) = sp.edit().putString("role", v).apply()

    /** 配对码：电视端随机生成，手机端手动输入 */
    var pairCode: String
        get() = sp.getString("pair_code", "") ?: ""
        set(v) = sp.edit().putString("pair_code", v.trim().lowercase()).apply()

    /** 每天允许观看的分钟数 */
    var dailyLimitMin: Int
        get() = sp.getInt("daily_limit_min", 120)
        set(v) = sp.edit().putInt("daily_limit_min", v).apply()

    /** 允许观看时段（一天中的分钟数，0..1439） */
    var allowStartMin: Int
        get() = sp.getInt("allow_start_min", 7 * 60)
        set(v) = sp.edit().putInt("allow_start_min", v).apply()

    var allowEndMin: Int
        get() = sp.getInt("allow_end_min", 21 * 60)
        set(v) = sp.edit().putInt("allow_end_min", v).apply()

    /** 是否真正执行关机（关掉则只记录时间） */
    var enforce: Boolean
        get() = sp.getBoolean("enforce", true)
        set(v) = sp.edit().putBoolean("enforce", v).apply()

    /** 家长手动暂停管控到这个时间点（毫秒时间戳） */
    var pauseUntil: Long
        get() = sp.getLong("pause_until", 0L)
        set(v) = sp.edit().putLong("pause_until", v).apply()

    var guardRunning: Boolean
        get() = sp.getBoolean("guard_running", false)
        set(v) = sp.edit().putBoolean("guard_running", v).apply()

    /** 电视端：4 位家长 PIN，空字符串表示还没设置 */
    var pin: String
        get() = sp.getString("pin", "") ?: ""
        set(v) = sp.edit().putString("pin", v).apply()

    /** 电视端：是否已收到手机发来的设置。配对前只计时、不关机，保证家长能完成安装 */
    var paired: Boolean
        get() = sp.getBoolean("paired", false)
        set(v) = sp.edit().putBoolean("paired", v).apply()

    /** 电视端：「时间到了」倒计时的截止时间（毫秒），0 表示没在倒计时 */
    var lockDeadline: Long
        get() = sp.getLong("lock_deadline", 0L)
        set(v) = sp.edit().putLong("lock_deadline", v).apply()

    var lockReason: String
        get() = sp.getString("lock_reason", "") ?: ""
        set(v) = sp.edit().putString("lock_reason", v).apply()

    /** 电视端：上一次开始倒计时的时间，用来防止反复开关机蹭时间 */
    var lastLockAt: Long
        get() = sp.getLong("last_lock_at", 0L)
        set(v) = sp.edit().putLong("last_lock_at", v).apply()

    /** 电视端：已处理到的最后一条指令 id */
    var lastCmdId: String
        get() = sp.getString("last_cmd_id", "") ?: ""
        set(v) = sp.edit().putString("last_cmd_id", v).apply()

    // ---- 当天数据，跨日自动清零 ----

    var usedSec: Long
        get() { rollDay(); return sp.getLong("used_sec", 0L) }
        set(v) = sp.edit().putLong("used_sec", v).apply()

    /** 今天额外奖励的分钟数 */
    var bonusMin: Int
        get() { rollDay(); return sp.getInt("bonus_min", 0) }
        set(v) = sp.edit().putInt("bonus_min", v).apply()

    /** 今天做题赚到的分钟数（已经算在 bonusMin 里，单独记下来用来判断上限） */
    var quizBonusMin: Int
        get() { rollDay(); return sp.getInt("quiz_bonus_min", 0) }
        set(v) = sp.edit().putInt("quiz_bonus_min", v).apply()

    /** 每天最多能靠做题加多少分钟 */
    var quizCapMin: Int
        get() = sp.getInt("quiz_cap_min", 60)
        set(v) = sp.edit().putInt("quiz_cap_min", v).apply()

    var lastStatus: String
        get() = sp.getString("last_status", "守护未启动") ?: ""
        set(v) = sp.edit().putString("last_status", v).apply()

    /** 电视端：时间到了却关不掉电视（本机 ADB 连不上），改为一直锁屏，直到家长输密码、做题或电视待机 */
    var hardLock: Boolean
        get() = sp.getBoolean("hard_lock", false)
        set(v) = sp.edit().putBoolean("hard_lock", v).apply()

    /** 电视端：最近一次本机 ADB 连不上的时间（毫秒），0 表示没有 */
    var lastAdbFailAt: Long
        get() = sp.getLong("last_adb_fail_at", 0L)
        set(v) = sp.edit().putLong("last_adb_fail_at", v).apply()

    /** 电视端：最近的事件记录（每行「月-日 时:分 事件」，最新的在最前面），出问题时用来查原因 */
    val eventLog: String
        get() = sp.getString("event_log", "") ?: ""

    @Synchronized
    fun logEvent(msg: String) {
        val t = java.time.LocalDateTime.now()
        val line = "%02d-%02d %02d:%02d %s".format(t.monthValue, t.dayOfMonth, t.hour, t.minute, msg)
        val lines = listOf(line) + eventLog.lines().filter { it.isNotBlank() }.take(MAX_EVENTS - 1)
        sp.edit().putString("event_log", lines.joinToString("\n")).apply()
    }

    private fun rollDay() {
        val today = LocalDate.now().toString()
        if (sp.getString("usage_date", "") != today) {
            sp.edit()
                .putString("usage_date", today)
                .putLong("used_sec", 0L)
                .putInt("bonus_min", 0)
                .putInt("quiz_bonus_min", 0)
                .apply()
        }
    }

    val totalAllowedSec: Long get() = (dailyLimitMin + bonusMin) * 60L

    val remainingSec: Long get() = (totalAllowedSec - usedSec).coerceAtLeast(0L)

    fun isInAllowedWindow(now: LocalTime = LocalTime.now()): Boolean {
        val t = now.hour * 60 + now.minute
        val s = allowStartMin
        val e = allowEndMin
        return if (s == e) true
        else if (s < e) t in s until e
        else t >= s || t < e // 跨午夜，例如 20:00–02:00
    }

    fun isPaused(): Boolean = System.currentTimeMillis() < pauseUntil

    fun resetToday() {
        sp.edit().putLong("used_sec", 0L).putInt("bonus_min", 0).putInt("quiz_bonus_min", 0).apply()
    }

    /** 做对一道题能加的分钟数（不超过今天剩下的做题上限） */
    fun quizRewardMin(): Int = minOf(QUIZ_REWARD_MIN, (quizCapMin - quizBonusMin).coerceAtLeast(0))

    /** 做题答对：加时并记账，返回实际加了多少分钟 */
    @Synchronized
    fun addQuizBonus(): Int {
        val add = quizRewardMin()
        if (add > 0) {
            // 时间用完后多算的几秒（倒计时等）不追究，保证答对后从现在起能看满 10 分钟
            if (usedSec > totalAllowedSec) usedSec = totalAllowedSec
            quizBonusMin = quizBonusMin + add
            bonusMin = bonusMin + add
        }
        return add
    }

    fun windowText() = "${fmtHm(allowStartMin)}–${fmtHm(allowEndMin)}"

    /** 手机端发给电视的完整设置 */
    fun settingsJson(): JSONObject = JSONObject()
        .put("t", "cfg")
        .put("limit", dailyLimitMin)
        .put("start", allowStartMin)
        .put("end", allowEndMin)
        .put("enforce", enforce)
        .put("quizCap", quizCapMin)

    fun applySettings(j: JSONObject) {
        dailyLimitMin = j.optInt("limit", dailyLimitMin)
        allowStartMin = j.optInt("start", allowStartMin)
        allowEndMin = j.optInt("end", allowEndMin)
        enforce = j.optBoolean("enforce", enforce)
        quizCapMin = j.optInt("quizCap", quizCapMin)
    }

    companion object {
        /** 做对一道题加几分钟 */
        const val QUIZ_REWARD_MIN = 10

        /** 事件记录最多保留几条 */
        const val MAX_EVENTS = 60

        fun fmtHm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

        fun newPairCode(): String {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..12).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        }
    }
}
