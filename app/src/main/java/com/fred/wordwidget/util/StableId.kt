package com.fred.wordwidget.util

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * 稳定 UUID：同一个名字永远算出同一个 uuid。**必须与 Windows 端一致**，
 * 否则两端第一次同步会把预置句变成两份。
 *
 * 为什么需要：两端各自预置了同样的《论语》14 条，若各自随机生成 uuid，
 * 第一次同步就会变成 28 条（互相认为对方是"新加的"）。
 *
 * 算法（v5 UUID，见 docs/sync-spec.md §2）：
 *   SHA1(命名空间16字节 || 名字UTF8) 取前 16 字节，版本位改成 5、variant 位改成 RFC 4122。
 */
object StableId {

    private val NS: UUID = UUID.fromString("6b5a1f3c-8d2e-4a7b-9c1d-0e2f3a4b5c6d")

    fun of(name: String): String {
        val ns = ByteBuffer.allocate(16)
            .putLong(NS.mostSignificantBits)
            .putLong(NS.leastSignificantBits)
            .array()

        val md = MessageDigest.getInstance("SHA-1")
        md.update(ns)
        val h = md.digest(name.toByteArray(Charsets.UTF_8))

        h[6] = (h[6].toInt() and 0x0F or 0x50).toByte()
        h[8] = (h[8].toInt() and 0x3F or 0x80).toByte()

        val bb = ByteBuffer.wrap(h, 0, 16)
        return UUID(bb.long, bb.long).toString()
    }

    fun list(name: String): String = of("list:$name")

    fun preset(listName: String, index: Int): String = of("preset:$listName:$index")
}
