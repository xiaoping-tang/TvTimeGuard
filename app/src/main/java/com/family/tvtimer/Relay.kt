package com.family.tvtimer

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 通过 ntfy.sh（免费公共消息中转，无需注册）在手机和电视之间传消息，
 * 这样家长不在家也能控制。配对码就是 topic 名的一部分，请不要外传。
 *   <code>-cmd : 手机 → 电视（设置、指令）
 *   <code>-st  : 电视 → 手机（状态上报）
 */
object Relay {
    private const val SERVER = "https://ntfy.sh"

    data class Msg(val id: String, val time: Long, val body: JSONObject)

    fun cmdTopic(code: String) = "tvtimer-$code-cmd"
    fun statusTopic(code: String) = "tvtimer-$code-st"

    fun publish(topic: String, body: JSONObject) {
        val conn = URL("$SERVER/$topic").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("中转服务器返回 $code")
        } finally {
            conn.disconnect()
        }
    }

    /** 拉取 since 之后的消息（since 可以是消息 id、"all"、或 "2h" 这类时长） */
    fun poll(topic: String, since: String): List<Msg> {
        val url = "$SERVER/$topic/json?poll=1&since=" + URLEncoder.encode(since, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("中转服务器返回 $code")
            return conn.inputStream.bufferedReader().readLines()
                .filter { it.isNotBlank() }
                .map { JSONObject(it) }
                .filter { it.optString("event") == "message" }
                .mapNotNull { m ->
                    runCatching {
                        Msg(m.getString("id"), m.getLong("time") * 1000, JSONObject(m.getString("message")))
                    }.getOrNull()
                }
        } finally {
            conn.disconnect()
        }
    }
}
