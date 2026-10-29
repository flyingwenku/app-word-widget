package com.fred.wordwidget.prefs

import android.content.Context

/**
 * 同步设置（坚果云 WebDAV）。
 *
 * 密码存在 App 私有的 SharedPreferences 里：别的 App 读不到（系统按 uid 隔离），
 * 但**root 过的机器或备份出来的文件**是能看到明文的 ——
 * 所以这里只放"应用密码"，别填登录密码。
 */
class SyncPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    var url: String
        get() = prefs.getString("url", DEFAULT_URL) ?: DEFAULT_URL
        set(v) = prefs.edit().putString("url", v.trim()).apply()

    var user: String
        get() = prefs.getString("user", "") ?: ""
        set(v) = prefs.edit().putString("user", v.trim()).apply()

    var password: String
        get() = prefs.getString("pass", "") ?: ""
        set(v) = prefs.edit().putString("pass", v).apply()

    var autoSync: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) = prefs.edit().putBoolean("auto", v).apply()

    var lastSyncAt: Long
        get() = prefs.getLong("last", 0L)
        set(v) = prefs.edit().putLong("last", v).apply()

    /** 上次尝试同步的时刻（用来节流，别每次开页面都打一次网络） */
    var lastAttemptAt: Long
        get() = prefs.getLong("attempt", 0L)
        set(v) = prefs.edit().putLong("attempt", v).apply()

    val ready: Boolean get() = url.isNotBlank() && user.isNotBlank() && password.isNotEmpty()

    companion object {
        const val DEFAULT_URL = "https://dav.jianguoyun.com/dav/Yiju/yiju-sync.json"
    }
}
