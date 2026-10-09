package com.example.novelscraper.translation.picker

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelscraper.ui.theme.AppColors

/**
 * 自前ブラウザの選択画面（一覧+チェック+範囲選択+パンくず+.txt絞り+決定）。
 * 既存パネルからはコールバック経由の疎結合で呼び出す。
 * 技術的根拠1行：フォルダは直下全件意図・範囲は長押し2点式に一本化し、確定側の再帰展開を持たない。
 */
internal fun formatPickerBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${if (kb >= 100) kb.toInt().toString() else String.format("%.1f", kb)}KB"
    val mb = kb / 1024.0
    return "${if (mb >= 100) mb.toInt().toString() else String.format("%.1f", mb)}MB"
}
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SafBrowserDialog(
    state: SafBrowserState,
    onConfirm: (List<PickerTarget>, SafBrowserState.CollectResult) -> Unit,
    onDismiss: () -> Unit
) {
    val crumbs by state.crumbs.collectAsState()
    val visibleRows by state.visibleRows.collectAsState(initial = emptyList())
    val query by state.query.collectAsState()
    val loading by state.loading.collectAsState()
    val error by state.error.collectAsState()
    val checked by state.checked.collectAsState()
    val txtOnly by state.txtOnly.collectAsState()
    val sortMode by state.sortMode.collectAsState()
    val anchorUri by state.anchorUri.collectAsState()
    var sortMenuOpen by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<SafBrowserState.CollectResult?>(null) }
    // 技術的根拠1行：確定は同期的確定（IOなし・保持済み状態のみ参照）のため起動重複を作らず、プレビュー再計算の唯一入口に寄せる。
    fun refreshPreview() {
        preview = state.collectTargets()
    }
    // 技術的根拠1行：並替・絞り込み・移動は先頭から見せ直し、スクロール残存による「効いてない」誤認を作らない。
    val listState = rememberLazyListState()
    LaunchedEffect(sortMode, query, crumbs) {
        listState.scrollToItem(0)
    }

    // 技術的根拠1行：窓題は現在地フォルダ名にし、パンくずは出さない（題部の‹に一本化し伸縮を作らない）。
    PickerDialogShell(
        title = crumbs.lastOrNull()?.name ?: "フォルダ選択",
        onDismiss = onDismiss,
        navigationIcon = if (crumbs.size > 1) {
            {
                Text(
                    "‹",
                    color = AppColors.accentTealLight,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { state.goUp() }
                        .padding(8.dp)
                )
            }
        } else null
    ) {

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
                    Spacer(modifier = Modifier.width(4.dp))
                    // 技術的根拠1行：並替は読込済み行の再整列のみで追加IPCなし（Files by Google/iOSの右上方式）。
                    Box {
                        Text(
                            "並替:${sortMode.label}",
                            color = AppColors.accentTealLight,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { sortMenuOpen = true }
                                .padding(8.dp)
                        )
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false }
                        ) {
                            SafBrowserState.SortMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            (if (mode == sortMode) "✓ " else "") + mode.label,
                                            fontSize = 12.sp
                                        )
                                    },
                                    onClick = {
                                        state.setSortMode(mode)
                                        sortMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }

                // 技術的根拠1行：密集欄は公式のDecorationBox手順で作り、高さの下限56dpと戦わない。自作の行送り微調整は持たない。
                // 技術的根拠1行：単一行の中央寄せはM3配置契約に委ねる（AOSP明記）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val searchInteraction = remember { MutableInteractionSource() }
                    BasicTextField(
                        value = query,
                        onValueChange = { state.setQuery(it) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = AppColors.textPrimary,
                            fontSize = 12.sp
                        ),
                        cursorBrush = SolidColor(AppColors.accentTealLight),
                        interactionSource = searchInteraction,
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            OutlinedTextFieldDefaults.DecorationBox(
                                value = query,
                                innerTextField = inner,
                                enabled = true,
                                singleLine = true,
                                visualTransformation = VisualTransformation.None,
                                interactionSource = searchInteraction,
                                placeholder = {
                                    Text("🔍 名前で絞り込み", color = AppColors.textTertiary, fontSize = 12.sp)
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = AppColors.textPrimary,
                                    unfocusedTextColor = AppColors.textPrimary,
                                    cursorColor = AppColors.accentTealLight,
                                    focusedBorderColor = Color.DarkGray,
                                    unfocusedBorderColor = Color.DarkGray,
                                    focusedContainerColor = AppColors.backgroundDark,
                                    unfocusedContainerColor = AppColors.backgroundDark,
                                    focusedPlaceholderColor = AppColors.textTertiary,
                                    unfocusedPlaceholderColor = AppColors.textTertiary
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    )
                    // 技術的根拠1行：同一判定の二重評価を束ね、再構成たびの走査を1回にする。
                    val hasUnselected = visibleRows.any { !checked.contains(it.doc.uri) }
                    Text(
                        "全選択",
                        color = if (hasUnselected) AppColors.accentTealLight else AppColors.textTertiary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable(enabled = hasUnselected) {
                                state.selectAllVisible(visibleRows)
                            }
                            .padding(8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 技術的根拠1行：起点の所在は行の背景色でのみ示し、文言の増減による伸縮を作らない。
                Text(
                    "長押し起点→長押し終点で範囲選択・フォルダは直下のみ",
                    color = AppColors.textTertiary,
                    fontSize = 10.sp
                )

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
                        // 技術的根拠1行：絞り込み空の可視順は素行と同一のため、rows購読を持たず空判定を一本化する。
                        visibleRows.isEmpty() && query.trim().isEmpty() -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) { Text("対象がありません", color = AppColors.textTertiary, fontSize = 11.sp) }
                        visibleRows.isEmpty() -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) { Text("絞り込みに一致しません", color = AppColors.textTertiary, fontSize = 11.sp) }
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState
                        ) {
                            items(visibleRows, key = { it.doc.uri }) { row ->
                                val isDir = row.doc.isDirectory
                                val isAnchor = row.doc.uri == anchorUri
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .background(
                                            if (isAnchor) AppColors.accentTeal.copy(alpha = 0.15f)
                                            else Color.Transparent,
                                            RoundedCornerShape(4.dp)
                                        )
                                        .combinedClickable(
                                            onClick = {
                                                if (isDir) state.enter(row) else state.toggle(row)
                                            },
                                            onLongClick = {
                                                state.onLongPressRow(visibleRows, row)
                                            }
                                        )
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
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            row.doc.name,
                                            color = AppColors.textPrimary,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isDir) {
                                            Text(
                                                "直下のみ",
                                                color = AppColors.textTertiary,
                                                fontSize = 9.sp,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                    if (!isDir) {
                                        Text(
                                            formatPickerBytes(row.doc.length),
                                            color = AppColors.textTertiary,
                                            fontSize = 10.sp,
                                            maxLines = 1
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    if (isDir) {
                                        Text("›", color = AppColors.textTertiary, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // 技術的根拠1行：即登録では除外警告が目に触れないため、内容確認→登録の2段階にし、個別取消も可能にする。
                val previewEntries by state.checkedEntries.collectAsState()
                if (preview == null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            // 技術的根拠1行：除外・打切りなしのクリーンは即登録し、警告がある時だけ確認画面を挟んで摩擦をなくす。
                            onClick = {
                                refreshPreview()
                                val r = preview
                                if (r != null && !r.truncated && r.skipped.isEmpty()) {
                                    onConfirm(r.targets, r)
                                }
                            },
                            enabled = checked.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.accentTeal,
                                disabledContainerColor = AppColors.surfaceMedium
                            ),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                "登録する",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Button(
                            onClick = { state.clearChecked() },
                            enabled = checked.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.surfaceMedium
                            ),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("解除", color = AppColors.textSecondary, fontSize = 12.sp)
                        }
                    }
                } else {
                    val r = preview!!
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "選択内容の確認",
                        color = AppColors.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .background(AppColors.backgroundDark, RoundedCornerShape(4.dp))
                            .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                            .padding(4.dp)
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(previewEntries, key = { it.uri }) { e ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        (if (e.isDirectory) "📁 " else "📄 ") + e.relPath,
                                        color = AppColors.textPrimary,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    // 技術的根拠1行：フォルダ内包数は確定時点では数えない（IOなし）ため、件数誤認をバッジ表示で防ぐ。
                                    if (e.isDirectory) {
                                        Text(
                                            "直下全件",
                                            color = AppColors.accentTealLight,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        "✕",
                                        color = Color.Gray,
                                        fontSize = 12.sp,
                                        modifier = Modifier
                                            .clickable {
                                                state.uncheck(e.uri)
                                                refreshPreview()
                                            }
                                            .padding(8.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "フォルダ${r.dirCount}・ファイル${r.fileCount}件（フォルダは直下のみ）",
                        color = AppColors.accentTealLight,
                        fontSize = 11.sp,
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
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            // 技術的根拠1行：プレビュー表示後も一覧は操作可能なため、登録は最新確定で上書きし陳腐登録を作らない。
                            onClick = {
                                refreshPreview()
                                preview?.let { onConfirm(it.targets, it) }
                            },
                            enabled = preview?.targets?.isNotEmpty() == true,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.accentTeal,
                                disabledContainerColor = AppColors.surfaceMedium
                            ),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                "登録する",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Button(
                            onClick = { preview = null },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.surfaceMedium
                            ),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("選び直す", color = AppColors.textSecondary, fontSize = 12.sp)
                        }
                    }
                }
    }
}
