package com.example.novelscraper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelscraper.ExcludeCandidatesState
import com.example.novelscraper.ui.theme.AppColors

/**
 * 除外候補カード（テスト結果パネルの内側に表示される一時UI）。
 * 行タップ = 適用（確認ボタンなし）。誤タップは再タップ上書き/解除で復旧可能。
 */
@Composable
fun ExcludeCandidatesCard(
    state: ExcludeCandidatesState,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = AppColors.backgroundDarkest,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.DarkGray),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "除外候補を選択",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.textPrimary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "閉じる",
                            tint = AppColors.textPrimary
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.items) { cand ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onApply(cand.selector) },
                            shape = RoundedCornerShape(8.dp),
                            color = AppColors.surfaceMedium
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = cand.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = AppColors.accentTealLight
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = cand.metric,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AppColors.accentYellow
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = cand.selector,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppColors.textSecondary,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "消える内容:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppColors.textMuted
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 40.dp, max = 110.dp)
                                        .background(AppColors.backgroundMedium, RoundedCornerShape(4.dp))
                                        .verticalScroll(rememberScrollState())
                                        .padding(6.dp)
                                ) {
                                    Text(
                                        text = cand.preview,
                                        style = MaterialTheme.typography.bodySmall,
                                        lineHeight = 16.sp,
                                        color = AppColors.textPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "候補をタップすると即適用され、テストを自動でやり直します",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.textMuted
                )
            }
        }
    }
}
