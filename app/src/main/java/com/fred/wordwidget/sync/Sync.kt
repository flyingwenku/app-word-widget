package com.fred.wordwidget.sync

import android.content.Context
import com.fred.wordwidget.R
import com.fred.wordwidget.model.Sentence
import com.fred.wordwidget.model.SentenceList
import com.fred.wordwidget.model.SentenceStore
import com.fred.wordwidget.prefs.SyncPrefs
import java.util.UUID

/**
 * 多端同步（坚果云 WebDAV 单文件）。规格见知识库《多端同步规格》，
 * 踩坑见《多端同步踩坑与解法（通用）》。
 *
 * 流程：**一次 GET → 按 uuid 合并 → 有变化才一次 PUT**。
 * 不用 ETag / If-Match / 重试：坚果云 ETag 实测不可信（GET 刚拿到的值原样 PUT 会 412），
 * 带锁和重试都只是白绕一圈；并发窗口只有零点几秒，靠合并的幂等性兜底即可。
 * 这与新项目（桌面进度条）的同步策略一致。
 *
 * **必须在后台线程调用**：里面全是网络 + 文件读写。
 *
 * 两个刻意的设计：
 *   - 合并结果跟远端**一字不差**时就不 PUT，否则会陷入"同步 → 触发保存 → 又同步"的空转。
 *   - 变化判断**只比 lists**，不能拿整个文件比：文件里还有 deviceId（"最后谁写的"），
 *     两台设备各不相同，比它会导致一来一回互相覆盖。
 */
class Sync(private val context: Context, private val store: SentenceStore) {

    /** 同步结果：一句话结论。失败时 message 里带真实 HTTP 码或异常信息 */
    data class SyncResult(val message: String)

    private val running = Any()

    fun syncNow(): SyncResult {
        // 同时只允许一个同步在跑。连点几下（或打开页面时的自动同步跟手动那一下撞上）
        // 会让两个线程各拿一份数据去写，白白多一次 PUT。
        synchronized(running) {
            if (busy) return SyncResult(context.getString(R.string.sync_in_progress))
            busy = true
        }

        try {
            return doSync()
        } finally {
            busy = false
        }
    }

    @Volatile
    private var busy = false

    private fun doSync(): SyncResult {
        val cfg = SyncPrefs(context)
        if (!cfg.ready) return SyncResult(context.getString(R.string.sync_need_account))
        cfg.lastAttemptAt = System.currentTimeMillis()

        val dav = WebDav(cfg.url, cfg.user, cfg.password)
        val local = store.allIncludingDeleted()

        return try {
            val fetched = dav.fetch()
            val body = fetched.content

            // 远端还没有文件 → 把本地这份直接放上去（本地也是空库就别写一个空文件）
            if (!fetched.exists || body == null) {
                if (local.isEmpty()) {
                    return result(cfg, context.getString(R.string.sync_empty_no_upload))
                }
                dav.put(serialize(local, store.deviceId))
                return result(cfg, context.getString(R.string.sync_first_upload))
            }

            val remote = SentenceStore.parse(body) ?: mutableListOf()
            val merged = merge(local, remote)

            val mergedPayload = SentenceStore.serializeLists(merged)
            val remotePayload = SentenceStore.serializeLists(remote)
            val localPayload = SentenceStore.serializeLists(local)

            // 合并结果跟远端一样 → 没必要写远端；本地落后就把合并结果收下
            if (mergedPayload == remotePayload) {
                if (mergedPayload != localPayload) store.replaceAll(merged)
                return result(cfg, context.getString(R.string.sync_up_to_date))
            }

            dav.put(serialize(merged, store.deviceId))
            store.replaceAll(merged)
            result(cfg, context.getString(R.string.sync_done))
        } catch (e: Exception) {
            result(cfg, context.getString(R.string.sync_failed, e.message ?: ""))
        }
    }

    private fun result(cfg: SyncPrefs, msg: String): SyncResult {
        cfg.lastSyncAt = System.currentTimeMillis()
        return SyncResult(msg)
    }

