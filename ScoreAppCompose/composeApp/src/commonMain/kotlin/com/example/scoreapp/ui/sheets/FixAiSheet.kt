package com.example.scoreapp.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.domain.csvfix.AiFill
import com.example.scoreapp.ui.ScoreAppState
import com.example.scoreapp.ui.components.AppIcons
import com.example.scoreapp.ui.components.SheetGhostButton
import com.example.scoreapp.ui.components.SheetPrimaryButton
import com.example.scoreapp.ui.components.SheetScaffold
import com.example.scoreapp.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * AI 补全弹层。
 *
 * Jackson 拍板的两条路都在这里，**并列而不是二选一**：
 * 1. 直连 —— 填好接口地址/密钥，点一下就跑完；
 * 2. 手动粘贴 —— 用户自己去网页聊天窗里问，把回答贴回来解析。
 *
 * 为什么留着第二条：密钥是不可控的（额度、网络、公司策略），而粘贴这条路
 * 零配置、零成本，永远可用。它在界面上不是「降级方案」的口吻，
 * 而是与直连同级的一个选项 —— 用户不必先撞一次墙才知道有这条路。
 *
 * 密钥**只存在内存里**：`AiConfig` 不落盘、不进 DataStore。手机上的一个
 * 明文 API Key 是实打实的资产，宁可让用户每次开 App 重填一次。
 */
@Composable
fun FixAiSheet(state: ScoreAppState, onDismiss: () -> Unit) {
    val session = state.fixSession
    if (session == null) {
        onDismiss()
        return
    }

    // 提示词可编辑，但默认填好官方默认值，用户不改也能跑
    var prompt by remember { mutableStateOf(state.aiPrompt ?: AiFill.DEFAULT_PROMPT) }
    var showAdvanced by remember { mutableStateOf(false) }

    // 直连那次调用挂在这个 scope 上：弹层关掉 → 协程取消 → 请求不再占着资源。
    // 这正是把 launch 放在 UI 层、而不是把 runAi 做成「发起后不管」的原因。
    val scope = rememberCoroutineScope()

    // 待补的条目数：让用户先知道「这次要问多少条」，再去决定值不值得跑
    val pending = remember(session.entries.size, session.aiSuggestExisting, session.switches) {
        session.aiTargets(AiFill.AI_FIELDS).size
    }

    SheetScaffold(
        title = "AI 补全",
        onDismiss = onDismiss,
        footer = {
            // 按当前是否配好密钥决定主按钮落在哪条路上 —— 没配就直接把
            // 「复制提示词」推成主按钮，省得用户点一次直连再看报错。
            if (state.aiConfig.ready) {
                SheetPrimaryButton(
                    label = if (state.aiBusy) "调用中…" else "开始补全（$pending 条）",
                    onClick = {
                        if (state.aiBusy || pending == 0) return@SheetPrimaryButton
                        state.aiPrompt = prompt
                        scope.launch { state.runAi() }
                    },
                    modifier = Modifier.weight(1f),
                )
                SheetGhostButton("手动粘贴", onClick = { showAdvanced = true }, modifier = Modifier.weight(1f))
            } else {
                SheetPrimaryButton(
                    label = "复制提示词，去网页里问",
                    onClick = { state.copyAiPrompt(prompt) },
                    modifier = Modifier.weight(1f),
                )
                SheetGhostButton("填密钥", onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.weight(1f))
            }
        },
    ) {
        // ---------- 状态说明 ----------
        AiHint("这一趟会给 $pending 条乐谱补全空字段。AI 的建议只是建议，只在被你勾选的行上生效。")

        if (pending == 0) {
            AiHint("当前没有需要补全的条目。若想让它复核已经填过的字段，请打开下面的「已有的值也让它看一遍」。")
        }

        // ---------- 选项 ----------
        AiToggle(
            title = "已有的值也让它看一遍",
            subtitle = "会一起提建议，但覆盖类默认不勾选，需要你逐条确认",
            checked = session.aiSuggestExisting,
            onToggle = { session.aiSuggestExisting = it },
        )

        // ---------- 密钥设置（可折叠） ----------
        AiSectionHeader("接口设置")
        if (!state.aiConfig.ready) {
            AiHint("还没填密钥。你可以在下面填，也可以用「复制提示词」自己去网页里问完再粘回来。")
        }

        AiKeyRow(
            label = "接口地址",
            value = state.aiConfig.endpoint,
            hint = "形如 https://api.deepseek.com/v1/chat/completions",
        ) { state.updateAiConfig(state.aiConfig.copy(endpoint = it)) }

        AiKeyRow(
            label = "模型名",
            value = state.aiConfig.model,
            hint = "例如 deepseek-chat、gpt-4o-mini",
        ) { state.updateAiConfig(state.aiConfig.copy(model = it)) }

        AiKeyRow(
            label = "API 密钥",
            value = state.aiConfig.apiKey,
            hint = "只存在本次运行的内存里，关掉 App 就没了",
            masked = true,
        ) { state.updateAiConfig(state.aiConfig.copy(apiKey = it)) }

        // ---------- 提示词 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 6.dp)
                .clickable { showAdvanced = !showAdvanced },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("提示词", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Tokens.Text2, modifier = Modifier.weight(1f))
            Text(if (showAdvanced) "收起" else "展开", fontSize = 12.sp, color = Tokens.LinkBlue)
        }
        if (showAdvanced) {
            AiMultiline(
                value = prompt,
                onChange = {
                    prompt = it
                    state.aiPrompt = it
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                AiMiniButton("恢复默认") {
                    prompt = AiFill.DEFAULT_PROMPT
                    state.aiPrompt = null
                }
                AiMiniButton("复制提示词") { state.copyAiPrompt(prompt) }
            }
        }

        // ---------- 手动粘贴那条路 ----------
        AiSectionHeader("手动粘贴")
        AiHint("去任意聊天窗里问一遍，把它的回答原样贴进下面。只认 JSON 数组，多出来的解释文字会自动跳过。")
        AiMultiline(
            value = state.aiPaste,
            onChange = { state.aiPaste = it },
            placeholder = "[{\"i\":0,\"composers\":\"Frédéric Chopin\"}]",
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Tokens.Surface3)
                .clickable(enabled = state.aiPaste.isNotBlank()) { state.applyPastedAi() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "解析粘贴的内容",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (state.aiPaste.isNotBlank()) Tokens.Text1 else Tokens.Text3,
            )
        }

        // ---------- 结果 ----------
        state.aiError?.let { err ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Tokens.DangerBg)
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(AppIcons.Close, contentDescription = null, tint = Tokens.DangerFg, modifier = Modifier.size(14.dp))
                    Text(err, fontSize = 12.sp, color = Tokens.DangerFg, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        if (state.aiLastApplied > 0) {
            AiHint("上一次补全产生了 ${state.aiLastApplied} 处建议，已在列表里标出。")
        }
    }
}

