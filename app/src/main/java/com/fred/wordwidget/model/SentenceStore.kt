package com.fred.wordwidget.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 句子库：多个**有名字的列表**，每个列表里是普通的句子（增删改查，删了就是真删）。
 *
 * 存储：App 私有目录 lists.json（沿用项目三的 JSON 做法，零依赖）。
 * 首次启动预置一个「论语」列表，方便开箱即测。
 *
 * 迁移：v1.0 用的是 sentences.json（单层句子 + hidden 标记），
 * 这里会把里面**没被隐藏**的句子收进一个「论语」列表；
 * 被隐藏的那部分按"用户已经删掉了"处理，不再恢复。
 */
class SentenceStore(context: Context) {

    private val dir = context.filesDir
    private val file = File(dir, "lists.json")
    private val legacyFile = File(dir, "sentences.json")
    private val lists: MutableList<SentenceList> = load()

    // ===== 列表级 =====

    fun lists(): List<SentenceList> = lists
    fun list(id: Long): SentenceList? = lists.find { it.id == id }
    fun firstList(): SentenceList? = lists.firstOrNull()

    fun addList(name: String): SentenceList {
        val l = SentenceList(nextListId(), name.trim().ifEmpty { "新列表" })
        lists.add(l)
        save()
        return l
    }

    fun renameList(id: Long, name: String) {
        val l = list(id) ?: return
        val n = name.trim()
        if (n.isEmpty()) return
        l.name = n
        save()
    }

    /** 删除整个列表。至少保留一个（组件总要有个数据源） */
    fun deleteList(id: Long) {
        if (lists.size <= 1) return
        // 不用 removeIf：那是 Java 8 的 Collection 默认方法，API 24 才有（本项目 minSdk 21）
        val i = lists.indexOfFirst { it.id == id }
        if (i >= 0) {
            lists.removeAt(i)
            save()
        }
    }

    // ===== 句子级 =====

    fun addSentence(listId: Long, text: String, source: String): Sentence? {
        val l = list(listId) ?: firstList() ?: return null
        val s = Sentence(nextItemId(l), text.trim(), source.trim())
        l.items.add(s)
        save()
        return s
    }

    fun updateSentence(listId: Long, sentenceId: Long, text: String, source: String) {
        val l = list(listId) ?: return
        val i = l.items.indexOfFirst { it.id == sentenceId }
        if (i < 0) return
        l.items[i] = l.items[i].copy(text = text.trim(), source = source.trim())
        save()
    }

    fun deleteSentence(listId: Long, sentenceId: Long) {
        val l = list(listId) ?: return
        val i = l.items.indexOfFirst { it.id == sentenceId }
        if (i >= 0) {
            l.items.removeAt(i)
            save()
        }
    }

    // ===== 持久化 =====

    private fun load(): MutableList<SentenceList> {
        if (file.exists()) {
            return try {
                val arr = JSONObject(file.readText()).optJSONArray("lists") ?: return presets()
                val out = mutableListOf<SentenceList>()
                for (i in 0 until arr.length()) {
                    val lo = arr.getJSONObject(i)
                    val items = mutableListOf<Sentence>()
                    val ia = lo.optJSONArray("items")
                    if (ia != null) {
                        for (j in 0 until ia.length()) {
                            val o = ia.getJSONObject(j)
                            items.add(Sentence(o.getLong("id"), o.getString("t"), o.optString("s", "")))
                        }
                    }
                    out.add(SentenceList(lo.getLong("id"), lo.optString("n", "列表"), items))
                }
                if (out.isEmpty()) presets() else out
            } catch (e: Exception) {
                presets()
            }
        }
        return migrateLegacy() ?: presets()
    }

    private fun migrateLegacy(): MutableList<SentenceList>? {
        if (!legacyFile.exists()) return null
        return try {
            val arr = JSONObject(legacyFile.readText()).optJSONArray("items") ?: return null
            val items = mutableListOf<Sentence>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optBoolean("h", false)) continue       // 隐藏过的 = 用户已删，不恢复
                items.add(Sentence(o.getLong("id"), o.getString("t"), o.optString("s", "")))
            }
            if (items.isEmpty()) return null
            val l = SentenceList(1L, "论语", items)
            val out = mutableListOf(l)
            save(out)                                         // 写成新格式；旧文件保留，方便回滚
            out
        } catch (e: Exception) {
            null
        }
    }

    private fun save(items: List<SentenceList> = lists) {
        val arr = JSONArray()
        items.forEach { l ->
            val ia = JSONArray()
            l.items.forEach { s ->
                ia.put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("t", s.text)
                        put("s", s.source)
                    }
                )
            }
            arr.put(
                JSONObject().apply {
                    put("id", l.id)
                    put("n", l.name)
                    put("items", ia)
                }
            )
        }
        file.writeText(JSONObject().put("lists", arr).toString())
    }

    private fun nextListId(): Long = (lists.maxOfOrNull { it.id } ?: 0L) + 1
    private fun nextItemId(l: SentenceList): Long = (l.items.maxOfOrNull { it.id } ?: 0L) + 1

    private fun presets(): MutableList<SentenceList> =
        mutableListOf(
            SentenceList(
                1L,
                "论语",
                PRESET.mapIndexed { i, (text, source) -> Sentence((i + 1).toLong(), text, source) }
                    .toMutableList()
            )
        )

    companion object {
        /** 预置《论语》：开箱即可看到效果，不需要先自己录入 */
        private val PRESET = listOf(
            "学而时习之，不亦说乎？" to "《论语·学而》",
            "温故而知新，可以为师矣。" to "《论语·为政》",
            "学而不思则罔，思而不学则殆。" to "《论语·为政》",
            "知之为知之，不知为不知，是知也。" to "《论语·为政》",
            "三人行，必有我师焉。" to "《论语·述而》",
            "知之者不如好之者，好之者不如乐之者。" to "《论语·雍也》",
            "己所不欲，勿施于人。" to "《论语·颜渊》",
            "君子坦荡荡，小人长戚戚。" to "《论语·述而》",
            "工欲善其事，必先利其器。" to "《论语·卫灵公》",
            "吾日三省吾身。" to "《论语·学而》",
            "岁寒，然后知松柏之后凋也。" to "《论语·子罕》",
            "见贤思齐焉，见不贤而内自省也。" to "《论语·里仁》",
            "三军可夺帅也，匹夫不可夺志也。" to "《论语·子罕》",
            "不患人之不己知，患不知人也。" to "《论语·学而》"
        )
    }
}
