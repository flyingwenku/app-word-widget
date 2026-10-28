package com.fred.wordwidget.model

/**
 * 一个有名字的句子列表。
 *
 * 为什么要有多个：桌面上会放多个组件，每个组件可以各选一个自己的列表
 * （比如「论语」放书桌、「英语」放通勤页）。
 */
data class SentenceList(
    val id: Long,
    var name: String,
    val items: MutableList<Sentence> = mutableListOf()
)
