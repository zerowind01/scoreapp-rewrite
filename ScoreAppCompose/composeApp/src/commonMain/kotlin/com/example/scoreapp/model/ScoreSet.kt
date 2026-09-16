package com.example.scoreapp.model

/**
 * 谱单（曲目集合）。用 [seeds] 引用乐谱的缩略图种子，而不是硬编码 id，
 * 这样样例数据重建后引用依然有效。
 */
data class ScoreSet(
    val name: String,
    val desc: String,
    val seeds: List<Int>,
)
