package com.example.scoreapp

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.example.scoreapp.ui.AppRoot
import com.example.scoreapp.ui.PickKind
import com.example.scoreapp.ui.ScoreAppState
import com.example.scoreapp.ui.SheetKind
import com.example.scoreapp.util.createFileBridge
import com.example.scoreapp.util.installFileBridge

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

        setContent {
            val state = remember { ScoreAppState() }

            state.bridge = remember { createFileBridge() }

            // 启动时把随包分发的内置乐谱补齐成真实绝对路径。
            // 放在 LaunchedEffect 而不是构造器里：拷贝会读 assets 并写磁盘，
            // 不该阻塞首帧；封面在拿到路径前先走程序化绘制，拿到后自动重画。
            LaunchedEffect(Unit) {
                state.installBundledScores()
            }

            // 相册多选。用 GetMultipleContents 而不是 GetContent：一次选多张、
            // 合成一份多页 PDF 是这条通道的主要用法。
            val imagePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.GetMultipleContents(),
            ) { uris ->
                state.consumePick()
                state.importFromImages(uris.map { it.toString() })
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

            val imagePermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                state.consumePick()
                if (granted) {
                    imagePicker.launch("image/*")
                } else {
                    state.showToast("没有读取相册的权限，无法选择图片")
                }
            }

            // 拉起选择器前先确认权限。Android 13 起相册归 READ_MEDIA_IMAGES，
            // 12 及以下仍是 READ_EXTERNAL_STORAGE。两个都不申请也没关系——
            // 系统选择器本身不需要权限，这里只是为了在部分定制 ROM 上更稳妥。
            fun requestImages() {
                val permission = if (Build.VERSION.SDK_INT >= 33) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
                val alreadyGranted = checkSelfPermission(permission) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (alreadyGranted) {
                    state.consumePick()
                    imagePicker.launch("image/*")
                } else {
                    imagePermission.launch(permission)
                }
            }

            // 导入意图一旦挂上就立刻拉起选择器，拉完即复位
            LaunchedEffect(state.pendingPick) {
                when (state.pendingPick) {
                    PickKind.Images -> requestImages()
                    PickKind.Pdf -> pdfPicker.launch("application/pdf")
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
                    else -> state.back()
                }
            }

            AppRoot(
                state = state,
                // 这两个回调保留给非 Android 平台或测试；Android 上的拉起逻辑
                // 走上面的 LaunchedEffect，因为需要先做权限判断。
                onPickImages = {},
                onPickPdf = {},
            )
        }
    }
}
