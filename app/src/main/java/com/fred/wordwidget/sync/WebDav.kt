package com.fred.wordwidget.sync

import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * 极简 WebDAV 客户端：只做同步需要的两件事（拉 / 推）。
 *
 * 用 HttpURLConnection 手写，不引 OkHttp —— 本项目一直是零第三方依赖。
 * 代价：只支持 GET / PUT（HttpURLConnection 不允许自定义 MKCOL/PROPFIND 方法），
 * 所以云端目录要**先存在**（坚果云网页端建一个 Yiju 文件夹即可）。
 *
 * 认证用 Basic：坚果云要用**应用密码**（网页端"账户信息 → 添加应用"里生成），
 * 不是登录密码。
 *
 * **刻意不带 ETag / If-Match**：坚果云实测 GET 刚拿到的 ETag，原样带回去 PUT
 * 立刻被判 412 `ETag doesn't match`；同一个 ETag 还对应过两份不同的内容。
 * 乐观锁在这套后端上等于废的，重试也只会反复撞。同步层改成
 * "一次 GET → 合并 → 一次 PUT"，并发靠合并的幂等性兜底。
 * 踩坑详见知识库《多端同步踩坑与解法（通用）》。
 */
internal class WebDav(
    private val fileUrl: String,
    private val user: String,
    private val pass: String
) {

    /** 出错时把服务端返回的内容摘一段出来 —— 光看状态码常常不足以说明原因 */
    private fun readErr(c: HttpURLConnection): String = try {
        (c.errorStream ?: c.inputStream).readBytes()
            .toString(Charset.forName("UTF-8"))
            .replace('\n', ' ')
            .take(200)
    } catch (e: Exception) {
        "(读不到响应体)"
    }

    data class Fetched(val exists: Boolean, val content: String?)

    /**
     * 拉远端文件。存在性**只看 GET 是不是 404** —— 坚果云的 PROPFIND 响应不带 ETag 头，
     * 早期实现把"拿不到 ETag"当成"文件不存在"，每次启动都把本地覆盖上去，
     * 另一端刚加的句子直接被抹掉。现在完全不碰 ETag。
     *
     * GET 必须绕开缓存：坚果云返回 `Cache-Control: max-age=5`，命中缓存会拿到旧内容，
     * 于是"写完马上又读"的场景会读到上一版，凭空制造一次覆盖写。
     */
    fun fetch(): Fetched {
        val c = URL(fileUrl).openConnection() as HttpURLConnection
        return try {
            c.useCaches = false
            c.setRequestProperty("Cache-Control", "no-cache")
            c.setRequestProperty("Pragma", "no-cache")

            c.setRequestProperty("Authorization", auth())
            c.connectTimeout = CONNECT_TIMEOUT
            c.readTimeout = READ_TIMEOUT

            val code = c.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return Fetched(false, null)
            if (code !in 200..299) throw IOException("GET $code: ${readErr(c)}")

            val body = c.inputStream.readBytes().toString(Charset.forName("UTF-8"))
            Fetched(true, body)
        } finally {
            c.disconnect()
        }
    }

    /**
     * 推上去。**没有 If-Match、没有 412 分支、没有重试**，非 2xx 直接抛，
     * 让界面能显示真实 HTTP 码（401 密码错 / 507 空间满 / 5xx 服务端）。
     */
    fun put(content: String) {
        val c = URL(fileUrl).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "PUT"
            c.doOutput = true
            c.setRequestProperty("Authorization", auth())
            c.setRequestProperty("Content-Type", "application/json")
            c.connectTimeout = CONNECT_TIMEOUT
            c.readTimeout = READ_TIMEOUT

            c.outputStream.use { it.write(content.toByteArray(Charset.forName("UTF-8"))) }

            val code = c.responseCode
            if (code !in 200..299) throw IOException("PUT $code: ${readErr(c)}")
        } finally {
            c.disconnect()
        }
    }

    private fun auth(): String = "Basic " + Base64.encodeToString(
        "$user:$pass".toByteArray(Charset.forName("UTF-8")),
        Base64.NO_WRAP
    )

    companion object {
        private const val CONNECT_TIMEOUT = 15_000
        private const val READ_TIMEOUT = 30_000
    }
}
