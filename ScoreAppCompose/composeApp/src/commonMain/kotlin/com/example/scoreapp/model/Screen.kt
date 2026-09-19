package com.example.scoreapp.model

/**
 * 导航目标。采用「栈」式导航：底部三个主页面为根，
 * [Works] 这类详情页压栈后可逐层回退。
 */
sealed interface Screen {
    data object Manage : Screen
    data object Composers : Screen
    data object Me : Screen

    /** 某位作曲家的作品列表 */
    data class Works(val composer: String) : Screen

    /**
     * forScore 标签校对页。
     *
     * 是一张**整页**而不是弹层：它要展示一张多列的可勾选表格，
     * 弹层的高度与宽度都撑不开（列多、行可能有几百条）。
     */
    data object Fix : Screen
}

/** 底部导航项 */
enum class RootTab(val screen: Screen, val label: String) {
    Library(Screen.Manage, "乐谱"),
    Composers(Screen.Composers, "作曲家"),
    Me(Screen.Me, "我的"),
}