    companion object {

        /**
         * 合并结果序列化成与 [SentenceStore] 一致的格式（同步层要用）。
         * lists / items 都按 uuid 升序写出，保证 wire format 字节级确定，
         * 两端不会因为"排列顺序不同"而反复认为内容变了。
         */
        private fun serialize(lists: List<SentenceList>, deviceId: String): String {
            val arr = org.json.JSONArray()
            lists.sortedBy { it.uuid }.forEach { l ->
                val ia = org.json.JSONArray()
                l.items.sortedBy { it.uuid }.forEach { s ->
                    ia.put(
                        org.json.JSONObject().apply {
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
                    org.json.JSONObject().apply {
                        put("uuid", l.uuid)
                        put("id", l.id)
                        put("n", l.name)
                        put("updatedAt", l.updatedAt)
                        put("deleted", l.deleted)
                        put("items", ia)
                    }
                )
            }
            return org.json.JSONObject().apply {
                put("v", SentenceStore.FORMAT_VERSION)
                put("deviceId", deviceId)
                put("lists", arr)
            }.toString()
        }

        /**
         * 按 uuid 对齐合并。**幂等**，且结果固定按 uuid 升序：
         * 如果按"我这边原来的顺序"排，两端会各自认为跟对方不一样，
         * 轮流把同样的数据按自己的顺序重写一遍，永远在空转。
         */
        fun merge(mine: List<SentenceList>, theirs: List<SentenceList>): MutableList<SentenceList> {
            val out = mutableListOf<SentenceList>()

            val uuids = (mine + theirs).map { it.uuid }
                .filter { it.isNotEmpty() }.distinct().sorted()
            for (uuid in uuids) {
                val m = mine.firstOrNull { it.uuid == uuid }
                val t = theirs.firstOrNull { it.uuid == uuid }

                val winner = when {
                    m == null -> copyList(t!!)
                    t == null -> copyList(m)
                    newer(m, t) -> copyList(m)
                    else -> copyList(t)
                }

                // 条目要合并**两边**的，不能只留胜者那边的：
                // 电脑上加了 A、手机上加了 B，两边都得在
                val items = mergeItems(m?.items ?: emptyList(), t?.items ?: emptyList())
                winner.items.clear()
                winner.items.addAll(items)
                winner.updatedAt = maxOf(winner.updatedAt, items.maxOfOrNull { it.updatedAt } ?: 0L)

                out.add(winner)
            }
            return out
        }

        private fun mergeItems(mine: List<Sentence>, theirs: List<Sentence>): List<Sentence> {
            val out = mutableListOf<Sentence>()
            val uuids = (mine + theirs).map { it.uuid }
                .filter { it.isNotEmpty() }.distinct().sorted()
            for (uuid in uuids) {
                val m = mine.firstOrNull { it.uuid == uuid }
                val t = theirs.firstOrNull { it.uuid == uuid }
                out.add(
                    when {
                        m == null -> copySentence(t!!)
                        t == null -> copySentence(m)
                        newer(m, t) -> copySentence(m)
                        else -> copySentence(t)
                    }
                )
            }
            return out
        }

        /**
         * 时间戳完全相同时的定序键。**两端必须逐字一致**：
         * Windows 端用 `Name + "|" + (Deleted ? "1" : "0")` 做 String.CompareOrdinal
         * 并挑**较大**的那个；这里必须一样。否则同一毫秒两端各改一次会各选各的，永远收敛不了。
         */
        fun tieKeyList(x: SentenceList): String =
            x.name + "|" + (if (x.deleted) "1" else "0")

        fun tieKeySentence(x: Sentence): String =
            x.text + "|" + x.source + "|" + (if (x.deleted) "1" else "0")

        /** 最后写入者胜；时间戳完全一样时按上面的键挑较大者 */
        private fun newer(a: SentenceList, b: SentenceList): Boolean {
            if (a.updatedAt != b.updatedAt) return a.updatedAt > b.updatedAt
            return tieKeyList(a) >= tieKeyList(b)
        }

        private fun newer(a: Sentence, b: Sentence): Boolean {
            if (a.updatedAt != b.updatedAt) return a.updatedAt > b.updatedAt
            return tieKeySentence(a) >= tieKeySentence(b)
        }

        private fun copyList(x: SentenceList) = SentenceList(
            id = x.id,
            uuid = x.uuid,
            name = x.name,
            items = mutableListOf(),
            updatedAt = x.updatedAt,
            deleted = x.deleted
        )

        private fun copySentence(x: Sentence) = Sentence(
            id = x.id,
            uuid = x.uuid.ifEmpty { UUID.randomUUID().toString() },
            text = x.text,
            source = x.source,
            updatedAt = x.updatedAt,
            deleted = x.deleted
        )
    }
}
