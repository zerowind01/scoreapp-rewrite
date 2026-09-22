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

    /**
     * AI 接口设置页。
     *
     * 也是整页：三个字段（地址 / 密钥 / 模型）都可能是长串，
     * 弹层里键盘一起会把这几个框挤得看不清自己输了什么。
     * 与 [Fix] 同样在进入时隐藏底栏 —— 它们都是**从「我的」压栈进去的子页**，
     * 不是四个主页签之一。
     */
    data object AiSetup : Screen

    /**
     * 网盘浏览页（AList / WebDAV）。
     *
     * 也是整页：它自带顶栏、连接条、面包屑和一列要滚动的目录，
     * 弹层的高度撑不开。与 [Fix] / [AiSetup] 同样在进入时隐藏底栏。
     */
    data object Netdisk : Screen
}

/** 底部导航项 */
enum class RootTab(val screen: Screen, val label: String) {
    Library(Screen.Manage, "乐谱"),
    Composers(Screen.Composers, "作曲家"),
    Me(Screen.Me, "我的"),
}
