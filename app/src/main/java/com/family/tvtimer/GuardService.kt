package com.family.tvtimer

import android.app.Activity
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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
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
    private var publishBackoffUntil = 0L
    private var quizLaunchAt = 0L
    private var quizLaunchFails = 0
    private var quizFailAt = 0L
    private var lockLaunchAt = 0L
    private var overlayTryAt = 0L
    private var hardOffTryAt = 0L
    private var adbFailLoggedAt = 0L

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            forcePublish = true
            executor?.execute { safeTick() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 手机发来「做题加时」的时间，等电视亮屏时打开做题界面；0 表示没有 */
    private var quizRequestAt = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        tv = TvController(this)
        createChannel()
        instance = this
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
            prefs.logEvent("守护启动")
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
        if (instance === this) instance = null
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
            prefs.hardLock = false
            QuizActivity.declined = false // 下次开机时间还是用完的话，重新出题
            quizLaunchFails = 0
            quizLaunchAt = 0L
            update("电视待机中")
            maybePublish(on = false)
            return
        }

        ensureOverlay(now)

        // 手机要求做题：亮屏时打开做题界面（请求 10 分钟内有效）
        if (quizRequestAt > 0) {
            if (now - quizRequestAt < 10 * 60_000 && !forceOff) showQuiz()
            quizRequestAt = 0L
        }
        val quizzing = QuizActivity.isActive()

        // 累计两次检查之间的时间（最多算 2 个周期，防止休眠后一次加太多）。做题时不算看电视
        if (lastTick > 0 && !quizzing) {
            val delta = ((now - lastTick) / 1000).coerceIn(0, CHECK_SEC * 2)
            prefs.usedSec = prefs.usedSec + delta
        }
        lastTick = now

        if (QuizActivity.visible) quizLaunchFails = 0
        if (quizzing && !forceOff) {
            prefs.lockDeadline = 0L // 做题界面优先，「时间到了」先不弹
            update(if (prefs.remainingSec <= 0) "📝 时间用完，正在做题赢 10 分钟" else "📝 正在做题加时 · 剩 ${prefs.remainingSec / 60} 分钟")
            maybePublish(on = true)
            return
        }

        // 只有「今天的时间用完了」（且在允许时段内）才能靠做题加时；做题加时到上限后照旧倒计时关机
        val timeUp = prefs.isInAllowedWindow() && prefs.remainingSec <= 0
        val quizCanHelp = prefs.quizRewardMin() > 0
        val reason = when {
            forceOff -> "家长远程关机"
            !prefs.isInAllowedWindow() -> "不在允许时段（${prefs.windowText()}）"
            timeUp && !quizCanHelp -> "今天的时间用完了（做题加时也到上限了）"
            timeUp -> "今天的时间用完了"
            else -> null
        }

        if (reason == null) QuizActivity.declined = false
        if (reason == null || forceOff || prefs.isPaused() || !prefs.enforce || !prefs.paired) {
            prefs.lockDeadline = 0L // LockActivity 看到 0 会自己关闭
            prefs.hardLock = false
        }
        when {
            reason == null -> {
                update("正在看 · 剩 ${prefs.remainingSec / 60} 分钟")
                // 时间快用完时，到点就检查，不用等下一个 30 秒周期
                val left = prefs.remainingSec
                if (left < CHECK_SEC) executor?.schedule({ safeTick() }, left + 1, TimeUnit.SECONDS)
            }
            forceOff -> {
                turnOff(reason)
                return
            }
            prefs.isPaused() -> update("管控暂停中 · $reason")
            !prefs.enforce -> update("仅记录 · $reason")
            !prefs.paired -> update("还没配对手机，只计时不关机 · $reason")
            // 时间用完：不弹关机倒计时，直接去做题（小孩主动放弃做题、或做题界面打不开时才走倒计时关机）
            timeUp && quizCanHelp && !QuizActivity.declined && quizUsable(now) -> {
                prefs.lockDeadline = 0L
                prefs.hardLock = false
                showQuiz()
                update("📝 时间用完，打开做题界面")
                maybePublish(on = true)
                return
            }
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
        if (prefs.hardLock) {
            holdLock(reason, now)
            return
        }
        if (prefs.lockDeadline == 0L) {
            // 10 分钟内刚倒计时过（小孩关了又开），只给短倒计时
            val sec = if (now - prefs.lastLockAt < 10 * 60_000) SHORT_LOCK_SEC else LOCK_SEC
            prefs.lastLockAt = now
            prefs.lockReason = reason
            prefs.lockDeadline = now + sec * 1000
        }
        val left = (prefs.lockDeadline - now + 999) / 1000
        if (left <= 0) {
            prefs.lockDeadline = 0L
            turnOff(reason)
            return
        }
        val blocked = uiBlocked()
        // 倒计时界面被关掉（按主页键）就拉回来；最多 5 秒拉一次。App 被暂停时界面打不开，就不去试，免得系统一直弹框
        if (!LockActivity.visible && !blocked && now - lockLaunchAt > 5_000) {
            lockLaunchAt = now
            showLock()
        }
        update("⏳ $reason，$left 秒后关机" + if (blocked) " · ⚠ $BLOCKED_MSG" else "")
        maybePublish(on = true)
        // 倒计时期间加快检查，按时关机，小孩按主页键退出提示也会被马上拉回来
        executor?.schedule({ safeTick() }, minOf(left, 3L), TimeUnit.SECONDS)
    }

    /**
     * 关不掉电视（本机 ADB 连不上）时的后备：一直显示锁屏，小孩按主页键溜走马上拉回来。
     * 锁屏上家长可以输密码，小孩也可以去做题。每分钟再试一次关电视，ADB 恢复了就照常关。
     */
    private fun holdLock(reason: String, now: Long) {
        if (now - hardOffTryAt > 60_000) {
            hardOffTryAt = now
            try {
                tv.turnOff()
                prefs.hardLock = false
                prefs.lockDeadline = 0L
                lastTick = 0L
                prefs.logEvent("本机 ADB 恢复，关电视：$reason")
                update("已关闭电视：$reason")
                forcePublish = true
                maybePublish(on = false)
                return
            } catch (t: Throwable) {
                adbFailed("关电视", t)
            }
        }
        if (prefs.lockDeadline == 0L) prefs.lockDeadline = now // 非 0：锁屏界面不会自己关掉
        prefs.lockReason = reason
        val blocked = uiBlocked()
        if (!LockActivity.visible && !blocked && now - lockLaunchAt > 3_000) {
            lockLaunchAt = now
            showLock()
        }
        update("🔒 $reason：电视关不掉（本机 ADB 连不上），已改为锁屏" + if (blocked) " · ⚠ $BLOCKED_MSG" else "")
        maybePublish(on = true)
        executor?.schedule({ safeTick() }, 3L, TimeUnit.SECONDS)
    }

    private fun showLock() {
        launch(LockActivity::class.java)
    }

    /**
     * 打开本 App 的一个全屏界面。有「显示在其他应用上层」权限时直接打开（不依赖本机 ADB），
     * 否则借本机 ADB 的 am start 打开（后台服务一般不能直接弹界面）。返回是否成功发出。
     */
    private fun launch(cls: Class<out Activity>): Boolean {
        val intent = Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(intent)
                // 系统也可能悄悄拦下不报错：4 秒后界面还没出现，就再用本机 ADB 打开一次
                executor?.schedule({
                    val shown = if (cls == QuizActivity::class.java) QuizActivity.visible else LockActivity.visible
                    if (!shown) runCatching { tv.shell("am start -n $packageName/${cls.name}") }
                        .onFailure { adbFailed("打开界面", it) }
                }, 4, TimeUnit.SECONDS)
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "direct start failed", t)
            }
        }
        return try {
            tv.shell("am start -n $packageName/${cls.name}")
            true
        } catch (t: Throwable) {
            adbFailed("打开${if (cls == QuizActivity::class.java) "做题" else "锁屏"}界面", t)
            runCatching { startActivity(intent) } // 碰碰运气，系统可能会放行
            false
        }
    }

    /** 还没有「显示在其他应用上层」权限时，趁本机 ADB 能用授予一次（10 分钟最多试一次） */
    private fun ensureOverlay(now: Long) {
        if (Settings.canDrawOverlays(this) || now - overlayTryAt < 10 * 60_000) return
        overlayTryAt = now
        try {
            tv.grantOverlay()
            if (Settings.canDrawOverlays(this)) prefs.logEvent("已获得「显示在其他应用上层」权限，界面不再依赖本机 ADB")
        } catch (t: Throwable) {
            adbFailed("授予悬浮窗权限", t)
        }
    }

    /** 记下本机 ADB 连不上（事件记录 10 分钟最多写一条，免得刷屏） */
    private fun adbFailed(what: String, t: Throwable) {
        Log.w(TAG, "adb failed: $what", t)
        val now = System.currentTimeMillis()
        prefs.lastAdbFailAt = now
        if (now - adbFailLoggedAt > 10 * 60_000) {
            adbFailLoggedAt = now
            prefs.logEvent("⚠ 本机 ADB 连不上（$what）：${t.message ?: t.javaClass.simpleName}")
        }
    }

    /**
     * 本 App 被系统「暂停使用」了（Google TV 儿童资料、家长控制、屏幕使用时间限制等会这样做）。
     * 这时做题和倒计时界面都打不开，每次尝试系统都会弹「应用无法使用 …… 由 Google TV 所管理」。
     * 守护服务本身还在运行，关电视用的是本机 ADB，不受影响。
     */
    private fun uiBlocked(): Boolean = Build.VERSION.SDK_INT >= 29 &&
        runCatching { packageManager.isPackageSuspended() }.getOrDefault(false)

    /**
     * 做题界面能不能用：App 被暂停就不能用；另外连续 2 次打开后它都没出现（被系统拦了），
     * 也当作不能用，改走倒计时关机。10 分钟后再重新试做题（可能只是一时打不开）。
     */
    private fun quizUsable(now: Long): Boolean {
        if (uiBlocked()) return false
        if (quizLaunchAt > 0 && now - quizLaunchAt > 15_000) {
            if (QuizActivity.lastResumeAt < quizLaunchAt) {
                quizLaunchFails++
                quizFailAt = now
                if (quizLaunchFails == 2) prefs.logEvent("⚠ 做题界面连续 2 次没打开，改为倒计时关机")
            } else quizLaunchFails = 0
            quizLaunchAt = 0L
        }
        if (quizLaunchFails >= 2 && now - quizFailAt > 10 * 60_000) quizLaunchFails = 0
        return quizLaunchFails < 2
    }

    private fun showQuiz() {
        if (uiBlocked()) {
            update("⚠ $BLOCKED_MSG")
            return
        }
        // 不需要另外暂停视频：做题界面盖上来时，YouTube 等播放器退到后台会自己暂停；
        // 答对后做题界面关掉，系统回到原来的 App，播放器会自己接着播（已在电视上实测）
        quizLaunchAt = System.currentTimeMillis()
        QuizActivity.launchGraceUntil = System.currentTimeMillis() + 15_000
        if (!launch(QuizActivity::class.java)) QuizActivity.launchGraceUntil = 0L
    }

    private fun turnOff(reason: String) {
        try {
            tv.turnOff()
            lastTick = 0L
            prefs.logEvent("关电视：$reason")
            update("已关闭电视：$reason")
            forcePublish = true
            maybePublish(on = false)
        } catch (t: Throwable) {
            // 关不掉就一直锁屏，不能让小孩接着看
            adbFailed("关电视", t)
            prefs.hardLock = true
            hardOffTryAt = System.currentTimeMillis()
            prefs.logEvent("🔒 关不掉电视，改为锁屏：$reason")
            holdLock(reason, System.currentTimeMillis())
        }
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
                // 做题加时：电视待机时先记着，10 分钟内亮屏就出题
                "quiz" -> if (now - m.time < 10 * 60_000) quizRequestAt = m.time
            }
            prefs.lastCmdId = m.id
        }
        if (msgs.isNotEmpty()) forcePublish = true
        return off
    }

    /**
     * 上报状态给手机。ntfy.sh 免费额度是每个网络每天 250 条（手机在家发的指令也算），所以要省着用：
     *  - 只有「状态类别」变了才算变化：状态文字里的数字（剩几分钟、几秒后关机）不算
     *  - 没变化时定期上报：开着 10 分钟一次，待机 1 小时一次
     *  - 两次上报至少隔 1 分钟（收到手机指令后的回报除外，让家长马上看到结果）
     *  - 额度用完（429）后 30 分钟内不再尝试
     */
    private fun maybePublish(on: Boolean) {
        val code = prefs.pairCode
        if (code.isBlank()) return
        val now = System.currentTimeMillis()
        if (now < publishBackoffUntil) return
        val state = prefs.lastStatus.substringBefore('\n').replace(Regex("\\d+"), "#")
        val key = "$on|$state|${prefs.dailyLimitMin}|${prefs.bonusMin}|${prefs.pauseUntil}|${prefs.enforce}|" +
            "${prefs.allowStartMin}-${prefs.allowEndMin}|${prefs.quizCapMin}"
        val interval = if (on) 10 * 60_000 else 60 * 60_000
        if (!forcePublish && key == lastPublishedKey && now - lastPublish < interval) return
        if (!forcePublish && now - lastPublish < 60_000) return // 下一次检查（30 秒后）再发
        try {
            Relay.publish(
                Relay.statusTopic(code),
                prefs.settingsJson()
                    .put("t", "st")
                    .put("on", on)
                    .put("used", prefs.usedSec)
                    .put("bonus", prefs.bonusMin)
                    .put("quiz", prefs.quizBonusMin)
                    .put("pause", prefs.pauseUntil)
                    .put("adbFail", prefs.lastAdbFailAt)
                    .put("overlay", Settings.canDrawOverlays(this))
                    .put("log", prefs.eventLog.lines().take(8).joinToString("\n"))
                    .put("msg", prefs.lastStatus.substringBefore('\n'))
                    .put("ts", now)
            )
            lastPublish = now
            lastPublishedKey = key
            forcePublish = false
        } catch (t: Throwable) {
            Log.w(TAG, "publish failed", t)
            publishBackoffUntil = now + if (t is Relay.QuotaExceeded) 30 * 60_000 else 60_000
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
        private const val BLOCKED_MSG = "电视时间管家被 Google TV 暂停了（儿童资料/家长控制），做题和倒计时界面打不开"

        @Volatile
        private var instance: GuardService? = null

        /** 让守护马上检查一次（答对加时、退出做题界面时调用）。是否上报由 maybePublish 自己判断 */
        fun poke() {
            val s = instance ?: return
            s.executor?.execute { s.safeTick() }
        }

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, GuardService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, GuardService::class.java).setAction(ACTION_STOP))
        }
    }
}
