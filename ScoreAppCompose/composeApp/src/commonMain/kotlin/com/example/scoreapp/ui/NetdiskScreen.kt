package com.example.scoreapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.domain.netdisk.Netdisk
import com.example.scoreapp.domain.netdisk.NetdiskConfig
import com.example.scoreapp.domain.netdisk.NetdiskEntry
import com.example.scoreapp.ui.components.AppIcons
import com.example.scoreapp.ui.components.HairlineDivider
import com.example.scoreapp.ui.components.NetOpenOverlay
import com.example.scoreapp.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * 网盘浏览整页（AList / WebDAV）。
 *
 * 对应原型 `prototype/netdisk-demo.html`，规格注释在那个文件头。
 *
 * ## 为什么是整页
 *
 * 它自带顶栏、连接条、面包屑和一列要滚动的目录 —— 弹层的高度撑不开。
 * 与校对页、AI 设置页同样在进入时隐藏底栏。
 *
 * ## 四条不能改回去的规格
 *
 * 1. **只在线打开，不入库**：谱子不进乐谱库列表。点开时下载到缓存，缓存只留最近 1 份。
 * 2. **AList 地址末尾必须带 /dav**：填错一律 404，而 404 的文案要直接把这句话说出来。
 * 3. **非 PDF 不列，但要交代**：底部写「已跳过 N 个非 PDF」，一声不响地藏掉会让人以为文件丢了。
 * 4. **错误不许显示裸 HTTP 数字**：统一走 [Netdisk.errorText] 翻成人话。
 */
@Composable
fun NetdiskScreen(state: ScoreAppState) {
    val scope = rememberCoroutineScope()

    // 换目录自然重跑；原地「重试」靠 tick —— 目录没变也要能再发一次请求
    LaunchedEffect(state.netdiskCwd, state.netdiskTick) {
        state.loadNetdiskDir()
    }

    val cfg = state.netdiskConfig
    val entries = state.netdiskEntries
    val sorted = Netdisk.sortEntries(entries)
    val skipped = Netdisk.skippedCount(entries)
    val error = state.netdiskError

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.BgPage)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(Modifier.fillMaxSize()) {
            NetdiskTopBar(
                // 有上级就往上退一级；已经在根了就退出这一页
                onBack = { if (!state.netdiskUp()) state.closeNetdisk() },
                onSetup = { state.openNetdiskSetup() },
            )
            NetdiskConnBar(state, onTest = { scope.launch { state.testNetdisk() } })
            NetdiskCrumb(cwd = state.netdiskCwd, onJump = { state.netdiskGo(it) })
            // 绑定条：把这个目录收进首页「网盘」页 —— 浏览页本身不入库
            NetdiskBindBar(state)

            when {
                error != null -> {
                    NetdiskStateBlock(
                        message = Netdisk.errorText(error),
                        isError = true,
                        onRetry = { state.netdiskRetry() },
                        onUp = if (state.netdiskCwd.isNotBlank()) {
                            { state.netdiskUp() }
                        } else {
                            null
                        },
                    )
                }

                !cfg.ready -> {
                    NetdiskStateBlock(
                        message = "还没配网盘。点右上「设置」填上 AList 的地址、账号、密码。",
                        isError = false,
                        onRetry = null,
                        onUp = null,
                    )
                }

                sorted.isEmpty() -> {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (state.netdiskLoading) "正在列目录…" else "这个目录是空的",
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = Tokens.Text2,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                else -> {
                    LazyColumn(modifier = Modifier.weight(1f).background(Tokens.Surface)) {
                        items(sorted, key = { it.path }) { entry ->
                            if (entry.dir || Netdisk.isPdfName(entry.name)) {
                                NetdiskRow(entry) {
                                    if (entry.dir) {
                                        state.netdiskGo(relOf(entry, state))
                                    } else {
                                        scope.launch { state.netdiskOpen(entry) }
                                    }
                                }
                            }
                            // 非 PDF 文件整行不画 —— 但下面有一行交代，不能悄悄藏掉
                        }
                    }
                    if (skipped > 0) {
                        Text(
                            "已跳过 $skipped 个非 PDF 文件（乐谱库只认 PDF）",
                            fontSize = 10.5.sp,
                            color = Tokens.Text3,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Tokens.Surface)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }

        // 设置面板：从上面盖下来，跟整页一个层级（原型同一处理）
        if (state.netdiskSetupOpen) {
            NetdiskSetupPanel(state)
        }

        NetdiskOpenOverlay(state, onRetry = { entry -> scope.launch { state.netdiskOpen(entry) } })
    }
}

/** 把条目地址减掉根，还原成相对路径（面包屑 / 进目录用） */
private fun relOf(entry: NetdiskEntry, state: ScoreAppState): String {
    val root = Netdisk.normPath(state.netdiskConfig.addr)
    val abs = Netdisk.normPath(entry.path)
    return if (root.isNotEmpty() && abs.startsWith(root)) abs.substring(root.length) else ""
}

// ---------------------------------------------------------------- 顶栏 / 连接条 / 面包屑

@Composable
private fun NetdiskTopBar(onBack: () -> Unit, onSetup: () -> Unit) {
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
                contentDescription = "返回上一级",
                tint = Tokens.Text2,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            "网盘",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Tokens.Text1,
            modifier = Modifier.padding(start = 9.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            "设置",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Tokens.LinkBlue,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Tokens.TagAiBg)
                .clickable(onClick = onSetup)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * 绑定条：把**当前目录**收进首页「网盘」页。
 *
 * 浏览页本身不入库（点开即看、看完就丢），要进乐谱库必须显式绑一次。
 * 只收当前层的 PDF —— 这句话必须写在按钮旁边，否则用户会以为子目录里的也进来了。
 */
@Composable
private fun NetdiskBindBar(state: ScoreAppState) {
    val bound = state.netdiskCurrentBound()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.Surface2)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (bound) {
                "已收进首页「网盘」页 · 只收当前层的 PDF"
            } else {
                "把这个文件夹收进首页「网盘」页 · 只收当前层的 PDF"
            },
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = Tokens.Text2,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (bound) "取消绑定" else "绑定此文件夹",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (bound) Tokens.Text2 else Tokens.LinkBlue,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (bound) Tokens.Surface3 else Tokens.TagAiBg)
                .clickable { state.toggleBindCurrent() }
                .padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

/**
 * 常驻连接条：一眼知道连的是哪儿、现在什么状态。
 *
 * 失败时这一行显示的是**翻成人话的原因**（见 [Netdisk.errorText]），
 * 不是 `HTTP 404` —— 后者用户看不懂，也不会想到是地址少了 /dav。
 */
@Composable
private fun NetdiskConnBar(state: ScoreAppState, onTest: () -> Unit) {
    val cfg = state.netdiskConfig
    val error = state.netdiskError
    Column(Modifier.fillMaxWidth().background(Tokens.Surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            !cfg.ready -> Tokens.Text3
                            error != null -> Tokens.DangerFg
                            else -> Tokens.PillLive
                        },
                    ),
            )
            Text(
                text = when {
                    !cfg.ready -> "未配置，点右上「设置」"
                    error != null -> Netdisk.errorText(error)
                    else -> "已连上 · ${cfg.addr}"
                },
                fontSize = 11.sp,
                color = Tokens.Text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "测试连接",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Tokens.LinkBlue,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Tokens.TagAiBg)
                    .clickable(onClick = onTest)
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        HairlineDivider()
    }
}

