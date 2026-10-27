package com.fred.wordwidget.model

/**
 * 一条句子。
 *
 * hidden = 软删除标记：用户点"删除"时只打这个标记，数据还在，
 * 可在「已隐藏」列表里恢复（需求原文：不是真删除，标记不显示而已）。
 */
data class Sentence(
    val id: Long,
    val text: String,
    val source: String = "",
    val hidden: Boolean = false
)
