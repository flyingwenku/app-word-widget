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
 */
internal class WebDav(
    private val fileUrl: String,
    private val user: String,
    private val pass: String,
    /** 排查用的流水账：每次 GET / PUT 各记一行，最后跟着结果一起显示在界面上 */
    private val log: StringBuilder? = null
) {

    private fun note(s: String) { log?.append(s)?.append('\n') }

    /** 出错时把服务端返回的内容摘一段出来 —— 光看状态码常常不足以说明原因 */
    private fun readErr(c: HttpURLConnection): String = try {
        (c.errorStream ?: c.inputStream).readBytes()
            .toString(Charset.forName("UTF-8"))
            .replace('\n', ' ')
            .take(200)
    } catch (e: Exception) {
        "(读不到响应体)"
    }

    // data class 才能 `val (exists, etag, json) = dav.fetch()` 这样解构
    data class Fetched(val exists: Boolean, val etag: String?, val content: String?)

    /**
     * 拉远端文件。
     *
     * 存在性只看 GET 是不是 404 —— **不能**用"有没有 ETag"来判断
     * （坚果云的 PROPFIND 压根不带 ETag 头，Windows 端踩过：被判成"文件不存在"，
     * 每次都把本地覆盖上去，另一端刚加的句子直接没了）。
     */
    fun fetch(): Fetched {
        val c = URL(fileUrl).openConnection() as HttpURLConnection
        return try {
            // 坚果云的响应头是 Cache-Control: max-age=5 —— GET 结果允许被缓存 5 秒。
            // 一旦被缓存（ROM / Wi-Fi 代理 / 移动网络都可能），第二次同步拿到的还是上一次的
            // ETag，而服务端此时已经换过了 → 必然 412，还会连着失败三次
            // （就是那个"云端一直被别的一端改写"）。同步场景里 GET 绝不能用缓存。
            c.useCaches = false
            c.setRequestProperty("Cache-Control", "no-cache")
            c.setRequestProperty("Pragma", "no-cache")

            c.setRequestProperty("Authorization", auth())
            c.connectTimeout = CONNECT_TIMEOUT
            c.readTimeout = READ_TIMEOUT

            val code = c.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                note("GET -> 404（远端还没有这个文件）")
                return Fetched(false, null, null)
            }
            if (code !in 200..299) {
                note("GET -> $code body=${readErr(c)}")
                throw IOException("GET $code")
            }

            // 有的服务器带引号（"abc"），有的还带弱标记（W/"abc"），一律剥掉。
            // raw 也一并记下来：ETag 一旦跟服务端校验的对不上，这就是唯一的线索
            val raw = c.getHeaderField("ETag")
            val etag = raw?.trim('"', 'W', '/')
            val body = c.inputStream.readBytes().toString(Charset.forName("UTF-8"))

            note("GET -> $code len=${body.length} etag=${etag ?: "(没有)"} raw=$raw " +
                "lastmod=${c.getHeaderField("Last-Modified") ?: "-"}")
            Fetched(true, etag, body)
        } finally {
            c.disconnect()
        }
    }

    /**
     * 推上去，**返回 HTTP 状态码**。
     *
     * etag 不为 null 时带 If-Match（乐观锁）；412 = 别人在我们 GET 之后又写了一次，
     * 得重新拉取再合并。把状态码交给调用方，界面上才能显示真实原因
     * （只说"失败"没法区分是 401 密码错还是 412 撞车）。
     */
    fun put(content: String, etag: String?): Int {
        val c = URL(fileUrl).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "PUT"
            c.doOutput = true
            c.setRequestProperty("Authorization", auth())
            c.setRequestProperty("Content-Type", "application/json")
            if (!etag.isNullOrEmpty()) c.setRequestProperty("If-Match", "\"$etag\"")
            c.connectTimeout = CONNECT_TIMEOUT
            c.readTimeout = READ_TIMEOUT

            note("PUT len=${content.length} If-Match=${if (etag.isNullOrEmpty()) "(不带锁)" else "\"$etag\""}")

            c.outputStream.use { it.write(content.toByteArray(Charset.forName("UTF-8"))) }

            val code = c.responseCode
            note(
                if (code in 200..299) "PUT -> $code（成功）"
                else "PUT -> $code body=${readErr(c)}"
            )
            return code      // 含 412：交给调用方决定怎么处理
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
