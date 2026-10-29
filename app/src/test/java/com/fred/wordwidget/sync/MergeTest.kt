package com.fred.wordwidget.sync

import com.fred.wordwidget.model.Sentence
import com.fred.wordwidget.model.SentenceList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合并规则（docs/sync-spec.md §3）：幂等、最后写入者胜、墓碑。
 *
 * 这里测的是**最容易被改坏**的那几条规则：
 * 两边各加的东西都要留着、删除不能被另一端复活、顺序换了结果一样。
 */
class MergeTest {

    private fun list(text: String, uuid: String, at: Long, deleted: Boolean = false) =
        SentenceList(
            uuid = "L1",
            name = "论语",
            items = mutableListOf(Sentence(uuid = uuid, text = text, source = "", updatedAt = at, deleted = deleted)),
            updatedAt = at
        )

    /** 两端各加一句 → 合并后两句都还在（只留胜者那边的条目是最容易犯的错） */
    @Test
    fun `keeps additions from both sides`() {
        val mine = list("A", uuid = "a", at = 100).also {
            it.items.add(Sentence(uuid = "mine-only", text = "M", updatedAt = 110))
        }
        val theirs = list("A", uuid = "a", at = 100).also {
            it.items.add(Sentence(uuid = "theirs-only", text = "T", updatedAt = 120))
        }

        val items = Sync.merge(listOf(mine), listOf(theirs))[0].items
        assertEquals(setOf("a", "mine-only", "theirs-only"), items.map { it.uuid }.toSet())
    }

    /** 同一条两端都改过 → 时间戳大的胜 */
    @Test
    fun `newer timestamp wins`() {
        val mine = list("电脑改的", uuid = "a", at = 900)
        val theirs = list("手机改的", uuid = "a", at = 500)

        assertEquals("电脑改的", Sync.merge(listOf(mine), listOf(theirs))[0].items[0].text)
        assertEquals("电脑改的", Sync.merge(listOf(theirs), listOf(mine))[0].items[0].text)
    }

    /** 幂等：谁先谁后都一样，否则两台设备会来回横跳 */
    @Test
    fun `is order independent`() {
        val mine = list("M", uuid = "a", at = 100).also { it.items.add(Sentence(uuid = "b", text = "B", updatedAt = 300)) }
        val theirs = list("T", uuid = "a", at = 200)

        val ab = Sync.merge(listOf(mine), listOf(theirs))[0].items.associateBy { it.uuid }
        val ba = Sync.merge(listOf(theirs), listOf(mine))[0].items.associateBy { it.uuid }

        assertEquals(ab["a"]?.text, ba["a"]?.text)
        assertEquals(ab["b"]?.text, ba["b"]?.text)
    }

    /** 删了就不能被另一端"复活" */
    @Test
    fun `tombstone survives merge`() {
        val mine = list("这句删了", uuid = "a", at = 900, deleted = true)
        val theirs = list("这句删了", uuid = "a", at = 500)

        val merged = Sync.merge(listOf(mine), listOf(theirs))[0].items[0]
        assertTrue("删除标记不能丢", merged.deleted)
    }

    /** 删完之后又被改了 → 听后来的（用户是拿改的动作覆盖删除） */
    @Test
    fun `edit after deletion resurrects it`() {
        val mine = list("这句删了", uuid = "a", at = 500, deleted = true)
        val theirs = list("又想留着了", uuid = "a", at = 900)

        val merged = Sync.merge(listOf(mine), listOf(theirs))[0].items[0]
        assertFalse("后来的修改应该覆盖墓碑", merged.deleted)
        assertEquals("又想留着了", merged.text)
    }

    /** 列表被删掉也算墓碑，不能因为远端还有就回来 */
    @Test
    fun `deleted list stays deleted`() {
        val mine = list("A", uuid = "a", at = 900).apply { deleted = true }
        val theirs = list("A", uuid = "a", at = 100)

        assertTrue(Sync.merge(listOf(mine), listOf(theirs))[0].deleted)
    }
}
