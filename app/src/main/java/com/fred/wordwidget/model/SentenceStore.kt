package com.fred.wordwidget.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 句子库：App 私有目录下的 JSON（沿用项目三的存储做法，零依赖）。
 *
 * 首次启动预置一批《论语》句子，方便开箱即测；不喜欢可以在管理页批量隐藏。
 */
class SentenceStore(context: Context) {

    private val file = File(context.filesDir, "sentences.json")
    private val list: MutableList<Sentence> = load()

    private fun load(): MutableList<Sentence> {
        if (!file.exists()) {
            val seed = presets()
            save(seed)
            return seed.toMutableList()
        }
        return try {
            val arr = JSONObject(file.readText()).optJSONArray("items") ?: return presets().toMutableList()
            val out = mutableListOf<Sentence>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Sentence(
                        o.getLong("id"),
                        o.getString("t"),
                        o.optString("s", ""),
                        o.optBoolean("h", false)
                    )
                )
            }
            if (out.isEmpty()) presets().toMutableList() else out
        } catch (e: Exception) {
            presets().toMutableList()
        }
    }

    fun all(): List<Sentence> = list
    fun visible(): List<Sentence> = list.filter { !it.hidden }
    fun hidden(): List<Sentence> = list.filter { it.hidden }

    fun add(text: String, source: String): Sentence {
        val s = Sentence(nextId(), text.trim(), source.trim())
        list.add(s)
        save(list)
        return s
    }

    fun update(id: Long, text: String, source: String) {
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) {
            list[i] = list[i].copy(text = text.trim(), source = source.trim())
            save(list)
        }
    }

    /** 软删除 / 恢复：只改标记，不动数据 */
    fun setHidden(ids: Collection<Long>, hidden: Boolean) {
        val set = ids.toSet()
        if (set.isEmpty()) return
        for (i in list.indices) {
            if (list[i].id in set) list[i] = list[i].copy(hidden = hidden)
        }
        save(list)
    }

    private fun nextId(): Long = (list.maxOfOrNull { it.id } ?: 0L) + 1

    private fun save(items: List<Sentence>) {
        val arr = JSONArray()
        items.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("t", s.text)
                    put("s", s.source)
                    put("h", s.hidden)
                }
            )
        }
        file.writeText(JSONObject().put("items", arr).toString())
    }

    private fun presets(): List<Sentence> =
        PRESET.mapIndexed { i, (text, source) -> Sentence((i + 1).toLong(), text, source) }

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
