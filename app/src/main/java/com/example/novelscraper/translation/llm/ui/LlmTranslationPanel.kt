package com.example.novelscraper.translation.llm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
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
import com.example.novelscraper.translation.llm.engine.LlmTranslationConfig
import com.example.novelscraper.ui.theme.AppColors

@Composable
fun LlmTranslationPanel(
    uiState: MainUiState,
    config: LlmTranslationConfig,
    onSelectFolderClick: () -> Unit,
    onRemoveFolderClick: (Int) -> Unit,
    onClearFoldersClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    val liveState = uiState.llmEngineLiveState
    val folderList = uiState.llmTranslationState.selectedFolders

    Column(modifier = Modifier.fillMaxWidth()) {
        // 設定サマリー & 詳細設定ボタン
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val primaryProfile = config.modelProfiles.firstOrNull()
            val primaryModelName = primaryProfile?.modelName ?: "未設定"
            val totalModels = config.modelProfiles.size
            val workers = config.parallelWorkers

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "主モデル: $primaryModelName (${workers}並列 / 全${totalModels}モデル巡回)",
                    color = AppColors.accentTealLight,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "辞書: ${if (config.enableDictGen) "有効 (${config.dictParallelCount}並列)" else "OFF"} / 完了マーカー: ${if (config.enableCompletionMarker) "有効" else "OFF"}",
                    color = AppColors.textTertiary,
                    fontSize = 10.sp
                )
            }

            Button(
                onClick = onOpenSettingsClick,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceMedium),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "設定", tint = AppColors.accentTealLight, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("詳細設定", color = AppColors.textPrimary, fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // フォルダ選択エリア
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("対象フォルダ:", color = AppColors.textSecondary, fontSize = 12.sp)
            if (folderList.size > 1) {
                Text(
                    text = "全 ${folderList.size} フォルダ一括処理",
                    color = AppColors.accentTealLight,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

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
                    .clickable(enabled = !liveState.isTranslating) { onSelectFolderClick() }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📁", fontSize = 15.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (folderList.isNotEmpty()) {
                            folderList.first().name + if (folderList.size > 1) " 他${folderList.size - 1}件" else ""
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
                enabled = !liveState.isTranslating,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(if (folderList.isEmpty()) "選択" else "＋ 追加", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 複数フォルダチップス
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
                    Text("キュー一覧 (${folderList.size}件):", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    if (!liveState.isTranslating) {
                        Text("全解除", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.clickable { onClearFoldersClick() })
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
                            Text("${index + 1}. ${item.name}", color = Color.White, fontSize = 11.sp, maxLines = 1)
                            if (!liveState.isTranslating) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "削除",
                                    tint = Color.LightGray,
                                    modifier = Modifier.size(13.dp).clickable { onRemoveFolderClick(index) }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 進捗 & ログ表示エリア
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.backgroundDark, RoundedCornerShape(4.dp))
                .padding(8.dp)
        ) {
            Text(
                text = "ステータス: ${liveState.statusText}",
                color = AppColors.accentTealLight,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            if (liveState.progress.second > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "進捗: ${liveState.progress.first} / ${liveState.progress.second} ファイル",
                    color = AppColors.textSecondary,
                    fontSize = 11.sp
                )
            }

            if (liveState.chunkProgress.second > 0) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "大ファイル進捗: ${liveState.chunkProgress.first} / ${liveState.chunkProgress.second} チャンク",
                    color = AppColors.accentTealLight,
                    fontSize = 11.sp
                )
            }

            if (liveState.currentFileName.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "処理中: ${liveState.currentFileName}",
                    color = AppColors.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 簡易ログビューア
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .background(Color(0xFF0F1416), RoundedCornerShape(4.dp))
                    .padding(6.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = true
                ) {
                    items(liveState.logs.reversed()) { logLine ->
                        Text(
                            text = logLine,
                            color = when {
                                logLine.contains("✅") -> Color(0xFF81C784)
                                logLine.contains("❌") -> Color(0xFFE57373)
                                logLine.contains("⚠️") -> Color(0xFFFFB74D)
                                logLine.contains("🔁") -> Color(0xFF64B5F6)
                                else -> AppColors.textTertiary
                            },
                            fontSize = 10.sp,
                            lineHeight = 13.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 開始プリフライト: モデル0件・キー未設定では開始不可
        val hasModel = config.modelProfiles.isNotEmpty()
        val hasKey = config.geminiApiKeys.any { it.isNotBlank() } ||
                config.openRouterApiKey.isNotBlank() || config.groqApiKey.isNotBlank()
        val startBlockedReason = when {
            !hasModel -> "モデル未登録のため開始できません (詳細設定で追加)"
            !hasKey -> "APIキー未設定のため開始できません (詳細設定で入力)"
            else -> null
        }
        if (folderList.isNotEmpty() && startBlockedReason != null && !liveState.isTranslating) {
            Text(
                text = "⚠️ $startBlockedReason",
                color = Color(0xFFFFAA66),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        // 操作ボタン (開始 / 停止)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onStartClick,
                enabled = !liveState.isTranslating && folderList.isNotEmpty() && hasModel && hasKey,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.accentOrange,
                    disabledContainerColor = AppColors.surfaceMedium
                ),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "翻訳開始", tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("LLM翻訳開始", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onStopClick,
                enabled = liveState.isTranslating,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.surfaceMedium,
                    disabledContainerColor = AppColors.surfaceMedium
                ),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "停止", tint = if (liveState.isTranslating) Color.White else AppColors.textTertiary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("停止", color = if (liveState.isTranslating) Color.White else AppColors.textTertiary, fontSize = 13.sp)
            }
        }
    }
}