package com.fred.wordwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.TextView
import com.fred.wordwidget.model.Sentence
import com.fred.wordwidget.model.SentenceStore
import com.fred.wordwidget.prefs.WidgetPrefs

/**
 * 添加小组件时弹出的配置页（android:configure 指定）。
 *
 * 选：显示方式（随机 / 顺序 / 每日 / 固定）+ 配色（跟随壁纸 / 纸色 / 深色）。
 * 未点「完成」就返回 → 结果保持 RESULT_CANCELED，组件不会被添加。
 */
class ConfigActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var prefs: WidgetPrefs
    private lateinit var store: SentenceStore
    private lateinit var lvPick: ListView
    private var pickItems: List<Sentence> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // 关键：先给取消，用户直接返回就不会留下一个没配置的组件
        setResult(RESULT_CANCELED)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        prefs = WidgetPrefs(this)
        store = SentenceStore(this)

        val tvPick = findViewById<TextView>(R.id.tvPick)
        lvPick = findViewById(R.id.lvPick)
        pickItems = store.visible()
        lvPick.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_single_choice,
            pickItems.map { it.text }
        )
        if (pickItems.isNotEmpty()) lvPick.setItemChecked(0, true)

        val rgMode = findViewById<RadioGroup>(R.id.rgMode)
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val show = checkedId == R.id.rbFixed
            tvPick.visibility = if (show) View.VISIBLE else View.GONE
            lvPick.visibility = if (show) View.VISIBLE else View.GONE
        }

        findViewById<Button>(R.id.btnDone).setOnClickListener { save() }
    }

    private fun save() {
        val mode = when (findViewById<RadioGroup>(R.id.rgMode).checkedRadioButtonId) {
            R.id.rbSequence -> WidgetPrefs.MODE_SEQUENCE
            R.id.rbDaily -> WidgetPrefs.MODE_DAILY
            R.id.rbFixed -> WidgetPrefs.MODE_FIXED
            else -> WidgetPrefs.MODE_RANDOM
        }
        val theme = when (findViewById<RadioGroup>(R.id.rgTheme).checkedRadioButtonId) {
            R.id.thPaper -> WidgetPrefs.THEME_PAPER
            R.id.thDark -> WidgetPrefs.THEME_DARK
            else -> WidgetPrefs.THEME_AUTO
        }

        prefs.setMode(appWidgetId, mode)
        prefs.setTheme(appWidgetId, theme)
        if (mode == WidgetPrefs.MODE_FIXED) {
            val pos = lvPick.checkedItemPosition
            if (pos in pickItems.indices) prefs.setFixedId(appWidgetId, pickItems[pos].id)
        }

        // 立刻渲染一次，不用等 30 分钟周期
        sendBroadcast(
            Intent(this, SentenceWidget::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
        )

        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}
