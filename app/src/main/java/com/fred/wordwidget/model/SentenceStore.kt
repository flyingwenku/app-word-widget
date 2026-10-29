package com.fred.wordwidget.model

import android.content.Context
import com.fred.wordwidget.util.StableId
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 句子库：多个**有名字的列表**，每个列表里是普通的句子（增删改查）。
 *
 * 存储：App 私有目录 lists.json（沿用项目三的 JSON 做法，零依赖）。
 * 首次启动预置一个「论语」列表，方便开箱即测。
 *
 * 文件格式 v3（多端同步的地基，规格见 win-word-widget/docs/sync-spec.md）：
 *   {"v":3,"deviceId":…,"lists":[{"uuid":…,"n":…,"updatedAt":…,"deleted":…,
 *                                 "items":[{"uuid":…,"t":…,"s":…,"updatedAt":…,"deleted":…}]}]}
 *
 * 关键改动（对比 v2）：
 *   - 身份从"列表内 max+1 的数字 id"换成 uuid —— 否则两端必然撞号
 *   - 每条都带 updatedAt（Unix 毫秒）—— 不然没法判断两边谁更新
 *   - 删除改成写墓碑（deleted=true）而不是真的删掉
 *
 * 迁移：v1.0 用的是 sentences.json（单层句子 + hidden 标记），
 * 这里会把里面**没被隐藏**的句子收进一个「论语」列表；
 * 被隐藏的那部分按"用户已经删掉了"处理，不再恢复。
 */
class SentenceStore(context: Context) {

    private val file = File(context.filesDir, "lists.json")
    private val legacyFile = File(context.filesDir, "sentences.json")
    private val lists: MutableList<SentenceList>

    /** 本机身份（同步时写进远端文件，方便排查是谁改的） */
    var deviceId: String = UUID.randomUUID().toString()
        private set

    init {
        val loaded = load()
        lists = loaded.lists
        deviceId = loaded.deviceId

        // 升级完立刻落盘。必须放在这里 —— 不能在 load() 里调 save()：
        // 那时 `lists` 还没赋值（Windows 端踩过同一个坑，一写就是空指针）
        if (loaded.needUpgrade) {
            migrate(lists, if (file.exists()) file.lastModified() else now())
            save()
        }
        dropOldTombstones()
    }

    // ===== 列表级 =====

    /** 没被删掉的列表 */
    fun lists(): List<SentenceList> = lists.filter { !it.deleted }

    /** 含墓碑在内的全部列表（同步层用） */
    fun allIncludingDeleted(): List<SentenceList> = lists

    fun list(uuid: String): SentenceList? = lists.firstOrNull { it.uuid == uuid && !it.deleted }
    fun firstList(): SentenceList? = lists().firstOrNull()

