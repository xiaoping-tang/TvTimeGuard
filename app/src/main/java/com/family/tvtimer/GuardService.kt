package com.family.tvtimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 运行在电视上的前台守护服务，每 30 秒：
 *  1. 从中转站拉取手机发来的设置/指令
 *  2. 屏幕亮着就累计观看时间；超时或不在允许时段就让电视待机
 *  3. 把状态上报给手机
 * 电视一开屏也会立刻检查一次。
 */
class GuardService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var tv: TvController
    private var executor: ScheduledExecutorService? = null
    private var lastTick = 0L
    private var lastPublish = 0L
    private var lastPublishedKey = ""
    private var forcePublish = true

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            forcePublish = true
            executor?.execute { safeTick() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        tv = TvController(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.guardRunning = false
            prefs.lastStatus = "守护已停止"
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(
            NOTIF_ID, buildNotification("守护运行中"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        prefs.guardRunning = true
        if (executor == null) {
            registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
            lastTick = 0L
            executor = Executors.newSingleThreadScheduledExecutor().also {
                it.scheduleWithFixedDelay({ safeTick() }, 0, CHECK_SEC, TimeUnit.SECONDS)
            }
        } else {
            executor?.execute { safeTick() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        executor?.let {
            it.shutdownNow()
            unregisterReceiver(screenOnReceiver)
        }
        executor = null
        super.onDestroy()
    }

    private fun safeTick() {
        try {
            tick()
        } catch (t: Throwable) {
            Log.w(TAG, "tick failed", t)
            update("⚠ 出错：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val forceOff = syncCommands()
        val on = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

        if (!on) {
            lastTick = 0L
            // 倒计时中被关屏，下次开屏重新开始（短）倒计时，保证家长总有机会输 PIN
            prefs.lockDeadline = 0L
            update("电视待机中")
            maybePublish(on = false)
            return
        }

        // 累计两次检查之间的时间（最多算 2 个周期，防止休眠后一次加太多）
        if (lastTick > 0) {
            val delta = ((now - lastTick) / 1000).coerceIn(0, CHECK_SEC * 2)
            prefs.usedSec = prefs.usedSec + delta
        }
        lastTick = now

        val reason = when {
            forceOff -> "家长远程关机"
            !prefs.isInAllowedWindow() -> "不在允许时段（${prefs.windowText()}）"
            prefs.remainingSec <= 0 -> "今天的时间用完了"
            else -> null
        }

        if (reason == null || forceOff || prefs.isPaused() || !prefs.enforce || !prefs.paired) {
            prefs.lockDeadline = 0L // LockActivity 看到 0 会自己关闭
        }
        when {
            reason == null -> update("正在看 · 剩 ${prefs.remainingSec / 60} 分钟")
            forceOff -> {
                turnOff(reason)
                return
            }
            prefs.isPaused() -> update("管控暂停中 · $reason")
            !prefs.enforce -> update("仅记录 · $reason")
            !prefs.paired -> update("还没配对手机，只计时不关机 · $reason")
            else -> {
                countdownThenOff(reason)
                return
            }
        }
        maybePublish(on = true)
    }

    /** 先全屏提示「时间到了」并倒计时，期间家长可输 PIN 解锁；倒计时结束才关机 */
    private fun countdownThenOff(reason: String) {
        val now = System.currentTimeMillis()
        if (prefs.lockDeadline == 0L) {
            // 10 分钟内刚倒计时过（小孩关了又开），只给短倒计时
            val sec = if (now - prefs.lastLockAt < 10 * 60_000) SHORT_LOCK_SEC else LOCK_SEC
            prefs.lastLockAt = now
            prefs.lockReason = reason
            prefs.lockDeadline = now + sec * 1000
            forcePublish = true
        }
        val left = (prefs.lockDeadline - now + 999) / 1000
        if (left <= 0) {
            prefs.lockDeadline = 0L
            turnOff(reason)
            return
        }
        if (!LockActivity.visible) showLock()
        update("⏳ $reason，$left 秒后关机")
        maybePublish(on = true)
        // 倒计时期间加快检查，按时关机，小孩按主页键退出提示也会被马上拉回来
        executor?.schedule({ safeTick() }, minOf(left, 3L), TimeUnit.SECONDS)
    }

    private fun showLock() {
        try {
            // 后台服务不能直接弹界面，借本机 ADB 启动
            tv.shell("am start -n $packageName/.LockActivity")
        } catch (t: Throwable) {
            Log.w(TAG, "show lock failed", t)
        }
    }

    private fun turnOff(reason: String) {
        try {
            tv.turnOff()
            lastTick = 0L
            update("已关闭电视：$reason")
        } catch (t: Throwable) {
            update("⚠ 想关电视但 ADB 失败（请在电视上重新授权）：$reason")
        }
        forcePublish = true
        maybePublish(on = false)
    }

    /** 处理手机发来的指令，返回是否要求立即关机 */
    private fun syncCommands(): Boolean {
        val code = prefs.pairCode
        if (code.isBlank()) return false
        val msgs = try {
            Relay.poll(Relay.cmdTopic(code), prefs.lastCmdId.ifBlank { "all" })
        } catch (t: Throwable) {
            Log.w(TAG, "poll failed", t)
            return false // 断网时照常按本地设置管控
        }
        val today = LocalDate.now().toString()
        val now = System.currentTimeMillis()
        var off = false
        for (m in msgs) {
            val j = m.body
            when (j.optString("t")) {
                "cfg" -> {
                    prefs.applySettings(j)
                    prefs.paired = true
                }
                "bonus" -> if (j.optString("date") == today) prefs.bonusMin = prefs.bonusMin + j.optInt("min")
                "reset" -> if (j.optString("date") == today) prefs.resetToday()
                "pause" -> prefs.pauseUntil = j.optLong("until")
                // 过期的关机指令不执行，免得电视第二天一开机就被关
                "off" -> if (now - m.time < 3 * 60_000) off = true
            }
            prefs.lastCmdId = m.id
        }
        if (msgs.isNotEmpty()) forcePublish = true
        return off
    }

    private fun maybePublish(on: Boolean) {
        val code = prefs.pairCode
        if (code.isBlank()) return
        val now = System.currentTimeMillis()
        val key = "$on|${prefs.lastStatus.substringBefore('\n')}|${prefs.dailyLimitMin}|${prefs.bonusMin}"
        val interval = if (on) 5 * 60_000 else 30 * 60_000
        if (!forcePublish && key == lastPublishedKey && now - lastPublish < interval) return
        try {
            Relay.publish(
                Relay.statusTopic(code),
                prefs.settingsJson()
                    .put("t", "st")
                    .put("on", on)
                    .put("used", prefs.usedSec)
                    .put("bonus", prefs.bonusMin)
                    .put("pause", prefs.pauseUntil)
                    .put("msg", prefs.lastStatus.substringBefore('\n'))
                    .put("ts", now)
            )
            lastPublish = now
            lastPublishedKey = key
            forcePublish = false
        } catch (t: Throwable) {
            Log.w(TAG, "publish failed", t)
        }
    }

    private fun update(status: String) {
        val used = "今天已看 ${prefs.usedSec / 60}/${prefs.dailyLimitMin + prefs.bonusMin} 分钟"
        prefs.lastStatus = "$status\n$used"
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification("$status · $used"))
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "电视守护", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tv)
            .setContentTitle("电视时间管家")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "GuardService"
        private const val CHANNEL = "guard"
        private const val NOTIF_ID = 1
        private const val CHECK_SEC = 30L
        private const val LOCK_SEC = 60L
        private const val SHORT_LOCK_SEC = 20L
        const val ACTION_STOP = "com.family.tvtimer.STOP"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, GuardService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, GuardService::class.java).setAction(ACTION_STOP))
        }
    }
}
