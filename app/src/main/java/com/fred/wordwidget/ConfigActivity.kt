package com.fred.wordwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import com.fred.wordwidget.model.Sentence
import com.fred.wordwidget.model.SentenceList
import com.fred.wordwidget.model.SentenceStore
import com.fred.wordwidget.prefs.WidgetPrefs

/**
 * 添加小组件时弹出的配置页（android:configure 指定）。
 *
 * 选：用哪个列表 + 显示方式（随机 / 顺序 / 每日 / 固定）+ 配色。
 * 未点「完成」就返回 → 结果保持 RESULT_CANCELED，组件不会被添加。
 */
class ConfigActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var prefs: WidgetPrefs
    private lateinit var store: SentenceStore
    private lateinit var spList: Spinner
    private lateinit var lvPick: ListView
    private var lists: List<SentenceList> = emptyList()
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
        prefs.migrateToUuid(store)

        spList = findViewById(R.id.spList)
        lvPick = findViewById(R.id.lvPick)

        lists = store.lists()
        val ad = ArrayAdapter(this, R.layout.item_spinner_closed, lists.map { it.name })
        ad.setDropDownViewResource(R.layout.item_spinner_row)
        spList.adapter = ad
        val savedUuid = prefs.listUuid(appWidgetId)
        val idx = lists.indexOfFirst { it.uuid == savedUuid }
        spList.setSelection(if (idx >= 0) idx else 0)

        spList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                refreshPick(pos)          // 换列表 → 固定句候选也要跟着换
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        refreshPick(spList.selectedItemPosition)

        val tvPick = findViewById<TextView>(R.id.tvPick)
        val rgMode = findViewById<RadioGroup>(R.id.rgMode)
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val show = checkedId == R.id.rbFixed
            tvPick.visibility = if (show) View.VISIBLE else View.GONE
            lvPick.visibility = if (show) View.VISIBLE else View.GONE
        }

        findViewById<Button>(R.id.btnDone).setOnClickListener { save() }
    }

    /** 固定句候选 = 当前选中列表里没被删掉的句子 */
    private fun refreshPick(pos: Int) {
        pickItems = lists.getOrNull(pos)?.alive() ?: emptyList()
        lvPick.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_single_choice,
            pickItems.map { it.text }
        )
        if (pickItems.isNotEmpty()) lvPick.setItemChecked(0, true)
    }

    private fun save() {
        val listUuid = lists.getOrNull(spList.selectedItemPosition)?.uuid ?: ""
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

        prefs.setListUuid(appWidgetId, listUuid)
        prefs.setMode(appWidgetId, mode)
        prefs.setTheme(appWidgetId, theme)
        if (mode == WidgetPrefs.MODE_FIXED) {
            val pos = lvPick.checkedItemPosition
            if (pos in pickItems.indices) prefs.setFixedUuid(appWidgetId, pickItems[pos].uuid)
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
