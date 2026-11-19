package com.family.tvtimer

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * 电视上的 App 通过本机 ADB（127.0.0.1:5555）给自己发指令，从而能让电视待机。
 * 前提：电视「开发者选项」里打开了「USB 调试」。
 * 第一次连接时电视会弹出「允许 USB 调试吗？」，勾选「始终允许」后点确定。
 */
class TvController(private val context: Context) {

    private val keyPair: AdbKeyPair by lazy {
        val priv = File(context.filesDir, "adbkey")
        val pub = File(context.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        AdbKeyPair.read(priv, pub)
    }

    // socketTimeout 要足够长，等人在电视上点「允许」
    private fun <T> withAdb(block: (Dadb) -> T): T =
        Dadb.create("127.0.0.1", 5555, keyPair, 5_000, 60_000).use(block)

    /** 让电视进入待机 */
    fun turnOff() {
        shell("input keyevent KEYCODE_SLEEP")
    }

    fun shell(cmd: String) {
        withAdb { it.shell(cmd) }
    }

    /** 测试 ADB 并顺便把本 App 加入省电白名单（避免被系统杀掉），再授予「显示在其他应用上层」 */
    fun setup(): String = withAdb { adb ->
        val pkg = context.packageName
        adb.shell("dumpsys deviceidle whitelist +$pkg")
        adb.shell("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
        adb.shell("cmd appops set $pkg RUN_IN_BACKGROUND allow")
        adb.shell("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        adb.shell("getprop ro.product.model").output.trim()
    }

    /** 授予「显示在其他应用上层」：之后打开做题/锁屏界面就不再依赖本机 ADB */
    fun grantOverlay() {
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
    }
}
