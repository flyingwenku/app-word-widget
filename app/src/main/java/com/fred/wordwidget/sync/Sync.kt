package com.fred.wordwidget.sync

import android.content.Context
import com.fred.wordwidget.model.Sentence
import com.fred.wordwidget.model.SentenceList
import com.fred.wordwidget.model.SentenceStore
import com.fred.wordwidget.prefs.SyncPrefs
import java.util.UUID

/**
 * 多端同步（坚果云 WebDAV 单文件），规则见 win-word-widget/docs/sync-spec.md。
 *
 * 流程：拉远端 → 按 uuid 合并 → 带 ETag 乐观锁写回；412 说明别人刚写过，重来（最多 3 次）。
 *
 * **必须在后台线程调用**：里面全是网络 + 文件读写。
 *
 * 一个刻意的设计：合并结果跟远端**一字不差**时就不 PUT ——
 * 否则会陷入"同步 → 触发保存 → 又同步"的空转，白耗流量。
 */
class Sync(private val context: Context, private val store: SentenceStore) {

    private val running = Any()

    /** 返回一句人话，直接显示在界面上 */
    fun syncNow(): String {
        // 同时只允许一个同步在跑。以前没有这个保护：连点几下（或打开页面时的自动同步
        // 跟手动点那一下撞上）就会两个线程拿着同一个 ETag 去写，
        // 一个成功、另一个必被服务端判 412 —— 表现就是"云端一直被别的一端改写"
        synchronized(running) {
            if (busy) return context.getString(com.fred.wordwidget.R.string.sync_in_progress)
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

    private fun doSync(): String {
        val cfg = SyncPrefs(context)
        if (!cfg.ready) return context.getString(com.fred.wordwidget.R.string.sync_need_account)
        cfg.lastAttemptAt = System.currentTimeMillis()

        val dav = WebDav(cfg.url, cfg.user, cfg.password)

        return try {
            for (attempt in 1..3) {
                val (exists, etag, remoteJson) = dav.fetch()

                // 远端还没有 → 把本地这份直接放上去
                if (!exists || remoteJson == null) {
                    val code = dav.put(store.serialize(), null)
                    if (code !in 200..299) throw java.io.IOException("PUT $code")
                    return finish(cfg, context.getString(com.fred.wordwidget.R.string.sync_first_upload))
                }

                val remote = SentenceStore.parse(remoteJson) ?: mutableListOf()
                val merged = merge(store.allIncludingDeleted(), remote)
                val mergedJson = serialize(merged, store.deviceId)

                // 只比 lists，**不能拿整个文件比**：文件里还有 deviceId（"最后谁写的"），
                // 两台设备各不相同 —— 比它会导致一来一回互相把对方撞成 412
                val mergedPayload = SentenceStore.serializeLists(merged)
                val remotePayload = SentenceStore.serializeLists(remote)
                val localPayload = SentenceStore.serializeLists(store.allIncludingDeleted())

                // 合并结果跟远端一样 → 没必要写远端
                if (mergedPayload == remotePayload) {
                    if (mergedPayload != localPayload) store.replaceAll(merged)
                    return finish(cfg, context.getString(com.fred.wordwidget.R.string.sync_up_to_date))
                }

                val code = dav.put(mergedJson, etag)
                if (code == 412) continue                            // 别人刚写过，重来
                if (code !in 200..299) throw java.io.IOException("PUT $code")

                store.replaceAll(merged)
                return finish(cfg, context.getString(com.fred.wordwidget.R.string.sync_done))
            }
            context.getString(com.fred.wordwidget.R.string.sync_conflict_retry)
        } catch (e: Exception) {
            context.getString(com.fred.wordwidget.R.string.sync_failed, e.message ?: "")
        }
    }



    private fun finish(cfg: SyncPrefs, msg: String): String {
        cfg.lastSyncAt = System.currentTimeMillis()
        return msg
    }

    companion object {

        /** 合并结果序列化成与 [SentenceStore] 一致的格式（同步层要用） */
        private fun serialize(lists: List<SentenceList>, deviceId: String): String {
            val arr = org.json.JSONArray()
            lists.forEach { l ->
                val ia = org.json.JSONArray()
                l.items.forEach { s ->
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
         * 按 uuid 对齐合并（docs/sync-spec.md §3）。**幂等**：两台设备各跑一次，结果相同。
         */
        fun merge(mine: List<SentenceList>, theirs: List<SentenceList>): MutableList<SentenceList> {
            val out = mutableListOf<SentenceList>()

            val uuids = (mine + theirs).map { it.uuid }.filter { it.isNotEmpty() }.distinct()
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
            val uuids = (mine + theirs).map { it.uuid }.filter { it.isNotEmpty() }.distinct()
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

        /** 最后写入者胜；时间戳完全一样时按内容定序，保证两端收敛到同一结果 */
        private fun newer(a: SentenceList, b: SentenceList): Boolean {
            if (a.updatedAt != b.updatedAt) return a.updatedAt > b.updatedAt
            return a.name <= b.name
        }

        private fun newer(a: Sentence, b: Sentence): Boolean {
            if (a.updatedAt != b.updatedAt) return a.updatedAt > b.updatedAt
            return (a.text + "|" + a.source) <= (b.text + "|" + b.source)
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