// ---------------------------------------------------------------- 小部件

@Composable
private fun AiHint(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = Tokens.Text3,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
private fun AiSectionHeader(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.ExtraBold,
        color = Tokens.Text1,
        modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
    )
}

/** 一行开关 */
@Composable
private fun AiToggle(title: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Tokens.Surface2)
            .clickable { onToggle(!checked) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Tokens.Text1)
            Text(subtitle, fontSize = 11.sp, color = Tokens.Text3)
        }
        Box(
            modifier = Modifier
                .padding(start = 10.dp)
                .size(22.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (checked) Tokens.Accent else Tokens.Surface3),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Text("✓", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Tokens.AccentFg)
        }
    }
}

/**
 * 一行可编辑的设置项。
 *
 * 用最朴素的 `BasicTextField` 观感（外层套圆角底色）而不是 Material 的
 * `OutlinedTextField` —— 工程其它地方的输入框都是这个长相，保持一致。
 */
@Composable
private fun AiKeyRow(
    label: String,
    value: String,
    hint: String,
    masked: Boolean = false,
    onChange: (String) -> Unit,
) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Tokens.Text2)
        AiMultiline(
            value = value,
            onChange = onChange,
            placeholder = hint,
            singleLine = true,
            masked = masked,
        )
    }
}

@Composable
private fun AiMultiline(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    singleLine: Boolean = false,
    masked: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp)
            .heightIn(min = if (singleLine) 0.dp else 96.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Tokens.Surface2)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, fontSize = 13.sp, color = Tokens.Text3)
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 13.sp,
                color = Tokens.Text1,
            ),
            visualTransformation = if (masked) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AiMiniButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Tokens.RadiusChip))
            .background(Tokens.Surface3)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Tokens.Text1)
    }
}
