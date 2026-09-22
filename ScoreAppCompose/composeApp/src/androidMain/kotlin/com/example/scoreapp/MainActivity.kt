package com.example.scoreapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.scoreapp.ui.AppRoot
import com.example.scoreapp.ui.PickKind
import com.example.scoreapp.ui.ScoreAppState
import com.example.scoreapp.ui.SheetKind
import com.example.scoreapp.ui.components.CrashReportOverlay
import com.example.scoreapp.util.createFileBridge
import com.example.scoreapp.util.installFileBridge

/**
 * 一次最多能选几张相册图片。
 *
 * 上限交给系统选择器自己限制（超出时用户根本选不了第 N+1 张），
 * 比选完再由应用报错更早、更清楚。32 张足够覆盖「一本乐谱拍完」的场景，
 * 再多则合成 PDF 的内存与耗时都不划算（每页解码后按最长边 1400px 采样）。
 */
private const val MaxPickImages = 32

/**
 * 唯一 Activity，承载全部 Compose 界面，同时是平台能力的注入点。
 *
 * 返回键按「覆盖层优先」的顺序处理：先关阅读器，再关弹层，再关详情，
 * 最后回退页面栈；都没有可关的对象时交还系统（退出应用）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 文件能力需要 Context，而 ScoreAppState 住在 commonMain，拿不到。
        // 在这里把实现注入进去，commonMain 就只依赖那个不泄漏平台类型的接口。
        installFileBridge(this)

        // 崩溃恢复与原应用 MainActivity 同序：setContent **之前**读上次日志并立刻清除，
        // 保证一次崩溃只提示一次；读到什么就弹什么，之后发生的崩溃留给下次启动。
        // （Application 装的处理器只负责落盘与转交，浮层只认这份「上次」的日志。）
        val app = application as ScoreApp
        val lastCrashLog = app.lastCrash()
        app.clearCrash()
        val crashHint = externalCrashHint()

        setContent {
            val state = remember { ScoreAppState() }

            state.bridge = remember { createFileBridge() }

            // 启动时把随包分发的内置乐谱补齐成真实绝对路径。
            // 放在 LaunchedEffect 而不是构造器里：拷贝会读 assets 并写磁盘，
            // 不该阻塞首帧；封面在拿到路径前先走程序化绘制，拿到后自动重画。
            LaunchedEffect(Unit) {
                state.installBundledScores()
                // AI 设置也在这里读一次（同样是读盘）。晚一帧没关系 ——
                // 用户不可能在首帧就去点校对页的「生成」。
                state.loadAiConfig()
                // 网盘连接配置同理：读盘不该阻塞首帧
                state.loadNetdiskConfig()
                // 乐谱库存档（网盘条目 + 改过的元数据 + 导入的谱子）。
                // **必须在 installBundledScores 之后**：内置谱要按解析后的 assetPdf 认人，
                // 早一步会认不出、把上次改过的元数据丢掉。
                state.loadLibraryStore()
            }

            // 相册多选。走系统的照片选择器（Photo Picker），**不申请任何相册权限**：
            // 选择器由系统进程托管，只对用户勾选的那几张授予临时读权限。
            //
            // 这里换掉的是此前的 `GetMultipleContents` + 「先申请 READ_MEDIA_IMAGES
            // 再拉相册」那条链。那条链在真机上表现成「所有照片都无法读取」——
            // 权限批给了 Activity，而文件桥持有的是 applicationContext，
            // 部分 ROM 上查询 MediaProvider 会被拒，异常被解码逻辑吞成「读取失败」。
            // Photo Picker 从设计上就没有这一环，属根治而非绕开。
            //
            // PickMultipleVisualMedia 在不支持该特性的旧系统上会自动回退到
            // ACTION_OPEN_DOCUMENT（仍然免权限），因此不需要写版本分支。
            val imagePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.PickMultipleVisualMedia(MaxPickImages),
            ) { uris ->
                state.consumePick()
                if (uris.isNotEmpty()) {
                    state.importFromImages(uris.map { it.toString() })
                }
            }

            // PDF 单选
            val pdfPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.GetContent(),
            ) { uri ->
                state.consumePick()
                // 用户在中途取消时 uri 为 null，此时什么都不做——
                // 不该弹「导入失败」，因为用户知道自己取消了
                if (uri != null) state.importFromPdf(uri.toString())
            }

            // forScore 导出的标签 CSV 单选。
            //
            // MIME 写 `text/*` 而不是 `text/csv`：CSV 在 Android 上没有统一的
            // 注册 MIME，各家文件管理器给出的类型五花八门（text/csv、
            // text/comma-separated-values、application/csv，甚至 text/plain），
            // 写死一个会让文件在选择器里变灰选不中。放开到 text/* 再在解析时
            // 校验内容，是这类文件唯一稳的做法。
            val csvPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.GetContent(),
            ) { uri ->
                state.consumePick()
                if (uri != null) state.importCsvForFix(uri.toString())
            }

            // 导入意图一旦挂上就立刻拉起选择器，拉完即复位
            LaunchedEffect(state.pendingPick) {
                when (state.pendingPick) {
                    PickKind.Images -> {
                        state.consumePick()
                        imagePicker.launch(PickVisualMediaRequest(ImageOnly))
                    }
                    PickKind.Pdf -> pdfPicker.launch("application/pdf")
                    PickKind.Csv -> csvPicker.launch("text/*")
                    PickKind.None -> Unit
                }
            }

            BackHandler(
                enabled = state.reader != null ||
                    state.sheet != SheetKind.None ||
                    state.detail != null ||
                    state.navStack.size > 1,
            ) {
                when {
                    // 阅读器在最上层，优先关它
                    state.reader != null -> state.closePdf()
                    state.sheet != SheetKind.None -> state.closeSheet()
                    state.detail != null -> state.closeDetail()
                    // 网盘设置是盖在浏览页上的面板，先收它再谈退出这一页
                    state.netdiskSetupOpen -> state.closeNetdiskSetup()
                    else -> state.back()
                }
            }

            AppRoot(
                state = state,
                // 这两个回调保留给非 Android 平台或测试；Android 上真正的拉起逻辑
                // 走上面的 LaunchedEffect（需要 launcher 实例，只能在 Composable 里取）。
                onPickImages = {},
                onPickPdf = {},
                onPickCsv = {},
            )

            // 崩溃浮层盖在最上层；关掉只收浮层，不影响应用内容
            if (lastCrashLog != null) {
                var showCrash by remember { mutableStateOf(true) }
                if (showCrash) {
                    CrashReportOverlay(
                        log = lastCrashLog,
                        storageHint = crashHint,
                        onDismiss = { showCrash = false },
                    )
                }
            }
        }
    }
}