@Composable
private fun NetdiskCrumb(cwd: String, onJump: (String) -> Unit) {
    val segs = cwd.split('/').filter { it.isNotBlank() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.Surface2)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CrumbPiece("网盘") { onJump("") }
        var acc = ""
        segs.forEach { seg ->
            Text("/", fontSize = 11.sp, color = Tokens.Text3)
            acc += "/$seg"
            val rel = acc
            CrumbPiece(seg) { onJump(rel) }
        }
    }
    HairlineDivider()
}

@Composable
private fun CrumbPiece(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = Tokens.LinkBlue,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

// ---------------------------------------------------------------- 列表行

@Composable
private fun NetdiskRow(entry: NetdiskEntry, onClick: () -> Unit) {
    Column(Modifier.background(Tokens.Surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (entry.dir) Tokens.Surface3 else Tokens.TagAiBg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (entry.dir) "目录" else "PDF",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (entry.dir) Tokens.Text2 else Tokens.LinkBlue,
                )
            }
            Text(
                entry.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Tokens.Text1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!entry.dir) {
                Text(Netdisk.formatSize(entry.size), fontSize = 11.sp, color = Tokens.Text3)
            }
            Text(
                if (entry.dir) "进入" else "打开",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Tokens.LinkBlue,
            )
        }
        HairlineDivider(Modifier.padding(start = 16.dp))
    }
}

// ---------------------------------------------------------------- 空 / 错误态

/**
 * 空态 / 错误态。
 *
 * 声明成 `ColumnScope` 扩展而不是普通函数：它要吃掉父 Column 剩下的全部高度，
 * 而 `Modifier.weight` 只在 ColumnScope 里才有 —— 写成普通函数会在里面调用时报错。
 */
