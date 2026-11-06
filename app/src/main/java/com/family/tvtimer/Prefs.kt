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

    var lastStatus: String
        get() = sp.getString("last_status", "守护未启动") ?: ""
        set(v) = sp.edit().putString("last_status", v).apply()

    private fun rollDay() {
        val today = LocalDate.now().toString()
        if (sp.getString("usage_date", "") != today) {
            sp.edit()
                .putString("usage_date", today)
                .putLong("used_sec", 0L)
                .putInt("bonus_min", 0)
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
        sp.edit().putLong("used_sec", 0L).putInt("bonus_min", 0).apply()
    }

    fun windowText() = "${fmtHm(allowStartMin)}–${fmtHm(allowEndMin)}"

    /** 手机端发给电视的完整设置 */
    fun settingsJson(): JSONObject = JSONObject()
        .put("t", "cfg")
        .put("limit", dailyLimitMin)
        .put("start", allowStartMin)
        .put("end", allowEndMin)
        .put("enforce", enforce)

    fun applySettings(j: JSONObject) {
        dailyLimitMin = j.optInt("limit", dailyLimitMin)
        allowStartMin = j.optInt("start", allowStartMin)
        allowEndMin = j.optInt("end", allowEndMin)
        enforce = j.optBoolean("enforce", enforce)
    }

    companion object {
        fun fmtHm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

        fun newPairCode(): String {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..12).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        }
    }
}
