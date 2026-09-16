package com.example.scoreapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.example.scoreapp.ui.AppRoot
import com.example.scoreapp.ui.ScoreAppState
import com.example.scoreapp.ui.SheetKind

/**
 * 唯一 Activity，承载全部 Compose 界面。
 *
 * 返回键按「覆盖层优先」的顺序处理：先关弹层，再关详情，最后回退页面栈；
 * 三者都没有可关的对象时交还系统（退出应用）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state = remember { ScoreAppState() }

            BackHandler(
                enabled = state.sheet != SheetKind.None ||
                    state.detail != null ||
                    state.navStack.size > 1,
            ) {
                when {
                    state.sheet != SheetKind.None -> state.closeSheet()
                    state.detail != null -> state.closeDetail()
                    else -> state.back()
                }
            }

            AppRoot(state)
        }
    }
}
