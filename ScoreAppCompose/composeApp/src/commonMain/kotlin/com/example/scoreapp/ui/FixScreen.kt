package com.example.scoreapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.domain.csvfix.AiFill
import com.example.scoreapp.domain.csvfix.ScoreKey
import com.example.scoreapp.ui.components.AppIcons
import com.example.scoreapp.ui.components.HairlineDivider
import com.example.scoreapp.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * 逐条校对页。
 *
 * ## 一屏一条，AI 条与便签窗同屏
 *
 * 页面自上而下四段，全部挤在一屏里（Jackson 的要求：「一个页面内就铺完
 * ai 窗和便签窗」）：
 *
 * 1. **顶栏**：返回 / 「第 N / 总数 条」 / 「已存 N」 + 进度条 + 三个工具入口；
 * 2. **AI 条**：一行输入（34dp 单行）、「生成」按钮、结果铺成五行 + 复制 / 填入本条；
 * 3. **文件名牌**：`Filename` 原样显示，标着「关联主键，永不被改」——
 *    用户要知道这一行改完之后靠什么匹配回 forScore；
 * 4. **字段区**（可滚）+ **底部动作**：「← 上一条 / 采纳并继续 →」。
 *
 * ## 为什么 AI 条在顶部而不是独立页面
 *
 * 第一版把 AI 做成独立小页，用户要先「进入 AI 页 → 生成 → 返回」。
 * 实际用起来那一步返回是纯多余：AI 生成的结果**只对当前这一条有意义**，
 * 而「当前这一条」在原来的设计里得靠参数传回来，一不小心就串了行。
 * 并到同屏之后，「生成的结果属于眼前这一条」变成了一件显而易见的事，
 * 顺便省掉了「来源条号」这个状态。
 *
 * ## 字段区为什么不用 LazyColumn
 *
 * 只有 7 行且整屏本来就放得下，用普通 Column + verticalScroll 更简单，
 * 也避免了「每条重建一个 LazyList」这种在小列表上反而更贵的用法。
 */
@Composable
fun FixScreen(state: ScoreAppState) {
    val session = state.fixSession

    // 会话被关掉（例如导出后返回）时不要崩，直接什么都不画。
    // 正常情况下 navStack 会同步弹栈，这里只是兜底。
    if (session == null) {
        Box(Modifier.fillMaxSize().background(Tokens.BgPage))
        return
    }

    val index = session.cursor
    // 视图每次重组现算。它只是把已有数据拍平，代价是几次 map 查找，
    // 比维护一份「派生缓存 + 失效逻辑」便宜得多，也不会出现缓存过期。
    val view = session.viewOf(index)
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.BgPage)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        FixTopBar(session, state)

        // ---- AI 条：与逐条页同屏常驻 ----
        FixAiBar(
            session = session,
            onGenerate = { prompt -> scope.launch { state.runAiOne(prompt) } },
            onCopy = { state.copyAiResult() },
            onFill = {
                session.fillCurrentWithAi()
                session.aiStatus = "已填入，下面勾选后「采纳并继续」"
            },
            onToggleSearch = { state.toggleSearchFallback() },
            onSearch = { scope.launch { state.runAiSearchFallback() } },
        )

        // ---- 文件名牌 ----
        FixFileBanner(view.row.fileName)

        // ---- 字段区 ----
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Tokens.Surface)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 2.dp),
        ) {
            FixFieldList(
                session = session,
                view = view,
                index = index,
                onToggle = { key -> session.toggleField(index, key) },
            )
        }

        FixFooter(session, state)
    }
}

// ---------------------------------------------------------------- 顶栏

/**
 * 顶栏：返回 + 进度 + 三个工具。
 *
 * 「已存 N」这个计数与进度条是配套的：进度条给的是**翻阅位置**，
 * 已存数给的是**实际产出**。两者分开是因为它们会显著不一致 ——
 * 用户可能连翻 30 条都不采纳任何东西（那些行本来就没问题），
 * 只显示进度会让他以为自己什么都没干成。
 */
