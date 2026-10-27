package com.fred.wordwidget

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.fred.wordwidget.model.Sentence
import com.fred.wordwidget.model.SentenceStore

/**
 * 管理页：句子的增删改，**以及唯一的"删除"入口**——批量隐藏 / 批量恢复。
 *
 * 小组件上不放任何按钮（包括删除按钮），删除全部在这里做（用户已确认）。
 */
class MainActivity : Activity() {

    private lateinit var store: SentenceStore
    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var btnAction: Button
    private lateinit var btnToggle: Button
    private lateinit var etSentence: EditText
    private lateinit var etSource: EditText
    private lateinit var adapter: Adapter

    private val selected = HashSet<Long>()
    private var showingHidden = false
    private var items: List<Sentence> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = SentenceStore(this)
        listView = findViewById(R.id.listView)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnAction = findViewById(R.id.btnAction)
        btnToggle = findViewById(R.id.btnToggle)
        etSentence = findViewById(R.id.etSentence)
        etSource = findViewById(R.id.etSource)

        findViewById<TextView>(R.id.tvVersion).text =
            getString(R.string.settings_version, BuildConfig.VERSION_NAME)

        adapter = Adapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> editDialog(items[position]) }

        findViewById<Button>(R.id.btnAdd).setOnClickListener { addSentence() }
        btnAction.setOnClickListener { applyBatch() }
        btnToggle.setOnClickListener {
            showingHidden = !showingHidden
            selected.clear()
            reload()
        }

        reload()
    }

    private fun addSentence() {
        val text = etSentence.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.need_text, Toast.LENGTH_SHORT).show()
            return
        }
        store.add(text, etSource.text.toString())
        etSentence.setText("")
        etSource.setText("")
        reload()
        SentenceWidget.refreshAll(this)
    }

    /** 批量隐藏（可见列表）或批量恢复（已隐藏列表） */
    private fun applyBatch() {
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.none_selected, Toast.LENGTH_SHORT).show()
            return
        }
        val n = selected.size
        store.setHidden(selected, !showingHidden)
        selected.clear()
        reload()
        SentenceWidget.refreshAll(this)
        Toast.makeText(
            this,
            if (showingHidden) getString(R.string.restored_done, n) else getString(R.string.hidden_done, n),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun reload() {
        items = if (showingHidden) store.hidden() else store.visible()
        adapter.notifyDataSetChanged()

        listView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        btnAction.text = if (showingHidden) getString(R.string.restore_selected) else getString(R.string.hide_selected)
        btnToggle.text = if (showingHidden) getString(R.string.show_visible) else getString(R.string.show_hidden, store.hidden().size)
    }

    private fun editDialog(s: Sentence) {
        val input = EditText(this).apply {
            setText(s.text)
            setSingleLine(false)
            maxLines = 3
            setSelection(text.length)
        }
        val src = EditText(this).apply { setText(s.source) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
            addView(src)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                store.update(s.id, input.text.toString(), src.text.toString())
                reload()
                SentenceWidget.refreshAll(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Sentence = items[position]
        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_sentence, parent, false)
            val s = items[position]

            val cb = v.findViewById<CheckBox>(R.id.cbSel)
            cb.setOnCheckedChangeListener(null)          // 复用行时先解绑，避免错乱
            cb.isChecked = selected.contains(s.id)
            cb.setOnCheckedChangeListener { _, on ->
                if (on) selected.add(s.id) else selected.remove(s.id)
            }

            v.findViewById<TextView>(R.id.tvText).text = s.text
            val src = v.findViewById<TextView>(R.id.tvSource)
            src.text = s.source
            src.visibility = if (s.source.isEmpty()) View.GONE else View.VISIBLE
            return v
        }
    }
}
