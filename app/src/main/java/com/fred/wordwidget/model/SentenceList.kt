package com.fred.wordwidget.model

import java.util.UUID

/**
 * 一个有名字的句子列表。
 *
 * 为什么要有多个：桌面上会放多个组件，每个组件可以各选一个自己的列表
 * （比如「论语」放书桌、「英语」放通勤页）。
 *
 * 同步字段（uuid / updatedAt / deleted）的含义见 [Sentence]。
 */
data class SentenceList(
    var id: Long = 0,
    var uuid: String = UUID.randomUUID().toString(),
    var name: String = "",
    val items: MutableList<Sentence> = mutableListOf(),
    var updatedAt: Long = 0L,
    var deleted: Boolean = false
) {
    /** 没被删掉的条目（显示、选句用这个） */
    fun alive(): List<Sentence> = items.filter { !it.deleted }

    fun findAlive(target: String): Sentence? = items.firstOrNull { it.uuid == target && !it.deleted }
}