    fun addList(name: String): SentenceList {
        val l = SentenceList(
            uuid = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "新列表" },
            updatedAt = now()
        )
        lists.add(l)
        save()
        return l
    }

    fun renameList(uuid: String, name: String) {
        val l = list(uuid) ?: return
        val n = name.trim()
        if (n.isEmpty()) return
        l.name = n
        l.updatedAt = now()
        save()
    }

    /** 删除整个列表（写墓碑）。至少保留一个（组件总要有个数据源） */
    fun deleteList(uuid: String) {
        if (lists().size <= 1) return
        val l = list(uuid) ?: return
        l.deleted = true
        l.updatedAt = now()
        save()
    }

    // ===== 句子级 =====

    fun addSentence(listUuid: String, text: String, source: String): Sentence? {
        val l = list(listUuid) ?: firstList() ?: return null
        val s = Sentence(uuid = UUID.randomUUID().toString(), text = text.trim(), source = source.trim(), updatedAt = now())
        l.items.add(s)
        l.updatedAt = now()
        save()
        return s
    }

    fun updateSentence(listUuid: String, sentenceUuid: String, text: String, source: String) {
        val l = list(listUuid) ?: return
        val s = l.findAlive(sentenceUuid) ?: return
        s.text = text.trim()
        s.source = source.trim()
        s.updatedAt = now()
        l.updatedAt = now()
        save()
    }

    /** 删除一句（写墓碑，不是真删 —— 否则另一端会把它送回来） */
    fun deleteSentence(listUuid: String, sentenceUuid: String) {
        val l = list(listUuid) ?: return
        val s = l.findAlive(sentenceUuid) ?: return
        s.deleted = true
        s.updatedAt = now()
        l.updatedAt = now()
        save()
    }

    // ===== 持久化 =====

    private class Loaded(
        val lists: MutableList<SentenceList>,
        val deviceId: String,
        val needUpgrade: Boolean
    )

    private fun load(): Loaded {
        if (file.exists()) {
            try {
                val root = JSONObject(file.readText())
                val device = root.optString("deviceId").takeIf { it.isNotEmpty() }
                    ?: UUID.randomUUID().toString()
                val version = root.optInt("v", 1)
                val arr = root.optJSONArray("lists")
                if (arr != null) {
                    val out = mutableListOf<SentenceList>()
                    for (i in 0 until arr.length()) {
                        val lo = arr.getJSONObject(i)
                        val items = mutableListOf<Sentence>()
                        val ia = lo.optJSONArray("items")
                        if (ia != null) {
                            for (j in 0 until ia.length()) {
                                val o = ia.getJSONObject(j)
                                items.add(
                                    Sentence(
                                        id = o.optLong("id", 0L),
                                        uuid = o.optString("uuid", ""),
                                        text = o.optString("t", ""),
                                        source = o.optString("s", ""),
                                        updatedAt = o.optLong("updatedAt", 0L),
                                        deleted = o.optBoolean("deleted", false)
                                    )
                                )
                            }
                        }
                        out.add(
                            SentenceList(
                                id = lo.optLong("id", 0L),
                                uuid = lo.optString("uuid", ""),
                                name = lo.optString("n", "列表"),
                                items = items,
                                updatedAt = lo.optLong("updatedAt", 0L),
                                deleted = lo.optBoolean("deleted", false)
                            )
                        )
                    }
                    if (out.isNotEmpty()) return Loaded(out, device, version < FORMAT_VERSION)
                }
            } catch (e: Exception) {
                // 文件坏了不能直接崩：退回预置
            }
        }

        val legacy = migrateLegacy()
        if (legacy != null) return Loaded(legacy, deviceId, true)
        return Loaded(presets(), deviceId, true)
    }

    /** v1.0 的 sentences.json → 列表格式 */
    private fun migrateLegacy(): MutableList<SentenceList>? {
        if (!legacyFile.exists()) return null
        return try {
            val arr = JSONObject(legacyFile.readText()).optJSONArray("items") ?: return null
            val items = mutableListOf<Sentence>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optBoolean("h", false)) continue       // 隐藏过的 = 用户已删，不恢复
                items.add(Sentence(id = o.optLong("id", 0L), text = o.optString("t", ""), source = o.optString("s", "")))
            }
            if (items.isEmpty()) return null
            val stamp = legacyFile.lastModified()
            items.forEach { it.updatedAt = stamp }
            mutableListOf(
                SentenceList(id = 1L, name = "论语", items = items, updatedAt = stamp)
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * v2 → v3：给每条数据补 uuid / updatedAt / deleted。
     * 预置的《论语》14 条走**稳定 uuid**，这样两端迁移完还是同一批 id，
     * 第一次同步合并得到 14 条而不是 28 条。
     */
    private fun migrate(target: MutableList<SentenceList>, stamp: Long) {
        for (l in target) {
            // 只有"确实是预置原句"的列表才用稳定 uuid；用户自己建的用随机 uuid ——
            // 否则两端各自建了个同名列表，会被当成同一个合并掉，丢数据
            val presetList = isPreset(l)
            if (l.uuid.isEmpty()) {
                l.uuid = if (presetList) StableId.list(l.name) else UUID.randomUUID().toString()
            }
            if (l.updatedAt <= 0L) l.updatedAt = stamp

            l.items.forEachIndexed { i, s ->
                if (s.uuid.isEmpty()) {
                    s.uuid = if (presetList) StableId.preset(l.name, i) else UUID.randomUUID().toString()
                }
                if (s.updatedAt <= 0L) s.updatedAt = stamp
            }
        }
    }

    private fun isPreset(l: SentenceList): Boolean =
        l.name == "论语" && l.items.size == PRESET.size && l.items.map { it.text } == PRESET.map { it.first }

    /** 墓碑保留 30 天，过期彻底丢掉，免得文件无限膨胀 */
    private fun dropOldTombstones() {
        val deadline = now() - TOMBSTONE_KEEP_MS
        lists.forEach { l ->
            val it = l.items.iterator()
            while (it.hasNext()) {
                val s = it.next()
                if (s.deleted && s.updatedAt < deadline) it.remove()
            }
        }
        val li = lists.iterator()
        while (li.hasNext()) {
            val l = li.next()
            if (l.deleted && l.updatedAt < deadline) li.remove()
        }
    }

    private fun save() {
        file.writeText(serialize())
    }

    /** 落成同步用的那份文本（远端传的就是这个） */
    fun serialize(): String = toJson(lists, deviceId)

    /** 同步合并完之后整体换掉（会落盘） */
    fun replaceAll(merged: List<SentenceList>) {
        lists.clear()
        lists.addAll(merged)
        save()
    }

    companion object {
        const val FORMAT_VERSION = 3
        private val TOMBSTONE_KEEP_MS = 30L * 24 * 60 * 60 * 1000

        fun now(): Long = System.currentTimeMillis()

        private fun presets(): MutableList<SentenceList> {
            val stamp = now()
            return mutableListOf(
                SentenceList(
                    id = 1L,
                    uuid = StableId.list("论语"),
                    name = "论语",
                    items = PRESET.mapIndexed { i, (text, source) ->
                        Sentence(
                            id = (i + 1).toLong(),
                            uuid = StableId.preset("论语", i),
                            text = text,
                            source = source,
                            updatedAt = stamp
                        )
                    }.toMutableList(),
                    updatedAt = stamp
                )
            )
        }

        private fun toJson(items: List<SentenceList>, deviceId: String): String {
            val arr = JSONArray()
            items.forEach { l ->
                val ia = JSONArray()
                l.items.forEach { s ->
                    ia.put(
                        JSONObject().apply {
                            put("uuid", s.uuid)
                            put("id", s.id)
                            put("t", s.text)
                            put("s", s.source)
                            put("updatedAt", s.updatedAt)
                            put("deleted", s.deleted)
                        }
                    )
                }
                arr.put(
                    JSONObject().apply {
                        put("uuid", l.uuid)
                        put("id", l.id)
                        put("n", l.name)
                        put("updatedAt", l.updatedAt)
                        put("deleted", l.deleted)
                        put("items", ia)
                    }
                )
            }
            return JSONObject().apply {
                put("v", FORMAT_VERSION)
                put("deviceId", deviceId)
                put("lists", arr)
            }.toString()
        }

        /** 解析另一端传来的文本（认不出返回 null，交给调用方当"没有远端数据"处理） */
        fun parse(json: String): MutableList<SentenceList>? = try {
            val arr = JSONObject(json).optJSONArray("lists") ?: return null
            val out = mutableListOf<SentenceList>()
            for (i in 0 until arr.length()) {
                val lo = arr.getJSONObject(i)
                val items = mutableListOf<Sentence>()
                val ia = lo.optJSONArray("items")
                if (ia != null) {
                    for (j in 0 until ia.length()) {
                        val o = ia.getJSONObject(j)
                        items.add(
                            Sentence(
                                id = o.optLong("id", 0L),
                                uuid = o.optString("uuid", ""),
                                text = o.optString("t", ""),
                                source = o.optString("s", ""),
                                updatedAt = o.optLong("updatedAt", 0L),
                                deleted = o.optBoolean("deleted", false)
                            )
                        )
                    }
                }
                out.add(
                    SentenceList(
                        id = lo.optLong("id", 0L),
                        uuid = lo.optString("uuid", ""),
                        name = lo.optString("n", "列表"),
                        items = items,
                        updatedAt = lo.optLong("updatedAt", 0L),
                        deleted = lo.optBoolean("deleted", false)
                    )
                )
            }
            out
        } catch (e: Exception) {
            null
        }

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
