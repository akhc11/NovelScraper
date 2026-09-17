package com.example.novelscraper.translation.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelscraper.ui.theme.AppColors
import kotlinx.coroutines.launch

/**
 * 自前ブラウザの選択画面(Phase 1: 一覧+チェック+パンくず+.txt絞り+決定のみ)。
 * 既存パネルからはコールバック経由の疎結合で呼び出す。
 */
@Composable
fun SafBrowserDialog(
    state: SafBrowserState,
    onConfirm: (List<PickerTarget>, SafBrowserState.CollectResult) -> Unit,
    onDismiss: () -> Unit
) {
    val crumbs by state.crumbs.collectAsState()
    val rows by state.rows.collectAsState()
    val loading by state.loading.collectAsState()
    val error by state.error.collectAsState()
    val checked by state.checked.collectAsState()
    val txtOnly by state.txtOnly.collectAsState()
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SafBrowserState.CollectResult?>(null) }

    PickerDialogShell(title = "フォルダ内から選択", onDismiss = onDismiss) {
                // パンくず(タップで移動)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (crumbs.size > 1) {
                        Text(
                            "↑上へ",
                            color = AppColors.accentTealLight,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clickable { state.goUp() }
                                .padding(4.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    // 1行固定の横スクロールパンくず(高さ変動とDialog伸縮の防止。各階層タップで移動)。
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        crumbs.forEachIndexed { index, crumb ->
                            if (index > 0) {
                                Text(" > ", color = AppColors.textTertiary, fontSize = 11.sp)
                            }
                            Text(
                                crumb.name,
                                color = if (index == crumbs.lastIndex) AppColors.textSecondary else AppColors.accentTealLight,
                                fontSize = 11.sp,
                                maxLines = 1,
                                modifier = Modifier.clickable { state.goTo(index) }
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = txtOnly,
                        onCheckedChange = { state.setTxtOnly(it) }
                    )
                    Text(".txtのみ表示", color = AppColors.textSecondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "選択 ${checked.size}件",
                        color = AppColors.accentTealLight,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    // 器の高さ一杯に広げて一覧性を上げる。窓サイズ自体は器が固定するため伸縮しない。
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(AppColors.backgroundDark, RoundedCornerShape(4.dp))
                        .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                        .padding(4.dp)
                ) {
                    when {
                        loading -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
                        error != null -> Column(
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(error ?: "", color = Color(0xFFE57373), fontSize = 11.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(onClick = { state.refresh() }) {
                                Text("再試行", color = AppColors.accentTealLight, fontSize = 12.sp)
                            }
                        }
                        rows.isEmpty() -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) { Text("対象がありません", color = AppColors.textTertiary, fontSize = 11.sp) }
                        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(rows, key = { it.doc.uri }) { row ->
                                val isDir = row.doc.isDirectory
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (isDir) state.enter(row) else state.toggle(row)
                                        }
                                        .padding(horizontal = 4.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = checked.contains(row.doc.uri),
                                        onCheckedChange = { state.toggle(row) }
                                    )
                                    Text(
                                        if (isDir) "📁" else "📄",
                                        fontSize = 14.sp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        row.doc.name,
                                        color = AppColors.textPrimary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isDir) {
                                        Text("›", color = AppColors.textTertiary, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                result?.let { r ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "フォルダ${r.dirCount}・ファイル${r.fileCount}件",
                        color = AppColors.accentTealLight,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (r.truncated) {
                        Text(
                            "⚠️ 上限のため一部除外 (${r.skipped.size}件)",
                            color = Color(0xFFFFB74D),
                            fontSize = 10.sp
                        )
                    }
                    if (r.skipped.isNotEmpty()) {
                        Text(
                            r.skipped.take(3).joinToString("\n"),
                            color = AppColors.textTertiary,
                            fontSize = 9.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            confirming = true
                            scope.launch {
                                try {
                                    val r = state.collectTargets()
                                    result = r
                                    if (r.targets.isNotEmpty()) {
                                        onConfirm(r.targets, r)
                                    }
                                } finally {
                                    confirming = false
                                }
                            }
                        },
                        enabled = checked.isNotEmpty() && !confirming,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.accentTeal,
                            disabledContainerColor = AppColors.surfaceMedium
                        ),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (confirming) "集計中..." else "決定",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(
                        onClick = { state.clearChecked() },
                        enabled = checked.isNotEmpty() && !confirming,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.surfaceMedium
                        ),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("解除", color = AppColors.textSecondary, fontSize = 12.sp)
                    }
                }
    }
}
