package com.example.novelscraper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelscraper.MainUiState
import com.example.novelscraper.TranslationEngine
import com.example.novelscraper.ui.theme.AppColors

@Composable
fun TranslationPanel(
    uiState: MainUiState,
    onSelectEngineTab: (TranslationEngine) -> Unit,
    onSelectFolderClick: () -> Unit,
    onRemoveFolderClick: (TranslationEngine, Int) -> Unit,
    onClearFoldersClick: (TranslationEngine) -> Unit,
    onStartTranslationClick: (TranslationEngine) -> Unit,
    onStopTranslationClick: (TranslationEngine) -> Unit,
    onOpenWebTranslateClick: (TranslationEngine) -> Unit,
    onCloseClick: () -> Unit
) {
    val activeEngine = uiState.activeTranslationEngine
    val engineState = uiState.currentEngineState
    val folderList = engineState.selectedFolders

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(8.dp),
        shape = RoundedCornerShape(8.dp),
        color = AppColors.surfaceDark,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // ヘッダー行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Webバックグラウンド翻訳",
                    color = AppColors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = onCloseClick,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "閉じる",
                        tint = AppColors.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // エンジン切り替えタブ (Google翻訳 / DeepL翻訳)
            TabRow(
                selectedTabIndex = if (activeEngine == TranslationEngine.GOOGLE) 0 else 1,
                containerColor = AppColors.surfaceMedium,
                contentColor = AppColors.accentTeal,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = activeEngine == TranslationEngine.GOOGLE,
                    onClick = { onSelectEngineTab(TranslationEngine.GOOGLE) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (uiState.googleTranslationState.isTranslating) {
                                Text("● ", color = Color(0xFF4CAF50), fontSize = 12.sp)
                            }
                            Text(
                                "Google翻訳",
                                fontWeight = if (activeEngine == TranslationEngine.GOOGLE) FontWeight.Bold else FontWeight.Normal,
                                color = if (activeEngine == TranslationEngine.GOOGLE) AppColors.accentTeal else AppColors.textSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                )
                Tab(
                    selected = activeEngine == TranslationEngine.DEEPL,
                    onClick = { onSelectEngineTab(TranslationEngine.DEEPL) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (uiState.deeplTranslationState.isTranslating) {
                                Text("● ", color = Color(0xFF4CAF50), fontSize = 12.sp)
                            }
                            Text(
                                "DeepL翻訳",
                                fontWeight = if (activeEngine == TranslationEngine.DEEPL) FontWeight.Bold else FontWeight.Normal,
                                color = if (activeEngine == TranslationEngine.DEEPL) AppColors.accentTeal else AppColors.textSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // フォルダ選択エリア
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("対象フォルダ:", color = AppColors.textSecondary, fontSize = 12.sp)
                if (folderList.size > 1) {
                    Text(
                        text = "全 ${folderList.size} フォルダ一括翻訳",
                        color = AppColors.accentTealLight,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // フォルダ選択ボックス ＆ 追加ボタン
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .background(AppColors.surfaceMedium, RoundedCornerShape(4.dp))
                        .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                        .clickable(enabled = !engineState.isTranslating) { onSelectFolderClick() }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📁", fontSize = 15.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (folderList.isNotEmpty()) {
                                engineState.folderName
                            } else {
                                "フォルダを選択 (OSピッカー)"
                            },
                            color = if (folderList.isNotEmpty()) AppColors.textPrimary else AppColors.textTertiary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Button(
                    onClick = onSelectFolderClick,
                    enabled = !engineState.isTranslating,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(if (folderList.isEmpty()) "選択" else "＋ 追加", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            // 【複数選択時】選択中フォルダのキュー一覧チップス
            if (folderList.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1B2F2C), RoundedCornerShape(4.dp))
                        .border(1.dp, AppColors.accentTeal, RoundedCornerShape(4.dp))
                        .padding(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "選択中 (${folderList.size}件):",
                            color = AppColors.accentTealLight,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (!engineState.isTranslating) {
                            Text(
                                text = "全解除",
                                color = Color.Gray,
                                fontSize = 11.sp,
                                modifier = Modifier.clickable { onClearFoldersClick(activeEngine) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(folderList) { index, item ->
                            Row(
                                modifier = Modifier
                                    .background(Color(0xFF112220), RoundedCornerShape(3.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${index + 1}. ${item.name}",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!engineState.isTranslating) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "削除",
                                        tint = Color.LightGray,
                                        modifier = Modifier
                                            .size(13.dp)
                                            .clickable { onRemoveFolderClick(activeEngine, index) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 設定・出力先案内 & Webサイト確認ボタン
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val outputInfo = if (activeEngine == TranslationEngine.GOOGLE) {
                    "自動検出 → 日本語 (出力: 翻訳完了_GOOGLE / 3,500字)"
                } else {
                    "自動検出 → 日本語 (出力: 翻訳完了_DEEPL / 1,300字)"
                }

                Text(
                    text = outputInfo,
                    color = AppColors.textTertiary,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f)
                )

                TextButton(
                    onClick = { onOpenWebTranslateClick(activeEngine) },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (activeEngine == TranslationEngine.GOOGLE) "Google翻訳を開く" else "DeepLを開く",
                        color = AppColors.accentTealLight,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 進捗表示エリア
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.backgroundDark, RoundedCornerShape(4.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = "ステータス: ${engineState.statusText}",
                    color = AppColors.accentTealLight,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                if (engineState.progress.second > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "全体進捗: ${engineState.progress.first} / ${engineState.progress.second} 件",
                        color = AppColors.textSecondary,
                        fontSize = 11.sp
                    )
                }
                if (engineState.currentFileName.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "処理中: ${engineState.currentFileName}" +
                                if (engineState.chunkProgress.second > 0) " (チャンク ${engineState.chunkProgress.first}/${engineState.chunkProgress.second})" else "",
                        color = AppColors.textMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 操作ボタン (開始 / 停止)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onStartTranslationClick(activeEngine) },
                    enabled = !engineState.isTranslating && (folderList.isNotEmpty() || engineState.folderUri != null),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.accentOrange,
                        disabledContainerColor = AppColors.surfaceMedium
                    ),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "翻訳開始",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (activeEngine == TranslationEngine.GOOGLE) "Google翻訳開始" else "DeepL翻訳開始",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = { onStopTranslationClick(activeEngine) },
                    enabled = engineState.isTranslating,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.surfaceMedium,
                        disabledContainerColor = AppColors.surfaceMedium
                    ),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "停止",
                        tint = if (engineState.isTranslating) Color.White else AppColors.textTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "停止",
                        color = if (engineState.isTranslating) Color.White else AppColors.textTertiary,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
