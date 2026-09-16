package com.example.scoreapp.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.data.SampleLibrary
import com.example.scoreapp.domain.ComposerNames
import com.example.scoreapp.ui.ScoreAppState
import com.example.scoreapp.ui.components.SheetDangerButton
import com.example.scoreapp.ui.components.SheetGhostButton
import com.example.scoreapp.ui.components.SheetPrimaryButton
import com.example.scoreapp.ui.components.SheetScaffold
import com.example.scoreapp.ui.theme.Tokens

/**
 * 「编辑乐谱」弹层。
 *
 * 表单绑定到 [ScoreAppState.editing] 草稿对象，取消时不会污染原数据。
 * 文本字段旁给出常用取值建议，减少手输错别字导致的筛选分面碎片化。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditSheet(state: ScoreAppState, onDismiss: () -> Unit) {
    val draft = state.editing ?: return

    SheetScaffold(
        title = "编辑乐谱",
        onDismiss = onDismiss,
        footer = {
            SheetDangerButton(
                label = "删除",
                onClick = {
                    state.allScores.firstOrNull { it.id == state.editingId }?.let(state::delete)
                    onDismiss()
                },
                modifier = Modifier.width(88.dp),
            )
            SheetGhostButton(
                label = "取消",
                onClick = onDismiss,
                modifier = Modifier.width(80.dp),
            )
            SheetPrimaryButton(
                label = "保存",
                onClick = { state.saveDraft() },
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        FormField("标题", placeholder = "乐谱标题", value = draft.title) { draft.title = it }

        FormField(
            label = "作曲家（点选下方常用姓氏自动填全名）",
            placeholder = "如 路德维希·范·贝多芬",
            value = draft.composer,
            suggestions = ComposerNames.ALIAS.values.toList(),
        ) { draft.composer = it }

        FormField("曲目类型", placeholder = "如 奏鸣曲", value = draft.type, suggestions = SampleLibrary.TYPES) { draft.type = it }
        FormField("乐器", placeholder = "如 钢琴", value = draft.instrument, suggestions = SampleLibrary.INSTRUMENTS) { draft.instrument = it }
        FormField("时期 / 风格", placeholder = "如 浪漫", value = draft.period, suggestions = SampleLibrary.PERIODS) { draft.period = it }
        FormField("难度", placeholder = "如 高级", value = draft.level, suggestions = SampleLibrary.LEVELS) { draft.level = it }
        FormField("来源", placeholder = "如 本地导入", value = draft.source, suggestions = SampleLibrary.SOURCES) { draft.source = it }
        FormField("页数", placeholder = "如 16", value = draft.pages, keyboardNumber = true) { draft.pages = it }

        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormField(
    label: String,
    placeholder: String,
    value: String,
    suggestions: List<String>? = null,
    keyboardNumber: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    Column(Modifier.padding(top = 11.dp)) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Tokens.Text2,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            placeholder = {
                Text(
                    text = placeholder,
                    fontSize = 14.5.sp,
                    color = Tokens.Text3,
                )
            },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.5.sp),
            keyboardOptions = if (keyboardNumber) {
                androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                )
            } else {
                androidx.compose.foundation.text.KeyboardOptions.Default
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
        if (suggestions != null) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                suggestions.take(6).forEach { suggestion ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(Tokens.Surface)
                            .clickable { onValueChange(suggestion) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = suggestion,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Tokens.Text2,
                        )
                    }
                }
            }
        }
    }
}
