package com.fred.wordwidget.prefs

import android.content.Context
import com.fred.wordwidget.model.SentenceStore

/**
 * 每个小组件实例一份配置：全部以 appWidgetId 为 key。
 *
 * 为什么必须按 id 分开：需求要"放多个、各自独立"，
 * 顺序游标放在全局会导致几个组件互相推进。
 *
 * v1.4 起"用哪个列表 / 固定句 / 当前句"改成存 **uuid**：
 * 以前存的是数字 id，那是各端自己记账的，同步之后不是同一个东西
 * （见 win-word-widget/docs/sync-spec.md）。
 */
class WidgetPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun mode(id: Int): Int = prefs.getInt("mode_$id", MODE_RANDOM)
    fun setMode(id: Int, v: Int) = prefs.edit().putInt("mode_$id", v).apply()

    /** 这个组件用哪个句子列表（uuid）；空 = 跟随第一个列表（列表被删时也能自动兜住） */
    fun listUuid(id: Int): String = prefs.getString("list_uuid_$id", "") ?: ""
    fun setListUuid(id: Int, v: String) = prefs.edit().putString("list_uuid_$id", v).apply()

    fun fixedUuid(id: Int): String = prefs.getString("fixed_uuid_$id", "") ?: ""
    fun setFixedUuid(id: Int, v: String) = prefs.edit().putString("fixed_uuid_$id", v).apply()

    /** 顺序模式的游标：只在点击换句时前进，周期刷新不推进（否则顺序会自己漂移） */
    fun cursor(id: Int): Int = prefs.getInt("cursor_$id", 0)
    fun setCursor(id: Int, v: Int) = prefs.edit().putInt("cursor_$id", v).apply()

    /** 当前正在显示的句子（uuid）：随机模式靠它避免连续抽到同一句 */
    fun currentUuid(id: Int): String = prefs.getString("cur_uuid_$id", "") ?: ""
    fun setCurrentUuid(id: Int, v: String) = prefs.edit().putString("cur_uuid_$id", v).apply()

    fun theme(id: Int): Int = prefs.getInt("theme_$id", THEME_AUTO)
    fun setTheme(id: Int, v: Int) = prefs.edit().putInt("theme_$id", v).apply()

    /**
     * 老配置里存的是数字 id，句子库升级成 uuid 之后要翻译过来 ——
     * 必须拿着句子库才能翻，所以由调用方在 store 建好之后调一次。
     */
    fun migrateToUuid(store: SentenceStore) {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return

        val ids = prefs.all.keys.mapNotNull { key ->
            LEGACY_KEY.matchEntire(key)?.groupValues?.get(1)?.toIntOrNull()
        }.distinct()

        val e = prefs.edit()
        // 数字 id 只在"同一个列表内"有意义，所以按老配置选的那个列表去翻
        ids.forEach { id ->
            val listId = prefs.getLong("list_$id", -1L)
            if (listId <= 0L) return@forEach
            val l = store.allIncludingDeleted().firstOrNull { it.id == listId } ?: return@forEach

            e.putString("list_uuid_$id", l.uuid)
            val fixed = prefs.getLong("fixed_$id", -1L)
            l.items.firstOrNull { it.id == fixed }?.let { e.putString("fixed_uuid_$id", it.uuid) }
            val cur = prefs.getLong("cur_$id", -1L)
            l.items.firstOrNull { it.id == cur }?.let { e.putString("cur_uuid_$id", it.uuid) }
        }

        e.putBoolean(KEY_MIGRATED, true).apply()
    }

    /** 组件被删除时必须清：系统会回收复用 appWidgetId，不清的话新组件会继承旧配置 */
    fun remove(id: Int) {
        prefs.edit()
            .remove("list_$id")
            .remove("mode_$id")
            .remove("fixed_$id")
            .remove("cursor_$id")
            .remove("cur_$id")
            .remove("theme_$id")
            .remove("list_uuid_$id")
            .remove("fixed_uuid_$id")
            .remove("cur_uuid_$id")
            .apply()
    }

    companion object {
        const val MODE_RANDOM = 0
        const val MODE_SEQUENCE = 1
        const val MODE_DAILY = 2
        const val MODE_FIXED = 3

        const val THEME_AUTO = 0
        const val THEME_PAPER = 1
        const val THEME_DARK = 2

        private const val KEY_MIGRATED = "uuid_migrated"
        private val LEGACY_KEY = Regex("^(?:list|fixed|cur)_(\\d+)$")
    }
}
