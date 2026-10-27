package com.fred.wordwidget

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.fred.wordwidget.model.Sentence
import com.fred.wordwidget.model.SentenceStore
import com.fred.wordwidget.prefs.WidgetPrefs
import kotlin.math.floor

/**
 * 桌面句子小组件。
 *
 * 三种换句来源（方案里定的 A+B+C）：
 *   A 点击组件 → 本类收 ACTION_NEXT → 换一句（唯一 100% 可控的方式）
 *   B updatePeriodMillis = 30 分钟（系统硬下限，写更小会被忽略）
 *   C 每日一句：以"距 epoch 的天数"取模，不需要任何更新动作也能每天换
 *
 * 不做的事：监听解锁广播（API 26+ 静态注册无效），也不做前台服务常驻。
 */
class SentenceWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, awm: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(ctx, awm, it) }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        ids.forEach { WidgetPrefs(ctx).remove(it) }
    }

    /** 拖动改大小会走到这里：必须按新尺寸重新分档，否则字号不跟着变 */
    override fun onAppWidgetOptionsChanged(
        ctx: Context,
        awm: AppWidgetManager,
        id: Int,
        newOptions: Bundle
    ) {
        render(ctx, awm, id)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_NEXT) {
            val id = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                // 先推进到下一句，再画（保证渲染出来就是新的那句）
                val next = resolve(ctx, id, advance = true)
                render(ctx, AppWidgetManager.getInstance(ctx), id, next)
            }
        }
    }

    // ===== 渲染 =====

    private fun render(
        ctx: Context,
        awm: AppWidgetManager,
        id: Int,
        forced: Sentence? = null
    ) {
        val p = WidgetPrefs(ctx)
        val s = forced ?: resolve(ctx, id, advance = false)
        val views = RemoteViews(ctx.packageName, R.layout.widget_sentence)

        val (bg, textColor) = palette(ctx, p.theme(id))
        views.setInt(R.id.widgetBg, "setColorFilter", bg)
        views.setInt(R.id.tvSentence, "setTextColor", textColor)
        views.setInt(R.id.tvSource, "setTextColor", withAlpha(textColor, 0.72f))

        if (s == null) {
            views.setTextViewText(R.id.tvSentence, ctx.getString(R.string.widget_empty))
            views.setViewVisibility(R.id.tvSource, View.GONE)
            // 库空时点击 = 打开 App 去添加
            views.setOnClickPendingIntent(R.id.widgetRoot, openApp(ctx, id))
        } else {
            views.setTextViewText(R.id.tvSentence, s.text)
            if (s.source.isEmpty()) {
                views.setViewVisibility(R.id.tvSource, View.GONE)
            } else {
                views.setViewVisibility(R.id.tvSource, View.VISIBLE)
                views.setTextViewText(R.id.tvSource, s.source)
            }
            // 固定句模式下换句没意义，点击改为打开 App
            views.setOnClickPendingIntent(
                R.id.widgetRoot,
                if (p.mode(id) == WidgetPrefs.MODE_FIXED) openApp(ctx, id) else nextIntent(ctx, id)
            )
        }

        // 按实际高度分档：RemoteViews 不会自己测量文字，
        // 拖大以后字号不会自动跟着涨，必须自己按尺寸映射。
        val h = awm.getAppWidgetOptions(id)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 80)
        val sizeSp = when {
            h < 70 -> 13f
            h < 110 -> 16f
            else -> 20f
        }
        views.setTextViewTextSize(R.id.tvSentence, TypedValue.COMPLEX_UNIT_SP, sizeSp)
        views.setTextViewTextSize(R.id.tvSource, TypedValue.COMPLEX_UNIT_SP, sizeSp * 0.68f)

        awm.updateAppWidget(id, views)
    }

    // ===== 选句逻辑 =====

    private fun resolve(ctx: Context, id: Int, advance: Boolean): Sentence? {
        val store = SentenceStore(ctx)
        val visible = store.visible()
        if (visible.isEmpty()) return null

        val p = WidgetPrefs(ctx)
        val cur = p.currentId(id)
        val stillThere = visible.any { it.id == cur }

        return when (p.mode(id)) {
            WidgetPrefs.MODE_SEQUENCE -> {
                if (advance || !stillThere) {
                    val idx = p.cursor(id).coerceIn(0, visible.size - 1)
                    val pick = visible[idx]
                    p.setCursor(id, idx + 1)
                    p.setCurrentId(id, pick.id)
                    pick
                } else visible.first { it.id == cur }
            }

            WidgetPrefs.MODE_DAILY -> {
                val day = floor(System.currentTimeMillis() / 86_400_000.0).toLong().toInt()
                val idx = ((day % visible.size) + visible.size) % visible.size
                val pick = visible[idx]
                p.setCurrentId(id, pick.id)
                pick
            }

            WidgetPrefs.MODE_FIXED -> {
                val fixed = p.fixedId(id)
                visible.find { it.id == fixed } ?: visible.first().also { p.setCurrentId(id, it.id) }
            }

            else -> {   // MODE_RANDOM
                if (advance || !stillThere) {
                    // 抽新的，且尽量不抽到当前这句
                    val pool = visible.filter { it.id != cur }.ifEmpty { visible }
                    val pick = pool[(Math.random() * pool.size).toInt().coerceIn(0, pool.size - 1)]
                    p.setCurrentId(id, pick.id)
                    pick
                } else visible.first { it.id == cur }
            }
        }
    }

    // ===== 配色：跟随壁纸 =====

    @SuppressLint("NewApi")
    private fun palette(ctx: Context, theme: Int): Pair<Int, Int> {
        when (theme) {
            WidgetPrefs.THEME_PAPER -> return Color.parseColor("#B8B6B5") to Color.parseColor("#2B2B2B")
            WidgetPrefs.THEME_DARK -> return Color.parseColor("#333332") to Color.parseColor("#EFEDEB")
        }
        if (Build.VERSION.SDK_INT >= 27) {
            val wc: WallpaperColors? = try {
                WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            } catch (e: Exception) {
                null
            }
            if (wc != null) {
                val primary = wc.primaryColor.toArgb()
                // HINT_SUPPORTS_DARK_TEXT 是系统给的对比度判断，比自己算亮度可靠
                val darkText = (wc.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
                val bg = blend(primary, if (darkText) Color.WHITE else Color.BLACK, 0.35f)
                val text = if (darkText) Color.parseColor("#2B2B2B") else Color.parseColor("#F6F4F2")
                return bg to text
            }
        }
        // API <27 没取色 API，或 ROM 返回 null：回退纸色
        return Color.parseColor("#B8B6B5") to Color.parseColor("#2B2B2B")
    }

    private fun blend(a: Int, b: Int, ratioB: Float): Int {
        val r = (Color.red(a) * (1 - ratioB) + Color.red(b) * ratioB).toInt()
        val g = (Color.green(a) * (1 - ratioB) + Color.green(b) * ratioB).toInt()
        val bl = (Color.blue(a) * (1 - ratioB) + Color.blue(b) * ratioB).toInt()
        return Color.rgb(r, g, bl)
    }

    private fun withAlpha(color: Int, ratio: Float): Int =
        Color.argb(
            (Color.alpha(color) * ratio).toInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )

    // ===== 点击 =====

    private fun nextIntent(ctx: Context, id: Int): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        val i = Intent(ctx, SentenceWidget::class.java)
            .setAction(ACTION_NEXT)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        // requestCode 用 appWidgetId：多个组件各自独立，不会互相覆盖
        return PendingIntent.getBroadcast(ctx, id, i, flags)
    }

    private fun openApp(ctx: Context, id: Int): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, id + 10_000, i, flags)
    }

    private fun immutableFlag(): Int =
        // FLAG_IMMUTABLE 自 API 23 引入；targetSdk 31+ 强制要求，这里是必要的版本护栏
        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        const val ACTION_NEXT = "com.fred.wordwidget.action.NEXT"

        /** App 里改了句子库后调用：立刻刷新桌面上所有组件 */
        fun refreshAll(ctx: Context) {
            val awm = AppWidgetManager.getInstance(ctx)
            val ids = awm.getAppWidgetIds(ComponentName(ctx, SentenceWidget::class.java))
            if (ids.isEmpty()) return
            ctx.sendBroadcast(
                Intent(ctx, SentenceWidget::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }
    }
}
