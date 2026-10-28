package com.fred.wordwidget.model

/**
 * 一条句子。
 *
 * v1.2 起取消软删除（hidden 标记）：内容就是普通的增删改查，删了就是真删。
 */
data class Sentence(
    val id: Long,
    val text: String,
    val source: String = ""
)
