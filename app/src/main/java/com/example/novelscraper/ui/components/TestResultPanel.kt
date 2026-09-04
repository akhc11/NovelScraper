package com.example.novelscraper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelscraper.scraper.ScrapingResult
import com.example.novelscraper.ui.theme.AppColors

/**
 * 動作テスト実行結果パネル（全面改修版）
 * - 完成ファイル名リアルタイムプレビュー
 * - 各項目の合否ステータスバッジ（緑/赤）
 * - 次ページURL検証 & ワンタップ遷移
 * - 概要プレビュー（先頭200文字）と除外行リストのタブ切替
 * - 設定パネルへのクイック修正連携
 */
@Composable
fun TestResultPanel(
    result: ScrapingResult,
    onDismiss: () -> Unit,
    onRequestExclude: (String) -> Unit,
    onNavigateNext: ((String) -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onReTest: (() -> Unit)? = null
) {
    // タブ選択状態（0: 概要プレビュー, 1: 除外指定モード）
    var selectedTab by remember { mutableStateOf(0) }
    // 概要プレビューでの全文展開フラグ
    var isExpandedContent by remember { mutableStateOf(false) }

    // 保存予定ファイル名の組み立て
    val folderName = result.folderName.trim().ifEmpty { "未分類小説" }
    val chapNum = (result.chapterDisplay ?: result.chapter).trim()
    val title = result.title.trim()
    val estimatedFileName = when {
        chapNum.isNotEmpty() && title.isNotEmpty() -> "${chapNum}_${title}.txt"
        chapNum.isNotEmpty() -> "${chapNum}.txt"
        title.isNotEmpty() -> "${title}.txt"
        else -> "0001_(タイトル未取得).txt"
    }

    // 本文文字数・行数計算
    val cleanContent = result.content.trim()
    val charCount = cleanContent.replace("\\s+".toRegex(), "").length
    val lineCount = cleanContent.lines().count { it.isNotBlank() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AppColors.backgroundDarkest
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // --- 1. ヘッダーバー ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.backgroundDark)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "🧪 動作テスト結果",
                        color = AppColors.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onReTest != null) {
                        Button(
                            onClick = onReTest,
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceLight),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "再テスト", modifier = Modifier.size(14.dp), tint = AppColors.textPrimary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("再テスト", color = AppColors.textPrimary, fontSize = 12.sp)
                        }
                    }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceLight),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("閉じる", color = AppColors.textPrimary, fontSize = 12.sp)
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF2C2C2C))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
            ) {
                // --- 2. 完成ファイル名リアルプレビューカード ---
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF14241C)),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E4620))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "📁 保存先フォルダ:",
                                    color = Color(0xFF81C784),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = folderName,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "📄 ファイル名:",
                                    color = Color(0xFF81C784),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = estimatedFileName,
                                    color = Color(0xFFE8F5E9),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // --- 3. 各項目の合否ステータス一覧 ---
                item {
                    Text(
                        text = "抽出項目ステータス",
                        color = AppColors.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppColors.surfaceMedium, RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF333333), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 作品名
                        StatusRow(
                            label = "作品名",
                            value = result.folderName,
                            isSuccess = result.folderName.isNotEmpty(),
                            successBadge = "取得済",
                            failureBadge = "未取得"
                        )

                        // チャプター番号
                        StatusRow(
                            label = "チャプター番号",
                            value = result.chapterDisplay ?: result.chapter,
                            isSuccess = (result.chapterDisplay ?: result.chapter).isNotEmpty(),
                            successBadge = "OK: ${(result.chapterDisplay ?: result.chapter)}",
                            failureBadge = "未設定"
                        )

                        // タイトル
                        StatusRow(
                            label = "タイトル",
                            value = result.title,
                            isSuccess = result.title.isNotEmpty(),
                            successBadge = "取得済",
                            failureBadge = "未取得"
                        )

                        // 次ページURL
                        StatusRow(
                            label = "次ページURL",
                            value = result.nextUrl,
                            isSuccess = result.nextUrl.isNotEmpty(),
                            successBadge = "取得済",
                            failureBadge = "なし(最終話)",
                            extraContent = {
                                if (result.nextUrl.isNotEmpty() && onNavigateNext != null) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Button(
                                        onClick = { onNavigateNext(result.nextUrl) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00695C)),
                                        shape = RoundedCornerShape(4.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text("🔗 次ページを開いてテスト", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        )

                        // 本文文字数・行数
                        StatusRow(
                            label = "本文",
                            value = if (charCount > 0) "取得成功 (${charCount}文字 / ${lineCount}行)" else "本文が取得できていません",
                            isSuccess = charCount > 0,
                            successBadge = "${charCount}文字",
                            failureBadge = "本文なし"
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                }

                // --- 4. 本文表示（タブ切り替え: 概要プレビュー / 除外行リスト） ---
                item {
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = AppColors.surfaceMedium,
                        contentColor = Color.White,
                        modifier = Modifier.border(1.dp, Color(0xFF333333), RoundedCornerShape(6.dp))
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("📖 概要プレビュー (200字)", fontSize = 12.sp, fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("🎯 除外指定モード (行タップ)", fontSize = 12.sp, fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // タブ 0: 概要プレビュー（AGENTS.md ルール3 準拠: 先頭200〜300文字）
                if (selectedTab == 0) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = AppColors.surfaceDark),
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E2E2E))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                val displayText = if (cleanContent.isEmpty()) {
                                    "(本文テキストが抽出されていません。セレクタを見直してください)"
                                } else if (!isExpandedContent && cleanContent.length > 250) {
                                    cleanContent.substring(0, 250) + "…\n(以下省略)"
                                } else {
                                    cleanContent
                                }

                                Text(
                                    text = displayText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (cleanContent.isEmpty()) Color.Gray else AppColors.textPrimary,
                                    lineHeight = 20.sp,
                                    fontSize = 13.sp
                                )

                                if (cleanContent.length > 250) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = if (isExpandedContent) "▲ 先頭のみ表示に戻す" else "▼ 全文を表示する (${cleanContent.length}文字)",
                                        color = AppColors.accentTealLight,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clickable { isExpandedContent = !isExpandedContent }
                                            .padding(vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // タブ 1: 除外指定モード（行単位リスト・タップで除外候補プローブ）
                    item {
                        Text(
                            text = "💡 広告や余計な行をタップすると除外候補を呼び出せます",
                            color = AppColors.accentTealLight,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }

                    val lines = result.debugLines
                    if (lines != null && lines.isNotEmpty()) {
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
                                text = cleanContent.ifEmpty { "(本文なし)" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.textPrimary,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            // --- 5. 下部クイックアクションバー ---
            HorizontalDivider(color = Color(0xFF2C2C2C))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.backgroundDark)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onOpenSettings != null) {
                    Button(
                        onClick = onOpenSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = "設定修正", modifier = Modifier.size(14.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("設定パネルで修正", color = Color.White, fontSize = 12.sp)
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceLight),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("閉じる", color = AppColors.textPrimary, fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * 抽出項目のステータス行（ラベル、値、合否バッジ、任意拡張コンテンツ）
 */
@Composable
private fun StatusRow(
    label: String,
    value: String,
    isSuccess: Boolean,
    successBadge: String,
    failureBadge: String,
    extraContent: @Composable (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = AppColors.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )

            // 合否バッジ
            Surface(
                color = if (isSuccess) Color(0xFF1B5E20) else Color(0xFF424242),
                shape = RoundedCornerShape(4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = if (isSuccess) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = null,
                        tint = if (isSuccess) Color(0xFF81C784) else Color(0xFFB0BEC5),
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = if (isSuccess) successBadge else failureBadge,
                        color = if (isSuccess) Color(0xFFE8F5E9) else Color(0xFFCFD8DC),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = value.ifEmpty { "(未取得)" },
            color = if (value.isNotEmpty()) AppColors.textPrimary else Color.Gray,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        if (extraContent != null) {
            extraContent()
        }
    }
}

@Composable
private fun DebugLineRow(
    text: String,
    selector: String,
    onRequestExclude: (String) -> Unit
) {
    val hasSelector = selector.isNotEmpty()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(enabled = hasSelector) { onRequestExclude(selector) },
        color = if (hasSelector) AppColors.surfaceMedium.copy(alpha = 0.5f) else Color.Transparent,
        shape = RoundedCornerShape(4.dp),
        border = if (hasSelector) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF383838)) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.textPrimary,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            if (hasSelector) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "除外候補 →",
                    color = AppColors.accentTealLight,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
