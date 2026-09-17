package com.example.scoreapp.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.model.Score
import com.example.scoreapp.model.ScoreSet
import com.example.scoreapp.ui.ScoreAppState
import com.example.scoreapp.ui.components.ScoreCard
import com.example.scoreapp.ui.components.SheetPrimaryButton
import com.example.scoreapp.ui.components.SheetScaffold
import com.example.scoreapp.ui.theme.Tokens

/**
 * 谱单详情弹层：列出谱单包含的乐谱，底部提供「打开谱单」。
 */
@Composable
fun SetDetailSheet(
    state: ScoreAppState,
    set: ScoreSet,
    members: List<Score>,
    onDismiss: () -> Unit,
) {
    SheetScaffold(
        title = set.name,
        onDismiss = onDismiss,
        footer = {
            SheetPrimaryButton(
                label = "打开谱单",
                onClick = { state.openSetInLibrary(set) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Text(
            text = set.desc,
            fontSize = 12.5.sp,
            color = Tokens.Text2,
            lineHeight = 19.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            members.forEach { score ->
                ScoreCard(
                    score = score,
                    onOpen = { state.openDetail(score) },
                    onEdit = { state.openEditor(score) },
                    onView = { state.openPdf(score) },
                    onShare = { state.share(score) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