@Composable
private fun FixTopBar(session: FixSession, state: ScoreAppState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.Surface)
            .padding(start = 8.dp, end = 16.dp, top = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(Tokens.Surface3)
                    .clickable { state.closeFix() },
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
                "第 ${session.cursor + 1} / ${session.total} 条",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Tokens.Text1,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 9.dp),
            )

            Text(
                "已存 ${session.savedCount}",
                fontSize = 11.sp,
                color = Tokens.Text3,
            )
        }

        // 进度条：宽度按「已翻到第几条」算，与原型一致
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 9.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Tokens.Surface3),
        ) {
            val fraction = if (session.total <= 0) 0f
            else (session.cursor + 1).toFloat() / session.total.toFloat()
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .background(Tokens.Accent),
            )
        }

        Row(
            modifier = Modifier.padding(top = 7.dp, bottom = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FixToolLink("跳到下一条未处理") {
                if (!session.jumpUnhandled()) state.showToast("没有未处理的条目了")
            }
            FixToolLink("导出 CSV") { state.commitFix() }
            FixToolLink("清空存档", color = Tokens.Text3) { state.clearFixStore() }
        }
    }
}

@Composable
private fun FixToolLink(label: String, color: androidx.compose.ui.graphics.Color = Tokens.LinkBlue, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.sp,
        color = color,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

// ---------------------------------------------------------------- AI 条

/**
 * 顶部 AI 生成条。
 *
 * 输入框刻意是**单行**（34dp 高）而不是多行文本框：这里要的是「写个曲名」，
 * 不是写提示词 —— 提示词本身已经写在 [AiFill.DEFAULT_PROMPT] 里了。
 * 三行高的输入框会把字段区挤下去，而字段区才是这一页的主体。
 *
 * 结果区只在有结果时展开（`aipanel` 的 `on` 类），所以不生成时整条只占一行高度。
 */
@Composable
private fun FixAiBar(
    session: FixSession,
    onGenerate: (String) -> Unit,
    onCopy: () -> Unit,
    onFill: () -> Unit,
    onToggleSearch: () -> Unit,
    onSearch: () -> Unit,
) {
    val prompt = session.aiPrompt
    var collapsed by remember { mutableStateOf(false) }

    // 换行、重新生成、清空结果时都要把折叠态收回展开。
    //
    // 折叠是**针对某一条结果**的动作（「这条我看过了」）。留着它跨条生效，
    // 下一条生成完会直接显示成收起的，用户看不到新结果，只会觉得「AI 没反应」。
    // 两个键都听：`cursor` 管换行，`aiResult` 管同一条上重新生成 / 生成失败。
    LaunchedEffect(session.cursor, session.aiResult) {
        collapsed = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.Surface)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AI 生成", fontSize = 11.sp, color = Tokens.Text3)
                Spacer(Modifier.width(7.dp))
                // 「生成中」旁边给一句话说明这次在等什么。
                // 单条生成要等模型把五个字段逐一判定完，比一次问答慢得多，
                // 光一个「…」会让人以为卡死了。
                when {
                    session.aiBusy -> {
                        Text("在识别，约几秒…", fontSize = 11.sp, color = Tokens.Text3)
                    }
                    session.aiSearching -> {
                        Text("在联网搜资料…", fontSize = 11.sp, color = Tokens.Text3)
                    }
                }
            }

            // 联网兜底徽章 = 总开关：开着是安静的小标记，关掉后黄条上出手动键
            Box(
                modifier = Modifier
                    .padding(start = 7.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (session.aiConfig.searchOn) Tokens.TagAiBg else Tokens.Surface2)
                    .clickable(onClick = onToggleSearch)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    "联网",
                    fontSize = 10.sp,
                    color = if (session.aiConfig.searchOn) Tokens.LinkBlue else Tokens.Text3,
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 7.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Tokens.Surface2)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (prompt.isEmpty()) {
                    Text("写曲名，如 月光奏鸣曲", fontSize = 13.sp, color = Tokens.Text3)
                }
                BasicTextField(
                    value = prompt,
                    onValueChange = { session.aiPrompt = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = Tokens.Text1),
                    cursorBrush = SolidColor(Tokens.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 生成中**或**兜底搜索中都禁点：兜底是两轮网络往返悬在半空，
            // 再点一次生成会把两轮的状态搅在一起（runAiOne 里还有一道同样的守卫）
            val busy = session.aiBusy || session.aiSearching
            Box(
                modifier = Modifier
                    .padding(start = 7.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (busy) Tokens.Surface3 else Tokens.Accent)
                    .clickable(enabled = !busy) { onGenerate(prompt) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (busy) "…" else "生成",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (busy) Tokens.Text2 else Tokens.AccentFg,
                )
            }
        }

        // 常驻的「怎么问才准」清单。
        //
        // 提示词是这一页唯一的质量旋钮，可原先只有生成失败后才弹一句
        // 「没认出这首曲子。曲名写全一点」。用户得失败两三次才知道该补作曲家 ——
        // 而他要填的本来就是 549 条，失败一次的代价是一整轮往返。
        // 既然它每次生成都用得上，就该常驻，而不是藏在失败里。
        //
        // 最后那句「五个字段都要列出来」是冲着 gemini-2.5-flash-lite 这类小模型写的：
        // 它们读到「不确定就填空」会理解成「不确定就别说」，于是整条只回两三个字段。
        if (session.aiResult == null) {
            AiTips()
        }

        // 状态行：优先显示错误，其次显示操作反馈
        val statusText = session.aiError ?: session.aiStatus
        if (statusText != null) {
            Text(
                statusText,
                fontSize = 11.sp,
                color = if (session.aiError != null) Tokens.DangerFg else Tokens.Text3,
                modifier = Modifier.padding(top = 5.dp),
            )
        }

        if (session.aiSearching) {
            // 搜索中：第一遍结果不画 —— 免得先闪一下“没认出”再变“搜索中”
            AiNote("第一遍没认出，正在联网搜索资料…", warn = false)
        } else if (session.aiUnknown) {
            // “搜过仍没认出”与“城根没搜过”分开措辞：路已经试过了
            val note = if (session.aiSearched) {
                "联网也没认出这首曲子" +
                    (session.aiSearchNote?.let { " —— $it" } ?: "，资料里没有可靠信息") +
                    "。曲名写全一点，或补上作曲家和作品号再试。"
            } else {
                "没认出这首曲子。曲名写全一点，或补上作曲家和作品号再试。"
            }
            AiNote(note, warn = true)
            Row(modifier = Modifier.padding(top = 7.dp)) {
                AiMiniButton(
                    if (session.aiSearched) "再搜一次" else "联网搜一次",
                    primary = false,
                    modifier = Modifier.weight(1f),
                    onClick = onSearch,
                )
            }
        } else {
            session.aiResult?.let { r ->
                // 结果区必须**限高 + 可折叠**，不能让它无限往上长。
                //
                // AI 条的上面就是字段区（本页主体）。五个字段全是长值 ——
                // 「Piano Sonata No.14, Op.27 No.2」这种会各占两行 —— 整个结果块
                // 能长到 200dp 以上，把字段区挤到只剩一条缝；软键盘一弹，
                // 字段区直接没了，用户想去核对某个字段得先盲滑。
                //
                // 两件事各管一半：
                // - `heightIn(max = 168.dp)` + `verticalScroll`：
                //   上限之内照旧全展开（常见两三个字段根本不会触发滚动），
                //   超了在结果块内部滚，不侵占字段区。
                // - 「收起」：用户已经看完结果、正专心核对字段时，
                //   把整块收掉，AI 条退回到只有输入框那一行。
                //
                // 折叠态用**独立的按钮行**，而不是让整块可点：这块里有「复制」，
                // 整块可点会跟它抢手势。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 168.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // 对不上就别存 —— AI 不知道自己在改哪一行，硬写会把数据改烂
                    if (session.aiMismatch) {
                        AiNote(
                            "生成的是「${r.title}」，但这一条原本叫「${session.currentRow?.title.orEmpty()}」。" +
                                "对不上就别存，会把这条改坏。",
                            warn = true,
                        )
                    } else if (session.aiTitleOnly) {
                        // 「只认出曲名」不能说成「识别完成」。
                        //
                        // 这个形态长得跟「认出来了但信息少」一模一样，意思却相反：
                        // 曲名往往是模型把**用户刚敲进去的那行字**原样重复了一遍，
                        // 零信息量。gemini-3.8-flash 真机上就这么干过 ——
                        // 用户看到「识别完成」+ 孤零零一行曲名，会以为 AI 认出了这首曲子、
                        // 只是信息少，于是照着那行曲名存下去。
                        AiNote(
                            "只认出曲名，其余四项模型没给出 —— 曲名可能只是把你写的字原样抄回来，" +
                                "不代表它认得这首曲子。其余字段自己填更稳。",
                            warn = true,
                        )
                        // 联网不是通行证：搜回来的 titleOnly 照样给手动键
                        Row(modifier = Modifier.padding(top = 7.dp)) {
                            AiMiniButton(
                                if (session.aiSearched) "再搜一次" else "联网搜一次",
                                primary = false,
                                modifier = Modifier.weight(1f),
                                onClick = onSearch,
                            )
                        }
                    } else {
                        AiNote("识别完成，结果如下。勾选「填入本条」才会进来。", warn = false)
                    }
                    // 来源行：联网查来的结果必须亮出依据 —— 可信度用户自己把关，
                    // App 不替搜索引擎背书。域名最多 3 条（SearchFallback.domainsOf），
                    // 一排小字点开「 · 」分隔，够定位就够了
                    if (session.aiViaSearch && session.aiSources.isNotEmpty()) {
                        Text(
                            "依据：" + session.aiSources.joinToString(" · "),
                            fontSize = 10.5.sp,
                            color = Tokens.Text3,
                            modifier = Modifier.padding(top = 7.dp),
                        )
                    }
                    // 只画 AI 真给出值的字段，空的不占行（见 FixAiResultRow 的注释）。
                    // 调性那行先换算成缩写，换算不出来的原样显示 —— 那种情况说明
                    // 模型给了个认不出的写法，更应该让用户看见。
                    if (r.title.isNotEmpty()) FixAiResultRow("曲名", r.title)
                    if (r.composer.isNotEmpty()) FixAiResultRow("作曲家", r.composer)
                    if (r.instrument.isNotEmpty()) FixAiResultRow("乐器", r.instrument)
                    if (r.genre.isNotEmpty()) FixAiResultRow("乐曲类型", r.genre)
                    val keyText = ScoreKey.shortLabel(
                        ScoreKey.parse(r.key)?.keysf,
                        ScoreKey.parse(r.key)?.keymi,
                    ).ifEmpty { r.key }
                    if (keyText.isNotEmpty()) FixAiResultRow("调性", keyText)

                    Row(
                        modifier = Modifier.padding(top = 8.dp, bottom = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 收起键常驻在**这里**而不是贴在结果块外面：
                        // 外面那圈是五个字段的地方，多一行就少一行字段。
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .clickable { collapsed = true }
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                        ) {
                            Text(
                                "收起",
                                fontSize = 11.sp,
                                color = Tokens.Text3,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            AiMiniButton("复制", primary = false, modifier = Modifier.weight(1f), onClick = onCopy)
                            AiMiniButton("填入本条", primary = true, modifier = Modifier.weight(1f), onClick = onFill)
                        }
                    }
                }
            }
        }

        // 折叠态：AI 条退回一行，只留「结果还在这儿」的提示与展开键。
        //
        // 提示里带上曲名是有必要的 —— 收起之后用户看不见任何结果内容，
        // 翻了几条回来容易忘了这行说的是哪首。
        if (collapsed && session.aiResult != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "结果已收起：${session.aiResult?.title.orEmpty()}",
                    fontSize = 11.sp,
                    color = Tokens.Text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(7.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .clickable { collapsed = false }
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text("展开", fontSize = 11.sp, color = Tokens.LinkBlue)
                }
            }
        }
    }
}

