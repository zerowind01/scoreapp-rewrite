package com.example.scoreapp.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.data.SampleLibrary
import com.example.scoreapp.domain.ComposerSuggest
import com.example.scoreapp.ui.ScoreAppState
import com.example.scoreapp.ui.components.SheetDangerButton
import com.example.scoreapp.ui.components.SheetGhostButton
import com.example.scoreapp.ui.components.SheetPrimaryButton
import com.example.scoreapp.ui.components.SheetScaffold
import com.example.scoreapp.ui.theme.Tokens
import kotlinx.coroutines.delay

/**
 * 以下四个尺寸是"编辑页减密度"的落地参数，`internal` 是为了让
 * `LayoutMetricsTest` 直接断言——散落在 Composable 里的字面量
 * 会在后续改动中被顺手调回去，抽成常量才锁得住。
 *
 * 取值对齐原型 `.field` / `.suggchip` 的 CSS。
 */

/** 表单字段上下留白。原先 11dp 挤在弹层里像一张表格，放宽后单屏滚动也不压抑 */
internal val editFieldTopPadding = 16.dp

/** 字段标签字号。原来 12sp 与正文 14.5sp 贴得太近，层级分不出来 */
internal val editFieldLabelSize = 13.sp

/** 输入框内文字与占位符字号 */
internal val editFieldTextSize = 15.sp

/** 普通字段的建议 chip 字号 */
internal val editChipTextSize = 13.sp

/**
 * 「编辑乐谱」弹层。
 *
 * 表单绑定到 [ScoreAppState.editing] 草稿对象，取消时不会污染原数据。
 * 作曲家一栏改为输入框 + 自动补全候选（见 [ComposerSuggest]），
 * 其余字段保留点选 chip 作快速填充。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditSheet(state: ScoreAppState, onDismiss: () -> Unit) {
    val draft = state.editing ?: return

    // 与「更多」弹层一致：确认态 3 秒无操作自动复原，避免残留
    LaunchedEffect(state.deleteArmed) {
        if (state.deleteArmed) {
            delay(3000)
            state.disarmDelete()
        }
    }

    SheetScaffold(
        title = "编辑乐谱",
        onDismiss = onDismiss,
        footer = {
            SheetDangerButton(
                // 两段式确认：首点切到「确认删除」，再点才真正执行，避免与「保存」误触
                label = if (state.deleteArmed) "确认删除" else "删除",
                onClick = {
                    val target = state.allScores.firstOrNull { it.id == state.editingId }
                    if (target != null) state.requestDelete(target)
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

        ComposerField(value = draft.composer) { draft.composer = it }

        FormField("曲目类型", placeholder = "如 奏鸣曲", value = draft.type, suggestions = SampleLibrary.TYPES) { draft.type = it }
        FormField("乐器", placeholder = "如 钢琴", value = draft.instrument, suggestions = SampleLibrary.INSTRUMENTS) { draft.instrument = it }
        FormField("时期 / 风格", placeholder = "如 浪漫", value = draft.period, suggestions = SampleLibrary.PERIODS) { draft.period = it }
        FormField("难度", placeholder = "如 高级", value = draft.level, suggestions = SampleLibrary.LEVELS) { draft.level = it }
        FormField("来源", placeholder = "如 本地导入", value = draft.source, suggestions = SampleLibrary.SOURCES) { draft.source = it }
        FormField("页数", placeholder = "如 16", value = draft.pages, keyboardNumber = true) { draft.pages = it }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 作曲家一栏：输入即过滤的自动补全。
 *
 * 三个状态决定候选面板是否展开：
 * - [focused]：输入框是否持有焦点。失焦后收起，避免盖住下面的字段。
 * - [justPicked]：刚点过某条候选。此时输入框会因为赋值而短暂失去/重获焦点，
 *   若不屏蔽，面板会在点选的瞬间又弹回来——用户以为没生效。
 * - 候选为空时不展开，避免留一条空白边。
 */