@Composable
private fun ColumnScope.NetdiskStateBlock(
    message: String,
    isError: Boolean,
    onRetry: (() -> Unit)?,
    onUp: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .background(Tokens.Surface)
            .padding(horizontal = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Text(
            message,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            color = if (isError) Tokens.DangerFg else Tokens.Text2,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            MiniKey("重试", primary = true, onClick = onRetry)
        }
        if (onUp != null) {
            MiniKey("返回上一级", primary = false, onClick = onUp)
        }
    }
}

@Composable
private fun MiniKey(text: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) Tokens.Accent else Tokens.Surface3)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (primary) Tokens.AccentFg else Tokens.Text2,
        )
    }
}

// ---------------------------------------------------------------- 下载浮层

/**
 * 下载浮层。
 *
 * 成功时不经过它（直接进阅读器）；失败时留在原地给「重试 / 关闭」——
 * 一声不响地退回列表，用户只会以为点错了。
 */
@Composable
private fun NetdiskOpenOverlay(state: ScoreAppState, onRetry: (NetdiskEntry) -> Unit) {
    val entry = state.netdiskOpening ?: return
    NetOpenOverlay(
        title = entry.name,
        subtitle = Netdisk.formatSize(entry.size).ifBlank { null },
        progress = state.netdiskProgress,
        done = state.netdiskDone,
        total = state.netdiskTotal,
        error = state.netdiskOpenError,
        detail = state.netdiskOpenDetail,
        onRetry = { onRetry(entry) },
        onDismiss = { state.netdiskDismissOpen() },
    )
}

@Composable
private fun ReaderKey(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF43434A))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

// ---------------------------------------------------------------- 设置面板

/**
 * 网盘设置。
 *
 * 盖在列表上而不是另开一页：它是「填完就回到列表」的表单，
 * 压栈会多出一层返回键语义要处理。
 */
@Composable
private fun NetdiskSetupPanel(state: ScoreAppState) {
    var draft by remember { mutableStateOf(state.netdiskConfig) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.BgPage),
    ) {
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
                    .clickable(onClick = { state.closeNetdiskSetup() }),
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
                "网盘设置",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Tokens.Text1,
                modifier = Modifier.padding(start = 9.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            Text(
                "走标准 WebDAV。AList 挂了夸克、坚果云、群晖都能用 —— " +
                    "App 只认 WebDAV，不认识任何一家网盘自己的协议。",
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = Tokens.Text3,
            )

            NetdiskField(
                label = "WebDAV 地址",
                hint = "https://你的域名/dav",
                note = "AList 的 WebDAV 地址末尾要带 /dav。少了它一律 404。",
                value = draft.addr,
                keyboardUrl = true,
                onValueChange = { draft = draft.copy(addr = it) },
            )
            NetdiskField(
                label = "用户名",
                hint = "admin",
                value = draft.user,
                onValueChange = { draft = draft.copy(user = it) },
            )
            NetdiskField(
                label = "密码",
                hint = "AList 的密码",
                note = "明文存在手机上，跟 AI 配置一个套路。公网地址别用弱口令。",
                value = draft.pass,
                masked = true,
                onValueChange = { draft = draft.copy(pass = it) },
            )

            Spacer(Modifier.height(18.dp))
            Text(
                "谱子不进乐谱库",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = Tokens.Text2,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                "点开时才下载到缓存，看完就丢；缓存只留最近 1 份 —— " +
                    "打开新的会把上一份删掉，同一份再打开不重下。" +
                    "列表里只显示 PDF，其它文件会跳过并在底部写明跳过了几个。",
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
                color = Tokens.Text3,
            )

            Spacer(Modifier.height(24.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Tokens.Surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Tokens.Surface3)
                    .clickable(onClick = {
                        state.clearNetdiskConfig()
                        draft = NetdiskConfig()
                    }),
                contentAlignment = Alignment.Center,
            ) {
                Text("清空", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Tokens.Text2)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    // 三项没填全时变灰：让「还不能用」在按下去之前就看得出来
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (draft.ready) Tokens.Accent else Tokens.Surface3)
                    .clickable(enabled = draft.ready) { state.saveNetdiskConfig(draft) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "保存并连接",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (draft.ready) Tokens.AccentFg else Tokens.Text3,
                )
            }
        }
    }
}

@Composable
private fun NetdiskField(
    label: String,
    hint: String,
    value: String,
    note: String = "",
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
        if (note.isNotEmpty()) {
            Text(
                note,
                fontSize = 10.5.sp,
                lineHeight = 16.sp,
                color = Tokens.Text3,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