/**
 * 常驻的「怎么问才准」清单。
 *
 * 只给一条左侧竖线，不加背景块：它紧挨着下面的结果面板，做成块状会跟真正的
 * 生成结果抢注意力，用户会分不清哪块是「AI 说的」。
 *
 * 第二行是踩坑换来的，别再改回去。曾经为了治「只认两三个字段」在这里写
 * 「五个字段都要列出来」，结果它跟提示词里的「不确定就填空」打架 ——
 * 小模型遇到认不出的曲子时凑不出五个键，干脆整条放弃（五个字段一个都不给），
 * 还顺手从曲名里抄一个调性交差。**看起来有用的噪音，比明说不知道危险得多。**
 * 现在改成：认得出就补齐五项，认不出就直接说没认出。
 */
@Composable
private fun AiTips() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 7.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .width(2.dp)
                .height(30.dp)
                .background(Tokens.Line),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                "写全一点更准：带上作曲家、作品号，外国曲子可以写原文；调性写 am / bE / c#m；乐器写 Piano / 二胡。",
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                color = Tokens.Text3,
            )
            Text(
                "认得的曲子会补齐五项，不确定的留空；认不出就直接说没认出，不要硬凑 —— 猜出来的调性比留空更糟。",
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                color = Tokens.Text3,
            )
            // 这一条是踩坑补上的：提示词里已经三令五申不许回「只填曲名」那种形态，
            // 但模型不一定听 —— 真机上 gemini-3.8-flash 就没听。
            // 界面这层得让用户也认得出来，别把「它其实没认出」看成「信息少」。
            Text(
                "只回了曲名、其余四项空着的，不算认出来：那行字可能只是把你写的抄了一遍。",
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                color = Tokens.Text3,
            )
        }
    }
}

