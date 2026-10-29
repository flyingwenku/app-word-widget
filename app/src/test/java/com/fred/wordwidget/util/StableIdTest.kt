package com.fred.wordwidget.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 锁死"稳定 UUID"的取值 —— 一旦和 Windows 端对不上，
 * 两台设备第一次同步就会把 14 条预置句变成 28 条。
 *
 * 基准值取自 Windows 端**实际生成并存进数据文件**的 uuid
 * （见 win-word-widget/docs/sync-spec.md §2）。
 *
 * 注意字节序：这是跟着 .NET `Guid` 的小端布局来的，**不是标准 v5 UUID**。
 * 真要改成标准算法，必须两端同时改 + 迁移已有数据，不能只改一端。
 */
class StableIdTest {

    @Test
    fun `preset list uuid matches Windows side`() {
        assertEquals("5cbd5673-d822-a659-84e1-4fadcc544464", StableId.list("论语"))
    }

    @Test
    fun `preset sentence uuids match Windows side`() {
        assertEquals("5df0179d-4a9b-e950-98e5-00ad8a1a1cf8", StableId.preset("论语", 0))
        assertEquals("16e6e6ac-dded-f259-b7b4-bb9764a7ec15", StableId.preset("论语", 1))
        assertEquals("f9f86ef1-e25f-805e-97b5-42dde0c505e2", StableId.preset("论语", 2))
    }

    /** 同样的名字必须永远算出同样的 uuid */
    @Test
    fun `is deterministic`() {
        assertEquals(StableId.list("论语"), StableId.list("论语"))
    }

    /** 不同的名字必须算出不同的 uuid */
    @Test
    fun `differs by name`() {
        assertNotEquals(StableId.preset("论语", 0), StableId.preset("论语", 1))
        assertNotEquals(StableId.list("论语"), StableId.list("英语"))
    }
}
