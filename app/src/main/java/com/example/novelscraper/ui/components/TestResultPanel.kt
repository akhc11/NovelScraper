package com.example.novelscraper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelscraper.ScrapingResult
import com.example.novelscraper.ui.theme.AppColors

/**
 * テスト実行結果パネル（非モーダルoverlay・SettingsPanel同機構）。
 * ツールバーを塞がず、開いたまま他パネルへ切替可能（排他制御は ViewModel が担当）。
 */
@Composable
fun TestResultPanel(
    result: ScrapingResult,
    onDismiss: () -> Unit,
    onRequestExclude: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AppColors.backgroundDarkest
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ヘッダー行（全パネル統一形式: 機能名 + 右側に閉じるボタン）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.backgroundDark)
                    .padding(horizontal = 15.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "テスト実行結果",
                    color = AppColors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceLight),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("閉じる", color = AppColors.textPrimary, fontSize = 12.sp)
                }
            }

            HorizontalDivider(color = Color.DarkGray)

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    InfoRow("作品名", result.folderName)
                    InfoRow("タイトル", result.title)
                    InfoRow("チャプター", result.chapterDisplay ?: result.chapter)
                    InfoRow("次URL", result.nextUrl)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "本文プレビュー (行をタップ → 除外候補を選択)",
                        style = MaterialTheme.typography.labelMedium,
                        color = AppColors.accentTealLight
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                val lines = result.debugLines
                if (lines != null) {
                    items(lines) { line ->
                        DebugLineRow(
                            text = line.text,
                            selector = line.selector,
                            onRequestExclude = onRequestExclude
                        )
                    }
                } else {
                    item {
                        Text(
                            text = result.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.textPrimary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = Color.DarkGray)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = AppColors.textMuted)
        Text(text = value.ifEmpty { "(未取得)" }, style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary)
    }
}

@Composable
private fun DebugLineRow(
    text: String,
    selector: String,
    onRequestExclude: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable { if (selector.isNotEmpty()) onRequestExclude(selector) },
        color = if (selector.isEmpty()) Color.Transparent else AppColors.surfaceMedium.copy(alpha = 0.3f),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.textPrimary,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
