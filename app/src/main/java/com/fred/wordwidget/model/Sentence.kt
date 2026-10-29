package com.fred.wordwidget.model

import java.util.UUID

/**
 * 一条句子。
 *
 * v1.4 起为多端同步加了三个字段（规格见 win-word-widget/docs/sync-spec.md）：
 *   uuid      —— 跨设备**唯一且永久不变**的身份。以前是"列表内 max+1"的数字 id，
 *                两端各自记账必然撞号：电脑端的 15 号和手机端的 15 号是两句不同的话。
 *   updatedAt —— 最后修改时间（Unix 毫秒），两端都改过同一条时用它裁决谁留下。
 *   deleted   —— 墓碑。删掉的东西不能直接抹掉，否则下次同步时另一端（还没删的那家）
 *                会把它原样送回来，删了等于没删。
 *
 * id 只为读老文件保留，读完即弃。
 */
data class Sentence(
    var id: Long = 0,
    var uuid: String = UUID.randomUUID().toString(),
    var text: String = "",
    var source: String = "",
    var updatedAt: Long = 0L,
    var deleted: Boolean = false
)
