package com.example.scoreapp.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 图标集。
 *
 * 全部由 SVG 路径数据在运行时构建为 [ImageVector]，不依赖 material-icons 扩展包
 * （该包在 Compose Multiplatform 1.8 起已不再随版本发布）。
 * 每个图标统一 24×24 视口，线宽 1.9，圆头圆角，与纸面风格保持一致。
 *
 * 颜色占位为黑色，实际渲染时由 `Icon(tint = ...)` 覆盖。
 */
object AppIcons {

    private const val VIEWPORT = 24f
    private const val STROKE = 1.9f

    /** 线性图标：仅描边 */
    private fun outline(name: String, path: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        ).addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()

    /** 实心图标：仅填充 */
    private fun filled(name: String, path: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        ).addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            fill = SolidColor(Color.Black),
        ).build()

    // ---------- 工具栏 ----------
    val Search: ImageVector by lazy {
        outline("Search", "M11 4a7 7 0 1 0 0 14a7 7 0 0 0 0-14z M20 20l-3.6-3.6")
    }
    val Filter: ImageVector by lazy {
        outline("Filter", "M3 5h18 M6 12h12 M10 19h4")
    }
    val Grid: ImageVector by lazy {
        outline("Grid", "M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h6v6h-6z")
    }
    val ListView: ImageVector by lazy {
        outline("ListView", "M4 6h16 M4 12h16 M4 18h16")
    }
    val Sort: ImageVector by lazy {
        outline("Sort", "M7 4v16 M4 17l3 3l3-3 M17 20V4 M14 7l3-3l3 3")
    }
    val MoreVert: ImageVector by lazy {
        filled(
            "MoreVert",
            "M12 5.4a1.8 1.8 0 1 0 0 3.6a1.8 1.8 0 0 0 0-3.6z " +
                "M12 10.2a1.8 1.8 0 1 0 0 3.6a1.8 1.8 0 0 0 0-3.6z " +
                "M12 15a1.8 1.8 0 1 0 0 3.6a1.8 1.8 0 0 0 0-3.6z",
        )
    }
    val Close: ImageVector by lazy {
        outline("Close", "M6 6l12 12 M18 6L6 18")
    }
    val ArrowBack: ImageVector by lazy {
        outline("ArrowBack", "M15 5l-7 7l7 7")
    }
    val ChevronRight: ImageVector by lazy {
        outline("ChevronRight", "M9 5l7 7l-7 7")
    }
    val Add: ImageVector by lazy {
        outline("Add", "M12 5v14 M5 12h14")
    }

    // ---------- 内容 ----------
    val Book: ImageVector by lazy {
        outline(
            "Book",
            "M4 5.5A2.5 2.5 0 0 1 6.5 3H19v15H6.5A2.5 2.5 0 0 0 4 20.5z " +
                "M4 20.5A2.5 2.5 0 0 1 6.5 18H19v3H6.5",
        )
    }
    val MusicNote: ImageVector by lazy {
        outline(
            "MusicNote",
            "M9 18V6l10-2v12 " +
                "M6.5 18a2.5 2.5 0 1 0 5 0a2.5 2.5 0 0 0-5 0z " +
                "M16.5 16a2.5 2.5 0 1 0 5 0a2.5 2.5 0 0 0-5 0z",
        )
    }
    val Person: ImageVector by lazy {
        outline("Person", "M12 4a4 4 0 1 0 0 8a4 4 0 0 0 0-8z M5 21c0-3.6 3.1-6 7-6s7 2.4 7 6")
    }
    val AccountCircle: ImageVector by lazy {
        outline(
            "AccountCircle",
            "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18z M12 7a3 3 0 1 0 0 6a3 3 0 0 0 0-6z " +
                "M6.6 19c1-2.3 3-3.5 5.4-3.5s4.4 1.2 5.4 3.5",
        )
    }
    val Layers: ImageVector by lazy {
        outline("Layers", "M12 3l9 5l-9 5l-9-5z M3 13l9 5l9-5")
    }
    val Download: ImageVector by lazy {
        outline("Download", "M12 3v11 M8 11l4 4l4-4 M5 20h14")
    }
    val Cleaning: ImageVector by lazy {
        outline("Cleaning", "M14 4l6 6 M9 20l7-7 M4 20l3-3l3 3l-3 3z")
    }
    val Photo: ImageVector by lazy {
        outline(
            "Photo",
            "M4 7h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z " +
                "M8 7l1.5-2h5L16 7 " +
                "M12 9.6a3.4 3.4 0 1 0 0 6.8a3.4 3.4 0 0 0 0-6.8z",
        )
    }
    val FileDoc: ImageVector by lazy {
        outline("FileDoc", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z M14 3v5h5")
    }
    val Share: ImageVector by lazy {
        outline("Share", "M12 3v12 M8 7l4-4l4 4 M5 14v4a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-4")
    }
    val Edit: ImageVector by lazy {
        outline("Edit", "M4 20h4L20 8l-4-4L4 16z")
    }
    val Delete: ImageVector by lazy {
        outline("Delete", "M4 7h16 M9 7V5h6v2 M6 7l1 13h10l1-13")
    }
    val OpenInNew: ImageVector by lazy {
        outline(
            "OpenInNew",
            "M14 4h6v6 M20 4l-8 8 M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4",
        )
    }
    val Visibility: ImageVector by lazy {
        outline(
            "Visibility",
            "M2 12s4-7 10-7s10 7 10 7s-4 7-10 7s-10-7-10-7z " +
                "M12 9a3 3 0 1 0 0 6a3 3 0 0 0 0-6z",
        )
    }
}
