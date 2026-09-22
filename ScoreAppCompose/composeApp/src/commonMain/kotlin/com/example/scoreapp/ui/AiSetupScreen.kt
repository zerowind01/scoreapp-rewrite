package com.example.scoreapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.ui.components.AppIcons
import com.example.scoreapp.ui.theme.Tokens
import com.example.scoreapp.util.AiConfig

/**
 * AI 接口设置整页。
 *
 * ## 这一页存在的意义：把「假的」换成「真的」
 *
 * 「我的」页原先有一行「自动识别元数据」开关，点了只翻转一个布尔值并弹提示，
 * **没有任何代码读它**。而真正会被用到的接口设置 [AiConfig] 只存在于内存里，
 * 却始终没有界面可以填 —— 校对页的「生成」按下去必然报「还没填接口地址或密钥，
 * 去「我的 → AI 设置」里填一下」，而那个入口当时根本不存在。
 *
 * 现在删掉那个假开关，这一页就是它本来该有的样子。
 *
 * ## 为什么是整页而不是弹层
 *
 * 三个字段（地址 / 密钥 / 模型）都可能是长串，键盘一弹起来，弹层里剩下的
 * 高度不足以让用户看清自己输了什么。整页还能顺带把下面那段「明文存储」
 * 的说明完整地摊开 —— 那是用户应当知情的事，不该塞进一个会被划走的小条。
 *
 * ## 布局
 *
 * 顶栏（返回 + 标题）+ 可滚动的字段区 + 底部固定「保存」。保存放底部而不是
 * 顶栏：这是个「改完就走」的表单，主操作的落点应该在拇指够得到的地方。
 */
@Composable
fun AiSetupScreen(state: ScoreAppState) {
    // 编辑副本：改到一半退出不该污染已有配置。进页时从 state 拷一份出来。
    var draft by remember { mutableStateOf(state.aiConfig) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.BgPage)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        AiSetupTopBar(onBack = { state.closeAiSetup() })

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(6.dp))

            Text(
                "填好之后，校对页顶部的「AI 生成」才跑得起来。",
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = Tokens.Text3,
            )
            Spacer(Modifier.height(5.dp))
            // 顺手把「AI 管哪几个字段」说清楚。用户跑到校对页才发现标签和来源
            // 得自己填，会以为是 AI 失败 —— 这条边界是设计，不是故障。
            Text(
                "AI 只看这五项：曲名、作曲家、乐器、乐曲类型、调性。" +
                    "标签和来源由你自己填，AI 不会给。",
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = Tokens.Text3,
            )

            AiField(
                label = "接口地址",
                hint = "OpenAI 兼容的 /chat/completions",
                value = draft.endpoint,
                keyboardUrl = true,
                onValueChange = { draft = draft.copy(endpoint = it) },
            )
            AiField(
                label = "密钥",
                hint = "sk-...",
                value = draft.apiKey,
                masked = true,
                onValueChange = { draft = draft.copy(apiKey = it) },
            )
            AiField(
                label = "模型",
                hint = AiConfig.DEFAULT_MODEL,
                value = draft.model,
                onValueChange = { draft = draft.copy(model = it) },
            )

            // ---- 联网兜底 ----
            //
            // 开关不放在这一页：它长在校对页 AI 条上（「联网」小徽章），
            // 因为开/关最常发生在「看到没认出的黄条那一刻」，跑到设置页来翻
            // 反而绕远。这里只管密钥 —— 没有密钥，开了也是空转。
            Spacer(Modifier.height(18.dp))
            Text(
                "联网搜索（可选）",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Tokens.Text2,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                "没认出曲子时自动搜一次网页资料、带着资料再问一遍。" +
                    "密钥在 tavily.com 免费申请（每月 1000 次）；不填也能用，" +
                    "只是没认出时就只会提示手动补充信息。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = Tokens.Text3,
            )
            AiField(
                label = "搜索密钥（Tavily）",
                hint = "tvly-…",
                value = draft.searchKey,
                masked = true,
                onValueChange = { draft = draft.copy(searchKey = it) },
            )

            Spacer(Modifier.height(18.dp))
            AiPlaintextNotice()

            // 恢复默认：用户把地址改坏了想退回去时，不必自己回忆原本是什么
            Spacer(Modifier.height(14.dp))
            Text(
                "恢复默认地址与模型",
                fontSize = 12.sp,
                color = Tokens.LinkBlue,
                modifier = Modifier.clickable {
                    draft = draft.copy(
                        endpoint = AiConfig.DEFAULT_ENDPOINT,
                        model = AiConfig.DEFAULT_MODEL,
                    )
                },
            )

            Spacer(Modifier.height(24.dp))
        }

        AiSetupFooter(
            ready = draft.ready,
            onSave = { state.saveAiConfig(draft) },
        )
    }
}

