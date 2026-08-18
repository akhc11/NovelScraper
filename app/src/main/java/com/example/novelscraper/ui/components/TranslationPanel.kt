package com.example.novelscraper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
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
import com.example.novelscraper.ui.theme.AppColors

@Composable
fun TranslationPanel(
    uiState: MainUiState,
    onSelectFolderClick: () -> Unit,
    onStartTranslationClick: () -> Unit,
    onStopTranslationClick: () -> Unit,
    onOpenGoogleTranslateClick: () -> Unit,
    onCloseClick: () -> Unit
) {
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
                    text = "Google翻訳 (バックグラウンド)",
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
                        text = if (uiState.translationFolderName.isNotEmpty()) {
                            uiState.translationFolderName
                        } else {
                            "フォルダを選択してください (.txt)"
                        },
                        color = if (uiState.translationFolderName.isNotEmpty()) AppColors.textPrimary else AppColors.textTertiary,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Button(
                    onClick = onSelectFolderClick,
                    enabled = !uiState.isTranslating,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("選択", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 言語設定 & Google翻訳Web確認ボタン
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "設定: 自動検出 → 日本語 (出力先: 翻訳完了_GOOGLE)",
                    color = AppColors.textTertiary,
                    fontSize = 11.sp
                )

                TextButton(
                    onClick = onOpenGoogleTranslateClick,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("Google翻訳を開く", color = AppColors.accentTealLight, fontSize = 11.sp)
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
                    text = "ステータス: ${uiState.translationStatusText}",
                    color = AppColors.accentTealLight,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                if (uiState.translationProgress.second > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "全体進捗: ${uiState.translationProgress.first} / ${uiState.translationProgress.second} 件",
                        color = AppColors.textSecondary,
                        fontSize = 11.sp
                    )
                }
                if (uiState.translationCurrentFileName.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "処理中: ${uiState.translationCurrentFileName}" +
                                if (uiState.translationChunkProgress.second > 0) " (チャンク ${uiState.translationChunkProgress.first}/${uiState.translationChunkProgress.second})" else "",
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
                    onClick = onStartTranslationClick,
                    enabled = !uiState.isTranslating && uiState.translationFolderUri != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.accentOrange,
                        disabledContainerColor = AppColors.surfaceMedium
                    ),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("翻訳開始", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onStopTranslationClick,
                    enabled = uiState.isTranslating,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F),
                        disabledContainerColor = AppColors.surfaceMedium
                    ),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("停止", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