/** 结果提示条：蓝色=正常说明，红色=对不上别存 */
@Composable
private fun AiNote(text: String, warn: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 7.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (warn) Tokens.DangerBg else Tokens.TagAiBg)
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            fontSize = 11.sp,
            color = if (warn) Tokens.DangerFg else Tokens.LinkBlue,
        )
    }
}

/**
 * AI 结果的一行。
 *
 * 布局同 [FixFieldRow] 的教训：**标签定宽、值独占剩余宽度**，
 * 长值（`Piano Sonata No.14, Op.27 No.2`）自己折行，不与别的 Text 挤在一行。
 */
@Composable
private fun FixAiResultRow(label: String, value: String) {
    // 空值整行不画（调用点先判空），不再显示「（未给出）」。
    //
    // 原先五行全画、空的写灰字「（未给出）」。可「没给」本就是常态 ——
    // 提示词明确要求不确定就留空；而小模型往往只回两三个字段，
    // 屏幕上于是出现好几行灰字，看着像功能坏了。
    // 反过来只画有值的，剩下的行一眼就能看出它到底认出了什么。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, fontSize = 11.sp, color = Tokens.Text3, modifier = Modifier.width(52.dp))
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Tokens.Text1,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AiMiniButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (primary) Tokens.Accent else Tokens.Surface3)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (primary) Tokens.AccentFg else Tokens.Text2,
        )
    }
}