@Composable
private fun ComposerField(value: String, onValueChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var justPicked by remember { mutableStateOf(false) }

    val candidates = remember(value) { ComposerSuggest.suggestions(value) }
    val expanded = focused && !justPicked && candidates.isNotEmpty()

    Column(Modifier.padding(top = editFieldTopPadding)) {
        Text(
            text = "作曲家",
            fontSize = editFieldLabelSize,
            fontWeight = FontWeight.Bold,
            color = Tokens.Text2,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = {
                justPicked = false
                onValueChange(it)
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    if (it.isFocused) {
                        justPicked = false
                    }
                    focused = it.isFocused
                },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            placeholder = {
                Text(text = "输入姓氏，如 贝多芬", fontSize = editFieldTextSize, color = Tokens.Text3)
            },
            textStyle = TextStyle(fontSize = editFieldTextSize),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    // 回车即采纳首条候选——这正是「常用作曲家」原来的点选效果
                    val first = candidates.firstOrNull()
                    if (first != null) {
                        justPicked = true
                        onValueChange(first)
                    }
                },
            ),
            colors = fieldColors(),
        )

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Tokens.Surface)
                    .border(1.dp, Tokens.Line, RoundedCornerShape(12.dp))
                    .heightIn(max = 264.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                candidates.forEach { name ->
                    ComposerSuggestionRow(name = name, query = value) {
                        justPicked = true
                        onValueChange(name)
                    }
                }
            }
        } else if (value.isNotBlank()) {
            // 收起后仍给一行提示，说明这个名字是不是库里的常用写法
            Text(
                text = if (ComposerSuggest.all.contains(value)) {
                    "已在常用作曲家表中"
                } else {
                    "未在常用表中，将按原文保存"
                },
                fontSize = 12.sp,
                color = Tokens.Text3,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 单条候选项：整串里命中查询词的那一段加深并加下划线色，便于确认匹配位置 */
@Composable
private fun ComposerSuggestionRow(name: String, query: String, onClick: () -> Unit) {
    val parts = remember(name, query) { ComposerSuggest.splitHighlight(name, query) }
    val highlighted = buildAnnotatedString {
        append(parts.first)
        if (parts.second.isNotEmpty()) {
            // 命中段用蓝强调 + 加粗。Accent 是墨色（按钮底色用），
            // 在候选列表里做高亮会与正文糊在一起，所以这里走 TagTypeFg 的蓝
            withStyle(SpanStyle(color = Tokens.TagTypeFg, fontWeight = FontWeight.Bold)) {
                append(parts.second)
            }
        }
        append(parts.third)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = highlighted,
            fontSize = editFieldTextSize,
            color = Tokens.Text1,
        )
    }
}

/**
 * 通用文本字段。给出 [suggestions] 时在输入框下方铺一排 chip，
 * 点一下即整串填入。
 */
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
    Column(Modifier.padding(top = editFieldTopPadding)) {
        Text(
            text = label,
            fontSize = editFieldLabelSize,
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
            placeholder = {
                Text(
                    text = placeholder,
                    fontSize = editFieldTextSize,
                    color = Tokens.Text3,
                )
            },
            textStyle = TextStyle(fontSize = editFieldTextSize),
            keyboardOptions = if (keyboardNumber) {
                KeyboardOptions(keyboardType = KeyboardType.Number)
            } else {
                KeyboardOptions.Default
            },
            colors = fieldColors(),
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
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = suggestion,
                            fontSize = editChipTextSize,
                            fontWeight = FontWeight.SemiBold,
                            color = Tokens.Text2,
                        )
                    }
                }
            }
        }
    }
}

/** 两处输入框共用配色，抽出来免得改一处漏一处 */
@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Tokens.Surface,
    unfocusedContainerColor = Tokens.Surface,
    focusedBorderColor = Tokens.Text3,
    unfocusedBorderColor = Tokens.Line,
    focusedTextColor = Tokens.Text1,
    unfocusedTextColor = Tokens.Text1,
    cursorColor = Tokens.Text1,
)