@Composable
private fun AiSetupTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.Surface)
            .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(Tokens.Surface3)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.ArrowBack,
                contentDescription = "返回",
                tint = Tokens.Text2,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            "AI 设置",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Tokens.Text1,
            modifier = Modifier.padding(start = 9.dp),
        )
    }
}

/**
 * 明文存储的告知条。
 *
 * 这是一个**知情的决定**，不是遗漏：密钥加密要连密钥派生一起做，
 * 对一个自用工具得不偿失；但用户有权知道自己的密钥被写在哪儿、是否加密。
 * 所以这句话必须显式写出来，而不是藏在代码注释里。
 */
@Composable
private fun AiPlaintextNotice() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Tokens.Surface2)
            .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        Text(
            "密钥怎么存",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            color = Tokens.Text2,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            "存在应用私有目录的 fix-store.json 里，明文，不加密。" +
                "连同「已存到第几条」一起保存，所以下次打开不用重填。" +
                "应用卸载后随之删除；但如果设备被 root，或这个文件被备份出去，" +
                "密钥就是可见的。",
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            color = Tokens.Text3,
        )
    }
}

@Composable
private fun AiSetupFooter(ready: Boolean, onSave: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.Surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                // 三个字段没填全时按钮变灰：让「还不能用」在按下去之前就看得出来，
                // 而不是按了才弹一句报错
                .clip(RoundedCornerShape(14.dp))
                .background(if (ready) Tokens.Accent else Tokens.Surface3)
                .clickable(enabled = ready, onClick = onSave),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "保存",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (ready) Tokens.AccentFg else Tokens.Text3,
            )
        }
        if (!ready) {
            Spacer(Modifier.height(7.dp))
            Text(
                "三项都填了才能保存",
                fontSize = 11.sp,
                color = Tokens.Text3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 单个配置字段。
 *
 * [masked] 用于密钥：默认打码，右侧一个小眼睛切换。
 * 打码而不是明文显示，是因为这一页很可能在别人面前打开；
 * 但保留切换，是因为粘贴了带空格的密钥时必须能看见才能发现。
 */
@Composable
private fun AiField(
    label: String,
    hint: String,
    value: String,
    masked: Boolean = false,
    keyboardUrl: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }

    Column(Modifier.padding(top = 16.dp)) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Tokens.Text2,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            placeholder = { Text(hint, fontSize = 15.sp, color = Tokens.Text3) },
            textStyle = TextStyle(fontSize = 15.sp),
            visualTransformation = if (masked && !revealed) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = when {
                    masked -> KeyboardType.Password
                    keyboardUrl -> KeyboardType.Uri
                    else -> KeyboardType.Text
                },
            ),
            trailingIcon = if (masked) {
                {
                    Text(
                        if (revealed) "隐藏" else "显示",
                        fontSize = 12.sp,
                        color = Tokens.LinkBlue,
                        modifier = Modifier
                            .clickable { revealed = !revealed }
                            .padding(horizontal = 10.dp),
                    )
                }
            } else {
                null
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Tokens.Surface,
                unfocusedContainerColor = Tokens.Surface,
                focusedBorderColor = Tokens.Text3,
                unfocusedBorderColor = Tokens.Line,
                focusedTextColor = Tokens.Text1,
                unfocusedTextColor = Tokens.Text1,
                cursorColor = Tokens.Text1,
            ),
        )
    }
}
