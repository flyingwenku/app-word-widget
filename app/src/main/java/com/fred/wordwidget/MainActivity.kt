package com.fred.wordwidget

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.fred.wordwidget.model.Sentence
import com.fred.wordwidget.model.SentenceStore

/**
 * 管理页：**多列表 + 句子增删改查**。
 *
 * v1.2 起不再有"隐藏 / 恢复"那套软删除——内容就是普通的增删改查，删了就是真删。
 * 顶部的下拉框切换列表，「管理列表」里可以新建 / 重命名 / 删除列表；
 * 桌面上每个组件各自选一个列表（配置页里选）。
 */
class MainActivity : Activity() {

    private lateinit var store: SentenceStore
    private lateinit var spinner: Spinner
    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var etSentence: EditText
    private lateinit var etSource: EditText
    private lateinit var adapter: Adapter

    private var currentId: Long = -1L          // 当前列表
    private var items: List<Sentence> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = SentenceStore(this)
        spinner = findViewById(R.id.spinnerLists)
        listView = findViewById(R.id.listView)
        tvEmpty = findViewById(R.id.tvEmpty)
        etSentence = findViewById(R.id.etSentence)
        etSource = findViewById(R.id.etSource)

        findViewById<TextView>(R.id.tvVersion).text =
            getString(R.string.settings_version, BuildConfig.VERSION_NAME)

        adapter = Adapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> editDialog(items[position]) }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val l = store.lists().getOrNull(pos) ?: return
                currentId = l.id
                reload()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        findViewById<Button>(R.id.btnAdd).setOnClickListener { addSentence() }
        findViewById<Button>(R.id.btnLists).setOnClickListener { manageLists() }

        refreshSpinner()
    }

    // ===== 列表 =====

    private fun refreshSpinner(selectId: Long = currentId) {
        val lists = store.lists()
        // 收起态/弹层行都用自家布局：系统下拉行在荣耀等 ROM 上会吃 ROM 主题色（玫红高亮）
        val ad = ArrayAdapter(this, R.layout.item_spinner_closed, lists.map { it.name })
        ad.setDropDownViewResource(R.layout.item_spinner_row)
        spinner.adapter = ad
        val idx = lists.indexOfFirst { it.id == selectId }
        val pos = if (idx >= 0) idx else 0
        spinner.setSelection(pos)
        currentId = lists.getOrNull(pos)?.id ?: -1L
        reload()
    }

    private fun manageLists() {
        val options = arrayOf(
            getString(R.string.list_new),
            getString(R.string.list_rename),
            getString(R.string.list_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.list_manage)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> newListDialog()
                    1 -> renameDialog()
                    2 -> deleteListDialog()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun newListDialog() {
        val input = EditText(this).apply { hint = getString(R.string.list_name_hint) }
        AlertDialog.Builder(this)
            .setTitle(R.string.list_new)
            .setView(padded(input))
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast(R.string.list_need_name)
                    return@setPositiveButton
                }
                val l = store.addList(name)
                SentenceWidget.refreshAll(this)
                refreshSpinner(l.id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renameDialog() {
        val l = store.list(currentId) ?: return
        val input = EditText(this).apply {
            setText(l.name)
            setSelection(l.name.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.list_rename)
            .setView(padded(input))
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast(R.string.list_need_name)
                    return@setPositiveButton
                }
                store.renameList(l.id, name)
                SentenceWidget.refreshAll(this)
                refreshSpinner(l.id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteListDialog() {
        val lists = store.lists()
        val l = store.list(currentId) ?: return
        if (lists.size <= 1) {
            toast(R.string.list_last_one)
            return
        }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.list_delete_confirm, l.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                store.deleteList(l.id)
                SentenceWidget.refreshAll(this)
                refreshSpinner(store.firstList()?.id ?: -1L)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ===== 句子 =====

    private fun addSentence() {
        val text = etSentence.text.toString().trim()
        if (text.isEmpty()) {
            toast(R.string.need_text)
            return
        }
        store.addSentence(currentId, text, etSource.text.toString())
        etSentence.setText("")
        etSource.setText("")
        reload()
        SentenceWidget.refreshAll(this)
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
            addView(input)
            addView(src)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_title)
            .setView(padded(box))
            .setPositiveButton(R.string.save) { _, _ ->
                store.updateSentence(currentId, s.id, input.text.toString(), src.text.toString())
                reload()
                SentenceWidget.refreshAll(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(s: Sentence) {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                store.deleteSentence(currentId, s.id)
                reload()
                SentenceWidget.refreshAll(this)
                toast(R.string.deleted)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun reload() {
        val l = store.list(currentId) ?: store.firstList()
        currentId = l?.id ?: -1L
        items = l?.items?.toList() ?: emptyList()
        adapter.notifyDataSetChanged()

        listView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    // ===== 小工具 =====

    private fun padded(v: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        addView(v)
    }

    private fun toast(resId: Int) =
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Sentence = items[position]
        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_sentence, parent, false)
            val s = items[position]

            v.findViewById<TextView>(R.id.tvText).text = s.text
            val src = v.findViewById<TextView>(R.id.tvSource)
            src.text = s.source
            src.visibility = if (s.source.isEmpty()) View.GONE else View.VISIBLE

            v.findViewById<Button>(R.id.btnEdit).setOnClickListener { editDialog(s) }
            v.findViewById<Button>(R.id.btnDel).setOnClickListener { confirmDelete(s) }
            return v
        }
    }
}
