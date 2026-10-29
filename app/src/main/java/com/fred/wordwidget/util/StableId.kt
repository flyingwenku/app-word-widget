package com.fred.wordwidget.util

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * 稳定 UUID：同一个名字永远算出同一个 uuid。**必须与 Windows 端一致**，
 * 否则两端第一次同步会把预置句变成两份（14 条变 28 条）。
 *
 * 为什么需要：两端各自预置了同样的《论语》14 条，若各自随机生成 uuid，
 * 第一次同步就会互相认为对方是"新加的"。
 *
 * 算法：SHA1(命名空间16字节 || 名字UTF8) 取前 16 字节，
 *      版本位改成 5、variant 位改成 RFC 4122。
 *
 * ⚠️ 字节序：**这两个字段的字节序在 .NET 和 Java 里是反的，照 RFC 直写会算错。**
 *   - 命名空间要按 **.NET `Guid.ToByteArray()`** 的布局喂进去（前三个字段小端），
 *     因为 Windows 端就是这么算的，我们得跟着它走
 *   - 输出前要把字节再换一次序（.NET Guid 前三个字段小端，Java UUID 全大端）
 *
 * 实测对照（名字 = "list:论语"）：
 *   Windows 端实际生成     5cbd5673-d822-a659-84e1-4fadcc544464   ← 基准
 *   按下面这段代码          5cbd5673-d822-a659-84e1-4fadcc544464   ✅
 *   不加最后那次换序        7356bd5c-22d8-59a6-84e1-4fadcc544464   ❌
 *   命名空间也用大端        4ff3f2bc-100c-4657-96a5-393c79ae3229   ❌
 *
 * 改这个文件之前先跑一遍 win-word-widget/docs/sync-spec.md §2 里的对照值。
 */
object StableId {

    private val NS: UUID = UUID.fromString("6b5a1f3c-8d2e-4a7b-9c1d-0e2f3a4b5c6d")

    fun of(name: String): String {
        val canonical = ByteBuffer.allocate(16)
            .putLong(NS.mostSignificantBits)
            .putLong(NS.leastSignificantBits)
            .array()

        // .NET Guid.ToByteArray() 的布局：前三个字段小端
        val ns = byteArrayOf(
            canonical[3], canonical[2], canonical[1], canonical[0],
            canonical[5], canonical[4], canonical[7], canonical[6],
            canonical[8], canonical[9], canonical[10], canonical[11],
            canonical[12], canonical[13], canonical[14], canonical[15]
        )

        val md = MessageDigest.getInstance("SHA-1")
        md.update(ns)
        val h = md.digest(name.toByteArray(Charsets.UTF_8))   // SHA-1 是 20 字节，只取前 16

        h[6] = (h[6].toInt() and 0x0F or 0x50).toByte()
        h[8] = (h[8].toInt() and 0x3F or 0x80).toByte()

        // 换成 Java UUID 的大端布局（不换就会跟 Windows 端差一个字节序）
        val j = byteArrayOf(
            h[3], h[2], h[1], h[0],
            h[5], h[4], h[7], h[6],
            h[8], h[9], h[10], h[11],
            h[12], h[13], h[14], h[15]
        )

        val bb = ByteBuffer.wrap(j)
        return UUID(bb.long, bb.long).toString()
    }

    fun list(name: String): String = of("list:$name")

    fun preset(listName: String, index: Int): String = of("preset:$listName:$index")
}
