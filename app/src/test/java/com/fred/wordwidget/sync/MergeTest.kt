package com.fred.wordwidget.sync

import com.fred.wordwidget.model.Sentence
import com.fred.wordwidget.model.SentenceList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合并规则（知识库《多端同步规格》§3）：幂等、最后写入者胜、墓碑、uuid 升序。
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

    /**
     * 合并结果必须固定按 uuid 升序（列表层和条目层都是）。
     * 按"我这边原来的顺序"排的话，两端会各自认为跟对方不一样，
     * 轮流把同样的数据按自己的顺序重写一遍 —— 这就是之前同步空转的真凶。
     */
    @Test
    fun `merged lists and items are sorted by uuid ascending`() {
        fun one(listUuid: String, vararg itemUuids: String) = SentenceList(
            uuid = listUuid,
            name = listUuid,
            items = itemUuids.map { Sentence(uuid = it, text = it, updatedAt = 100) }.toMutableList(),
            updatedAt = 100
        )
        val mine = listOf(one("c", "y", "x"), one("a"))
        val theirs = listOf(one("b", "z"))

        val merged = Sync.merge(mine, theirs)
        assertEquals(listOf("a", "b", "c"), merged.map { it.uuid })
        assertEquals(listOf("x", "y"), merged.first { it.uuid == "c" }.items.map { it.uuid })
    }

    /**
     * 同一毫秒两端各改一次：必须挑**定序键较大**的那条。
     * 只测"换顺序结果相同"是不够的 —— 两端各自收敛到不同的那条，
     * 同样幂等但不跨端一致。Windows 端用同一套键 + CompareOrdinal 挑较大。
     */
    @Test
    fun `equal timestamp picks the greater tie key`() {
        val x = list("甲", uuid = "a", at = 1000)
        val y = list("乙", uuid = "a", at = 1000)

        // 顺手锁死键的格式，防止哪端改了分隔符导致跨端不收敛
        assertEquals("甲||0", Sync.tieKeySentence(x.items[0]))

        val bigger = if (Sync.tieKeySentence(x.items[0]) >= Sync.tieKeySentence(y.items[0])) x else y
        assertEquals(bigger.items[0].text, Sync.merge(listOf(x), listOf(y))[0].items[0].text)
        assertEquals(bigger.items[0].text, Sync.merge(listOf(y), listOf(x))[0].items[0].text)
    }
}
