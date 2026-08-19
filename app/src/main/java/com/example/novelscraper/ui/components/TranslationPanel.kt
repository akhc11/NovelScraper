package com.example.novelscraper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
    onStartTranslationClick: (TranslationEngine) -> Unit,
    onStopTranslationClick: (TranslationEngine) -> Unit,
    onOpenWebTranslateClick: (TranslationEngine) -> Unit,
    onCloseClick: () -> Unit
) {
    val activeEngine = uiState.activeTranslationEngine
    val engineState = uiState.currentEngineState

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
                IconButton(onClick = onCloseClick, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "閉じる",
                        tint = AppColors.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // エンジン切替タブ (Google翻訳 / DeepL翻訳)
            TabRow(
                selectedTabIndex = if (activeEngine == TranslationEngine.GOOGLE) 0 else 1,
                containerColor = AppColors.backgroundDark,
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
            Text("対象フォルダ:", color = AppColors.textSecondary, fontSize = 12.sp)
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
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = if (engineState.folderName.isNotEmpty()) {
                            engineState.folderName
                        } else {
                            "フォルダを選択してください (.txt)"
                        },
                        color = if (engineState.folderName.isNotEmpty()) AppColors.textPrimary else AppColors.textTertiary,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Button(
                    onClick = onSelectFolderClick,
                    enabled = !engineState.isTranslating,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("選択", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 操作ボタン行（開始・停止）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onStartTranslationClick(activeEngine) },
                    enabled = !engineState.isTranslating && engineState.folderUri != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.accentOrange,
                        disabledContainerColor = AppColors.surfaceMedium
                    ),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (activeEngine == TranslationEngine.GOOGLE) "Google翻訳開始" else "DeepL翻訳開始",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = { onStopTranslationClick(activeEngine) },
                    enabled = engineState.isTranslating,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F),
                        disabledContainerColor = AppColors.surfaceMedium
                    ),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("停止", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
