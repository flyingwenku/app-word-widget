package com.fred.wordwidget.prefs

import android.content.Context

/**
 * 每个小组件实例一份配置：全部以 appWidgetId 为 key。
 *
 * 为什么必须按 id 分开：需求要"放多个、各自独立"，
 * 顺序游标放在全局会导致几个组件互相推进。
 */
class WidgetPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun mode(id: Int): Int = prefs.getInt("mode_$id", MODE_RANDOM)
    fun setMode(id: Int, v: Int) = prefs.edit().putInt("mode_$id", v).apply()

    /** 这个组件用哪个句子列表；-1 = 跟随第一个列表（列表被删时也能自动兜住） */
    fun listId(id: Int): Long = prefs.getLong("list_$id", -1L)
    fun setListId(id: Int, v: Long) = prefs.edit().putLong("list_$id", v).apply()

    fun fixedId(id: Int): Long = prefs.getLong("fixed_$id", -1L)
    fun setFixedId(id: Int, v: Long) = prefs.edit().putLong("fixed_$id", v).apply()

    /** 顺序模式的游标：只在点击换句时前进，周期刷新不推进（否则顺序会自己漂移） */
    fun cursor(id: Int): Int = prefs.getInt("cursor_$id", 0)
    fun setCursor(id: Int, v: Int) = prefs.edit().putInt("cursor_$id", v).apply()

    /** 当前正在显示的句子：随机模式靠它避免连续抽到同一句 */
    fun currentId(id: Int): Long = prefs.getLong("cur_$id", -1L)
    fun setCurrentId(id: Int, v: Long) = prefs.edit().putLong("cur_$id", v).apply()

    fun theme(id: Int): Int = prefs.getInt("theme_$id", THEME_AUTO)
    fun setTheme(id: Int, v: Int) = prefs.edit().putInt("theme_$id", v).apply()

    /** 组件被删除时必须清：系统会回收复用 appWidgetId，不清的话新组件会继承旧配置 */
    fun remove(id: Int) {
        prefs.edit()
            .remove("list_$id")
            .remove("mode_$id")
            .remove("fixed_$id")
            .remove("cursor_$id")
            .remove("cur_$id")
            .remove("theme_$id")
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
    }
}