// ---------------------------------------------------------------- 文件名牌

@Composable
private fun FixFileBanner(fileName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.Surface2)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("文件名 · 关联主键，永不被改", fontSize = 11.sp, color = Tokens.Text3)
        Text(
            fileName.ifBlank { "（无文件名）" },
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Tokens.Text1,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
    HairlineDivider()
}

// ---------------------------------------------------------------- 字段区

/**
 * 七个字段全列出来，**包括没有建议的**。
 *
 * 为什么无建议的也要占一行：用户在逐条校对时，需要确认「这个字段规则和 AI
 * 都没动它」——只显示有建议的字段会让他分不清「没问题」和「没处理」。
 * 没建议的行画成灰「（空）/ 无建议」，一眼扫过即可。
 */
@Composable
private fun FixFieldList(
    session: FixSession,
    view: FixEntryView,
    index: Int,
    onToggle: (String) -> Unit,
) {
    // 同一时刻只展开一个字段的编辑框：七个输入框同屏会把这一条挤没。
    // 跟着 index 重置 —— 翻页时不该还留着上一条的编辑态。
    // editing 里带的是「展开时框里的初始文字」，input 靠它做 key 初始化，
    // 这样点开哪个字段框里就预填好哪个字段的现值。
    var editing by remember(index) { mutableStateOf<Pair<String, String>?>(null) }
    var input by remember(index, editing?.first) { mutableStateOf(editing?.second.orEmpty()) }

    FIELDS.forEach { f ->
        val open = editing?.first == f.key
        FixFieldRow(
            label = f.cn,
            next = view.proposedOf(f.key),
            before = view.overwrittenOf(f.key) ?: currentValue(view, f.key),
            alternate = view.alternateOf(f.key),
            taken = session.isTaken(index, f.key),
            selfTyped = view.isSelfTyped(f.key),
            blanked = view.isBlanked(f.key),
            editor = if (open) FieldEditor(
                text = input,
                used = session.usedValuesOf(f.key),
                onTextChange = { input = it },
                onApply = {
                    session.setTypedValue(index, f.key, input)
                    editing = null
                },
                onClear = {
                    session.clearFieldToBlank(index, f.key)
                    editing = null
                },
            ) else null,
            onToggle = if (view.proposedOf(f.key) != null || view.alternateOf(f.key) != null) {
                { onToggle(f.key) }
            } else {
                null
            },
            onOpenEdit = {
                editing = f.key to (
                    session.typedOf(index, f.key)
                        ?: view.proposedOf(f.key)
                        ?: prefillOf(view, f.key)
                    )
            },
            onCloseEdit = { editing = null },
            // 撤回自填：值、勾选、存档一起抹掉，退回规则/AI 给的值
            onRevoke = { session.clearTypedValue(index, f.key); editing = null },
        )    }

    if (!view.hasChange) {
        Text(
            "这一条无需改动，直接翻下一条即可",
            fontSize = 11.sp,
            color = Tokens.Text3,
            modifier = Modifier.padding(vertical = 10.dp),
        )
    }

    view.notes.forEach { note ->
        Text("· $note", fontSize = 11.sp, color = Tokens.Text3, modifier = Modifier.padding(bottom = 4.dp))
    }
    view.keyRejected?.let { raw ->
        Text(
            "· 调性「$raw」认不出来，已忽略",
            fontSize = 11.sp,
            color = Tokens.TabInactive,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

private val FIELDS = listOf(
    FixFieldSpec("title", "曲名"),
    FixFieldSpec("comp", "作曲家"),
    FixFieldSpec("genre", "乐曲类型"),
    FixFieldSpec("tag", "编配"),
    FixFieldSpec("label", "标签"),
    FixFieldSpec("ref", "来源"),
    FixFieldSpec("keysf", "调性"),
)

/** 当前值。调性走 [ScoreKey.label] 翻成人话 */
private fun currentValue(view: FixEntryView, key: String): String = when (key) {
    "title" -> view.row.title
    "comp" -> view.row.composer
    "genre" -> view.row.genre
    "tag" -> view.row.tag
    "label" -> view.row.label
    "ref" -> view.row.reference
    "keysf" -> ScoreKey.label(view.row.keysf, view.row.keymi)
    else -> ""
}

/**
 * 「自己填」展开时框里的初始文字。
 *
 * 调性单独走缩写（`c#m` 而不是 `升C小调`）：人读「C♯小调」，
 * 但要**写**就写缩写 —— 缩写才是这一栏认得回去的写法，也更好打。
 */
private fun prefillOf(view: FixEntryView, key: String): String =
    if (key == "keysf") ScoreKey.shortLabel(view.row.keysf, view.row.keymi)
    else currentValue(view, key)

/**
 * 某个字段正展开着「自己填」的编辑框
 */
private class FieldEditor(
    val text: String,
    /** 这个字段攒下的「用过的值」 */
    val used: List<String>,
    val onTextChange: (String) -> Unit,
    val onApply: () -> Unit,
    /** 「我要求这一格是空的」——连原值一起压掉，与撤回自填不是一回事 */
    val onClear: () -> Unit,
)

/**
 * 一个字段。
 *
 * **布局是踩过炸行换来的**：第一版把「标签 + 原值 + → + 新值」四个裸 `Text`
 * 排在同一 `Row` 里且都没有宽度约束，40+ 字符的长标题一进来整行炸成竖条。
 * 现在的两条硬规矩：
 *  - 标签定宽（46dp），值独占剩余宽度 —— 永远只有一个 Text 会折行；
 *  - 原值与新值**上下不同行**，各自只在自己那一行折。
 *
 * 三种视觉状态：
 *  - 有候选值：原值（灰，有的话）+ 新值（蓝粗），覆盖时挂红「覆盖」徽标；
 *    值是用户手打的时候再挂一个绿「自填」标，让他一眼看出这条不是机器猜的。
 *  - 只有备选：原值（黑）+ 备选（蓝粗，挂蓝「备选」徽标）+ 一行说明；
 *  - 什么都没有：原值或「（空）」+ 灰「无建议」。
 *
 * 第四种是**清空态**（比上面三种都强）：原值划掉 + 灰「将清空」+ 灰徽标「已清空」
 * + 一行「导出时这一格写成空，原值不再保留」。
 * 四样缺一不可 —— 只写一个灰「已清空」的话，用户看到那个错值不见了会以为
 * 「这一格已经没问题了」，实际导出后原值没了、新值也没填，比不清更糟。
 *
 * **每个字段都能自己填**（含规则和 AI 都给不出值的「标签」「来源」）：
 * 下面常驻一行「＋ 自己填」，点开是输入框 + 这个字段攒下的「用过的值」。
 * 没有它，这两列在页面上永远只是灰着的死行 —— 而这一页叫「标签工具」。
 */
@Composable
private fun FixFieldRow(
    label: String,
    next: String?,
    before: String,
    alternate: String?,
    taken: Boolean,
    selfTyped: Boolean,
    blanked: Boolean,
    editor: FieldEditor?,
    onToggle: (() -> Unit)?,
    onOpenEdit: () -> Unit,
    onCloseEdit: () -> Unit,
    onRevoke: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = Tokens.Text3,
            modifier = Modifier
                .width(46.dp)
                .padding(top = 2.dp),
        )

        Column(Modifier.weight(1f)) {
            if (blanked) {
                if (before.isNotBlank()) {
                    Text(
                        before,
                        fontSize = 12.sp,
                        color = Tokens.Text3,
                        textDecoration = TextDecoration.LineThrough,
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "将清空",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Tokens.Text3,
                        modifier = Modifier.weight(1f),
                    )
                    AiBadge("已清空", Tokens.Surface2, Tokens.Text3)
                }
                Text(
                    "导出时这一格写成空，原值不再保留",
                    fontSize = 11.sp,
                    color = Tokens.Text3,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else if (next == null) {
                Text(
                    before.ifBlank { "（空）" },
                    fontSize = 12.sp,
                    color = Tokens.Text2,
                )
                if (alternate != null) {
                    Row(
                        modifier = Modifier.padding(top = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            alternate,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Tokens.LinkBlue,
                            modifier = Modifier.weight(1f),
                        )
                        AiBadge("备选", Tokens.TagAiBg, Tokens.LinkBlue)
                    }
                    Text(
                        "AI 建议改成这个，原值会保留",
                        fontSize = 11.sp,
                        color = Tokens.Text3,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                } else {
                    Text(
                        "无建议",
                        fontSize = 11.sp,
                        color = Tokens.Text3,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            } else {
                if (before.isNotBlank()) {
                    Text(before, fontSize = 12.sp, color = Tokens.Text3)
                }
                Row(
                    modifier = Modifier.padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        next.ifBlank { "（清空）" },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (next.isBlank()) Tokens.Text3 else Tokens.LinkBlue,
                        modifier = Modifier.weight(1f),
                    )
                    if (before.isNotBlank()) {
                        AiBadge("覆盖", Tokens.DangerBg, Tokens.DangerFg)
                    }
                    if (selfTyped) {
                        AiBadge("自填", Tokens.PillLive, androidx.compose.ui.graphics.Color.White)
                    }
                }
            }

            // ---- 自己填 ----
            if (editor == null) {
                Text(
                    when {
                        blanked -> "✎ 改回原值"
                        selfTyped -> "✎ 改自己填的值"
                        else -> "＋ 自己填"
                    },
                    fontSize = 11.sp,
                    color = Tokens.LinkBlue,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable(onClick = onOpenEdit),
                )
            } else {
                Column(modifier = Modifier.padding(top = 7.dp)) {
                    FixEditInput(label = label, text = editor.text, onTextChange = editor.onTextChange)

                    if (editor.used.isNotEmpty()) {
                        Text(
                            "用过的值",
                            fontSize = 11.sp,
                            color = Tokens.Text3,
                            modifier = Modifier.padding(top = 7.dp),
                        )
                        // chip 会折行，用 FlowRow 而不是 Row ——
                        // 一行排不下时 Row 会把后面的 chip 挤出屏外
                        FlowRow(
                            modifier = Modifier.padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            editor.used.forEach { v ->
                                UsedValueChip(v) { editor.onTextChange(v) }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        // 左侧键在三个态之间换措辞：
                        //  - 清空态 → 「改回原值」（撤掉清空），不能说「撤回自填」——
                        //    清空不是一种自填，用户根本没往这儿写过字。
                        //  - 自填态 → 「撤回自填」（值退回规则/AI 给的）
                        //  - 其余   → 「收起」（取消编辑，不动任何值）
                        AiMiniButton(
                            label = if (blanked) "改回原值" else if (selfTyped) "撤回自填" else "收起",
                            primary = false,
                            modifier = Modifier.weight(1f),
                            onClick = if (blanked || selfTyped) onRevoke else onCloseEdit,
                        )
                        // 「清空」与「撤回自填」是两件不同的事，绝不能合并成一个键：
                        //   撤回自填 = 我写错了，退回规则/AI 给的值（原值该被覆盖时照样覆盖）
                        //   清空     = 我要求这一格是空的（连原值都不要）
                        // 后者严格更强。合并后，用户想「撤掉我填的」会把原值也清了，
                        // 而真正想清空的人反而找不到入口。
                        AiMiniButton(
                            label = "清空",
                            primary = false,
                            modifier = Modifier.weight(1f),
                            onClick = editor.onClear,
                        )
                        AiMiniButton(
                            label = "用这个值",
                            primary = true,
                            modifier = Modifier.weight(1f),
                            onClick = editor.onApply,
                        )
                    }
                }
            }
        }

        if (onToggle != null) {
            FixCheckBox(checked = taken, onClick = onToggle)
        }
    }
    HairlineDivider()
}

/** 「自己填」的单行输入框，观感与顶部 AI 条那个框一致 */
@Composable
private fun FixEditInput(label: String, text: String, onTextChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Tokens.Surface2)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (text.isEmpty()) {
            Text("写$label", fontSize = 13.sp, color = Tokens.Text3)
        }
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, color = Tokens.Text1),
            cursorBrush = SolidColor(Tokens.Accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 「用过的值」的 chip：点一下就把值填进输入框（不直接采用，还留一步确认） */
@Composable
private fun UsedValueChip(value: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Tokens.Surface3)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(value, fontSize = 12.sp, color = Tokens.Text2)
    }
}

@Composable
private fun AiBadge(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg)
    }
}

/** 自绘勾选框，与工程其它地方保持一致的方块 + 对勾观感 */
@Composable
private fun FixCheckBox(checked: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(start = 10.dp, top = 2.dp)
            .size(22.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (checked) Tokens.Accent else Tokens.Surface3)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text("✓", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Tokens.AccentFg)
        }
    }
}

// ---------------------------------------------------------------- 底部动作区

/**
 * 底部：「← 上一条 / 采纳并继续 →」。
 *
 * 「采纳并继续」不是「保存」—— 勾选时已经写进存档了。它的实际语义是
 * 「这一条我看完了，翻下一条」，顺带把草稿收尾。文案选「采纳并继续」
 * 而不是「下一条」，是为了让用户知道**勾了的东西会被记住**，
 * 而不是「翻页会丢掉」。
 */
@Composable
private fun FixFooter(session: FixSession, state: ScoreAppState) {
    Column(Modifier.fillMaxWidth()) {
        HairlineDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Tokens.Surface)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Tokens.Surface3)
                    .clickable(enabled = session.canPrev) { session.go(-1) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "← 上一条",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (session.canPrev) Tokens.Text2 else Tokens.Text3,
                )
            }

            Box(
                modifier = Modifier
                    .weight(1.5f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Tokens.Accent)
                    .clickable { session.commitAndNext() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (session.canNext) "采纳并继续 →" else "采纳并完成",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Tokens.AccentFg,
                )
            }
        }
    }
}
