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
internal class WebDav(private val fileUrl: String, private val user: String, private val pass: String) {

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
            c.setRequestProperty("Authorization", auth())
            c.connectTimeout = CONNECT_TIMEOUT
            c.readTimeout = READ_TIMEOUT

            val code = c.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return Fetched(false, null, null)
            if (code !in 200..299) throw IOException("GET $code")

            // 有的服务器带引号（"abc"），有的还带弱标记（W/"abc"），一律剥掉
            val etag = c.getHeaderField("ETag")?.trim('"', 'W', '/')
            Fetched(true, etag, c.inputStream.readBytes().toString(Charset.forName("UTF-8")))
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

            c.outputStream.use { it.write(content.toByteArray(Charset.forName("UTF-8"))) }

            val code = c.responseCode
            if (code !in 200..299) return code      // 含 412：交给调用方决定怎么处理
            return code
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
